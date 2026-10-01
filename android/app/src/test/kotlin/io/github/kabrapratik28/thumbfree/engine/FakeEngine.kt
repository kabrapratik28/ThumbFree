package io.github.kabrapratik28.thumbfree.engine

import kotlinx.coroutines.delay

/**
 * Answers transcribe calls from a script, one reply per call in order, and records every call. Aborts work as in
 * :engine: one naming a run token stops the transcribe given that token or an earlier one (status 13, no text), never a
 * later one; one naming none (0) stops whatever runs. With [holdAborts], an abort is recorded but lands only at
 * [deliverAborts], as a oneway Binder call can reach :engine late.
 */
class FakeEngine(
    vararg replies: Reply,
    var loadStatus: Int = 0, // of every load from now on
    private val loadMs: Long = 0,
    private var loadDeaths: Int = 0, // the first loads throw EngineDiedException
    private val holdAborts: Boolean = false,
) : Engine {
    /**
     * One transcribe: returns after [delayMs] of virtual time, or throws [EngineDiedException] when [dies]. [vadRejected]:
     * Silero heard no speech in the chunk.
     */
    class Reply(
        val text: String = "",
        val rawText: String = text,
        val status: Int = 0,
        val delayMs: Long = 0,
        val dies: Boolean = false,
        val vadRejected: Boolean = false,
    )

    val calls = mutableListOf<String>()
    val languages = mutableListOf<String?>() // of each transcribe call, in order
    val allowRetries = mutableListOf<Boolean>() // likewise
    val speechChecks = mutableListOf<Boolean>() // likewise
    val tokens = mutableListOf<Long>() // likewise
    private val script = ArrayDeque(replies.asList())
    private val held = mutableListOf<Long>() // aborts sent but not landed yet
    private var abortedUpTo = 0L // the highest run token an abort named
    private var running = 0L // the token of the transcribe in progress (0: none)

    /** A test sets it false for a death while idle; the next load starts the engine again, as in [RemoteEngine]. */
    override var isAlive = true

    override suspend fun load(modelPath: String, threads: Int): Int {
        calls += "load $modelPath $threads"
        isAlive = true
        delay(loadMs)
        if (loadDeaths > 0) {
            loadDeaths--
            throw EngineDiedException()
        }
        return loadStatus
    }

    override suspend fun transcribe(
        wavPath: String,
        fromSample: Long,
        toSample: Long,
        language: String?,
        allowRetry: Boolean,
        speechCheck: Boolean,
        token: Long,
    ): EngineResult {
        calls += "transcribe $fromSample-$toSample"
        languages += language
        allowRetries += allowRetry
        speechChecks += speechCheck
        tokens += token
        val reply = script.removeFirst()
        running = token
        try {
            // In 10 ms steps of virtual time, so an abort that lands meanwhile stops the run at the next step.
            var left = reply.delayMs
            while (left > 0) {
                delay(minOf(10L, left))
                left -= 10
                if (token != 0L && token <= abortedUpTo) {
                    return EngineResult(13, "", "", false, true, false, 0f, 0L)
                }
            }
        } finally {
            running = 0L
        }
        if (reply.dies) throw EngineDiedException()
        return EngineResult(reply.status, reply.text, reply.rawText, reply.status == 18, false, false, 0f, 0L, reply.vadRejected)
    }

    override fun abort(token: Long) {
        calls += "abort $token"
        if (holdAborts) held += token else land(token)
    }

    /** Lands every held abort now, whatever runs by then. */
    fun deliverAborts() {
        held.forEach(::land)
        held.clear()
    }

    private fun land(token: Long) {
        abortedUpTo = maxOf(abortedUpTo, if (token == 0L) running else token)
    }

    override suspend fun unload() {
        calls += "unload"
    }
}
