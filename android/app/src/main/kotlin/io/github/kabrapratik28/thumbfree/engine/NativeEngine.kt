package io.github.kabrapratik28.thumbfree.engine

/** JNI bridge to transcribe.cpp (android/app/src/main/cpp/engine_jni.cpp). */
object NativeEngine {
    init {
        System.loadLibrary("engine_jni")
    }

    fun version(): String = nativeVersion()

    /**
     * Loads the best ggml CPU module from [libDir] (the app's nativeLibraryDir) and logs it as cpu_variant.
     * Call before the first load; repeat calls return the first result. Returns a transcribe_status.
     */
    external fun nativeInit(libDir: String): Int

    /** The ggml CPU variant picked by [nativeInit], e.g. "android_armv8.2_2", or "" before it. */
    external fun nativeCpuVariant(): String

    /**
     * Test hook: patch 0009's direct conv against the general one, byte for byte, in every CPU module in [libDir] that
     * this CPU can run. One line per module: "<file> same|DIFFERENT <direct ms> <generic ms>" or "<file> skipped ...".
     */
    external fun nativeCpuConvCheck(libDir: String): String

    /** Loads a GGUF model on the CPU. Returns a handle (> 0), or minus the transcribe_status on failure. */
    external fun nativeLoad(path: String, threads: Int): Long

    /**
     * Transcribes the first [n] samples of 16 kHz mono [pcm]. [lang] is a hint such as "en", or null.
     * A bad handle or n > pcm.size returns INVALID_ARG (1). Text, flags and timings are only filled for
     * status 0, 13 (aborted, partial) or 18 (truncated, partial); otherwise they are empty and 0. With
     * [allowRetry], a Canary run that comes back empty runs once more without PnC ([NativeResult.retried]).
     * [token] names this run for [nativeAbort] (0: none); tokens must grow from run to run.
     */
    external fun nativeTranscribe(
        handle: Long, pcm: FloatArray, n: Int, lang: String?, allowRetry: Boolean = false, token: Long = 0,
    ): NativeResult

    /**
     * Asks a run to stop at its next abort check. With a [token], the run [nativeTranscribe] was given that token stops
     * (and any earlier one), whenever the abort lands, and no later run does: an abort that lands late, after its run
     * ended, never stops the next run, and one that lands before its run starts stops it at its first check. Without
     * one (0), the run in progress stops and an abort that arrives before a run starts is dropped. Parakeet checks
     * after the mel, after every encoder graph node (patch 0007) and after the encoder, so a Parakeet run stops within
     * a node, with status 13 and no text. Callers must not race this with [nativeFree].
     */
    external fun nativeAbort(handle: Long, token: Long = 0)

    /**
     * One line of key=value pairs: arch, variant, languages (comma separated), max_audio_ms,
     * supports_streaming, cancellation, cpu_variant, pnc_retries (Canary's PnC-off retries of
     * empty runs on this handle), stream_session (a preview's session exists: only while a take streams) and stream_active
     * (a preview stream is begun and not ended). "" for a bad handle.
     */
    external fun nativeInfo(handle: Long): String

    /**
     * The session's thread count and where its threads run. Call on the thread that runs this handle's transcribes: it
     * becomes thread 0 and gets [cpus] (null: every CPU the process may use). With [persistent], the other
     * [threads] - 1 are a pool kept until the next call or [nativeFree], its workers on [cpus] too ([strict]: one CPU
     * per thread), sleeping between runs; without, every graph starts and joins its own, as before. Returns a
     * transcribe_status.
     */
    external fun nativeSetThreads(
        handle: Long, threads: Int, cpus: IntArray?, strict: Boolean, persistent: Boolean,
    ): Int

    /**
     * An ADPF performance hint session for thread 0 (the caller, as for [nativeSetThreads]) and the pool's workers.
     * [mode]: EngineKnobs' HINTS_ bits (0 closes it); [rtf]: the target work duration per second of audio. Every
     * [nativeTranscribe] then updates the target from its length and reports its duration, or notifies a workload
     * increase (API 36). Fails open; true when a session exists.
     */
    external fun nativeSetHints(handle: Long, mode: Int, rtf: Float): Boolean

    /** The thread ids of the persistent pool's workers (thread 0 not included); empty without a pool. */
    external fun nativeThreadIds(handle: Long): IntArray

    /** Sets the calling thread's CPUs ([cpus], or every CPU for null or empty). True on success. */
    external fun nativeSetCurrentThreadCpus(cpus: IntArray?): Boolean

    /**
     * Begins the live preview's stream for take [token] (> 0; each take's is higher than the last) on a second session
     * of the loaded model, made here and freed by [nativeStreamFree]: parakeet-unified's buffered stream with a
     * [leftMs], [chunkMs], [rightMs] window, the offline run's parameters and [lang]. It shares the thread count and
     * pool. Call on the asr thread. Returns a transcribe_status: 13 when [nativeStreamAbort] named the take already.
     */
    external fun nativeStreamBegin(handle: Long, token: Long, leftMs: Int, chunkMs: Int, rightMs: Int, lang: String?): Int

    /**
     * Feeds the first [n] samples of 16 kHz mono [pcm] (floats, as Wav.readFloat; n may be 0) to the preview stream:
     * runs every chunk they complete and returns the stream's committed text and tentative tail. Status 13 after
     * [nativeStreamAbort]; 1 without a begun stream. Call on the asr thread.
     */
    external fun nativeStreamFeed(handle: Long, pcm: FloatArray, n: Int): StreamUpdate

    /**
     * Ends the preview stream and frees its session and buffers: nothing of it stays between takes. Call on the asr
     * thread.
     */
    external fun nativeStreamFree(handle: Long)

    /**
     * Ends take [token]'s preview stream: its chunk in progress stops within a graph node, and a begin that comes later
     * returns 13. A later take's stream is never touched. Any thread, not racing [nativeFree].
     */
    external fun nativeStreamAbort(handle: Long, token: Long)

    /**
     * Frees the sessions (the preview stream's too) and the model; the handle is invalid afterwards. A handle <= 0 is
     * ignored. Callers serialize this with [nativeAbort], [nativeStreamAbort] and every call that runs on the handle.
     */
    external fun nativeFree(handle: Long)

    /**
     * Loads Silero's ggml model (android/app/src/main/cpp/vad) on the CPU, one thread. Call after [nativeInit]. Returns
     * a handle (> 0), or 0 on failure.
     */
    external fun nativeVadLoad(path: String): Long

    /**
     * Silero's speech probability for each 512-sample window of the first [n] samples of 16 kHz mono [pcm], the last
     * window zero-padded, each call from a fresh state, as official Silero v6.2 computes them. Null on failure. One
     * call at a time per handle; it may run alongside [nativeTranscribe].
     */
    external fun nativeVadProbs(handle: Long, pcm: FloatArray, n: Int): FloatArray?

    /** Frees the model; the handle is invalid afterwards. A handle <= 0 is ignored. */
    external fun nativeVadFree(handle: Long)

    /**
     * Live preview: Silero on the next [n] samples of a stream (whole 512-sample windows) on its own context from
     * [nativeVadLoad], its recurrent state going on from the last call: one speech probability per window, or null on
     * failure.
     */
    external fun nativeVadStream(handle: Long, pcm: FloatArray, n: Int): FloatArray?

    private external fun nativeVersion(): String
}
