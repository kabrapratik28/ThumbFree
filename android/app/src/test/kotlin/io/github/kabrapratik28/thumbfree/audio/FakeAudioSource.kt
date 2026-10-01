package io.github.kabrapratik28.thumbfree.audio

import java.util.Random
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Plays [script] to a Recorder: a ShortArray is audio (split over reads when it is longer than the buffer), 0 is a read
 * that finds nothing yet, a CaptureException is thrown by its read, and a Runnable runs on the capture thread when the
 * reads reach it, for a stop in the middle of the audio. After the script every read returns a full buffer of zeros, and
 * [onScriptEnd] runs once, on the capture thread, just before the first of them.
 *
 * [clock] moves with what the source hands out: 1 ms per 16 samples and 20 ms per empty read, so a take's clock is its
 * audio time and tests do not depend on thread timing. With [mayDeliver], a read first waits (up to 5 s) until it returns
 * true for the samples handed out so far, so the source runs no further ahead of the writer than a real mic would.
 */
class FakeAudioSource(
    script: List<Any>,
    private val startError: Exception? = null,
    private val stopError: RuntimeException? = null,
    private val silencedAfter: Long = Long.MAX_VALUE, // samples handed out before `silenced` turns true
    private val mayDeliver: ((delivered: Long) -> Boolean)? = null,
    private val onScriptEnd: () -> Unit = {},
) : AudioSource {
    private val script = ArrayDeque(script)
    private var offset = 0 // into the ShortArray at the head of the script
    private var ended = false

    @Volatile private var idleMs = 0L

    @Volatile var delivered = 0L
        private set

    @Volatile var starts = 0
        private set

    @Volatile var stops = 0
        private set

    @Volatile var releases = 0
        private set

    val clock: () -> Long = { idleMs + delivered / 16 }

    override val silenced get() = delivered >= silencedAfter

    override fun start() {
        starts++
        startError?.let { throw it }
    }

    override fun stop() {
        stops++
        stopError?.let { throw it }
    }

    override fun release() {
        releases++
    }

    override fun read(buf: ShortArray): Int {
        val deadline = System.nanoTime() + 5_000_000_000
        while (mayDeliver?.invoke(delivered) == false && System.nanoTime() < deadline) Thread.yield()
        val item = script.firstOrNull()
        if (item == null && !ended) {
            ended = true
            onScriptEnd()
        }
        return when (item) {
            null -> {
                buf.fill(0)
                delivered += buf.size
                buf.size
            }
            0 -> {
                script.removeFirst()
                idleMs += 20
                0
            }
            is CaptureException -> {
                script.removeFirst()
                throw item
            }
            is Runnable -> {
                script.removeFirst()
                item.run()
                read(buf)
            }
            is ShortArray -> {
                val n = minOf(buf.size, item.size - offset)
                item.copyInto(buf, 0, offset, offset + n)
                offset += n
                if (offset == item.size) {
                    script.removeFirst()
                    offset = 0
                }
                delivered += n
                n
            }
            else -> error("unknown script item $item")
        }
    }
}

/** [ms] of speech-like bursts: Gaussian noise, 300 ms at -20 dBFS then 100 ms at -50 dBFS, repeated (seed 7). */
fun bursts(ms: Int): ShortArray {
    val random = Random(7)
    val loud = 32768 * 10.0.pow(-20 / 20.0)
    val quiet = 32768 * 10.0.pow(-50 / 20.0)
    return ShortArray(ms * 16) { i ->
        val sigma = if (i % 6_400 < 4_800) loud else quiet
        (random.nextGaussian() * sigma).roundToInt().coerceIn(-32768, 32767).toShort()
    }
}

/** 25 s of [bursts] with 400 ms of zeros at 21.0 s: past 20 s, the chunk planner cuts in that pause. */
fun burstsWithPause(): ShortArray = bursts(25_000).also { it.fill(0, 21_000 * 16, 21_400 * 16) }
