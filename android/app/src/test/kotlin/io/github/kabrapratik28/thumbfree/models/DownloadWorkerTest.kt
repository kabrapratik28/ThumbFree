package io.github.kabrapratik28.thumbfree.models

import android.app.Notification
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.work.Configuration
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.work.workDataOf
import com.google.common.truth.Truth.assertThat
import com.google.common.util.concurrent.Futures
import io.github.kabrapratik28.thumbfree.app.AppGraph
import io.github.kabrapratik28.thumbfree.audio.RecordingService
import io.github.kabrapratik28.thumbfree.core.models.BODY
import io.github.kabrapratik28.thumbfree.core.models.Catalog
import io.github.kabrapratik28.thumbfree.core.models.DownloadProgress
import io.github.kabrapratik28.thumbfree.core.models.DownloadResult
import io.github.kabrapratik28.thumbfree.core.models.Downloader
import io.github.kabrapratik28.thumbfree.core.models.FileCheck
import io.github.kabrapratik28.thumbfree.core.models.ModelFile
import io.github.kabrapratik28.thumbfree.core.models.ModelStatus
import io.github.kabrapratik28.thumbfree.core.models.ModelStore
import io.github.kabrapratik28.thumbfree.core.models.TestHttpServer
import io.github.kabrapratik28.thumbfree.core.models.sha256Hex
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowNetworkCapabilities

@RunWith(RobolectricTestRunner::class)
class DownloadWorkerTest {
    private val context = RuntimeEnvironment.getApplication()
    private val model = Catalog.PARAKEET_UNIFIED_Q8
    private val modelsDir = File(context.filesDir, "models")
    private val server = TestHttpServer()

    /** A 1 MiB model the test server really serves, so a download can end with real matching bytes. */
    private val served = ModelFile(
        id = "t/r/m.gguf",
        fileName = "m.gguf",
        sizeBytes = BODY.size.toLong(),
        sha256 = MessageDigest.getInstance("SHA-256").digest(BODY).joinToString("") { "%02x".format(it) },
        languages = listOf("en"),
        revision = "abc",
    )

    /** The real downloader, pointed at the test server: its locked finalization makes the check. */
    private val realDownloader: (File) -> Downloader = { dir ->
        object : Downloader(dir, usableSpace = { Long.MAX_VALUE }) {
            override fun download(model: ModelFile, url: String, isCancelled: () -> Boolean, onProgress: (DownloadProgress) -> Unit) =
                super.download(model, server.url(), isCancelled, onProgress)
        }
    }

