package io.github.kabrapratik28.thumbfree.engine

import android.app.Service
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.IBinder
import android.os.PowerManager
import android.os.Process
import android.system.Os
import android.util.Log
import io.github.kabrapratik28.thumbfree.core.audio.Padding
import io.github.kabrapratik28.thumbfree.core.audio.PreviewGate
import io.github.kabrapratik28.thumbfree.core.audio.Wav
import io.github.kabrapratik28.thumbfree.core.models.AsrPolicy
import io.github.kabrapratik28.thumbfree.core.models.AsrProfile
import io.github.kabrapratik28.thumbfree.core.models.EngineKnobs
import io.github.kabrapratik28.thumbfree.core.models.ThreadPolicy
import io.github.kabrapratik28.thumbfree.core.session.Preview
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors

/**
 * Runs in :engine, the only process that loads a model, so a native abort or the low-memory killer takes only this
 * process. Holds one [NativeEngine] handle. Bound only by [RemoteEngine].
 */
class EngineService : Service() {
    // load, transcribe, unload and info run one at a time (transcribe.h threading contract), under gate.lock. abort has
    // to reach a running transcribe, so it takes only handleLock, which every change of handle takes too: nativeAbort
    // must not race nativeFree. The live preview's stream calls go through the gate too and give way to offline calls
    // (AsrGate), so a transcribe never waits behind preview work admitted after it came.
    private val gate = AsrGate()
    private val handleLock = Any()
    private var handle = 0L
    private var loadedFile: String? = null // the loaded model's file name (gate.lock)
    private var generation = 0L // counts loads and frees (gate.lock): a stream is good only on the one it began on
    private var vad = 0L // Silero, loaded with the model; 0: none, and the speech check fails open

    // Live preview: the take whose stream is open (0: none), the generation it began on, and its gate: Silero run as a
    // stream (a Silero context of its own) deciding which audio reaches the stream, and the gated audio the stream has
    // not taken yet. The gate's parts are guarded by streamLock, taken after gate.lock when both are held.
    @Volatile private var streamToken = 0L
    private var streamGeneration = 0L
    private val streamLock = Any()
    private var streamVad = 0L
    private var previewGate: PreviewGate? = null
    private val backlog = StreamBacklog()
    private val chunk = FloatArray(StreamBacklog.FIRST) // gate.lock
    private var committed = "" // the stream's text after its last chunk (streamLock)
    private var tentative = ""
    private var committedMs = 0L // the audio it has decoded (streamLock)

    // Every native call on the loaded model runs on this one long-lived thread, thread 0 of the model's CPU pool, so
    // its CPUs and its performance hints stay put from take to take. Binder threads hand calls to it and wait.
    private val asr = Executors.newSingleThreadExecutor { Thread(it, "asr").apply { isDaemon = true } }
    private var knobs = EngineKnobs()
    private var base: AsrProfile? = null // the loaded model's profile with no thermal pressure
    private var profile: AsrProfile? = null // the one in force (asr thread)

    @Volatile private var vadCpus: IntArray? = null // Silero's CPUs (null: any), from THUMBFREE_VAD_CPUS
    private var vadCpusSet: IntArray? = null // the speech-check thread's own (read and written on it only)
    private var debuggable = false // debug builds read engine-env.txt and log each call's stage split

    private val check = SpeechCheck(
        { pcm ->
            val cpus = vadCpus
            if (!cpus.contentEquals(vadCpusSet)) { // a system call only when the placement changes
                NativeEngine.nativeSetCurrentThreadCpus(cpus)
                vadCpusSet = cpus
            }
            if (vad > 0) NativeEngine.nativeVadProbs(vad, pcm, pcm.size) else null
        },
        Executors.newSingleThreadExecutor { Thread(it, "speech-check").apply { isDaemon = true } },
    ) { Log.i("ThumbFree", it) }

