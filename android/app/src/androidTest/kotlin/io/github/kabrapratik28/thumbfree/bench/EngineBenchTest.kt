package io.github.kabrapratik28.thumbfree.bench

import android.os.PowerManager
import android.os.SystemClock
import android.system.Os
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import io.github.kabrapratik28.thumbfree.core.audio.Wav
import io.github.kabrapratik28.thumbfree.engine.NativeEngine
import io.github.kabrapratik28.thumbfree.engine.RemoteEngine
import io.github.kabrapratik28.thumbfree.engine.VadModel
import io.github.kabrapratik28.thumbfree.testing.TestModels
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Engine time, memory and transcripts in the test process, the way NativeEngineTest runs the engine.
 * Runs only with `-e engine_bench 1`, one method per command (android/tools/engine-bench.py drives it). Clips come from
 * filesDir/bench/<dir>/, results go to filesDir/bench/<method>-<tag>.tsv. `-e env NAME=1,...` sets transcribe.cpp
 * environment switches before the load, so one APK can compare builds of the engine in alternating processes.
 * `-e out <folder>` puts the TSVs in filesDir/bench/<folder> instead. `-e model <GGUF file name>` picks the model in
 * filesDir/models (default Parakeet Unified) and `-e lang` its language hint (default en; none for none, as the app
 * gives the multilingual model).
 */
