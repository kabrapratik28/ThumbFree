package io.github.kabrapratik28.thumbfree.engine

class EngineDiedException : Exception("engine process died")

/** The speech engine as the main process sees it. [RemoteEngine] runs it in the :engine process. */
interface Engine {
    /**
     * False from a death of the engine process until the next call, so a caller knows the model is gone and can load
     * it again before it needs it. Makes no Binder call and never blocks.
     */
    val isAlive: Boolean

    /**
     * Returns a transcribe_status: 0 ok, 3 missing file, 4 bad GGUF, 7 out of memory. [threads] is a cap: the engine
     * runs as many as its own fast allowed CPUs (ThreadPolicy.engineThreads).
     */
    suspend fun load(modelPath: String, threads: Int): Int

    /**
     * Transcribes samples [fromSample, toSample) of a 16 kHz mono PCM16 WAV. Status 1 when no model is loaded. Only with
     * [allowRetry] may an empty Canary run be run again without PnC (see [EngineResult.retried]). With [speechCheck],
     * Silero checks the chunk alongside the transcribe, and a chunk it hears no speech in comes back with no
     * text and [EngineResult.vadRejected]. [token] names this run for [abort] (0: none); tokens must grow from run to
     * run.
     */
    suspend fun transcribe(
        wavPath: String,
        fromSample: Long,
        toSample: Long,
        language: String?,
        allowRetry: Boolean = false,
        speechCheck: Boolean = false,
        token: Long = 0,
    ): EngineResult

    /**
     * Asks a transcribe to stop and returns without waiting for the engine. With a [token], the run given that token
     * stops and no later run does, however late the abort lands; without one (0), whatever runs now. Parakeet stops
     * within an encoder graph node (patch 0007); its decoder, the last tens of ms, runs to the end.
     */
    fun abort(token: Long = 0)

    suspend fun unload()

    /**
     * Live preview: opens take [token]'s preview stream once [modelFile], the take's model, is the loaded one. Returns
     * 0; 1 while it is not (no model yet, or another); [StreamUpdate.BUSY] while an offline call waits or runs;
     * [StreamUpdate.NO_ENGINE] while no engine process is up; [StreamUpdate.NO_VAD]; or another transcribe_status (2
     * for a model that cannot stream). Never loads, binds or waits for a transcribe.
     */
    suspend fun streamBegin(token: Long, modelFile: String): Int = StreamUpdate.NO_ENGINE

    /**
     * Live preview: gives the first [n] samples of [pcm] (16 kHz mono PCM16, whole 512-sample windows: all of the
     * take's audio, in order; n may be 0, which feeds :engine's waiting audio on) to take [token]'s preview stream and
     * returns its text, or null when no engine process is up (or it died). :engine keeps what it cannot feed yet: never
     * send audio twice.
     */
    suspend fun streamFeed(token: Long, pcm: ShortArray, n: Int): StreamUpdate? = null

    /**
     * Live preview: closes take [token]'s preview stream, stopping a chunk in progress, and returns once it is freed.
     */
    suspend fun streamEnd(token: Long) {}

    /** Live preview: frees any preview stream (the setting turned off). Never binds. */
    suspend fun streamRelease() {}
}
