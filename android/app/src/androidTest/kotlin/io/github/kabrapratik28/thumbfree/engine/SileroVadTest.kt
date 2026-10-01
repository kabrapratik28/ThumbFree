package io.github.kabrapratik28.thumbfree.engine

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import io.github.kabrapratik28.thumbfree.core.audio.Wav
import java.io.File
import kotlin.math.abs
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/** The Silero VAD vendored into the engine library (android/app/src/main/cpp/vad), on this device's ggml CPU module. */
@RunWith(AndroidJUnit4::class)
class SileroVadTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val vad = run {
        NativeEngine.nativeInit(context.applicationInfo.nativeLibraryDir)
        NativeEngine.nativeVadLoad(VadModel.file(context)!!.path)
    }

    @After
    fun free() = NativeEngine.nativeVadFree(vad)

    // The parity gate of android/tools/vad-parity.py, here: official Silero v6.2 on ONNX Runtime gave these
    // probabilities for jfk.wav on the Mac.
    @Test
    fun matchesOfficialSilero() {
        val expected = instrumentation.context.assets.open("vad/jfk-silero-v6.2-onnx.txt").bufferedReader()
            .readLines().filterNot { it.startsWith("#") }.map { it.toFloat() }
        val jfk = jfk()

        val probs = NativeEngine.nativeVadProbs(vad, jfk, jfk.size)!!

        assertThat(probs.size).isEqualTo(expected.size)
        val errors = probs.indices.map { abs(probs[it] - expected[it]) }
        Log.i("ThumbFree", "silero_parity jfk windows=${probs.size} mean=${errors.average()} max=${errors.max()}")
        assertThat(errors.average()).isAtMost(0.005)
        assertThat(errors.max()).isAtMost(0.02f)
        assertThat(SpeechCheck.isSpeech(probs)).isTrue()
    }

    // Every call starts from zero state and zero context, so a chunk's check never depends on the chunk before it.
    @Test
    fun eachCallStartsFresh() {
        val jfk = jfk()
        val second = jfk.copyOfRange(60_000, 120_000)
        val fresh = NativeEngine.nativeVadProbs(vad, second, second.size)!!

        NativeEngine.nativeVadProbs(vad, jfk, 60_000)

        assertThat(NativeEngine.nativeVadProbs(vad, second, second.size)).isEqualTo(fresh)
    }

    // A chunk that does not fill its last 512-sample window gets that window zero-padded, not dropped.
    @Test
    fun aPartialLastWindowIsZeroPadded() {
        val jfk = jfk()
        val padded = jfk.copyOf(3 * 512)
        padded.fill(0f, 2 * 512 + 100, padded.size)

        val probs = NativeEngine.nativeVadProbs(vad, jfk, 2 * 512 + 100)!!

        assertThat(probs.size).isEqualTo(3)
        assertThat(probs).isEqualTo(NativeEngine.nativeVadProbs(vad, padded, padded.size))
        assertThat(NativeEngine.nativeVadProbs(vad, jfk, 0)!!.size).isEqualTo(0)
    }

    @Test
    fun badCallsReturnNull() {
        assertThat(NativeEngine.nativeVadProbs(vad, FloatArray(10), 11)).isNull()
        assertThat(NativeEngine.nativeVadProbs(0, FloatArray(10), 10)).isNull()
        assertThat(NativeEngine.nativeVadLoad(File(context.cacheDir, "missing.bin").path)).isEqualTo(0L)
        NativeEngine.nativeVadFree(0) // ignored
    }

    private fun jfk(): FloatArray {
        val file = File(context.cacheDir, "jfk.wav")
        instrumentation.context.assets.open("audio/jfk.wav").use { input -> file.outputStream().use { input.copyTo(it) } }
        return Wav.readFloat(file.path, 0, 176_000) // 11.0 s
    }
}
