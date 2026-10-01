package io.github.kabrapratik28.thumbfree.bench

import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.github.kabrapratik28.thumbfree.audio.PcmRing
import io.github.kabrapratik28.thumbfree.core.audio.Wav
import io.github.kabrapratik28.thumbfree.core.audio.WavChunks
import io.github.kabrapratik28.thumbfree.core.audio.WavWriter
import io.github.kabrapratik28.thumbfree.core.session.Preview
import io.github.kabrapratik28.thumbfree.core.text.joinChunks
import io.github.kabrapratik28.thumbfree.engine.Engine
import io.github.kabrapratik28.thumbfree.engine.PreviewFeed
import io.github.kabrapratik28.thumbfree.engine.RemoteEngine
import io.github.kabrapratik28.thumbfree.engine.StreamUpdate
import io.github.kabrapratik28.thumbfree.testing.EngineMemory
import io.github.kabrapratik28.thumbfree.testing.TestModels
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Live preview: the phone check (android/tools/live-phone-check.sh drives it in the .bench copy of the app, never the
 * owner's; runs only with `-e live_check 1`). Engine level: no bubble, no accessibility service, no UI. One take of
 * `-e seconds` (30 or 60, or 900 for the long-take check) of JFK end to end, played at real time the way the app
 * records: the chunk planner's chunks transcribed by :engine as they close and the last one at the stop (today's path),
 * and with `-e mode on` the live preview too (PreviewFeed, :engine's Silero gate and stream), each transcribe waiting
 * for a stopped preview's stream to be freed, as the app's queue does. Into filesDir/bench/<out>/ go one TSV row (the
 * stream's real-time factor and whether it fell behind, the stop to the last chunk's text, :engine's peak PSS and its
 * peak RSS at 1 minute and at the end, so a long take shows whether memory stays flat, the thermal status, and the
 * boot-clock window the script reads the power rails over), the take's final text, for the off and on pair's
 * comparison, and :engine's info line with its effective environment. Numbers and public audio only.
 */
@RunWith(AndroidJUnit4::class)
class LivePreviewPhoneTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun take() = runBlocking {
        assumeTrue("pass -e live_check 1", arg("live_check", "0") == "1")
        val on = arg("mode", "off") == "on"
        val seconds = arg("seconds", "30").toInt()
        val out = File(context.filesDir, "bench/${arg("out", "live-check")}").apply { mkdirs() }
        val pcm = jfkLoop(seconds)
        val wav = File(out, "take-$seconds.wav")
        WavWriter.create(wav).use {
            it.append(pcm)
            it.finish()
        }
        val chunks = WavChunks.plan(wav) // the app's planner, as a Retry plans the WAV

        val remote = RemoteEngine(context)
        val computeMs = CopyOnWriteArrayList<Float>()
        val engine = object : Engine by remote {
            override suspend fun streamFeed(token: Long, pcm: ShortArray, n: Int): StreamUpdate? =
                remote.streamFeed(token, pcm, n)?.also { if (it.status == 0) computeMs += it.computeMs }
        }
        val model = checkNotNull(TestModels.find(TestModels.PARAKEET_Q8)) { "push the model: android/tools/push-test-model.sh" }
        assertThat(remote.load(model.path, 5)).isEqualTo(0)
        val warm = remote.transcribe(wav.path, 0, 16_000L * 3, "en") // the first run after a load builds its buffers
        assertThat(warm.status).isEqualTo(0)

        val events = CopyOnWriteArrayList<Preview.Event>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
        val feed = PreviewFeed(engine, scope) { events += it }
        val ring = PcmRing(PreviewFeed.RING_SAMPLES)
        val power = context.getSystemService(PowerManager::class.java)
        var thermal = power.currentThermalStatus
        var pssKb = 0L
        var hwmMinuteKb = 0L // peak RSS at 1 minute: the long take's baseline
        var behindMs = 0L // the most audio the stream had not taken yet
        var stopToTextMs = 0L
        val texts = mutableListOf<String>() // each chunk's text, in order: the take's final text
        val jobs = Channel<Pair<Long, Long>>(Channel.UNLIMITED) // the queue: chunk ranges in the order they close
        val queue = launch(Dispatchers.IO) {
            for ((from, to) in jobs) {
                feed.awaitEnded() // as the app's queue: a stopped preview's stream is freed before the transcribe
                val r = remote.transcribe(wav.path, from, to, "en", speechCheck = true)
                assertThat(r.status).isEqualTo(0)
                texts += if (r.vadRejected) "" else r.text
                if (to == pcm.size.toLong()) stopToTextMs = SystemClock.elapsedRealtime() - stopAt
            }
        }
        if (on) feed.start("take", TestModels.PARAKEET_Q8, ring)
        val start = SystemClock.elapsedRealtime()
        var next = 0 // the next chunk to send
        var read = 0
        while (read < pcm.size) { // the Recorder's reads, 20 ms each, at real time
            val block = pcm.copyOfRange(read, minOf(read + 320, pcm.size))
            if (on) ring.write(block, block.size) // as the Recorder: all of it, :engine's Silero picks the speech
            read += block.size
            while (next < chunks.size && chunks[next].toSample <= read && chunks[next].toSample < pcm.size) {
                jobs.send(chunks[next].fromSample to chunks[next].toSample)
                next++
            }
            if (read % (16_000 * 5) == 0) { // every 5 s
                thermal = maxOf(thermal, power.currentThermalStatus)
                pssKb = maxOf(pssKb, EngineMemory.pssKb())
                if (read == 16_000 * 60) hwmMinuteKb = EngineMemory.hwmKb()
            }
            behindMs = maxOf(behindMs, ring.available / 16)
            delay(maxOf(0L, start + read / 16 - SystemClock.elapsedRealtime()))
        }
        stopAt = SystemClock.elapsedRealtime()
        feed.stop()
        while (next < chunks.size) jobs.send(chunks[next].fromSample to chunks[next++].toSample) // the stop's last chunk
        jobs.close()
        queue.join()
        val end = SystemClock.elapsedRealtime()
        thermal = maxOf(thermal, power.currentThermalStatus)
        pssKb = maxOf(pssKb, EngineMemory.pssKb())
        val hwmKb = EngineMemory.hwmKb()
        val info = remote.info() // the engine and, in debug builds, its effective environment
        remote.unload()

        val streamRtf = computeMs.sum() / (seconds * 1_000f)
        val behind = events.any { it is Preview.Event.Behind }
        val failed = events.any { it is Preview.Event.Failed }
        val updates = events.count { it is Preview.Event.Text }
        val row = listOf(
            if (on) "on" else "off", seconds, pcm.size / 16, computeMs.size, "%.1f".format(computeMs.sum()),
            "%.3f".format(streamRtf), "%.0f".format(computeMs.maxOrNull() ?: 0f), behindMs, if (behind) 1 else 0,
            if (failed) 1 else 0, updates, stopToTextMs, pssKb, hwmMinuteKb, hwmKb, thermal, start, end,
        ).joinToString("\t")
        val tag = arg("tag", "run")
        File(out, "take-$tag.tsv").writeText(HEADER + "\n" + row + "\n")
        File(out, "take-$tag.txt").writeText(joinChunks(texts)) // the typed text, for the off and on pair's comparison
        File(out, "env-$tag.txt").writeText(info.replace(' ', '\n') + "\n")
        Log.i("ThumbFree", "live_check $row")
        if (on) assertThat(failed).isFalse()
        // Fails closed: an unreadable peak RSS is no measurement, and no pass.
        assertWithMessage(":engine's VmHWM").that(hwmKb).isGreaterThan(0L)
        if (seconds >= 60) assertWithMessage(":engine's VmHWM at 1 minute").that(hwmMinuteKb).isGreaterThan(0L)
    }

    private var stopAt = 0L

    /** JFK (the test assets' jfk.wav, 11 s with its own pauses) end to end until [seconds]. */
    private fun jfkLoop(seconds: Int): ShortArray {
        val file = File(context.cacheDir, "jfk.wav")
        InstrumentationRegistry.getInstrumentation().context.assets.open("audio/jfk.wav").use { input ->
            file.outputStream().use { input.copyTo(it) }
        }
        val jfk = Wav.readFloat(file.path, 0, 176_000)
        return ShortArray(seconds * 16_000) { (jfk[it % jfk.size] * 32_768).toInt().toShort() }
    }

    private companion object {
        const val HEADER = "mode\tseconds\taudio_ms\tcalls\tcompute_ms\tstream_rtf\tchunk_max_ms\tbehind_max_ms\tbehind\t" +
            "failed\ttexts\tstop_to_text_ms\tengine_pss_kb\tengine_hwm_1min_kb\tengine_hwm_kb\tthermal_max\tstart_ms\tend_ms"

        fun arg(name: String, default: String) = InstrumentationRegistry.getArguments().getString(name) ?: default
    }
}
