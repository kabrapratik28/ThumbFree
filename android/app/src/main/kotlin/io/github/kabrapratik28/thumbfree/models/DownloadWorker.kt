package io.github.kabrapratik28.thumbfree.models

import android.app.ForegroundServiceTypeException
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.Operation
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import io.github.kabrapratik28.thumbfree.R
import io.github.kabrapratik28.thumbfree.app.AppGraph
import io.github.kabrapratik28.thumbfree.core.models.Catalog
import io.github.kabrapratik28.thumbfree.core.models.DownloadResult
import io.github.kabrapratik28.thumbfree.core.models.Downloader
import io.github.kabrapratik28.thumbfree.core.models.ModelFile
import io.github.kabrapratik28.thumbfree.core.models.ModelStatus
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Downloads a [Catalog] model in the background, as a foreground service. WorkManager restarts it after a process
 * kill or a dropped network, resuming the ".part" file Downloader already leaves behind, so a Wi-Fi blip just
 * delays the next attempt instead of losing the transfer; a foreground start refused from the background (Android
 * 12+) is the same story, a retry later. A finished take never waits on this: the queue reads the model path once
 * per session, so a download that lands mid-take changes nothing until the next one.
 */
class DownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val model: ModelFile? = catalog.firstOrNull { it.id == inputData.getString(KEY_MODEL_ID) }

    override suspend fun doWork(): Result {
        val model = model ?: return Result.failure(workDataOf(KEY_REASON to "UNKNOWN_MODEL"))
        val start = inputData.getLong(KEY_START, 0)
        // A cancel or delete since this download was asked for, in this process or left on disk by one that died,
        // wins over it.
        if (!withContext(Dispatchers.IO) { ModelDownloads.stillWanted(applicationContext, model, start) }) return stopped()
        val modelsDir = File(applicationContext.filesDir, "models")
        // Started again for a model that is already here (the welcome flow and the Home tab may both ask): done.
        val verified = withContext(Dispatchers.IO) {
            try { AppGraph.modelStore.status(model) == ModelStatus.VERIFIED } catch (e: Exception) { false }
        }
        if (verified) return Result.success()
        // A long download runs only as a foreground service. Android 12+ refuses that start from the background
        // (WorkManager's own restart after a dropped network or a process kill): the partial file stays, and
        // WorkManager tries again later, up to a cap. A missing permission or a wrong service type can't be fixed by
        // waiting.
        try {
            setForeground(getForegroundInfo())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "download_foreground_refused ${e.javaClass.name} attempt=$runAttemptCount")
            val permanent = e is SecurityException || e is IllegalArgumentException ||
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && e is ForegroundServiceTypeException)
            return if (permanent || runAttemptCount >= MAX_FOREGROUND_ATTEMPTS) {
                Result.failure(workDataOf(KEY_REASON to "FOREGROUND_REFUSED")) // read as interrupted
            } else {
                Result.retry()
            }
        }

        val started = SystemClock.elapsedRealtime()
        val outcome = withContext(Dispatchers.IO) {
            Log.i(TAG, "download_start model=${model.fileName} part=${File(modelsDir, "${model.fileName}.part").length()}")
            var percent = -1
            downloaderFactory(modelsDir).download(
                model,
                isCancelled = { isStopped },
                onProgress = {
                    // Once per percent: each progress write lands in WorkManager's database.
                    val now = if (it.total > 0) (it.bytes * 100 / it.total).toInt().coerceIn(0, 100) else 0
                    if (now != percent) {
                        percent = now
                        setProgressAsync(workDataOf(KEY_BYTES to it.bytes, KEY_TOTAL to it.total))
                        setForegroundAsync(foregroundInfo(now)) // the notification shows the percentage too
                    }
                },
            )
        }
        val ms = SystemClock.elapsedRealtime() - started
        return when (outcome) {
            is DownloadResult.Done -> {
                // The same check before publishing, so a completion that came after a delete never turns ready.
                if (!withContext(Dispatchers.IO) { ModelDownloads.stillWanted(applicationContext, model, start) }) {
                    return stopped()
                }
                // Known and accepted: a cancel landing between that check and markVerified below still ends Ready. The
                // file is fully downloaded and verified by then, and a cancel only removes partial files, so the owner
                // keeps a working model and can delete it. A delete can't slip in here: its record fails the check.
                // The shared instance: a second, separately-constructed ModelStore's @Synchronized status() would
                // not block on this one's, and could re-hash the same 731 MB file at the same time. It takes only the
                // check the downloader made of the very file it renamed.
                if (!AppGraph.modelStore.markVerified(model, outcome.check)) {
                    Log.w(TAG, "download_check_refused model=${model.fileName}")
                    return Result.failure(workDataOf(KEY_REASON to DownloadResult.Reason.HASH_MISMATCH.name))
                }
                Log.i(TAG, "download_done model=${model.fileName} ms=$ms")
                Result.success()
            }
            is DownloadResult.Failed -> {
                // A network failure means "no internet" only when the phone has no validated network then.
                val offline = outcome.reason in NETWORK_REASONS && !hasValidatedNetwork()
                Log.w(TAG, "download_failed model=${model.fileName} reason=${outcome.reason} offline=$offline ms=$ms detail=${outcome.detail}")
                Result.failure(workDataOf(KEY_REASON to outcome.reason.name, KEY_OFFLINE to offline))
            }
        }
    }

    private fun stopped() = Result.failure(workDataOf(KEY_REASON to DownloadResult.Reason.CANCELLED.name))

    private fun hasValidatedNetwork(): Boolean {
        val connectivity = applicationContext.getSystemService(ConnectivityManager::class.java) ?: return false
        return connectivity.getNetworkCapabilities(connectivity.activeNetwork)
            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        applicationContext.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, applicationContext.getString(R.string.models_channel), NotificationManager.IMPORTANCE_LOW),
        )
        return foregroundInfo(0)
    }

    /** The ongoing "Downloading model" notification at [percent], with a determinate bar. */
    private fun foregroundInfo(percent: Int): ForegroundInfo {
        val notification = Notification.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.brand_mark)
            .setContentTitle(applicationContext.getString(R.string.models_notification_title))
            .setContentText("$percent%")
            .setOngoing(true)
            .setProgress(100, percent, false)
            .build()
        // One notification per model, so two downloads at once never share (and end) one.
        val id = FIRST_NOTIFICATION_ID + catalog.indexOf(model).coerceAtLeast(0)
        return ForegroundInfo(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    companion object {
        const val KEY_MODEL_ID = "model_id"
        /** The number ModelDownloads.start gave this request, to tell a later cancel or delete from an earlier one. */
        const val KEY_START = "start"
        const val KEY_BYTES = "bytes"
        const val KEY_TOTAL = "total"
        const val KEY_REASON = "reason"
        /** On a failure: true when the phone had no validated network, so the reason reads as no internet. */
        const val KEY_OFFLINE = "offline"
        private val NETWORK_REASONS = setOf(DownloadResult.Reason.NETWORK, DownloadResult.Reason.STALLED, DownloadResult.Reason.HTTP)
        private const val CHANNEL_ID = "model_downloads"
        private const val FIRST_NOTIFICATION_ID = 2 // RecordingService has 1
        private const val MAX_FOREGROUND_ATTEMPTS = 5
        private const val TAG = "ThumbFree"

        /** Test seam; internal, so no code outside this module can swap in a downloader that forges a FileCheck. */
        @VisibleForTesting(otherwise = VisibleForTesting.PACKAGE_PRIVATE)
        @Volatile internal var downloaderFactory: (File) -> Downloader = { Downloader(it) }

        /** The models a request may name; a test adds one its server can really serve. */
        @Volatile internal var catalog: List<ModelFile> = Catalog.all

        fun enqueue(context: Context, model: ModelFile, wifiOnly: Boolean, start: Long = 0L): UUID {
            val request = OneTimeWorkRequestBuilder<DownloadWorker>()
                .setInputData(workDataOf(KEY_MODEL_ID to model.id, KEY_START to start))
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                        .build(),
                )
                .build()
            // REPLACE, so the newest request wins with its own network rule. A download that is running goes on from
            // its partial file once the old run lets go of it.
            WorkManager.getInstance(context).enqueueUniqueWork(workName(model), ExistingWorkPolicy.REPLACE, request)
            return request.id
        }

        /** Only for ModelDownloads, which also removes the partial file. */
        internal fun cancel(context: Context, model: ModelFile): Operation = WorkManager.getInstance(context).cancelUniqueWork(workName(model))

        internal fun workName(model: ModelFile) = "download-${model.fileName}"
    }
}
