package io.github.kabrapratik28.thumbfree.engine

import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.github.kabrapratik28.thumbfree.core.audio.Wav
import io.github.kabrapratik28.thumbfree.testing.JFK_TEXT
import io.github.kabrapratik28.thumbfree.testing.TestModels
import io.github.kabrapratik28.thumbfree.testing.normalizeTranscript
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import org.junit.AfterClass
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeEngineTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val libDir = context.applicationInfo.nativeLibraryDir

    @Test
    fun versionIs024() {
        assertThat(NativeEngine.version()).isEqualTo("0.2.4")
    }

    @Test
    fun initFindsBackends() {
        assertThat(NativeEngine.nativeInit(libDir)).isEqualTo(0)
    }

    @Test
    fun loadsExpectedVariant() {
        NativeEngine.nativeInit(libDir)
        val variant = NativeEngine.nativeCpuVariant()
        // The emulator has dotprod and fp16 but no i8mm or SVE; nativeInit logs the variant on any device.
        if (Build.HARDWARE == "ranchu") {
            assertThat(variant).isEqualTo("android_armv8.2_2")
        } else {
            assertThat(variant).isNotEmpty()
        }
    }

    // Patch 0009's direct conv must match the general path byte for byte in each CPU module the device can run; the
    // SVE modules (opt-in, none on the emulator) compile it out. The emulator runs armv8.0_1 and armv8.2_2.
    @Test
    fun directConvIsBitIdenticalInEveryRunnableCpuModule() {
        NativeEngine.nativeInit(libDir)
        val lines = NativeEngine.nativeCpuConvCheck(libDir).lines().filter { it.isNotBlank() }
        lines.forEach { Log.i("ThumbFree", "conv_check $it") }

        assertThat(lines.filter { " DIFFERENT " in it }).isEmpty()
        val same = lines.filter { " same " in it }.map { it.substringBefore(' ') }
        assertThat(same).contains("libggml-cpu-android_armv8.0_1.so")
        if (Build.HARDWARE == "ranchu") assertThat(same).contains("libggml-cpu-android_armv8.2_2.so")
    }

    @Test
    fun transcribesJfkExactly() {
        val result = transcribe(jfk())

        assertThat(result.status).isEqualTo(0)
        assertThat(normalizeTranscript(result.text)).isEqualTo(JFK_TEXT)
    }

    @Test
    fun packagedBackendsAreExactlyTheShippedSet() {
        val libs = File(libDir).list()!!.toSet()

        assertThat(libs).containsAtLeast("libengine_jni.so", "libtranscribe.so", "libggml.so", "libggml-base.so",
            "libc++_shared.so", "libggml-cpu-android_armv8.0_1.so", "libggml-cpu-android_armv8.2_2.so",
            "libggml-cpu-android_armv8.6_1.so")
        assertThat(libs).containsNoneOf("libggml-cpu-android_armv8.2_1.so", "libggml-cpu-android_armv9.0_1.so",
            "libggml-cpu-android_armv9.2_1.so", "libggml-cpu-android_armv9.2_2.so")
    }

    @Test
    fun silenceIsEmpty() {
        val result = transcribe(FloatArray(3 * 16_000))

        assertThat(result.status).isEqualTo(0)
        assertThat(result.text).isEmpty()
    }

    @Test
    fun missingFileIsFileNotFound() {
        NativeEngine.nativeInit(libDir)

        assertThat(NativeEngine.nativeLoad(File(context.cacheDir, "missing.gguf").path, 4)).isEqualTo(-3L)
    }

    @Test
    fun junkFileIsGgufError() {
        NativeEngine.nativeInit(libDir)
        val junk = File(context.cacheDir, "junk.gguf").apply { writeText("not a gguf file") }

        assertThat(NativeEngine.nativeLoad(junk.path, 4)).isEqualTo(-4L)
    }

    @Test
    fun reportsCapabilities() {
        val info = info()

        assertThat(info["arch"]).isEqualTo("parakeet")
        assertThat(info["languages"]).isEqualTo("en")
        assertThat(info["max_audio_ms"]).isEqualTo("0")
        assertThat(info["supports_streaming"]).isEqualTo("true")
    }

    // Patch 0007: the CPU backend checks the abort flag after every graph node, so an abort sent while the encoder runs
    // stops the run: status 13 (aborted), no text.
    @Test
    fun abortDuringRunStopsParakeet() {
        val pcm = jfk()
        val result = whileAborting(0) { transcribe(pcm) }

        assertThat(result.status).isEqualTo(13)
        assertThat(result.aborted).isTrue()
        assertThat(result.text).isEmpty()
    }

    // An abort with a token stops only the run given that token. One that lands after its run ended (here all through
    // the next run) leaves the next run alone; one naming the run in progress stops it; one that lands before its run
    // starts stops it at its first check.
    @Test
    fun abortWithATokenStopsOnlyItsRun() {
        val pcm = jfk()
        val handle = model()
        assertThat(NativeEngine.nativeTranscribe(handle, pcm, pcm.size, "en", token = 1).status).isEqualTo(0)

        val next = whileAborting(1) { NativeEngine.nativeTranscribe(handle, pcm, pcm.size, "en", token = 2) }
        assertThat(next.status).isEqualTo(0)
        assertThat(normalizeTranscript(next.text)).isEqualTo(JFK_TEXT)

        val named = whileAborting(3) { NativeEngine.nativeTranscribe(handle, pcm, pcm.size, "en", token = 3) }
        assertThat(named.status).isEqualTo(13)

        NativeEngine.nativeAbort(handle, 4)
        assertThat(NativeEngine.nativeTranscribe(handle, pcm, pcm.size, "en", token = 4).status).isEqualTo(13)
    }

    @Test
    fun abortWhileIdleDoesNotAffectNextRun() {
        NativeEngine.nativeAbort(model())

        val result = transcribe(jfk())

        assertThat(result.status).isEqualTo(0)
        assertThat(result.text.lowercase()).contains("ask not what your country can do for you")
    }

    @Test
    fun rejectedCallReturnsNoStaleText() {
        val pcm = jfk()
        assertThat(transcribe(pcm).status).isEqualTo(0)

        // transcribe_run rejects n <= 0; the JNI rejects n > pcm.size without running. Both are INVALID_ARG (1).
        for (n in listOf(0, pcm.size + 1)) {
            val rejected = NativeEngine.nativeTranscribe(model(), pcm, n, "en")

            assertThat(rejected.status).isEqualTo(1)
            assertThat(rejected.text).isEmpty()
            assertThat(rejected.rawText).isEmpty()
        }
    }

    @Test
    fun invalidHandlesAreRejected() {
        for (bad in listOf(0L, -3L)) {
            NativeEngine.nativeAbort(bad)
            NativeEngine.nativeFree(bad)
            assertThat(NativeEngine.nativeInfo(bad)).isEmpty()
            assertThat(NativeEngine.nativeTranscribe(bad, FloatArray(16), 16, null).status).isEqualTo(1)
        }
    }

    @Test
    fun resultCarriesTimingsAndMemory() {
        val result = transcribe(jfk())
        Log.i(TAG, "jfk result: $result")

        assertThat(result.encodeMs).isGreaterThan(0f)
        // The patched engine (third_party/patches, docs/decisions/native-build.md) peaks near 1.0 GB here, the
        // unpatched one near 1.3 GB, so this also fails a build that silently lost its patches.
        assertThat(result.vmHwmKb).isGreaterThan(500_000L)
        assertThat(result.vmHwmKb).isLessThan(1_200_000L)
    }

    private fun transcribe(pcm: FloatArray) = NativeEngine.nativeTranscribe(model(), pcm, pcm.size, "en")

    // Runs [run], sending nativeAbort(token) every millisecond from 100 ms in (past the top-of-run check; the jfk
    // encoder alone takes about 250 ms here) until it returns.
    private fun whileAborting(token: Long, run: () -> NativeResult): NativeResult {
        val handle = model()
        val done = AtomicBoolean(false)
        val aborter = thread {
            Thread.sleep(100)
            while (!done.get()) {
                NativeEngine.nativeAbort(handle, token)
                Thread.sleep(1)
            }
        }
        return try { run() } finally { done.set(true); aborter.join() }
    }

    private fun info() =
        NativeEngine.nativeInfo(model()).split(' ').associate { it.substringBefore('=') to it.substringAfter('=') }

    private fun jfk(): FloatArray {
        val file = File(context.cacheDir, "jfk.wav")
        instrumentation.context.assets.open("audio/jfk.wav").use { input ->
            file.outputStream().use { input.copyTo(it) }
        }
        return Wav.readFloat(file.path, 0, JFK_SAMPLES)
    }

    // One load per class. A missing model fails the test: a skip would let the suite go green without running.
    private fun model(): Long {
        if (handle == 0L) {
            val file = TestModels.find(TestModels.PARAKEET_Q8)
            assertWithMessage("${TestModels.PARAKEET_Q8} missing: run android/tools/push-test-model.sh <serial>")
                .that(file).isNotNull()
            assertThat(NativeEngine.nativeInit(libDir)).isEqualTo(0)
            val start = SystemClock.elapsedRealtime()
            val loaded = NativeEngine.nativeLoad(file!!.path, 4)
            assertThat(loaded).isGreaterThan(0L)
            handle = loaded
            Log.i(TAG, "load_ms=${SystemClock.elapsedRealtime() - start} ${NativeEngine.nativeInfo(handle)}")
        }
        return handle
    }

    companion object {
        private const val TAG = "ThumbFree"
        private const val JFK_SAMPLES = 176_000L // 11.0 s at 16 kHz
        private var handle = 0L

        @JvmStatic
        @AfterClass
        fun freeModel() {
            if (handle > 0L) NativeEngine.nativeFree(handle)
        }
    }
}
