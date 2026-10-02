package io.github.kabrapratik28.thumbfree.models

import android.content.Context
import android.util.Log
import androidx.work.Data
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import io.github.kabrapratik28.thumbfree.app.AppGraph
import io.github.kabrapratik28.thumbfree.core.models.Catalog
import io.github.kabrapratik28.thumbfree.core.models.DownloadResult
import io.github.kabrapratik28.thumbfree.core.models.ModelFile
import io.github.kabrapratik28.thumbfree.core.models.ModelStatus
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One model's download, as the welcome flow, the Home tab and the Models screen show it. */
sealed interface DownloadState {
    data object NotDownloaded : DownloadState
    /**
     * Waiting to start: for Wi-Fi when [wifiOnly], else for any connection; or, [retrying], waiting out the pause
     * WorkManager takes before it starts a stopped download again by itself, from where it stopped, network or not.
     */
    data class Queued(val wifiOnly: Boolean, val retrying: Boolean = false) : DownloadState
    data class Downloading(val bytes: Long, val total: Long) : DownloadState
    /** Every byte is in; the file's SHA-256 is being checked before it can be used. */
    data object Verifying : DownloadState
    data object Ready : DownloadState
    data class Failed(val reason: FailReason) : DownloadState
}

/** Why a download stopped, in terms the screens can say in plain words. */
enum class FailReason { NO_INTERNET, NOT_ENOUGH_SPACE, INTERRUPTED, FILE_CHECK_FAILED }

/**
 * Starts, cancels and deletes model downloads, and reports each model's [DownloadState]. The only network use in the
 * app is [DownloadWorker]'s download. Every call returns at once; cancel and delete first write their few-byte durable
 * record, so a crash can't lose them. The rest runs in the background, one call at a time per model, and the newest
 * call wins.
 */
