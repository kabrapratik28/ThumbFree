package io.github.kabrapratik28.thumbfree.testing

import androidx.test.platform.app.InstrumentationRegistry
import io.github.kabrapratik28.thumbfree.audio.AudioSource
import io.github.kabrapratik28.thumbfree.core.audio.Wav
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Plays [pcm] at [speedup] times real time in 320-sample blocks, then returns zeros forever. With [holdAtEnd] the first
 * read after the end waits for [resume] (30 s at most): a stop tapped meanwhile lands on the audio's exact end, so the
 * take's WAV is the same at any speed.
 */
class WavFileSource(
    private val pcm: ShortArray,
    private val speedup: Int = 4,
    private val holdAtEnd: Boolean = false,
) : AudioSource {
    private val ended = CountDownLatch(1)
    private val resumed = CountDownLatch(1)
    private var startNs = 0L
    private var served = 0L // samples returned so far, the zeros after the end included

    override val silenced = false

    override fun start() {
        startNs = System.nanoTime()
    }

    /** Blocks until real time, sped up, has reached the next block, like a microphone read. */
    override fun read(buf: ShortArray): Int {
        if (holdAtEnd && served >= pcm.size) resumed.await(30, TimeUnit.SECONDS)
        val waitNs = startNs + served * 1_000_000_000L / (16_000L * speedup) - System.nanoTime()
        if (waitNs > 0) TimeUnit.NANOSECONDS.sleep(waitNs)
        val n = minOf(320, buf.size)
        val from = minOf(served, pcm.size.toLong()).toInt()
        val count = minOf(n, pcm.size - from)
        pcm.copyInto(buf, 0, from, from + count)
        buf.fill(0, count, n)
        served += n
        if (served >= pcm.size) ended.countDown()
        return n
    }

    override fun stop() = Unit

    override fun release() = Unit

    /** True once every sample of [pcm] has been read. */
    fun awaitEnd(timeoutMs: Long): Boolean = ended.await(timeoutMs, TimeUnit.MILLISECONDS)

    /** With holdAtEnd: the reads after the end go on, with zeros. */
    fun resume() = resumed.countDown()
}

/** assets audio/jfk.wav, samples 0 until 176,000. */
fun jfkPcm(): ShortArray = assetPcm("jfk.wav", 176_000)

/** assets audio/[name] (a 44-byte header, then 16 kHz mono PCM16): samples 0 until [samples], or all of them. */
fun assetPcm(name: String, samples: Long? = null): ShortArray {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val file = File(instrumentation.targetContext.cacheDir, name)
    instrumentation.context.assets.open("audio/$name").use { input -> file.outputStream().use { input.copyTo(it) } }
    val floats = Wav.readFloat(file.path, 0, samples ?: ((file.length() - 44) / 2))
    return ShortArray(floats.size) { (floats[it] * 32_768).toInt().toShort() } // readFloat divided by 32,768: exact
}