    private val binder = object : IEngine.Stub() {
        override fun load(modelPath: String, threads: Int): Int = gate.offline {
            free()
            // The fast CPUs this process may use, from its own cpuset, which can differ from the main process's: the
            // main process is top-app while its activity is on screen. The caller's count is a cap.
            val status = runCatching { File("/proc/self/status").readText() }.getOrNull()
            val freqs = ThreadPolicy.readMaxFreqsKHz()
            val allowed = status?.let(ThreadPolicy::allowedCpus)
            knobs = EngineKnobs.parse(System.getenv())
            val n = knobs.threads ?: ThreadPolicy.engineThreads(threads, freqs, status)
            val pinned = knobs.pin != EngineKnobs.Pin.OFF
            val fast = ThreadPolicy.fastCpus(freqs, allowed).takeIf { pinned && it.isNotEmpty() }
            base = AsrProfile(n, fast, strict = knobs.pin == EngineKnobs.Pin.STRICT, persistent = knobs.pool)
            val slow = ThreadPolicy.slowCpus(freqs, allowed)
            vadCpus = slow.toIntArray().takeIf { knobs.vadCpus == EngineKnobs.VadCpus.SLOW && slow.isNotEmpty() }
            Log.i("ThumbFree", "engine_threads n=$n cap=$threads $knobs")
            val loaded = onAsr { NativeEngine.nativeLoad(modelPath, n) }
            // A handle is positive and a failure is minus its status; 0 is neither, so it counts as INVALID_ARG.
            if (loaded <= 0) return if (loaded == 0L) 1 else (-loaded).toInt()
            synchronized(handleLock) { handle = loaded }
            loadedFile = File(modelPath).name
            onAsr {
                applyProfile()
                NativeEngine.nativeSetHints(loaded, knobs.hints, knobs.hintRtf)
            }
            // Silero never fails the load: without it, 0, the speech check fails open.
            vad = VadModel.open(this@EngineService, NativeEngine::nativeVadLoad) { Log.w("ThumbFree", it) }
            0
        }

        override fun transcribe(
            wavPath: String,
            fromSample: Long,
            toSample: Long,
            language: String?,
            allowRetry: Boolean,
            speechCheck: Boolean,
            token: Long,
        ): EngineResult = gate.offline {
            if (handle <= 0) return failed(1) // TRANSCRIBE_ERR_INVALID_ARG
            val start = System.nanoTime()
            // An exception would reach the caller as a null result: an unreadable range is a bad argument.
            val clip = runCatching { Wav.readFloat(wavPath, fromSample, toSample) }.getOrElse { return failed(1) }
            val pcm = Padding.forEngine(clip)
            val read = System.nanoTime()
            var nativeNs = 0L
            val asrRun = {
                onAsr {
                    applyProfile() // between jobs only
                    val t = System.nanoTime()
                    val result = NativeEngine.nativeTranscribe(handle, pcm, pcm.size, language, allowRetry, token)
                    nativeNs = System.nanoTime() - t
                    result
                }
            }
            // Silero hears the chunk as recorded; only the transcribe gets the padding.
            val r = if (speechCheck) check.run(clip, asrRun) else asrRun()
            // Debug builds, for phone measurements: the stage split of this call, numbers only: the WAV read and
            // padding, the native call and its mel, encoder and decoder, and the whole call, which adds the wait for
            // Silero (speech_check_wait); then :engine's resident memory after it (what a kept scheduler holds).
            if (debuggable) {
                Log.i("ThumbFree", "engine_stage samples=${pcm.size} read_ms=${ms(read - start)} " +
                    "native_ms=${ms(nativeNs)} mel_ms=${r?.melMs ?: 0f} enc_ms=${r?.encodeMs ?: 0f} " +
                    "dec_ms=${r?.decodeMs ?: 0f} total_ms=${ms(System.nanoTime() - start)} status=${r?.status ?: -1} " +
                    "rss_kb=${rssKb()}")
            }
            if (r == null) return EngineResult(0, "", "", false, false, false, 0f, 0L, vadRejected = true)
            EngineResult(r.status, r.text, r.rawText, r.truncated, r.aborted, r.retried, r.encodeMs, r.vmHwmKb)
        }

        override fun abort(token: Long) = synchronized(handleLock) { NativeEngine.nativeAbort(handle, token) }

        override fun unload() = gate.offline { free() }

        // Debug builds add the engine's effective environment (engine-env.txt and the app's own switches), for the phone
        // check's record: env=KEY=VALUE,...
        override fun info(): String = synchronized(gate.lock) {
            val env = System.getenv().filterKeys { it.startsWith("TRANSCRIBE_") || it.startsWith("THUMBFREE_") }
            NativeEngine.nativeInfo(handle) +
                if (debuggable) " env=" + env.entries.sortedBy { it.key }.joinToString(",") { "${it.key}=${it.value}" } else ""
        }

        // Live preview: 0 once take [token]'s stream is open on [modelFile]; 1 while that model is not the loaded one
        // (no model yet, or the previous take's: the queue is about to load this take's); BUSY while an offline call
        // waits or runs; NOT_IMPLEMENTED (from the engine, before it allocates anything) for a model that cannot
        // stream; NO_VAD when Silero cannot start, for which the preview goes for the take.
        override fun streamBegin(token: Long, modelFile: String?): Int =
            gate.preview({ StreamUpdate.BUSY }) {
                if (handle <= 0 || modelFile == null || modelFile != loadedFile) return@preview 1
                val vad = VadModel.open(this@EngineService, NativeEngine::nativeVadLoad) { Log.w("ThumbFree", it) }
                if (vad <= 0) return@preview StreamUpdate.NO_VAD
                // Debug builds: THUMBFREE_PREVIEW_BEGIN_FAILS=1 (engine-env.txt) asks for a chunk no model has, so the native
                // begin fails after it made the session: EngineServiceTest's check that a failed begin leaves none.
                val fails = debuggable && System.getenv("THUMBFREE_PREVIEW_BEGIN_FAILS") == "1"
                val chunkMs = if (fails) Preview.CHUNK_MS + 1 else Preview.CHUNK_MS
                val status = onAsr {
                    applyProfile() // between jobs only
                    NativeEngine.nativeStreamBegin(handle, token, Preview.LEFT_MS, chunkMs, Preview.RIGHT_MS, "en")
                }
                synchronized(streamLock) {
                    freeGate()
                    if (status == 0) {
                        streamVad = vad
                        previewGate = PreviewGate { samples, from, count -> backlog.add(samples, from, count) }
                    } else {
                        NativeEngine.nativeVadFree(vad)
                    }
                }
                streamToken = if (status == 0) token else 0L
                streamGeneration = generation
                status
            }

        // Live preview: [pcm16] is the take's next audio, all of it (16 kHz mono PCM16 little-endian, whole 512-sample
        // windows). Silero and the gate take it at once, outside the gate lock; the gated audio then goes to the stream
        // at most one chunk a call, and only while no offline call waits or runs (BUSY otherwise: nothing is lost, it
        // waits here).
        override fun streamFeed(token: Long, pcm16: ByteArray): StreamUpdate {
            if (token == 0L || token != streamToken) return StreamUpdate.of(StreamUpdate.STALE)
            synchronized(streamLock) {
                val g = previewGate
                if (token != streamToken || g == null) return StreamUpdate.of(StreamUpdate.STALE)
                val n = pcm16.size / 2 / PreviewGate.WINDOW * PreviewGate.WINDOW
                val pcm = FloatArray(n)
                val shorts = ByteBuffer.wrap(pcm16).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                for (i in 0 until n) pcm[i] = shorts.get(i) / 32_768f // as Wav.readFloat
                // Silero failing mid-take (it should not) ends the preview, as when it cannot start.
                val probs = if (n == 0) FloatArray(0) else {
                    NativeEngine.nativeVadStream(streamVad, pcm, n) ?: return StreamUpdate.of(StreamUpdate.NO_VAD)
                }
                for (w in probs.indices) g.window(pcm, w * PreviewGate.WINDOW, probs[w])
                if (backlog.overflowed) return StreamUpdate.of(StreamUpdate.BEHIND)
            }
            return gate.preview({ current(StreamUpdate.BUSY) }) {
                if (token != streamToken || generation != streamGeneration || handle <= 0) {
                    return@preview StreamUpdate.of(StreamUpdate.STALE) // ended, or its model went, meanwhile
                }
                val n = synchronized(streamLock) { backlog.next(chunk) }
                val update = onAsr { NativeEngine.nativeStreamFeed(handle, chunk, n) }
                // Numbers only (debug builds): the gated samples fed, the native time, how far the stream has got.
                if (debuggable && n > 0) {
                    Log.i("ThumbFree", "stream_feed samples=$n ms=${"%.1f".format(update.computeMs)} " +
                        "input_ms=${update.inputMs} committed_ms=${update.committedMs} status=${update.status}")
                }
                if (update.status != 0) return@preview update
                synchronized(streamLock) {
                    committed = update.committed
                    tentative = update.tentative
                    if (n > 0) committedMs = update.committedMs
                }
                current(0, update.computeMs)
            }
        }

        // Live preview: returns once take [token]'s stream has stopped (its chunk in progress within a node) and its
        // session and gate are freed; the caller awaits it before the take's final transcribe. The native end names the
        // take, so an end that overtakes its begin, or comes after the next take's, touches no other stream.
        override fun streamEnd(token: Long): Int {
            if (token <= 0L) return 0
            synchronized(handleLock) { NativeEngine.nativeStreamAbort(handle, token) }
            synchronized(gate.lock) {
                if (token == streamToken) freeStream()
            }
            return 0
        }

        // Live preview: frees any stream, whoever's (the setting turned off). Idempotent.
        override fun streamRelease() {
            val token = streamToken
            if (token > 0L) synchronized(handleLock) { NativeEngine.nativeStreamAbort(handle, token) }
            synchronized(gate.lock) { freeStream() }
        }
    }