object ModelDownloads {
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, e -> Log.w(TAG, "download_task_error ${e.javaClass.name}") },
    )
    private val lanes = ConcurrentHashMap<String, CoroutineDispatcher>() // by model id: one call at a time, in call order
    private val recordLocks = ConcurrentHashMap<String, Any>() // by model id: around each read and write of its tombstone
    // Every start, cancel and delete gets the next number. Seeded from the clock, so a number a download got in a process
    // that has since died is older than any this process gives.
    private val sequence = AtomicLong(System.currentTimeMillis())
    private val lastStart = ConcurrentHashMap<String, Long>()
    private val lastStop = ConcurrentHashMap<String, Long>() // the newest cancel or delete
    private val refused = MutableStateFlow(emptySet<String>()) // model ids whose last start found too little space
    private val changed = MutableStateFlow(0) // bumped by each tombstone change and each cleanup's result

    @Volatile private var all: StateFlow<Map<ModelFile, DownloadState>>? = null

    /**
     * Every catalog model's live state, one flow for the process, so the screens and the bubble read the same states.
     * Empty until each model's first state is known; it follows WorkManager while anything collects it.
     */
    fun states(context: Context): StateFlow<Map<ModelFile, DownloadState>> = all ?: synchronized(this) {
        all ?: combine(Catalog.all.map { model -> state(context, model).map { model to it } }) { it.toMap() }
            .stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyMap()).also { all = it }
    }

    /**
     * The live state. The first value can take seconds when a file already on disk has not been checked yet. A retry's
     * pause is read again once it ends: if the download then waits for Wi-Fi, nothing else would say so.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun state(context: Context, model: ModelFile): Flow<DownloadState> {
        val app = context.applicationContext
        return combine(
            WorkManager.getInstance(app).getWorkInfosForUniqueWorkFlow(DownloadWorker.workName(model)),
            refused,
            changed,
        ) { infos, refusedIds, _ -> infos to (model.id in refusedIds) }
            .flatMapLatest { (infos, isRefused) ->
                readAgainAt(System::currentTimeMillis) { now ->
                    downloadState(model, infos, isRefused, deletePending(app, model), now) { AppGraph.modelStore.status(model) } to
                        currentWork(infos)?.takeIf { inRetryPause(it, now) }?.nextScheduleTimeMillis
                }
            }
            .distinctUntilChanged()
            .flowOn(Dispatchers.IO)
    }

    /**
     * Downloads [model], resuming a partial file a failed or stopped download left. [wifiOnly] waits for Wi-Fi; false is
     * "use mobile data this time". Refused, with Failed(NOT_ENOUGH_SPACE), when the space it needs plus 1 GiB is not
     * free. A model already here is not fetched again. A later start, cancel or delete wins over it.
     */
    fun start(context: Context, model: ModelFile, wifiOnly: Boolean = AppGraph.settings.wifiOnly) {
        val app = context.applicationContext
        val start = sequence.incrementAndGet().also { mark(lastStart, model, it) }
        onLane(model) {
            // Only the newest start, with no cancel or delete after it, checked before anything is published or queued.
            if (!newest(model, start)) return@onLane
            val space = DownloadWorker.downloaderFactory(modelsDir(app)).hasSpaceFor(model)
            if (!newest(model, start)) return@onLane
            refused.update { if (space) it - model.id else it + model.id }
            if (!space) return@onLane
            supersede(app, model, start)
            DownloadWorker.enqueue(app, model, wifiOnly, start)
        }
    }

    /** The same call as [start]: a failed download keeps its partial file, so it goes on from where it stopped. */
    fun resume(context: Context, model: ModelFile) = start(context, model)

    /**
     * Stops the download, or its wait for a network, and removes its partial file. A start called after it wins and
     * resumes that file instead.
     */
    fun cancel(context: Context, model: ModelFile) {
        val app = context.applicationContext
        val stop = sequence.incrementAndGet()
        val record = record(app, model, CANCEL, stop)
        mark(lastStop, model, stop)
        refused.update { it - model.id }
        onLane(model) {
            if (startedSince(model, stop)) return@onLane // checked before any partial file is deleted
            finishStop(app, model, deleteModel = false, record)
        }
    }

    /**
     * Deletes [model]'s file, its check and any partial download. Returns false, deleting nothing, while a take, Retry
     * or history Transcribe holds the model, or when the delete could not be recorded on disk.
     */
    fun delete(context: Context, model: ModelFile): Boolean {
        // The model to itself until its files are gone; a take that starts meanwhile gets no lease on it.
        val reservation = AppGraph.leases.tryDelete(model)
        if (reservation == 0L) return false
        val app = context.applicationContext
        val stop = sequence.incrementAndGet()
        val record = record(app, model, DELETE, stop)
        if (record == null) {
            AppGraph.leases.deleted(model, reservation)
            return false
        }
        mark(lastStop, model, stop)
        refused.update { it - model.id }
        onLane(model) {
            try {
                if (startedSince(model, stop)) return@onLane // a start called after this delete wins
                finishStop(app, model, deleteModel = true, record)
            } finally {
                AppGraph.leases.deleted(model, reservation)
            }
        }
        return true
    }

    /**
     * At app start (AppGraph.init): finishes each cancel or delete that a process accepted and died before finishing. A
     * delete needs the model to itself; while a take holds it, it waits for the next start.
     */
    internal fun reconcile(context: Context) {
        val app = context.applicationContext
        for (model in DownloadWorker.catalog) onLane(model) {
            val file = tombstone(app, model)
            if (!file.exists()) return@onLane
            val record = readOrNull(file)
            if (kindOf(record) == CANCEL) return@onLane finishStop(app, model, deleteModel = false, record)
            val reservation = AppGraph.leases.tryDelete(model)
            if (reservation == 0L) {
                Log.w(TAG, "download_reconcile_busy model=${model.fileName}")
                return@onLane
            }
            try {
                finishStop(app, model, deleteModel = true, record)
            } finally {
                AppGraph.leases.deleted(model, reservation)
            }
        }
    }

    /**
     * For the worker, before it downloads and before it publishes: no cancel or delete since the start numbered
     * [start], and none on disk from a process that died. A request with no number (0) has only the latter.
     */
    internal fun stillWanted(context: Context, model: ModelFile, start: Long): Boolean =
        (start == 0L || !stoppedSince(model, start)) && !tombstone(context.applicationContext, model).exists()

    /**
     * Cancels the work, then removes the partial download, and with [deleteModel] the model and its check. The tombstone
     * goes last, only once they have all gone, and only while it still holds [record], the one this cleanup was for: a
     * newer cancel or delete wrote its own, which its cleanup or the next app start removes. A file that stays is
     * reported, and the next app start tries again. Every result refreshes the state.
     */
    private fun finishStop(app: Context, model: ModelFile, deleteModel: Boolean, record: String?) {
        try {
            DownloadWorker.cancel(app, model).result.get()
            val dir = modelsDir(app)
            DownloadWorker.downloaderFactory(dir).discardPartial(model) // waits for a stopped run to let go of it
            if (deleteModel) AppGraph.modelStore.delete(model)
            val names = listOf(".part", ".part.etag") + if (deleteModel) listOf("", ".verified") else emptyList()
            val left = names.map { File(dir, model.fileName + it) }.filter { it.exists() }
            if (left.isEmpty()) {
                synchronized(recordLock(model)) {
                    val file = tombstone(app, model)
                    if (record != null && readOrNull(file) == record) file.delete()
                }
            } else {
                Log.w(TAG, "download_cleanup_failed model=${model.fileName} left=${left.joinToString { it.name }}")
            }
        } finally {
            changed.update { it + 1 }
        }
    }

    /**
     * The durable record of a cancel or delete numbered [n], "kind n", on disk before the call returns: written to a
     * temporary file, then renamed over the old record. A pending delete (anything that does not read as a cancel
     * counts as one) is never turned into a cancel. Returns the record written, or null when none was: a pending delete
     * stayed, or the write failed.
     */
    private fun record(app: Context, model: ModelFile, kind: String, n: Long): String? {
        val written = synchronized(recordLock(model)) {
            val file = tombstone(app, model)
            try {
                if (kind == CANCEL && file.exists() && kindOf(readOrNull(file)) == DELETE) {
                    null
                } else {
                    val record = "$kind $n"
                    file.parentFile?.mkdirs()
                    val temp = File(file.path + ".tmp").apply { writeText(record) }
                    Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                    record
                }
            } catch (e: IOException) {
                Log.w(TAG, "download_record_failed model=${model.fileName} ${e.javaClass.name}")
                null
            }
        }
        changed.update { it + 1 }
        return written
    }

    /** A start takes over from an older cancel or delete: its record goes, but never one numbered after [start]. */
    private fun supersede(app: Context, model: ModelFile, start: Long) {
        synchronized(recordLock(model)) {
            val file = tombstone(app, model)
            val record = readOrNull(file) ?: return
            if ((record.substringAfter(' ').toLongOrNull() ?: 0L) < start) file.delete()
        }
        changed.update { it + 1 }
    }

    /** A delete still on disk reads as gone, so a screen never offers a model that is being deleted. */
    private fun deletePending(app: Context, model: ModelFile): Boolean {
        val file = tombstone(app, model)
        return file.exists() && kindOf(readOrNull(file)) == DELETE
    }

    private fun onLane(model: ModelFile, block: () -> Unit) {
        scope.launch(lanes.computeIfAbsent(model.id) { Dispatchers.IO.limitedParallelism(1) }) { block() }
    }

    private fun mark(last: ConcurrentHashMap<String, Long>, model: ModelFile, n: Long) {
        last.merge(model.id, n, ::maxOf)
    }

    private fun newest(model: ModelFile, start: Long) = lastStart[model.id] == start && !stoppedSince(model, start)

    private fun stoppedSince(model: ModelFile, n: Long) = (lastStop[model.id] ?: 0L) > n

    private fun startedSince(model: ModelFile, n: Long) = (lastStart[model.id] ?: 0L) > n

    private fun recordLock(model: ModelFile): Any = recordLocks.computeIfAbsent(model.id) { Any() }

    /** Anything that does not read as a cancel, torn or unknown, counts as a delete: the stronger intent. */
    private fun kindOf(record: String?) = if (record?.substringBefore(' ') == CANCEL) CANCEL else DELETE

    private fun modelsDir(app: Context) = File(app.filesDir, "models")

    /** Reads every model's state again, for a change its flow can't see: a device test that swaps the model folder. */
    internal fun refresh() = changed.update { it + 1 }

    /** The space [model]'s download needs, as its own check counts it, for the finish to say. Reads the disk: off main. */
    fun spaceNeeded(context: Context, model: ModelFile): Long =
        DownloadWorker.downloaderFactory(modelsDir(context.applicationContext)).spaceNeeded(model)

    private fun tombstone(app: Context, model: ModelFile) = File(modelsDir(app), "${model.fileName}.tombstone")

    private fun readOrNull(file: File): String? = try { file.readText() } catch (e: IOException) { null }

    private const val CANCEL = "cancel"
    private const val DELETE = "delete"
    private const val TAG = "ThumbFree"
}