@RunWith(AndroidJUnit4::class)
class EngineBenchTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val bench = File(context.filesDir, "bench")

    /** For each clip of `-e clips` (in `-e dir`): one warm-up run, then `-e runs` timed runs, one TSV row each. */
    @Test
    fun speed() {
        val handle = load()
        val rows = mutableListOf("clip\trun\tmel_ms\tencode_ms\tdecode_ms\tvm_hwm_kb\tpss_kb\tthermal\tcpus")
        val power = context.getSystemService(PowerManager::class.java)
        val cpus = File("/proc/self/status").readLines().first { it.startsWith("Cpus_allowed_list:") }
            .substringAfter(':').trim()
        for (name in arg("clips", "jfk.wav").split(',')) {
            val pcm = pcm(File(bench, "${arg("dir", "public")}/$name"))
            for (run in 0..arg("runs", "10").toInt()) {
                val r = NativeEngine.nativeTranscribe(handle, pcm, pcm.size, lang)
                assertThat(r.status).isEqualTo(0)
                if (run > 0) {
                    rows += "$name\t$run\t${r.melMs}\t${r.encodeMs}\t${r.decodeMs}\t${r.vmHwmKb}\t${pssKb()}\t" +
                        "${power.currentThermalStatus}\t$cpus"
                }
            }
        }
        write("speed", rows)
        NativeEngine.nativeFree(handle)
    }

    /** One run per WAV in `-e dir`; the TSV holds the texts, the log only their lengths (clips may be private). */
    @Test
    fun accuracy() {
        val handle = load()
        val rows = mutableListOf<String>()
        for (wav in File(bench, arg("dir", "public")).listFiles { f -> f.name.endsWith(".wav") }!!.sorted()) {
            val pcm = pcm(wav)
            val r = NativeEngine.nativeTranscribe(handle, pcm, pcm.size, lang)
            assertThat(r.status).isEqualTo(0)
            rows += "${wav.name}\t${r.text.replace(Regex("[\t\n]"), " ")}"
            Log.i(TAG, "engine-bench accuracy ${wav.name} chars=${r.text.length}")
        }
        write("accuracy", rows)
        NativeEngine.nativeFree(handle)
    }

    /**
     * Silero's cost, and what it adds to a transcribe when it runs alongside it on another thread, as SpeechCheck runs
     * it. For each clip, one warm-up round, then `-e runs` rounds of: the transcribe alone, the check alone, and both
     * at once (asr_ms_with: the transcribe's time then; wait_ms: how long the text then waits for the check). One TSV
     * row per round.
     */
    @Test
    fun speechCheck() {
        val handle = load()
        val vad = NativeEngine.nativeVadLoad(VadModel.file(context)!!.path)
        assertThat(vad).isGreaterThan(0L)
        val pool = Executors.newSingleThreadExecutor()
        val ms = { start: Long -> (System.nanoTime() - start) / 1_000_000 }
        val rows = mutableListOf("clip	run	asr_ms	vad_ms	both_ms	asr_ms_with	wait_ms")
        for (name in arg("clips", "jfk.wav").split(',')) {
            val pcm = pcm(File(bench, "${arg("dir", "public")}/$name"))
            for (run in 0..arg("runs", "10").toInt()) {
                var start = System.nanoTime()
                NativeEngine.nativeTranscribe(handle, pcm, pcm.size, lang)
                val asr = ms(start)
                start = System.nanoTime()
                NativeEngine.nativeVadProbs(vad, pcm, pcm.size)
                val check = ms(start)
                start = System.nanoTime()
                val probs = pool.submit<FloatArray?> { NativeEngine.nativeVadProbs(vad, pcm, pcm.size) }
                NativeEngine.nativeTranscribe(handle, pcm, pcm.size, lang)
                val asrWith = ms(start)
                val waited = System.nanoTime()
                assertThat(probs.get()).isNotNull()
                if (run > 0) rows += "$name\t$run\t$asr\t$check\t${ms(start)}\t$asrWith\t${ms(waited)}"
            }
        }
        write("speech-check", rows)
        pool.shutdown()
        NativeEngine.nativeVadFree(vad)
        NativeEngine.nativeFree(handle)
    }

    /**
     * The app's own path into :engine (RemoteEngine, EngineService, Silero alongside) for each clip of `-e clips`, one
     * warm-up and `-e runs` timed calls, each after `-e gap_ms` of rest (default 2,000, so a call starts from an idle
     * CPU as a take's last chunk does). :engine logs each call's split (engine_stage, speech_check_wait). For A/B runs,
     * `-e seed` above 0 shuffles the timed calls of all clips, and `-e sustain_s` instead calls the clips in turn for
     * that long. The TSV holds each call's boot-clock start (for the power rails), wall time, thermal status before and
     * after, the model file and the variant :engine loaded (its info line), and text (public clips only).
     */
    @Test
    fun remote() = runBlocking {
        val engine = RemoteEngine(context)
        val model = model()
        assertThat(engine.load(model.path, arg("threads", "5").toInt())).isEqualTo(0)
        val variant = Regex("variant=(\\S+)").find(engine.info())?.groupValues?.get(1).orEmpty()
        val power = context.getSystemService(PowerManager::class.java)
        val rows = mutableListOf("clip\trun\tstart_ms\twall_ms\tstatus\taudio_ms\tthermal0\tthermal1\tmodel\tvariant\ttext")
        val wavs = arg("clips", "jfk.wav").split(',').map { File(bench, "${arg("dir", "public")}/$it") }
        suspend fun call(wav: File, run: Int) { // run 0: the warm-up, not written
            delay(arg("gap_ms", "2000").toLong())
            val thermal = power.currentThermalStatus
            val start = SystemClock.elapsedRealtime()
            val r = engine.transcribe(wav.path, 0, (wav.length() - 44) / 2, lang, speechCheck = true)
            assertThat(r.status).isEqualTo(0)
            if (run > 0) rows += "${wav.name}\t$run\t$start\t${SystemClock.elapsedRealtime() - start}\t${r.status}\t" +
                "${(wav.length() - 44) / 32}\t$thermal\t${power.currentThermalStatus}\t${model.name}\t$variant\t" +
                r.text.replace(Regex("[\t\n]"), " ")
        }
        try {
            wavs.forEach { call(it, 0) }
            val sustain = arg("sustain_s", "0").toLong() * 1_000
            if (sustain > 0) {
                val end = SystemClock.elapsedRealtime() + sustain
                var run = 0
                while (SystemClock.elapsedRealtime() < end) call(wavs[run % wavs.size], ++run)
            } else {
                val calls = wavs.flatMap { wav -> (1..arg("runs", "10").toInt()).map { wav to it } }
                val seed = arg("seed", "0").toLong()
                for ((wav, run) in if (seed > 0) calls.shuffled(java.util.Random(seed)) else calls) call(wav, run)
            }
        } finally {
            write("remote", rows) // a failed call keeps the calls before it
        }
        engine.unload()
    }

    /** `-e loads` (default 5) loads of the model, each freed again; the TSV holds each load's wall time. */
    @Test
    fun loadTimes() {
        val rows = mutableListOf("load\tms")
        repeat(arg("loads", "5").toInt()) { i ->
            val start = SystemClock.elapsedRealtime()
            val handle = load()
            rows += "$i\t${SystemClock.elapsedRealtime() - start}"
            NativeEngine.nativeFree(handle)
        }
        write("loads", rows)
    }

    private fun load(): Long {
        for (kv in arg("env", "").split(',').filter { it.isNotEmpty() }) Os.setenv(kv.substringBefore('='), kv.substringAfter('='), true)
        assertThat(NativeEngine.nativeInit(context.applicationInfo.nativeLibraryDir)).isEqualTo(0)
        val before = pssKb()
        val start = SystemClock.elapsedRealtime()
        val handle = NativeEngine.nativeLoad(model().path, arg("threads", "4").toInt())
        assertThat(handle).isGreaterThan(0L)
        Log.i(TAG, "engine-bench load env=${arg("env", "")} load_ms=${SystemClock.elapsedRealtime() - start} " +
            "pss_before_kb=$before pss_loaded_kb=${pssKb()} ${NativeEngine.nativeInfo(handle)}")
        return handle
    }

    /** The clip; with `-e dither <seed>` above 0, plus a random -1, 0 or +1 LSB of PCM16 on every sample, an inaudible
     *  change that shows which transcripts sit on a knife edge. */
    private fun pcm(wav: File): FloatArray {
        val pcm = Wav.readFloat(wav.path, 0, (wav.length() - 44) / 2)
        val seed = arg("dither", "0").toLong()
        if (seed > 0) {
            val random = java.util.Random(seed * 7919 + wav.name.hashCode())
            for (i in pcm.indices) {
                pcm[i] = ((pcm[i] * 32768f).roundToInt() + random.nextInt(3) - 1).coerceIn(-32768, 32767) / 32768f
            }
        }
        return pcm
    }

    private fun model() =
        checkNotNull(TestModels.find(arg("model", TestModels.PARAKEET_Q8))) { "no model: run android/tools/push-test-model.sh" }

    private val lang: String? get() = arg("lang", "en").takeIf { it != "none" }

    /** This process's proportional set size, from smaps_rollup. */
    private fun pssKb() = File("/proc/self/smaps_rollup").readLines().first { it.startsWith("Pss:") }.split(Regex(" +"))[1]

    // In filesDir/bench, or its `-e out` folder (a phone script's run directory).
    private fun write(method: String, rows: List<String>) {
        val dir = arg("out", "").let { if (it.isEmpty()) bench else File(bench, it) }.apply { mkdirs() }
        File(dir, "$method-${arg("tag", "run")}.tsv").writeText(rows.joinToString("\n", postfix = "\n"))
    }

    companion object {
        private const val TAG = "ThumbFree"

        private fun arg(name: String, default: String) = InstrumentationRegistry.getArguments().getString(name) ?: default

        @BeforeClass
        @JvmStatic
        fun benchOnly() = assumeTrue("pass -e engine_bench 1", arg("engine_bench", "0") == "1")
    }
}
