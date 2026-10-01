package io.github.kabrapratik28.thumbfree.app

import android.app.Application
import android.content.Context
import android.os.SystemClock
import io.github.kabrapratik28.thumbfree.a11y.DictationAccessibilityService
import io.github.kabrapratik28.thumbfree.audio.AudioRecordSource
import io.github.kabrapratik28.thumbfree.audio.AudioSource
import io.github.kabrapratik28.thumbfree.audio.ForegroundHooks
import io.github.kabrapratik28.thumbfree.core.models.ModelLeases
import io.github.kabrapratik28.thumbfree.core.models.ModelStore
import io.github.kabrapratik28.thumbfree.core.models.ThreadPolicy
import io.github.kabrapratik28.thumbfree.data.HistoryDb
import io.github.kabrapratik28.thumbfree.data.Recovery
import io.github.kabrapratik28.thumbfree.data.Settings
import io.github.kabrapratik28.thumbfree.engine.Engine
import io.github.kabrapratik28.thumbfree.engine.RemoteEngine
import io.github.kabrapratik28.thumbfree.engine.TranscriptionQueue
import io.github.kabrapratik28.thumbfree.models.ModelDownloads
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The main process's objects, built once by ThumbFreeApp. The home screen reads controller, history, modelStore and settings. */
object AppGraph {
    lateinit var controller: DictationController
    lateinit var history: HistoryDb
    lateinit var modelStore: ModelStore
    lateinit var settings: Settings
    lateinit var queue: TranscriptionQueue
    lateinit var ports: AndroidPorts

    /** The process's one RemoteEngine (one per process): the queue's transcribes and the live preview's stream. */
    lateinit var engine: Engine

    /** Takes hold the model they use, and a delete must have it free. */
    var leases = ModelLeases()

    private val version = MutableStateFlow(0)

    /** Bumped after every committed history write; the home screen collects it and reloads. */
    val historyVersion: StateFlow<Int> = version.asStateFlow()

    private val moves = MutableStateFlow(0)

    /** Bumped when the owner drops the bubble somewhere new; Settings > Bubble collects it for Reset position. */
    val bubbleMoves: StateFlow<Int> = moves.asStateFlow()

    internal fun bubbleMoved() = moves.update { it + 1 }

    /** Tests replace it before a take starts. */
    @Volatile var audioSourceFactory: (Context) -> AudioSource = { AudioRecordSource(it) }

    var initialized = false
        private set

    /** Main process only (ThumbFreeApp); a second call does nothing. */
    fun init(app: Application) {
        if (initialized) return
        history = HistoryDb(app)
        modelStore = ModelStore(File(app.filesDir, "models"))
        // Finishes a model cancel or delete that the last process accepted but died before finishing.
        ModelDownloads.reconcile(app)
        // Loads the file in the background from here, so a take's first read on main finds it in memory.
        settings = Settings(app.getSharedPreferences("settings", Context.MODE_PRIVATE))
        engine = RemoteEngine(app)
        ports = AndroidPorts(app)
        controller = DictationController(ports, SystemClock::elapsedRealtime, customWords = { settings.customWords })
        val worker = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
        // A new model file takes seconds to hash. That happens here, ahead of the first take and on the queue's own
        // thread, not on the history thread: a take waits at most 500 ms for its row.
        worker.launch { runCatching { modelStore.status(settings.model) } }
        queue = TranscriptionQueue(
            engine,
            worker,
            modelPath = ports::modelPath,
            // A cap: :engine counts the fast CPUs its own cpuset allows (EngineService.load), since this process's can
            // differ.
            threads = { ThreadPolicy.MAX_THREADS },
            listener = ports,
            language = ports::language,
            beforeTranscribe = ports::awaitPreviewEnded,
        )
        DictationAccessibilityService.listener = ports
        ForegroundHooks.listener = ports
        // First on the history thread, so the first take and the first history load (which waits on db.barrier())
        // come after it: a take the process died in shows INTERRUPTED, never "In progress".
        ports.db.write { Recovery.run(history, app.filesDir) }
        ports.db.write { history.applyRetention(settings.retention, System.currentTimeMillis(), app.filesDir) }
        initialized = true
    }

    internal fun historyWritten() = version.update { it + 1 }
}