/**
 * A model's work as the screens follow it: the unfinished one, else the newest. A new request (REPLACE) deletes the older
 * records of its unique name, so there is one.
 */
internal fun currentWork(infos: List<WorkInfo>): WorkInfo? = infos.firstOrNull { !it.state.isFinished } ?: infos.firstOrNull()

/**
 * Whether [work] waits out WorkManager's pause before it runs again a download that asked for a retry: queued after a
 * run, stopped by nothing but itself (a system stop, such as a lost connection, records its reason and is a plain wait
 * again), its next run still ahead of [now].
 */
internal fun inRetryPause(work: WorkInfo, now: Long): Boolean = work.state == WorkInfo.State.ENQUEUED && work.runAttemptCount > 0 &&
    work.stopReason == WorkInfo.STOP_REASON_NOT_STOPPED && work.nextScheduleTimeMillis > now

/**
 * [read] at [clock]'s time, and again whenever the time it names (a retry pause's end) has passed, since nothing else
 * would read the state again then; done once a read names none.
 */
internal fun <T> readAgainAt(clock: () -> Long, read: (now: Long) -> Pair<T, Long?>): Flow<T> = flow {
    while (true) {
        val now = clock()
        val (value, again) = read(now)
        emit(value)
        if (again == null) break
        delay(maxOf(1L, again - now + 1))
    }
}

