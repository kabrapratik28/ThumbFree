package io.github.kabrapratik28.thumbfree.engine

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.github.kabrapratik28.thumbfree.core.audio.Wav
import io.github.kabrapratik28.thumbfree.core.audio.WavWriter
import io.github.kabrapratik28.thumbfree.testing.JFK_TEXT
import io.github.kabrapratik28.thumbfree.testing.TestModels
import io.github.kabrapratik28.thumbfree.testing.normalizeTranscript
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/** Canary 180M Flash, pushed by `android/tools/push-test-model.sh <serial> canary-180m-flash-Q8_0.gguf`. */
@RunWith(AndroidJUnit4::class)
class CanaryEngineTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val engine = RemoteEngine(context)

    @After
    fun unload() = runBlocking { engine.unload() }

    // The fixture for Canary's prompt, through the app's path: with "en" and the JNI's explicit transcribe task and PNC
    // on, Canary gives English (every JFK word, so no translation) with capitals and punctuation. A change to the
    // language, the task or PNC, here or in transcribe.cpp's defaults, fails this.
    @Test
    fun jfkThroughEngineServiceIsPunctuatedEnglish() = runBlocking {
        assertThat(engine.load(canary().path, 6)).isEqualTo(0)

        val result = engine.transcribe(jfk(), 0, JFK_SAMPLES, "en")
        Log.i(TAG, "canary jfk status=${result.status} encodeMs=${result.encodeMs} vmHwmKb=${result.vmHwmKb}")

        assertThat(result.status).isEqualTo(0)
        assertThat(normalizeTranscript(result.text)).isEqualTo(JFK_TEXT)
        assertThat(result.text).startsWith("And so")
        assertThat(result.text).contains("Americans")
        assertThat(result.text).contains(",")
        assertThat(result.text).endsWith(".")
    }

    // The fixture for the PnC-off retry: JFK at -22 dB behind 7 s of seeded noise. Canary with PnC on answers it with
    // end-of-text alone, as it did on 7 private clips; the retry runs once and brings the words back, without
    // punctuation or capitals. Plain JFK needs no retry. In process, so the handle's retry count can be read.
    @Test
    fun emptyRunIsRetriedOnceWithoutPnc() {
        val handle = load(canary())
        try {
            val jfk = jfkPcm()
            val plain = NativeEngine.nativeTranscribe(handle, jfk, jfk.size, "en", allowRetry = true)
            assertThat(plain.text).startsWith("And so")
            assertThat(plain.retried).isFalse()
            assertThat(retries(handle)).isEqualTo(0)

            val noisy = noisyJfk()
            // The JNI's side, with the retry allowed; when the app allows it is TranscriptionQueueTest's (30 gate frames).
            val result = NativeEngine.nativeTranscribe(handle, noisy, noisy.size, "en", allowRetry = true)
            Log.i(TAG, "canary noisy jfk status=${result.status} encodeMs=${result.encodeMs} retries=${retries(handle)}")

            assertThat(result.status).isEqualTo(0)
            assertThat(result.retried).isTrue()
            assertThat(retries(handle)).isEqualTo(1)
            assertThat(normalizeTranscript(result.text)).contains("ask not what") // JFK, though the noise costs some words
            assertThat(result.text).doesNotContainMatch("[A-Z.,?!]") // made with PnC off
        } finally {
            NativeEngine.nativeFree(handle)
        }
    }

    // The retry on the production path. RemoteEngine binds EngineService in :engine, which reads the WAV as it reads a
    // take's chunk; the flag goes in and `retried` comes back over Binder, with the rescued words. Both ways: a flag
    // forced to true anywhere on the path would defeat the 30-frame rule.
    @Test
    fun retryFlagCrossesBinderBothWays() = runBlocking {
        assertThat(engine.load(canary().path, 4)).isEqualTo(0)
        val noisy = noisyJfk()
        val file = wav(noisy)

        val refused = engine.transcribe(file.path, 0, noisy.size.toLong(), "en", allowRetry = false)
        assertThat(refused.status).isEqualTo(0)
        assertThat(refused.text).isEmpty()
        assertThat(refused.retried).isFalse()

        val rescued = engine.transcribe(file.path, 0, noisy.size.toLong(), "en", allowRetry = true)
        Log.i(TAG, "canary binder noisy jfk status=${rescued.status} retried=${rescued.retried}")
        assertThat(rescued.status).isEqualTo(0)
        assertThat(rescued.retried).isTrue()
        assertThat(normalizeTranscript(rescued.text)).contains("ask not what")
    }

    // A chunk without gate speech gets no retry, so the empty first run stays empty.
    @Test
    fun noRetryWhenTheCallerDoesNotAllowIt() {
        val handle = load(canary())
        try {
            val noisy = noisyJfk()
            val result = NativeEngine.nativeTranscribe(handle, noisy, noisy.size, "en", allowRetry = false)

            assertThat(result.status).isEqualTo(0)
            assertThat(result.text).isEmpty()
            assertThat(result.retried).isFalse()
            assertThat(retries(handle)).isEqualTo(0)
        } finally {
            NativeEngine.nativeFree(handle)
        }
    }

    @Test
    fun parakeetNeverRetries() {
        val parakeet = TestModels.find(TestModels.PARAKEET_Q8)
        assertWithMessage("${TestModels.PARAKEET_Q8} missing: run android/tools/push-test-model.sh <serial>").that(parakeet).isNotNull()
        val handle = load(parakeet!!)
        try {
            val noisy = noisyJfk()
            assertThat(NativeEngine.nativeTranscribe(handle, noisy, noisy.size, "en", allowRetry = true).status).isEqualTo(0)
            assertThat(retries(handle)).isEqualTo(0)
        } finally {
            NativeEngine.nativeFree(handle)
        }
    }

    // The engine's own caps: the chunk planner cuts at 30 s at the latest, well inside Canary's input limit.
    @Test
    fun maxAudioCoversTheLongestChunk() {
        val handle = load(canary())
        try {
            Log.i(TAG, "canary ${NativeEngine.nativeInfo(handle)}")
            val caps = info(handle)

            assertThat(caps["arch"]).isEqualTo("canary")
            assertThat(caps["max_audio_ms"]!!.toLong()).isAtLeast(30_000L)
        } finally {
            NativeEngine.nativeFree(handle)
        }
    }

    /** In this process, on 4 threads as the search on the Mac ran. */
    private fun load(model: File): Long {
        assertThat(NativeEngine.nativeInit(context.applicationInfo.nativeLibraryDir)).isEqualTo(0)
        return NativeEngine.nativeLoad(model.path, 4).also { assertThat(it).isGreaterThan(0L) }
    }

    private fun info(handle: Long) =
        NativeEngine.nativeInfo(handle).split(' ').associate { it.substringBefore('=') to it.substringAfter('=') }

    private fun retries(handle: Long) = info(handle).getValue("pnc_retries").toInt()

    private fun jfkPcm(): FloatArray = Wav.readFloat(jfk(), 0, JFK_SAMPLES)

    /**
     * JFK at 2600/32768 (-22 dB) after 7 s of noise at 1036/32768 that runs under all of it: an integer LCG (seed 1) and
     * floor shifts, so this is, sample for sample, the clip transcribe-cli found on the Mac (empty with PnC on, all the
     * words with PnC off, as was every clip around it).
     */
    /** [pcm] as a 16 kHz PCM16 WAV, sample for sample: its floats are PCM16 values over 32768. */
    private fun wav(pcm: FloatArray): File = File(context.cacheDir, "noisy-jfk.wav").also { file ->
        WavWriter.create(file).use {
            it.append(ShortArray(pcm.size) { i -> (pcm[i] * 32768).toInt().toShort() })
            it.finish()
        }
    }

    private fun noisyJfk(): FloatArray {
        val speech = jfkPcm()
        val lead = 7 * 16_000
        var x = 1L
        return FloatArray(lead + speech.size) { i ->
            x = (x * 1103515245 + 12345) and 0x7FFFFFFF
            val noise = (x shr 15).toInt() - 32768
            val voice = if (i < lead) 0 else (speech[i - lead] * 32768).toInt()
            (((voice * 2600) shr 15) + ((noise * 1036) shr 15)).coerceIn(-32768, 32767) / 32768f
        }
    }

    // A missing model fails the test: a skip would let the suite go green without running.
    private fun canary(): File {
        val model = TestModels.find(TestModels.CANARY_Q8)
        assertWithMessage("${TestModels.CANARY_Q8} missing: run android/tools/push-test-model.sh <serial> ${TestModels.CANARY_Q8}")
            .that(model).isNotNull()
        return model!!
    }

    private fun jfk(): String {
        val file = File(context.cacheDir, "jfk.wav")
        instrumentation.context.assets.open("audio/jfk.wav").use { input ->
            file.outputStream().use { input.copyTo(it) }
        }
        return file.path
    }

    private companion object {
        const val TAG = "ThumbFree"
        const val JFK_SAMPLES = 176_000L // 11.0 s at 16 kHz
    }
}