    /** The stream as it is, with [status]: its text, the gated audio it has taken and decoded so far. */
    private fun current(status: Int, computeMs: Float = 0f) = synchronized(streamLock) {
        StreamUpdate(status, committed, tentative, backlog.fed / 16, committedMs, computeMs)
    }

    /** gate.lock: ends the preview stream, if any, and frees its session and gate. */
    private fun freeStream() {
        streamToken = 0L
        if (handle > 0) onAsr { NativeEngine.nativeStreamFree(handle) } // with the preview off, no session at all
        synchronized(streamLock) { freeGate() }
    }

    /** streamLock: the stream's Silero, gate and gated audio go. */
    private fun freeGate() {
        NativeEngine.nativeVadFree(streamVad)
        streamVad = 0
        previewGate = null
        backlog.clear()
        committed = ""
        tentative = ""
        committedMs = 0L
    }

    override fun onCreate() {
        super.onCreate()
        // Debug builds only: KEY=VALUE lines in files/engine-env.txt become this process's environment before the
        // engine loads, so one APK can compare transcribe.cpp switches and EngineKnobs in :engine.
        debuggable = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        // Live preview: the stream's tentative tail (patch 0013), read by the engine at each chunk.
        Os.setenv("TRANSCRIBE_STREAM_TENTATIVE", "1", true)
        if (debuggable) {
            File(filesDir, "engine-env.txt").takeIf { it.isFile }?.readLines()?.map { it.trim() }
                ?.filter { '=' in it && !it.startsWith('#') }
                ?.forEach { Os.setenv(it.substringBefore('='), it.substringAfter('='), true) }
        }
        NativeEngine.nativeInit(applicationInfo.nativeLibraryDir)
    }