/**
 * A delete still pending on disk decides first ([deletePending]), then unfinished work. After those the
 * file does: ready once verified, else the newest failure, a refused start or a file that fails its check. [status] may
 * hash the whole file, so it runs only when nothing before it decided. Work waiting to run again after a stop, its next
 * run still ahead of [now] (WorkManager's backoff), is retrying: it starts by itself then, whatever the network, so it
 * is never a wait for Wi-Fi.
 */
internal fun downloadState(
    model: ModelFile, infos: List<WorkInfo>, refused: Boolean, deletePending: Boolean, now: Long, status: () -> ModelStatus,
): DownloadState {
    if (deletePending) return DownloadState.NotDownloaded
    val work = currentWork(infos)
    when (work?.state) {
        WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED ->
            return DownloadState.Queued(work.constraints.requiredNetworkType == NetworkType.UNMETERED, inRetryPause(work, now))
        WorkInfo.State.RUNNING -> {
            val bytes = work.progress.getLong(DownloadWorker.KEY_BYTES, 0)
            val total = work.progress.getLong(DownloadWorker.KEY_TOTAL, model.sizeBytes)
            return if (total > 0 && bytes >= total) DownloadState.Verifying else DownloadState.Downloading(bytes, total)
        }
        else -> {}
    }
    val file = try { status() } catch (e: Exception) { ModelStatus.CHECK_FAILED }
    val failure = work?.takeIf { it.state == WorkInfo.State.FAILED }?.let { failReason(it.outputData) }
    return when {
        file == ModelStatus.VERIFIED -> DownloadState.Ready
        refused -> DownloadState.Failed(FailReason.NOT_ENOUGH_SPACE)
        failure != null -> DownloadState.Failed(failure)
        file == ModelStatus.MISSING -> DownloadState.NotDownloaded
        else -> DownloadState.Failed(FailReason.FILE_CHECK_FAILED) // on disk but wrong: a download replaces it
    }
}

/** Null for a download a cancel or delete stopped: that is not a failure. */
private fun failReason(output: Data): FailReason? =
    when (DownloadResult.Reason.entries.firstOrNull { it.name == output.getString(DownloadWorker.KEY_REASON) }) {
        DownloadResult.Reason.NO_SPACE -> FailReason.NOT_ENOUGH_SPACE
        DownloadResult.Reason.SIZE_MISMATCH, DownloadResult.Reason.HASH_MISMATCH -> FailReason.FILE_CHECK_FAILED
        // The worker decides "no internet" from the phone's validated connectivity, not from the exception.
        DownloadResult.Reason.HTTP, DownloadResult.Reason.NETWORK, DownloadResult.Reason.STALLED ->
            if (output.getBoolean(DownloadWorker.KEY_OFFLINE, false)) FailReason.NO_INTERNET else FailReason.INTERRUPTED
        DownloadResult.Reason.CANCELLED -> null
        null -> FailReason.INTERRUPTED
    }
