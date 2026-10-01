package io.github.kabrapratik28.thumbfree.engine

import android.app.ActivityManager
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.collect.Range
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.github.kabrapratik28.thumbfree.testing.JFK_TEXT
import io.github.kabrapratik28.thumbfree.testing.TestModels
import io.github.kabrapratik28.thumbfree.testing.normalizeTranscript
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EngineServiceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val engine = RemoteEngine(context)

    @After
    fun unload() = runBlocking { engine.unload() }

    @Test
    fun runsInEngineProcess() = runBlocking {
        load()

        val pid = enginePid()
        assertThat(pid).isNotNull()
        assertThat(pid).isNotEqualTo(Process.myPid())
    }

    @Test
    fun bindLoadTranscribeUnload() = runBlocking {
        load()

        val result = engine.transcribe(jfk(), 0, JFK_SAMPLES, "en")
        Log.i(TAG, "engine jfk status=${result.status} encodeMs=${result.encodeMs} vmHwmKb=${result.vmHwmKb}")

        assertThat(result.status).isEqualTo(0)
        assertThat(normalizeTranscript(result.text)).isEqualTo(JFK_TEXT)
        assertThat(result.vmHwmKb).isGreaterThan(700_000L) // the 731 MB model is resident in :engine
        assertThat(result.vmHwmKb).isLessThan(1_468_006L) // 1.4 GiB
        engine.unload()
        awaitEngineGone()
    }

    // The queue's idle unload: :engine ends and takes the model's memory with it, and the next press loads again.
    // Silero's speech check runs in :engine on the chunk as recorded, alongside the transcribe. JFK is heard; a silent
    // chunk comes back with no text and vadRejected. Without the check, that chunk is transcribed as before.
    @Test
    fun speechCheckKeepsSpeechAndRefusesSilence() = runBlocking {
        load()
        val silent = File(context.cacheDir, "silent.wav").also { file ->
            io.github.kabrapratik28.thumbfree.core.audio.WavWriter.create(file).use {
                it.append(ShortArray(2 * 16_000))
                it.finish()
            }
        }

        val speech = engine.transcribe(jfk(), 0, JFK_SAMPLES, "en", speechCheck = true)
        val quiet = engine.transcribe(silent.path, 0, 2 * 16_000L, "en", speechCheck = true)
        val unchecked = engine.transcribe(silent.path, 0, 2 * 16_000L, "en")

        assertThat(speech.vadRejected).isFalse()
        assertThat(normalizeTranscript(speech.text)).isEqualTo(JFK_TEXT)
        assertThat(listOf(quiet.status, quiet.vadRejected, quiet.text)).containsExactly(0, true, "").inOrder()
        assertThat(listOf(unchecked.status, unchecked.vadRejected)).containsExactly(0, false).inOrder()
    }

    // Live preview: the stream on :engine's second session, behind its Silero gate. JFK fed as the Recorder sends it
    // (160 ms batches) gives JFK's words; committed text only ever grows; the end frees the stream's session, and the
    // offline path runs on as before. The feed calls' Binder times are logged (numbers only).
    @Test
    fun previewStreamGivesTheWordsAndEnds() = runBlocking {
        load()
        val pcm = jfkShorts()
        assertThat(engine.streamBegin(1, UNIFIED)).isEqualTo(0)
        var committed = ""
        val light = mutableListOf<Long>() // calls that ran no chunk: the Binder, copy and Silero cost alone
        for (at in 0 until pcm.size / 512 * 512 step BATCH) {
            val n = minOf(BATCH, pcm.size / 512 * 512 - at)
            val start = SystemClock.elapsedRealtimeNanos()
            val update = checkNotNull(engine.streamFeed(1, pcm.copyOfRange(at, at + n), n))
            val us = (SystemClock.elapsedRealtimeNanos() - start) / 1_000
            assertThat(update.status).isEqualTo(0)
            assertThat(update.committed).startsWith(committed) // never rewritten
            if (update.computeMs < 1f) light += us
            committed = update.committed
        }
        val last = drain(1)
        light.sort()
        Log.i(TAG, "stream_batches batch_ms=${BATCH / 16} light_calls=${light.size} light_p50_us=${light[light.size / 2]}")
        // Unfinalized, the stream holds back its last chunk and lookahead: the words so far are JFK's, in order.
        val words = normalizeTranscript(last.committed + last.tentative).split(" ")
        assertThat(words.size).isAtLeast(17)
        assertThat(JFK_TEXT.split(" ").take(words.size)).isEqualTo(words)

        engine.streamEnd(1) // returns once the stream is freed
        assertThat(engine.info()).contains("stream_session=0")
        assertThat(engine.info()).contains("stream_active=0")
        assertThat(engine.streamFeed(1, pcm, BATCH)?.status).isEqualTo(StreamUpdate.STALE) // ended: nothing is fed
        val result = engine.transcribe(jfk(), 0, JFK_SAMPLES, "en")
        assertThat(normalizeTranscript(result.text)).isEqualTo(JFK_TEXT)
        // A new take's stream starts from nothing.
        val next = streamAll(2, pcm.copyOfRange(0, 3 * 16_000))
        assertThat(normalizeTranscript(next.committed + next.tentative)).startsWith("and so my fellow")
    }

    // Live preview: the preview's gate is Silero run as a stream, the production path. Loud noise it rejects and clicks
    // never reach the stream; noise it sometimes takes for speech, and music, put no word on the panel; speech gets
    // through, also right after loud noise, and so does a short phrase. The MUSAN clips are OpenSLR 17 (CC BY 4.0).
    @Test
    fun theGateLetsSpeechInAndKeepsNoiseClicksAndMusicOut() = runBlocking {
        load()
        var token = 10L
        for ((name, clip) in listOf("noise" to loud(asset("musan-noise-0050.wav")), "clicks" to clicks(6))) {
            val update = streamAll(token++, clip)
            assertWithMessage(name).that(update.inputMs).isEqualTo(0L)
            assertWithMessage(name).that(update.committed + update.tentative).isEmpty()
        }
        for ((name, clip) in listOf("noise with false starts" to loud(asset("musan-noise-0300.wav")), "music" to music(6))) {
            val update = streamAll(token++, clip)
            Log.i(TAG, "preview_gate $name input_ms=${update.inputMs} of ${clip.size / 16}") // numbers only
            assertWithMessage(name).that((update.committed + update.tentative).trim()).isEmpty()
        }
        val jfk = jfkShorts()
        val afterNoise = streamAll(token++, loud(asset("musan-noise-0050.wav")).copyOf(3 * 16_000) + jfk)
        assertThat(normalizeTranscript(afterNoise.committed + afterNoise.tentative)).startsWith("and so my fellow americans")
        assertThat(afterNoise.inputMs).isIn(Range.closed(9_000L, 11_500L)) // JFK and its pre-roll, not the noise
        val short = streamAll(token, jfk.copyOf(24_000) + ShortArray(48_000)) // "And so, my fellow", then silence
        assertThat(normalizeTranscript(short.committed + short.tentative)).startsWith("and so")
    }

    // Live preview: the stream gives way to the offline path: its stream work while a transcribe runs is BUSY (the
    // audio waits in :engine), and the transcribe's text is the same.
    @Test
    fun previewStreamYieldsToATranscribe() = runBlocking {
        load()
        val pcm = jfkShorts()
        assertThat(engine.streamBegin(1, UNIFIED)).isEqualTo(0)
        val transcribe = async(Dispatchers.IO) { engine.transcribe(jfk(), 0, JFK_SAMPLES, "en") }
        var busy = false
        withTimeoutOrNull(10_000) {
            while (!busy && !transcribe.isCompleted) {
                busy = engine.streamFeed(1, pcm, 512)?.status == StreamUpdate.BUSY
                delay(5)
            }
        }
        assertThat(busy).isTrue()
        assertThat(normalizeTranscript(transcribe.await().text)).isEqualTo(JFK_TEXT)
        engine.streamEnd(1)
    }

    // Live preview: an end returns within a few ms whatever the stream's chunk is doing (encoder, committed decode,
    // tentative decode: patch 0014 stops each within a node or a step), so the final transcribe that waits for it never
    // waits for preview work. Swept across a chunk: the end comes 0 to 200 ms into it.
    @Test
    fun streamEndReturnsAtOnceWhateverTheChunkIsDoing() = runBlocking {
        load()
        val pcm = jfkShorts()
        val waits = mutableListOf<Long>()
        for (delayMs in 0..200 step 20) {
            val token = 100L + delayMs
            assertThat(engine.streamBegin(token, UNIFIED)).isEqualTo(0)
            engine.streamFeed(token, pcm.copyOf(3 * 16_000), 3 * 16_000) // one chunk runs; the rest waits in :engine
            val feeding = async(Dispatchers.IO) { engine.streamFeed(token, ShortArray(0), 0) } // the next chunk
            delay(delayMs.toLong())
            val start = SystemClock.elapsedRealtime()
            engine.streamEnd(token)
            waits += SystemClock.elapsedRealtime() - start
            assertThat(feeding.await()?.status).isAnyOf(0, 13, StreamUpdate.STALE)
        }
        Log.i(TAG, "stream_end_ms max=${waits.max()} all=$waits") // numbers only
        assertThat(waits.max()).isLessThan(100L) // a chunk here takes 150 to 200 ms
        assertThat(engine.info()).contains("stream_session=0")
    }

    // Live preview: a begin waits for the take's own model (1: the queue has not loaded it yet), and one on a model
    // that cannot stream (Canary) fails before :engine allocates anything.
    @Test
    fun beginWaitsForTheTakesModelAndAModelThatCannotStreamAllocatesNothing() = runBlocking {
        load() // Unified
        assertThat(engine.streamBegin(1, TestModels.CANARY_Q8)).isEqualTo(1) // another take's model
        val canary = TestModels.find(TestModels.CANARY_Q8)
        assertWithMessage("${TestModels.CANARY_Q8} missing: android/tools/push-test-model.sh <serial> ${TestModels.CANARY_Q8}")
            .that(canary).isNotNull()
        assertThat(engine.load(canary!!.path, 4)).isEqualTo(0)
        assertThat(engine.streamBegin(2, UNIFIED)).isEqualTo(1) // Unified is not the loaded one now
        assertThat(engine.streamBegin(3, TestModels.CANARY_Q8)).isEqualTo(2) // NOT_IMPLEMENTED
        assertThat(engine.info()).contains("stream_session=0")
        load() // back to Unified
        assertThat(engine.streamBegin(4, UNIFIED)).isEqualTo(0)
        engine.streamEnd(4)
    }

    // Live preview: begin is all or nothing. An end that came before its take's begin makes that begin return 13 and
    // make no session, and the next take's begin is untouched.
    @Test
    fun anEndBeforeItsBeginLeavesNoSession() = runBlocking {
        load()
        engine.streamEnd(7) // the stop overtook the begin in flight

        assertThat(engine.streamBegin(7, UNIFIED)).isEqualTo(13)
        assertThat(engine.info()).contains("stream_session=0")
        assertThat(engine.streamBegin(8, UNIFIED)).isEqualTo(0)
        engine.streamEnd(8)
        assertThat(engine.info()).contains("stream_session=0")
    }

    // Live preview: a begin that fails after the engine made the session frees it. The debug knob asks for a chunk the
    // model has not got, which the stream refuses once the session exists.
    @Test
    fun aBeginThatFailsAfterMakingTheSessionFreesIt() = runBlocking {
        val env = File(context.filesDir, "engine-env.txt")
        val saved = env.takeIf { it.isFile }?.readText()
        try {
            env.writeText("THUMBFREE_PREVIEW_BEGIN_FAILS=1\n") // read by the next :engine at its start
            load()

            assertThat(engine.streamBegin(1, UNIFIED)).isEqualTo(1) // TRANSCRIBE_ERR_INVALID_ARG from the stream
            assertThat(engine.info()).contains("stream_session=0")
        } finally {
            if (saved == null) env.delete() else env.writeText(saved)
        }
    }

    // Live preview: release frees any stream, and again does nothing; the stream's next feed finds none.
    @Test
    fun releaseFreesAnyStreamAndAgainIsHarmless() = runBlocking {
        load()
        assertThat(engine.streamBegin(1, UNIFIED)).isEqualTo(0)
        assertThat(engine.streamFeed(1, jfkShorts(), BATCH)?.status).isEqualTo(0)
        assertThat(engine.info()).contains("stream_session=1")

        engine.streamRelease()
        engine.streamRelease()

        assertThat(engine.info()).contains("stream_session=0")
        assertThat(engine.streamFeed(1, jfkShorts(), BATCH)?.status).isEqualTo(StreamUpdate.STALE)
    }

    // Live preview: an unload with a stream open frees it with the model; the preview's next call finds no engine.
    @Test
    fun unloadWithAStreamOpenLeavesNothing() = runBlocking {
        load()
        assertThat(engine.streamBegin(1, UNIFIED)).isEqualTo(0)
        assertThat(engine.streamFeed(1, jfkShorts(), BATCH)?.status).isEqualTo(0)

        engine.unload()
        awaitEngineGone()

        assertThat(engine.streamFeed(1, jfkShorts(), BATCH)).isNull()
        assertThat(engine.streamBegin(1, UNIFIED)).isEqualTo(StreamUpdate.NO_ENGINE) // it never binds or loads by itself
    }

    // Live preview: :engine dying mid-stream takes the stream with it; the next engine starts with none.
    @Test
    fun engineDeathMidStreamLeavesNoStreamBehind() = runBlocking {
        load()
        assertThat(engine.streamBegin(1, UNIFIED)).isEqualTo(0)
        assertThat(engine.streamFeed(1, jfkShorts(), BATCH)?.status).isEqualTo(0)

        Process.killProcess(checkNotNull(enginePid()))
        awaitEngineGone()
        assertThat(engine.streamFeed(1, jfkShorts(), BATCH)).isNull()

        runCatching { load() }.onFailure { load() } // the first call after a death may report it
        assertThat(engine.info()).contains("stream_session=0")
    }

    /** All of [pcm] through take [token]'s stream as the Recorder sends it, drained, then ended: its last update. */
    private suspend fun streamAll(token: Long, pcm: ShortArray): StreamUpdate {
        assertThat(engine.streamBegin(token, UNIFIED)).isEqualTo(0)
        val whole = pcm.size / 512 * 512
        for (at in 0 until whole step BATCH) {
            val n = minOf(BATCH, whole - at)
            assertThat(engine.streamFeed(token, pcm.copyOfRange(at, at + n), n)?.status).isEqualTo(0)
        }
        return drain(token).also { engine.streamEnd(token) }
    }

    /** Feeds nothing new until :engine has no gated audio left for the stream (one chunk a call): the last update. */
    private suspend fun drain(token: Long): StreamUpdate {
        var last = checkNotNull(engine.streamFeed(token, ShortArray(0), 0))
        repeat(20) {
            val next = checkNotNull(engine.streamFeed(token, ShortArray(0), 0))
            if (next.inputMs == last.inputMs) return next
            last = next
        }
        return last
    }

    private fun jfkShorts(): ShortArray {
        val floats = io.github.kabrapratik28.thumbfree.core.audio.Wav.readFloat(jfk(), 0, JFK_SAMPLES)
        return ShortArray(floats.size) { (floats[it] * 32_768).toInt().toShort() }
    }

    /** A 16 kHz mono clip from the test APK's assets. */
    private fun asset(name: String): ShortArray {
        val file = File(context.cacheDir, name)
        instrumentation.context.assets.open("audio/$name").use { input -> file.outputStream().use { input.copyTo(it) } }
        val floats = io.github.kabrapratik28.thumbfree.core.audio.Wav.readFloat(file.path, 0, (file.length() - 44) / 2)
        return ShortArray(floats.size) { (floats[it] * 32_768).toInt().toShort() }
    }

    /** [pcm] scaled to peak at -3 dBFS: loud. */
    private fun loud(pcm: ShortArray): ShortArray {
        val peak = pcm.maxOf { abs(it.toInt()) }.coerceAtLeast(1)
        return ShortArray(pcm.size) { (pcm[it] * 23_197.0 / peak).toInt().toShort() }
    }

    /** [seconds] of full-scale clicks, four a second. */
    private fun clicks(seconds: Int) = ShortArray(seconds * 16_000) { if (it % 4_000 < 2) 29_000.toShort() else 0 }

    /** [seconds] of a synthetic tune: harmonic notes a quarter second each, peaking at -3 dBFS. */
    private fun music(seconds: Int): ShortArray {
        val notes = doubleArrayOf(262.0, 330.0, 392.0, 523.0, 440.0, 349.0)
        val raw = DoubleArray(seconds * 16_000) { i ->
            val t = (i % 4_000) / 16_000.0
            val f = notes[i / 4_000 % notes.size]
            val envelope = minOf(1.0, t * 40) * exp(-t * 3)
            envelope * (sin(2 * PI * f * t) + 0.5 * sin(4 * PI * f * t) + 0.25 * sin(6 * PI * f * t))
        }
        val peak = raw.maxOf { abs(it) }
        return ShortArray(raw.size) { (raw[it] / peak * 23_197).toInt().toShort() }
    }

    @Test
    fun loadAfterUnloadStartsAFreshEngine() = runBlocking {
        load()
        val first = enginePid()!!
        engine.unload()
        awaitEngineGone()

        val start = SystemClock.elapsedRealtime()
        load()
        Log.i(TAG, "engine reload_ms=${SystemClock.elapsedRealtime() - start}")

        val second = enginePid()
        assertThat(second).isNotNull()
        assertThat(second).isNotEqualTo(first)
        assertThat(engine.transcribe(jfk(), 0, JFK_SAMPLES, "en").status).isEqualTo(0)
    }

    // transcribe.cpp reads the GGUF with ifstream, not mmap, so the maps line alone would pass even with the model
    // loaded here. Resident memory would grow by the model's 731 MB.
    @Test
    fun modelNotMappedInMainProcess() = runBlocking {
        val before = vmRssKb()
        load()

        assertThat(vmRssKb() - before).isLessThan(100_000L)
        assertThat(File("/proc/self/maps").readLines().filter { ".gguf" in it }).isEmpty()
    }

    @Test
    fun engineDeathIsTyped() = runBlocking {
        load()
        Process.killProcess(enginePid()!!)
        awaitEngineGone()
        delay(200) // binderDied has run: the next call reports the death without touching the dead binder

        val died = runCatching { engine.transcribe(jfk(), 0, JFK_SAMPLES, "en") }.exceptionOrNull()

        assertThat(died).isInstanceOf(EngineDiedException::class.java)
        load()
        assertThat(engine.transcribe(jfk(), 0, JFK_SAMPLES, "en").status).isEqualTo(0)
    }

    // The low-memory killer ends an idle :engine: isAlive shows it with no Binder call, and a load starts a new one.
    @Test
    fun idleDeathShowsAndLoadStartsAgain() = runBlocking {
        load()
        Process.killProcess(enginePid()!!)
        awaitEngineGone()
        delay(200) // binderDied has run

        assertThat(engine.isAlive).isFalse()
        load() // no EngineDiedException: the caller is loading the model again anyway
        assertThat(engine.isAlive).isTrue()
        assertThat(engine.transcribe(jfk(), 0, JFK_SAMPLES, "en").status).isEqualTo(0)
    }

    @Test
    fun deathDuringCallIsTypedOnce() = runBlocking {
        load()
        val wav = jfk()
        val pid = enginePid()!!
        val call = async { runCatching { engine.transcribe(wav, 0, JFK_SAMPLES, "en") }.exceptionOrNull() }
        delay(100) // still running: the encoder alone takes 290 to 570 ms on the emulator
        Process.killProcess(pid)

        assertThat(call.await()).isInstanceOf(EngineDiedException::class.java)
        awaitEngineGone()
        delay(200) // binderDied has run too, and must not report the same death again
        load()
        assertThat(engine.transcribe(wav, 0, JFK_SAMPLES, "en").status).isEqualTo(0)
    }

    @Test
    fun missingModelIsStatus3() = runBlocking {
        assertThat(engine.load(File(context.cacheDir, "missing.gguf").path, 6)).isEqualTo(3)
    }

    @Test
    fun badRequestsAreStatus1() = runBlocking {
        val wav = jfk()
        val missing = File(context.cacheDir, "missing.wav").apply { delete() }.path

        assertThat(engine.transcribe(wav, 0, JFK_SAMPLES, "en").status).isEqualTo(1) // no model loaded
        load()
        assertThat(engine.transcribe(missing, 0, 16_000, "en").status).isEqualTo(1)
        assertThat(engine.transcribe(wav, 0, JFK_SAMPLES, "en").status).isEqualTo(0)
    }

    @Test
    fun cancelledBindLeavesNothingBound() = runBlocking {
        awaitEngineGone()

        // A new :engine takes about 150 ms to publish its binder, so this cancels the caller in the middle of the bind.
        assertThat(withTimeoutOrNull(50) { load() }).isNull()

        awaitEngineGone() // a binding left behind would keep :engine alive
        load()
    }

    @Test
    fun bindsWhileMainThreadIsBusy() = runBlocking {
        val release = CountDownLatch(1)
        Handler(Looper.getMainLooper()).post { release.await() }
        try {
            assertThat(withTimeoutOrNull(5_000) { load() }).isNotNull()
        } finally {
            release.countDown()
        }
    }

    // The queue's abort is a oneway call, so it can reach :engine after the run it names has ended. Naming that run, it
    // leaves the next run alone even landing all through it, and the next chunk comes back whole; naming the run in
    // progress, it stops it.
    @Test
    fun lateAbortLeavesTheNextRunAlone() = runBlocking {
        load()
        val wav = jfk()
        assertThat(engine.transcribe(wav, 0, JFK_SAMPLES, "en", token = 1).status).isEqualTo(0)

        val next = async { engine.transcribe(wav, 0, JFK_SAMPLES, "en", token = 2) }
        val late = launch { while (next.isActive) { engine.abort(1); delay(1) } }
        val result = next.await()
        late.cancelAndJoin()

        assertThat(result.status).isEqualTo(0)
        assertThat(normalizeTranscript(result.text)).isEqualTo(JFK_TEXT)

        val stopped = async { engine.transcribe(wav, 0, JFK_SAMPLES, "en", token = 3) }
        val named = launch {
            delay(100)
            while (stopped.isActive) { engine.abort(3); delay(1) }
        }
        assertThat(stopped.await().status).isEqualTo(13)
        named.cancelAndJoin()
    }

    // :engine runs the model on its one "asr" thread and the model's persistent pool, whose workers inherit the name:
    // the same threads take after take, and a run leaves none behind.
    @Test
    fun engineThreadsStayPutAcrossTakes() = runBlocking {
        load()
        val wav = jfk()
        assertThat(engine.transcribe(wav, 0, JFK_SAMPLES, "en").status).isEqualTo(0)
        val pid = enginePid()!!
        val before = asrThreads(pid)

        repeat(10) { assertThat(engine.transcribe(wav, 0, JFK_SAMPLES, "en").status).isEqualTo(0) }

        assertThat(asrThreads(pid)).isEqualTo(before)
        assertThat(before.size).isGreaterThan(1) // the asr thread and the pool's workers
    }

    // Only Parakeet runs its graphs on a persistent pool (patch 0011). Canary takes the thread count alone, so after
    // its load and a take no idle worker is left in :engine, only the asr thread.
    @Test
    fun canaryGetsNoPool() = runBlocking {
        val model = TestModels.find(TestModels.CANARY_Q8)
        assertWithMessage("${TestModels.CANARY_Q8} missing: run android/tools/push-test-model.sh <serial> ${TestModels.CANARY_Q8}")
            .that(model).isNotNull()
        assertThat(engine.load(model!!.path, 6)).isEqualTo(0)
        assertThat(engine.transcribe(jfk(), 0, JFK_SAMPLES, "en").status).isEqualTo(0)

        assertThat(asrThreads(enginePid()!!)).hasSize(1)
    }

    @Test
    fun abortDoesNotWaitForTheEngine() = runBlocking {
        load()
        val pid = enginePid()!!
        val aborted = CountDownLatch(1)
        Os.kill(pid, OsConstants.SIGSTOP) // a stopped :engine cannot answer a call
        val returnedAtOnce = try {
            assertThat(eventually(1_000) { processState(pid) == 'T' }).isTrue()
            thread {
                engine.abort()
                aborted.countDown()
            }
            aborted.await(1, TimeUnit.SECONDS)
        } finally {
            Os.kill(pid, OsConstants.SIGCONT)
        }

        assertThat(returnedAtOnce).isTrue()
    }

    // A missing model fails the test: a skip would let the suite go green without running.
    private suspend fun load() {
        val model = TestModels.find(TestModels.PARAKEET_Q8)
        assertWithMessage("${TestModels.PARAKEET_Q8} missing: run android/tools/push-test-model.sh <serial>")
            .that(model).isNotNull()
        assertThat(engine.load(model!!.path, 6)).isEqualTo(0)
    }

    private suspend fun awaitEngineGone() {
        assertThat(eventually(5_000) { enginePid() == null }).isTrue()
    }

    private suspend fun eventually(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (!condition()) {
            if (SystemClock.elapsedRealtime() > deadline) return false
            delay(10)
        }
        return true
    }

    private fun asrThreads(pid: Int): Set<String> = File("/proc/$pid/task").listFiles().orEmpty()
        .filter { runCatching { File(it, "comm").readText().trim() }.getOrNull() == "asr" }.map { it.name }.toSet()

    private fun enginePid(): Int? = context.getSystemService(ActivityManager::class.java).runningAppProcesses.orEmpty()
        .firstOrNull { it.processName == "${context.packageName}:engine" }?.pid

    // The state letter follows the process name in /proc/<pid>/stat; T means stopped.
    private fun processState(pid: Int) = File("/proc/$pid/stat").readText().substringAfterLast(") ").first()

    private fun vmRssKb() = File("/proc/self/status").readLines().first { it.startsWith("VmRSS:") }
        .substringAfter(':').trim().substringBefore(' ').toLong()

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
        const val BATCH = 160 * 16 // 5 Silero windows, shorter than a chunk: most calls run none
        const val UNIFIED = TestModels.PARAKEET_Q8
    }
}