    override fun onBind(intent: Intent): IBinder = binder

    // The last client unbound: RemoteEngine.unload after freeing the model, or the main process died. Ending the
    // process returns grow-only native buffers to the system. It ends here, after the system dropped the binding,
    // so the system does not treat the exit as a crash of a bound service and restart it.
    override fun onDestroy() {
        super.onDestroy()
        Process.killProcess(Process.myPid())
    }

    /**
     * asr thread, between jobs: the thread profile for the thermal status now, applied when it differs from the one in
     * force. MODERATE takes the pool to 4 threads; SEVERE and above to 3, unpinned (AsrPolicy.forThermal).
     */
    private fun applyProfile() {
        val b = base ?: return
        val thermal = if (knobs.thermal) getSystemService(PowerManager::class.java).currentThermalStatus else 0
        val p = AsrPolicy.forThermal(thermal, b)
        if (p == profile) return
        val status = NativeEngine.nativeSetThreads(handle, p.threads, p.cpus?.toIntArray(), p.strict, p.persistent)
        Log.i("ThumbFree", "asr_profile thermal=$thermal threads=${p.threads} " +
            "cpus=${p.cpus?.joinToString(",") ?: "any"} strict=${p.strict} persistent=${p.persistent} status=$status")
        profile = p
    }

    private fun <T> onAsr(block: () -> T): T = try {
        asr.submit(Callable { block() }).get()
    } catch (e: ExecutionException) {
        throw e.cause ?: e
    }

    private fun free() = synchronized(handleLock) {
        streamToken = 0L // the preview stream goes with the model (nativeFree frees its session)
        synchronized(streamLock) { freeGate() }
        generation++
        loadedFile = null
        val h = handle
        onAsr {
            NativeEngine.nativeFree(h) // on thread 0: the pool goes with the session
            profile = null
        }
        handle = 0
        base = null
        NativeEngine.nativeVadFree(vad)
        vad = 0
    }

    private fun failed(status: Int) = EngineResult(status, "", "", false, false, false, 0f, 0L)

    private fun ms(ns: Long) = ns / 1_000_000

    private fun rssKb() = runCatching {
        File("/proc/self/status").useLines { lines -> lines.first { it.startsWith("VmRSS:") } }.split(Regex("\\s+"))[1]
    }.getOrDefault("0")
}
