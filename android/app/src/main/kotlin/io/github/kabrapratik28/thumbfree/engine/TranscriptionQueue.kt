package io.github.kabrapratik28.thumbfree.engine

import android.os.SystemClock
import android.util.Log
import io.github.kabrapratik28.thumbfree.core.audio.Chunk
import io.github.kabrapratik28.thumbfree.core.session.Code
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Sends chunk jobs to the [Engine] one at a time, in the order they arrive. A session reads [modelPath], [threads] and
 * [language] (its model's language hint, null for none) once, at its first job that needs the engine, and keeps them
 * for all its chunks, so a model change waits for the next take. [cancel] drops the session's queued jobs, discards
 * the result of its running chunk and asks the engine to stop that chunk (Parakeet stops within an encoder graph node
 * since patch 0007). Each transcribe gets a run token and the abort names it, so an abort that lands late can only stop
 * the chunk it was sent for. Once no take is left, the model stays loaded for [unloadAfterIdleMs]; then the engine
 * unloads it, which ends the :engine process and returns its memory, and the next job loads it again.
 */
class TranscriptionQueue(
    private val engine: Engine,
    private val scope: CoroutineScope, // one worker; jobs run in FIFO order
    private val modelPath: (sessionId: String) -> String?, // null: no verified model
    private val threads: () -> Int,
    private val listener: Listener,
    private val language: (sessionId: String) -> String? = { "en" },
    private val unloadAfterIdleMs: Long = UNLOAD_AFTER_IDLE_MS, // Long.MAX_VALUE never unloads
    private val clock: () -> Long = SystemClock::elapsedRealtime,
    private val log: (String) -> Unit = { Log.i("ThumbFree", it) }, // tests pass a recorder
    // Awaited before each transcribe: the live preview's stream, once its take has stopped, is freed in :engine first,
    // so the final chunk never runs behind preview work (AndroidPorts.awaitPreviewEnded).
    private val beforeTranscribe: suspend () -> Unit = {},
) {
    /**
     * Called by the worker while it holds the queue's lock, so nothing reaches a session after its [cancel] returns.
     * Post and return; never call the queue or take a lock that is also held around a queue call.
     */
    interface Listener {
        fun onLoading(sessionId: String)
        fun onLoaded(sessionId: String)
        fun onChunkDone(sessionId: String, index: Int, text: String, rawText: String) // EngineResult.text and .rawText
        /**
         * Chunks 0 until total, in order. [speech]: Silero heard a chunk, or its check failed open; otherwise every
         * text is empty and the take is no speech. [language]: the one the engine was told with this session's
         * model, null for none (the multilingual model, or a session that never needed the engine).
         */
        fun onDone(sessionId: String, texts: List<String>, rawTexts: List<String>, speech: Boolean, language: String?)
        fun onFailed(sessionId: String, code: Code)
    }

    // One take, or one retranscription of it. cancel and the end of the take remove it from sessions, which makes its
    // queued jobs and its running chunk's result stale; the next job for the same id starts a new Session.
    private class Session(val id: String) {
        var submitted = 0
        var total = -1 // until finish
        var model: String? = null // read with threads and language at the first job that needs the engine
        var threads = 0
        var language: String? = null
        var failure: Code? = null
        var speech = false // a chunk went to the transcribe: Silero heard it, or its check failed open
        val texts = mutableListOf<String>() // in index order: the worker takes jobs in the order they were numbered
        val rawTexts = mutableListOf<String>()
    }

    private sealed class Job
    private sealed class SessionJob(val session: Session) : Job()
    private class Load(session: Session) : SessionJob(session)
    private class Transcribe(session: Session, val index: Int, val wavPath: String, val chunk: Chunk) :
        SessionJob(session)
    private class Finish(session: Session) : SessionJob(session)
    // Sent by the idle timer, so it runs between jobs, never during a load or a transcribe.
    private class IdleUnload(val epoch: Long, val since: Long) : Job()

    private val lock = Any() // guards sessions, running, the tokens, Session.submitted and Session.total
    private val sessions = HashMap<String, Session>()
    private var running: Session? = null // whose chunk the engine is transcribing
    private var runningToken = 0L // the run token of that chunk's transcribe
    private var lastToken = 0L // run tokens count up from 1, one per transcribe
    private var loaded: String? = null // the engine's model; worker only
    private var engineUp = false // worker only; a load was sent since the last unload or death, so :engine may be up
    private var idleUnloaded = false // worker only; the next load is logged as an idle reload
    private var epoch = 0L // lock; every job sent and every idle timer started changes it
    private val jobs = Channel<Job>(Channel.UNLIMITED)

    /** Transcribe calls sent to the engine, for tests. */
    @Volatile var engineCalls = 0
        private set

    init {
        scope.launch {
            for (job in jobs) {
                // Anything else that goes wrong (a model file error, an unexpected Binder or listener exception) fails
                // only this job's session, so later takes still run.
                try {
                    when (job) {
                        is IdleUnload -> unloadIfIdle(job)
                        is SessionJob -> run(job)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    loaded = null
                    try {
                        if (job is SessionJob) fail(job.session, Code.ENGINE_CRASHED)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // The listener threw again; drop it and keep serving later jobs.
                    }
                }
                // After the last take's last job :engine stays up for unloadAfterIdleMs, unless another job comes.
                if (job is SessionJob && engineUp) {
                    synchronized(lock) { if (sessions.isEmpty()) startIdleTimer() }
                }
            }
        }
    }

    fun ensureLoaded(sessionId: String) = enqueue(sessionId) { Load(it) }

    fun submit(sessionId: String, wavPath: String, chunk: Chunk) =
        enqueue(sessionId) { Transcribe(it, it.submitted++, wavPath, chunk) }

    fun finish(sessionId: String, totalChunks: Int) = enqueue(sessionId) {
        it.total = totalChunks
        Finish(it)
    }

    fun cancel(sessionId: String) {
        val session = synchronized(lock) {
            sessions.remove(sessionId)?.also { if (sessions.isEmpty()) startIdleTimer() }
        } ?: return
        // abort is a Binder call, so it runs in scope rather than on the caller's thread, and only if the cancelled
        // chunk is still the one running by then. It names that chunk's run, so if it reaches :engine after the chunk
        // ended, the next chunk runs on untouched.
        scope.launch {
            val token = synchronized(lock) { if (running === session) runningToken else 0L }
            if (token != 0L) engine.abort(token)
        }
    }

    private fun enqueue(sessionId: String, job: (Session) -> Job) {
        synchronized(lock) {
            epoch++ // an idle timer started before this job unloads nothing
            jobs.trySend(job(sessions.getOrPut(sessionId) { Session(sessionId) }))
        }
    }

    // Lock held, with no take left. A job or a newer timer changes the epoch before this one ends, so only the newest
    // timer can unload. delay counts only awake time, so the deadline is on the clock, which counts deep sleep too,
    // checked every 10 s: a night in a pocket unloads within 10 s of waking.
    private fun startIdleTimer() {
        if (unloadAfterIdleMs == Long.MAX_VALUE) return
        val started = ++epoch
        val since = clock()
        scope.launch {
            while (clock() - since < unloadAfterIdleMs) {
                if (synchronized(lock) { epoch != started }) return@launch
                delay(minOf(unloadAfterIdleMs - (clock() - since), 10_000L))
            }
            jobs.trySend(IdleUnload(started, since))
        }
    }

    // Only a job adds a take, and every job changes the epoch: an unchanged epoch means no take and no job came
    // since the timer started.
    private suspend fun unloadIfIdle(job: IdleUnload) {
        if (synchronized(lock) { epoch != job.epoch } || !engineUp) return
        engineUp = false
        loaded = null
        if (!engine.isAlive) return // :engine died while idle and holds nothing; its reload is a plain press
        engine.unload()
        idleUnloaded = true
        log("engine_unload idle_ms=${clock() - job.since}")
    }

    private suspend fun run(job: SessionJob) {
        val session = job.session
        if (job is Finish) return report(session) { settle(session) }
        if (!live(session)) return
        if (job is Transcribe && !job.chunk.hasSpeech) return done(job, "", "") // no frame above -55 dBFS: no check
        val path = session.model
            ?: modelPath(session.id)?.also {
                session.model = it
                session.threads = threads()
                session.language = language(session.id)
            }
            ?: return fail(session, Code.NO_MODEL)
        if (!live(session)) return // cancelled while modelPath hashed: nothing to load for it
        // :engine died while idle and took the model with it, so ensureLoaded loads it again at press.
        if (!engine.isAlive) loaded = null
        // A dead engine has lost its model: load it again and run the job once more.
        repeat(2) { attempt ->
            try {
                if (loaded != path && !load(session, path, retry = attempt > 0)) return
                if (job is Transcribe && live(session)) transcribe(job)
                return
            } catch (e: EngineDiedException) {
                loaded = null
                engineUp = false
                if (!live(session)) return
            }
        }
        fail(session, Code.ENGINE_CRASHED)
    }

    // Frees another model first. Returns false once a failed load is reported.
    private suspend fun load(session: Session, path: String, retry: Boolean): Boolean {
        if (loaded != null) engine.unload()
        loaded = null
        report(session) { listener.onLoading(session.id) }
        val start = clock()
        engineUp = true // a failed load can leave :engine up without a model
        val status = engine.load(path, session.threads)
        if (status != 0) {
            log("engine_load_failed status=$status ms=${clock() - start}")
            fail(session, if (status == 7) Code.NO_MEMORY else Code.LOAD_FAILED)
            return false
        }
        loaded = path
        val reason = when {
            retry -> "retry"
            idleUnloaded -> "idle-reload"
            else -> "press"
        }
        log("engine_load ms=${clock() - start} reason=$reason")
        idleUnloaded = false
        report(session) { listener.onLoaded(session.id) }
        return true
    }

    private suspend fun transcribe(job: Transcribe) {
        val session = job.session
        val token = synchronized(lock) {
            running = session
            runningToken = ++lastToken
            runningToken
        }
        engineCalls++
        beforeTranscribe()
        val result = try {
            val allowRetry = job.chunk.speechFrames >= RETRY_MIN_SPEECH_FRAMES
            // Silero checks every chunk that gets here, whatever the gate heard.
            engine.transcribe(
                job.wavPath, job.chunk.fromSample, job.chunk.toSample, session.language, allowRetry, speechCheck = true,
                token = token,
            )
        } finally {
            synchronized(lock) {
                running = null
                runningToken = 0L
            }
        }
        when (result.status) {
            0 -> if (result.vadRejected) done(job, "", "") else done(job, result.text, result.rawText, heard = true)
            18 -> { // the native bridge keeps the text decoded before the cut
                report(session) { listener.onChunkDone(session.id, job.index, result.text, result.rawText) }
                fail(session, Code.TRUNCATED)
            }
            17 -> fail(session, Code.INPUT_TOO_LONG)
            else -> fail(session, Code.ENGINE_CRASHED)
        }
    }

    private fun done(job: Transcribe, text: String, rawText: String, heard: Boolean = false) {
        val session = job.session
        report(session) {
            if (heard) session.speech = true
            session.texts += text
            session.rawTexts += rawText
            listener.onChunkDone(session.id, job.index, text, rawText)
            settle(session)
        }
    }

    private fun fail(session: Session, code: Code) = report(session) {
        session.failure = code
        if (session.total >= 0) sessions.remove(session.id, session) // after finish, the failure ends the take
        listener.onFailed(session.id, code)
    }

    // Lock held. Once finish has set the total, ends the session: with its failure again, because failures during the
    // recording are ignored, or with onDone as soon as every chunk up to the total has a result.
    private fun settle(session: Session) {
        val total = session.total
        val failure = session.failure
        if (total < 0 || (failure == null && session.texts.size < total)) return
        if (!sessions.remove(session.id, session)) return // a listener already cancelled it (and maybe resubmitted)
        if (failure != null) {
            listener.onFailed(session.id, failure)
        } else {
            listener.onDone(
                session.id, session.texts.take(total), session.rawTexts.take(total), session.speech, session.language,
            )
        }
    }

    // Runs block under the lock, and only while session is current.
    private fun report(session: Session, block: () -> Unit) = synchronized(lock) {
        if (sessions[session.id] === session) block()
    }

    private fun live(session: Session) =
        synchronized(lock) { sessions[session.id] === session } && session.failure == null

    private companion object {
        // ponytail: fixed at 5 min; a model_unload_timeout setting (never, immediately, 2, 5, 10, 15 or 60 min) is the
        // upgrade path.
        const val UNLOAD_AFTER_IDLE_MS = 300_000L

        // Gate speech frames a chunk needs before an empty Canary run may be retried without PnC, which invents words on
        // noise (8.8% of non-speech clips against 4.4% with PnC). Measured through this gate and planner: the 5 public
        // clips where the retry invented words have at most 23; the 7 private clips that PnC on left empty have 182 to
        // 408. 30 was set above the 29 frames the gate's old cold start gave steady noise.
        const val RETRY_MIN_SPEECH_FRAMES = 30
    }
}
