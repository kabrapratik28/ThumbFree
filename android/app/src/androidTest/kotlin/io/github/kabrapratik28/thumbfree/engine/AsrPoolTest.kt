package io.github.kabrapratik28.thumbfree.engine

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.github.kabrapratik28.thumbfree.core.audio.Wav
import io.github.kabrapratik28.thumbfree.testing.TestModels
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The model's persistent CPU pool (NativeEngine.nativeSetThreads). Every native call runs on one thread named [THREAD],
 * as EngineService runs them on its asr thread; the pool's workers and the mel's threads inherit the name, so counting
 * the threads with it counts exactly the engine's.
 */
@RunWith(AndroidJUnit4::class)
class AsrPoolTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val engine = Executors.newSingleThreadExecutor { Thread(it, THREAD) }
    private val handles = mutableListOf<Long>()

    @After
    fun free() {
        on { handles.forEach(NativeEngine::nativeFree) }
        engine.shutdown()
    }

    @Test
    fun textIsTheSameWithAndWithoutThePool() {
        val pcm = jfk()
        val h = load()
        val texts = listOf(
            Triple(null, false, false), // a pool per graph, as before the persistent pool
            Triple(null, false, true), // persistent, unpinned
            Triple(allCpus(), false, true), // persistent, on a CPU set
            Triple(allCpus(), true, true), // persistent, one CPU per thread
        ).map { (cpus, strict, persistent) ->
            on {
                assertThat(NativeEngine.nativeSetThreads(h, 4, cpus, strict, persistent)).isEqualTo(0)
                assertThat(NativeEngine.nativeThreadIds(h).size).isEqualTo(if (persistent) 3 else 0)
                transcribe(h, pcm).text
            }
        }

        assertThat(texts.toSet()).hasSize(1)
        assertThat(texts[0].lowercase()).contains("ask not what your country can do for you")
    }

    @Test
    fun noThreadsLeakAcross50Takes() {
        val clip = jfk().copyOf(3 * 16_000)
        val h = load()
        on {
            NativeEngine.nativeSetThreads(h, 4, null, false, true)
            transcribe(h, clip)
        }
        val workers = on { NativeEngine.nativeThreadIds(h).toSet() }
        assertThat(engineThreads()).isEqualTo(workers + callerTid())

        repeat(50) { on { assertThat(transcribe(h, clip).status).isEqualTo(0) } }

        // The same workers, and no thread left behind by a run (the mel's threads are joined).
        assertThat(on { NativeEngine.nativeThreadIds(h).toSet() }).isEqualTo(workers)
        assertThat(engineThreads()).isEqualTo(workers + callerTid())
    }

    @Test
    fun takesShareNoState() {
        val pcm = jfk()
        val other = jfk().copyOfRange(5 * 16_000, 11 * 16_000)
        val h = load()
        val (first, middle, again) = on {
            NativeEngine.nativeSetThreads(h, 4, null, false, true)
            Triple(transcribe(h, pcm).text, transcribe(h, other).text, transcribe(h, pcm).text)
        }

        assertThat(again).isEqualTo(first)
        assertThat(middle).isNotEqualTo(first)
    }

    @Test
    fun unloadTakesThePoolDownAndAReloadWorks() {
        val pcm = jfk()
        val h = load()
        val text = on {
            NativeEngine.nativeSetThreads(h, 4, null, false, true)
            transcribe(h, pcm).text
        }
        assertThat(engineThreads()).hasSize(4)

        on { NativeEngine.nativeFree(h) }
        handles -= h
        assertThat(engineThreads()).containsExactly(callerTid())

        val again = load()
        val reloaded = on {
            NativeEngine.nativeSetThreads(again, 4, null, false, true)
            transcribe(again, pcm).text
        }
        assertThat(reloaded).isEqualTo(text)
        assertThat(engineThreads()).hasSize(4)
    }

    private fun <T> on(block: () -> T): T = engine.submit(Callable { block() }).get()

    private fun callerTid() = on { android.os.Process.myTid() }

    // The threads of this process named THREAD: the engine's.
    private fun engineThreads(): Set<Int> = File("/proc/self/task").listFiles().orEmpty()
        .filter { runCatching { File(it, "comm").readText().trim() }.getOrNull() == THREAD }
        .map { it.name.toInt() }.toSet()

    private fun allCpus() = IntArray(Runtime.getRuntime().availableProcessors()) { it }

    private fun transcribe(handle: Long, pcm: FloatArray) = NativeEngine.nativeTranscribe(handle, pcm, pcm.size, "en")

    private fun load(): Long = on {
        val file = TestModels.find(TestModels.PARAKEET_Q8)
        assertWithMessage("${TestModels.PARAKEET_Q8} missing: run android/tools/push-test-model.sh <serial>").that(file).isNotNull()
        assertThat(NativeEngine.nativeInit(context.applicationInfo.nativeLibraryDir)).isEqualTo(0)
        NativeEngine.nativeLoad(file!!.path, 4).also {
            assertThat(it).isGreaterThan(0L)
            handles += it
        }
    }

    private fun jfk(): FloatArray {
        val file = File(context.cacheDir, "jfk.wav")
        instrumentation.context.assets.open("audio/jfk.wav").use { input -> file.outputStream().use { input.copyTo(it) } }
        return Wav.readFloat(file.path, 0, 176_000)
    }

    private companion object {
        const val THREAD = "asr-pool-test" // 13 characters: the kernel keeps 15
    }
}