    @Before
    fun setUp() {
        // DownloadWorker reads the shared AppGraph.modelStore (never its own instance, see successUsesSharedModelStore
        // below); AppGraph.init() is a real app's job and is idempotent process-wide, so each test points it at its
        // own modelsDir directly instead, the same way a real run would once ThumbFreeApp.onCreate() already ran.
        AppGraph.modelStore = ModelStore(modelsDir)
        val config = Configuration.Builder().setExecutor(SynchronousExecutor()).build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, config)
    }

    @After
    fun tearDown() {
        DownloadWorker.downloaderFactory = { Downloader(it) }
        DownloadWorker.catalog = Catalog.all
        server.close()
        modelsDir.deleteRecursively()
    }

    private fun builder(model: ModelFile = this.model) = TestListenableWorkerBuilder<DownloadWorker>(context)
        .setInputData(workDataOf(DownloadWorker.KEY_MODEL_ID to model.id))

    /** A downloader that makes [reports] and then fails for [reason], for the tests of what a run shows along the way. */
    private fun reporting(reports: List<DownloadProgress>, reason: DownloadResult.Reason = DownloadResult.Reason.CANCELLED): (File) -> Downloader = { dir ->
        object : Downloader(dir) {
            override fun download(model: ModelFile, url: String, isCancelled: () -> Boolean, onProgress: (DownloadProgress) -> Unit): DownloadResult {
                reports.forEach(onProgress)
                return DownloadResult.Failed(reason, "stop")
            }
        }
    }

    /** The phone's active network, validated by Android ([validated]) or not. */
    private fun network(validated: Boolean) {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = ShadowNetworkCapabilities.newInstance()
        if (validated) shadowOf(caps).addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        shadowOf(cm).setNetworkCapabilities(cm.activeNetwork, caps)
    }

    // "No internet" only when the phone has no validated network; the same failure with one is an interrupted download.
    @Test
    fun aNetworkFailureIsOfflineOnlyWithoutAValidatedNetwork() = runBlocking {
        DownloadWorker.downloaderFactory = reporting(emptyList(), DownloadResult.Reason.NETWORK)

        network(validated = false)
        val offline = builder().build().doWork() as ListenableWorker.Result.Failure
        network(validated = true)
        val online = builder().build().doWork() as ListenableWorker.Result.Failure

        assertThat(offline.outputData.getBoolean(DownloadWorker.KEY_OFFLINE, false)).isTrue()
        assertThat(online.outputData.getBoolean(DownloadWorker.KEY_OFFLINE, true)).isFalse()
        assertThat(online.outputData.getString(DownloadWorker.KEY_REASON)).isEqualTo("NETWORK")
    }

    // Real bytes through the real downloader, whose locked finalization makes the check that marks the file.
    @Test
    fun successMarksVerified() = runBlocking {
        DownloadWorker.catalog = Catalog.all + served
        DownloadWorker.downloaderFactory = realDownloader

        val result = builder(served).build().doWork()

        assertThat(result).isEqualTo(ListenableWorker.Result.success())
        var hashCalls = 0
        val store = ModelStore(modelsDir) { f -> hashCalls++; sha256Hex(f) }
        assertThat(store.status(served)).isEqualTo(ModelStatus.VERIFIED)
        assertThat(hashCalls).isEqualTo(0)
    }

    // The verdict lands in the shared AppGraph.modelStore, not a private instance: two instances could each hash the same
    // file at once (their @Synchronized locks are per instance, not per model file). With the sidecar gone, only that
    // instance's memory can answer without a hash.
    @Test
    fun successUsesSharedModelStore() = runBlocking {
        DownloadWorker.catalog = Catalog.all + served
        DownloadWorker.downloaderFactory = realDownloader
        var calls = 0
        AppGraph.modelStore = ModelStore(modelsDir) { f -> calls++; sha256Hex(f) }

        builder(served).build().doWork()
        File(modelsDir, "${served.fileName}.verified").delete()

        assertThat(AppGraph.modelStore.status(served)).isEqualTo(ModelStatus.VERIFIED)
        assertThat(calls).isEqualTo(0)
    }

    // A Done whose check describes other bytes of the same size is not marked, so the model never counts as ready.
    @Test
    fun sameSizeWrongHashIsNotVerified() = runBlocking {
        DownloadWorker.catalog = Catalog.all + served
        DownloadWorker.downloaderFactory = { dir ->
            object : Downloader(dir) {
                override fun download(model: ModelFile, url: String, isCancelled: () -> Boolean, onProgress: (DownloadProgress) -> Unit): DownloadResult {
                    dir.mkdirs()
                    val file = File(dir, model.fileName).apply { writeBytes(BODY.copyOf().also { it[0] = (it[0] + 1).toByte() }) }
                    return DownloadResult.Done(file, FileCheck(file.length(), file.lastModified(), sha256Hex(file)))
                }
            }
        }

        val result = builder(served).build().doWork() as ListenableWorker.Result.Failure

        assertThat(result.outputData.getString(DownloadWorker.KEY_REASON)).isEqualTo("HASH_MISMATCH")
        assertThat(File(modelsDir, "${served.fileName}.verified").exists()).isFalse()
        assertThat(ModelStore(modelsDir).status(served)).isEqualTo(ModelStatus.CORRUPT)
    }

    @Test
    fun failureCarriesReason() = runBlocking {
        DownloadWorker.downloaderFactory = { dir ->
            object : Downloader(dir) {
                override fun download(model: ModelFile, url: String, isCancelled: () -> Boolean, onProgress: (DownloadProgress) -> Unit): DownloadResult =
                    DownloadResult.Failed(DownloadResult.Reason.HASH_MISMATCH, "x")
            }
        }
        val worker = TestListenableWorkerBuilder<DownloadWorker>(context)
            .setInputData(workDataOf(DownloadWorker.KEY_MODEL_ID to model.id))
            .build()

        val result = worker.doWork() as ListenableWorker.Result.Failure

        assertThat(result.outputData.getString(DownloadWorker.KEY_REASON)).isEqualTo("HASH_MISMATCH")
    }

    @Test
    fun progressIsPublished() = runBlocking {
        DownloadWorker.downloaderFactory = { dir ->
            object : Downloader(dir) {
                override fun download(model: ModelFile, url: String, isCancelled: () -> Boolean, onProgress: (DownloadProgress) -> Unit): DownloadResult {
                    onProgress(DownloadProgress(500, 1_000))
                    return DownloadResult.Failed(DownloadResult.Reason.CANCELLED, "stop")
                }
            }
        }
        // TestListenableWorkerBuilder's default ProgressUpdater just logs and drops the value (it never touches
        // WorkManager's own database), so the recorded progress is read back off a fake supplied in its place.
        var progress: Data? = null
        val worker = TestListenableWorkerBuilder<DownloadWorker>(context)
            .setInputData(workDataOf(DownloadWorker.KEY_MODEL_ID to model.id))
            .setProgressUpdater { _, _, data -> progress = data; Futures.immediateFuture(null) }
            .build()

        worker.doWork()

        assertThat(progress?.getLong(DownloadWorker.KEY_BYTES, -1)).isEqualTo(500L)
        assertThat(progress?.getLong(DownloadWorker.KEY_TOTAL, -1)).isEqualTo(1_000L)
    }

    @Test
    fun foregroundInfoIsDataSync() = runBlocking {
        val worker = TestListenableWorkerBuilder<DownloadWorker>(context)
            .setInputData(workDataOf(DownloadWorker.KEY_MODEL_ID to model.id))
            .build()

        val info = worker.getForegroundInfo()

        assertThat(info.foregroundServiceType).isEqualTo(ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    // A long download runs only as a foreground service. Android 12+ can refuse that start from the background
    // (WorkManager's own restart after a dropped network or a process kill); the partial file then stays as it is, and
    // WorkManager tries again later, instead of the transfer running without the service.
    @Test
    fun foregroundRefusalRetriesWithoutDownloading() = runBlocking {
        modelsDir.mkdirs()
        val part = File(modelsDir, "${model.fileName}.part").apply { writeText("half") }
        DownloadWorker.downloaderFactory = { error("no download without the foreground service") }
        val worker = builder()
            .setForegroundUpdater { _, _, _ -> Futures.immediateFailedFuture(IllegalStateException("denied")) }
            .build()

        assertThat(worker.doWork()).isEqualTo(ListenableWorker.Result.retry())
        assertThat(part.readText()).isEqualTo("half")
    }

    // Refusals are retried only so often; at the fifth attempt the download fails as interrupted, with the partial file
    // kept for a later Try again.
    @Test
    fun foregroundRefusalGivesUpAtTheCap() = runBlocking {
        DownloadWorker.downloaderFactory = { error("no download without the foreground service") }
        val worker = builder()
            .setRunAttemptCount(5)
            .setForegroundUpdater { _, _, _ -> Futures.immediateFailedFuture(IllegalStateException("denied")) }
            .build()

        val result = worker.doWork() as ListenableWorker.Result.Failure

        assertThat(result.outputData.getString(DownloadWorker.KEY_REASON)).isEqualTo("FOREGROUND_REFUSED")
    }

    // A missing permission or a wrong service type can't be fixed by waiting: it fails at once.
    @Test
    fun aSecurityOrConfigurationRefusalFailsAtOnce() = runBlocking {
        DownloadWorker.downloaderFactory = { error("no download without the foreground service") }
        for (refusal in listOf(SecurityException("no permission"), IllegalArgumentException("wrong type"))) {
            val worker = builder()
                .setForegroundUpdater { _, _, _ -> Futures.immediateFailedFuture(refusal) }
                .build()

            val result = worker.doWork() as ListenableWorker.Result.Failure

            assertThat(result.outputData.getString(DownloadWorker.KEY_REASON)).isEqualTo("FOREGROUND_REFUSED")
        }
    }

    // Parakeet and Canary can download at once, so each has its own notification: one finishing never takes the other's
    // away. Neither is the recording notification's.
    @Test
    fun eachModelHasItsOwnNotification() = runBlocking {
        val ids = Catalog.all.map { builder(it).build().getForegroundInfo().notificationId }

        assertThat(ids.toSet()).hasSize(Catalog.all.size)
        assertThat(ids).doesNotContain(RecordingService.NOTIFICATION_ID)
    }

    // The foreground notification shows the percentage, updated once per percent rather than per 256 KiB block.
    @Test
    fun notificationShowsThePercentage() = runBlocking {
        DownloadWorker.downloaderFactory = reporting((1L..1_000L).map { DownloadProgress(it, 1_000) })
        val shown = mutableListOf<ForegroundInfo>()
        val worker = builder()
            .setForegroundUpdater { _, _, info -> shown += info; Futures.immediateFuture(null) }
            .build()

        worker.doWork()

        val percents = shown.map { it.notification.extras.getInt(Notification.EXTRA_PROGRESS) }
        assertThat(percents).containsAtLeast(0, 50, 100).inOrder()
        assertThat(shown.size).isAtMost(102) // the first notification, then one per percent
        assertThat(shown.last().notification.extras.getInt(Notification.EXTRA_PROGRESS_MAX)).isEqualTo(100)
        assertThat(shown.last().notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString()).isEqualTo("100%")
    }

    // WorkManager writes each progress update to its database: once per percent is enough for the screens.
    @Test
    fun progressIsPublishedOncePerPercent() = runBlocking {
        DownloadWorker.downloaderFactory = reporting((1L..1_000L).map { DownloadProgress(it, 1_000) })
        val published = mutableListOf<Data>()
        val worker = builder()
            .setProgressUpdater { _, _, data -> published += data; Futures.immediateFuture(null) }
            .build()

        worker.doWork()

        assertThat(published.size).isAtMost(101)
        assertThat(published.last().getLong(DownloadWorker.KEY_BYTES, -1)).isEqualTo(1_000L)
    }

    // After a crash, WorkManager can run a download before the app's reconcile does; a tombstone stops it first.
    @Test
    fun aModelWithATombstoneIsNotDownloaded() = runBlocking {
        modelsDir.mkdirs()
        File(modelsDir, "${model.fileName}.tombstone").writeText("cancel")
        DownloadWorker.downloaderFactory = { error("no download for a cancelled model") }

        val result = builder().build().doWork() as ListenableWorker.Result.Failure

        assertThat(result.outputData.getString(DownloadWorker.KEY_REASON)).isEqualTo("CANCELLED")
    }

    // The welcome flow or the Try tab may start a model that is already there: nothing is fetched again.
    @Test
    fun verifiedModelSucceedsWithoutDownloading() = runBlocking {
        modelsDir.mkdirs()
        RandomAccessFile(File(modelsDir, model.fileName), "rw").use { it.setLength(model.sizeBytes) }
        AppGraph.modelStore = ModelStore(modelsDir) { model.sha256 } // a stand-in hash: the sparse file checks out
        DownloadWorker.downloaderFactory = { error("no download expected") }

        assertThat(builder().build().doWork()).isEqualTo(ListenableWorker.Result.success())
    }

    @Test
    fun wifiOnlyNeedsUnmetered() {
        val wm = WorkManager.getInstance(context)

        DownloadWorker.enqueue(context, model, wifiOnly = true)
        val wifiOnly = wm.getWorkInfosForUniqueWork(DownloadWorker.workName(model)).get().single()
        assertThat(wifiOnly.constraints.requiredNetworkType).isEqualTo(NetworkType.UNMETERED)

        wm.cancelUniqueWork(DownloadWorker.workName(model))
        DownloadWorker.enqueue(context, model, wifiOnly = false)
        val anyNetwork = wm.getWorkInfosForUniqueWork(DownloadWorker.workName(model)).get()
            .single { it.state == androidx.work.WorkInfo.State.ENQUEUED }
        assertThat(anyNetwork.constraints.requiredNetworkType).isEqualTo(NetworkType.CONNECTED)
    }

    // A Wi-Fi-only request left waiting must not silently swallow a later "Download now" tap. KEEP would leave
    // the old UNMETERED request sitting ENQUEUED forever, since it never becomes the duplicate KEEP checks for.
    @Test
    fun downloadNowReplacesWaitingWifiOnly() {
        val wm = WorkManager.getInstance(context)
        DownloadWorker.enqueue(context, model, wifiOnly = true)

        val newId = DownloadWorker.enqueue(context, model, wifiOnly = false)

        val live = wm.getWorkInfosForUniqueWork(DownloadWorker.workName(model)).get()
            .filterNot { it.state.isFinished }
        assertThat(live.map { it.id }).containsExactly(newId)
        assertThat(live.single().constraints.requiredNetworkType).isEqualTo(NetworkType.CONNECTED)
    }
}
