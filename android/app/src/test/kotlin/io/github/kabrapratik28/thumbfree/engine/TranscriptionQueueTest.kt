package io.github.kabrapratik28.thumbfree.engine

import com.google.common.truth.Truth.assertThat
import io.github.kabrapratik28.thumbfree.core.audio.Chunk
import io.github.kabrapratik28.thumbfree.core.session.Code
import io.github.kabrapratik28.thumbfree.engine.FakeEngine.Reply
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TranscriptionQueueTest {
    private val events = mutableListOf<String>()
    private val logs = mutableListOf<String>()
    private var onEvent: (String) -> Unit = {} // runs inside the listener call, after the event is recorded
    private var model: String? = "m"
    private var language: String? = "en"
    private val doneLanguages = mutableListOf<String?>() // each onDone's language, in order
    private var sleptMs = 0L // deep sleep: the clock (elapsedRealtime) counts it, the scheduler's awake time does not

    private val listener = object : TranscriptionQueue.Listener {
        override fun onLoading(sessionId: String) = record("loading $sessionId")

        override fun onLoaded(sessionId: String) = record("loaded $sessionId")

        override fun onChunkDone(sessionId: String, index: Int, text: String, rawText: String) =
            record("chunk $sessionId $index [$text] [$rawText]")

        override fun onDone(sessionId: String, texts: List<String>, rawTexts: List<String>, speech: Boolean, language: String?) {
            doneLanguages += language
            record("done $sessionId $texts $rawTexts" + if (speech) "" else " no-speech")
        }

        override fun onFailed(sessionId: String, code: Code) = record("failed $sessionId $code")
    }

    private fun record(event: String) {
        events += event
        onEvent(event)
    }

    // A scope of its own on the test scheduler: advanceUntilIdle runs the worker, and runTest does not wait for it. No
    // idle unload, which advanceUntilIdle would reach after every test's last job.
    private fun TestScope.queue(engine: Engine, modelPath: (String) -> String? = { model }) = TranscriptionQueue(
        engine, CoroutineScope(StandardTestDispatcher(testScheduler)), modelPath, { 4 }, listener, language = { language },
        unloadAfterIdleMs = Long.MAX_VALUE, clock = { testScheduler.currentTime + sleptMs }, log = { logs += it },
    )

    // With the default idle unload: 5 minutes.
    private fun TestScope.idleQueue(engine: Engine) = TranscriptionQueue(
        engine, CoroutineScope(StandardTestDispatcher(testScheduler)), { model }, { 4 }, listener,
        clock = { testScheduler.currentTime + sleptMs }, log = { logs += it },
    )

    private fun TranscriptionQueue.take(id: String) {
        submit(id, WAV, speech(0))
        finish(id, 1)
    }

    private fun TestScope.advanceTo(ms: Long) {
        advanceTimeBy(ms - testScheduler.currentTime)
        runCurrent()
    }

    // The live preview's stream is freed in :engine before a chunk's transcribe goes out, so the take's final chunk
    // never runs behind preview work.
    @Test
    fun eachTranscribeWaitsForThePreviewStreamToBeFreed() = runTest {
        val engine = FakeEngine(Reply("a"))
        val freed = CompletableDeferred<Unit>()
        val order = mutableListOf<String>()
        val queue = TranscriptionQueue(
            engine, CoroutineScope(StandardTestDispatcher(testScheduler)), { model }, { 4 }, listener,
            unloadAfterIdleMs = Long.MAX_VALUE, clock = { testScheduler.currentTime }, log = { logs += it },
            beforeTranscribe = { freed.await(); order += "stream freed" },
        )

        queue.take("s")
        advanceTimeBy(1_000)
        assertThat(engine.calls.none { it.startsWith("transcribe") }).isTrue() // still waiting for :engine's ack
        freed.complete(Unit)
        advanceUntilIdle()

        assertThat(engine.calls.last()).startsWith("transcribe")
        assertThat(order).containsExactly("stream freed")
        assertThat(events.last()).isEqualTo("done s [a] [a]")
    }

    @Test
    fun jobsRunInFifoOrder() = runTest {
        val engine = FakeEngine(Reply("a"), Reply("b"), Reply("c"))
        val queue = queue(engine)

        queue.submit("s", WAV, speech(0))
        queue.submit("s", WAV, speech(16_000))
        queue.submit("s", WAV, speech(32_000))
        queue.finish("s", 3)
        advanceUntilIdle()

        assertThat(engine.calls)
            .containsExactly("load m 4", "transcribe 0-16000", "transcribe 16000-32000", "transcribe 32000-48000")
            .inOrder()
        assertThat(events).containsExactly(
            "loading s", "loaded s", "chunk s 0 [a] [a]", "chunk s 1 [b] [b]", "chunk s 2 [c] [c]",
            "done s [a, b, c] [a, b, c]",
        ).inOrder()
    }

    @Test
    fun speechFreeChunkSkipsTheEngine() = runTest {
        val queue = queue(FakeEngine())

        queue.submit("s", WAV, Chunk(0, 16_000, false))
        advanceUntilIdle()

        assertThat(events).containsExactly("chunk s 0 [] []")
        assertThat(queue.engineCalls).isEqualTo(0)
    }

    // The speech check's first branch: a chunk with no frame above -55 dBFS is refused without the engine, and a take
    // of such chunks is no speech.
    @Test
    fun quietChunksAreRefusedWithoutTheEngineAndAreNoSpeech() = runTest {
        val queue = queue(FakeEngine())

        queue.submit("s", WAV, Chunk(0, 16_000, false))
        queue.finish("s", 1)
        advanceUntilIdle()

        assertThat(events).containsExactly("chunk s 0 [] []", "done s [] [] no-speech").inOrder()
        assertThat(queue.engineCalls).isEqualTo(0)
    }

    // Every other chunk goes to Silero, whatever the gate heard (speechFrames 0 here).
    @Test
    fun everyLoudChunkGoesToTheSpeechCheck() = runTest {
        val engine = FakeEngine(Reply("a"), Reply("b"))
        val queue = queue(engine)

        queue.submit("s", WAV, speech(0))
        queue.submit("s", WAV, speech(16_000))
        queue.finish("s", 2)
        advanceUntilIdle()

        assertThat(engine.speechChecks).containsExactly(true, true)
    }

    // The policy's second and third branches, and the take decision: a chunk Silero refuses types nothing, even if the
    // engine sent text, and counts for nothing; a take is speech once any chunk was heard (or its check failed open,
    // which the engine reports as heard).
    @Test
    fun aChunkSileroRefusesTypesNothingAndOneHeardChunkMakesTheTakeSpeech() = runTest {
        val queue = queue(FakeEngine(Reply("invented", vadRejected = true), Reply(vadRejected = true), Reply("hello")))

        queue.submit("s1", WAV, speech(0))
        queue.finish("s1", 1)
        advanceUntilIdle()
        queue.submit("s2", WAV, speech(0))
        queue.submit("s2", WAV, speech(16_000))
        queue.finish("s2", 2)
        advanceUntilIdle()

        assertThat(events).containsAtLeast(
            "chunk s1 0 [] []", "done s1 [] [] no-speech", "chunk s2 0 [] []", "chunk s2 1 [hello] [hello]",
            "done s2 [, hello] [, hello]",
        ).inOrder()
    }

    @Test
    fun engineDiesMidChunkRetriesOnce() = runTest {
        val engine = FakeEngine(Reply(dies = true), Reply("ok"))
        val queue = queue(engine)

        queue.submit("s", WAV, speech(0))
        queue.finish("s", 1)
        advanceUntilIdle()

        assertThat(engine.calls)
            .containsExactly("load m 4", "transcribe 0-16000", "load m 4", "transcribe 0-16000")
            .inOrder()
        assertThat(events).containsExactly(
            "loading s", "loaded s", "loading s", "loaded s", "chunk s 0 [ok] [ok]", "done s [ok] [ok]",
        ).inOrder()
    }

    @Test
    fun secondDeathFails() = runTest {
        val queue = queue(FakeEngine(Reply(dies = true), Reply(dies = true)))

        queue.submit("s", WAV, speech(0))
        queue.finish("s", 1)
        advanceUntilIdle()

        assertThat(events)
            .containsExactly("loading s", "loaded s", "loading s", "loaded s", "failed s ENGINE_CRASHED")
            .inOrder()
    }

    @Test
    fun truncatedKeepsPartialText() = runTest {
        val queue = queue(FakeEngine(Reply("part", "part raw", status = 18)))

        queue.submit("s", WAV, speech(0))
        queue.finish("s", 1)
        advanceUntilIdle()

        assertThat(events)
            .containsExactly("loading s", "loaded s", "chunk s 0 [part] [part raw]", "failed s TRUNCATED")
            .inOrder()
    }

    @Test
    fun rawTextTravelsWithText() = runTest {
        val queue = queue(FakeEngine(Reply("Hello.", "hello")))

        queue.submit("s", WAV, speech(0))
        queue.finish("s", 1)
        advanceUntilIdle()

        assertThat(events)
            .containsExactly("loading s", "loaded s", "chunk s 0 [Hello.] [hello]", "done s [Hello.] [hello]")
            .inOrder()
    }

    @Test
    fun noModelFails() = runTest {
        model = null
        val engine = FakeEngine()
        val queue = queue(engine)

        queue.ensureLoaded("s")
        advanceUntilIdle()

        assertThat(events).containsExactly("failed s NO_MODEL")
        assertThat(engine.calls).isEmpty()
    }

    @Test
    fun oomLoadIsNoMemory() = runTest {
        val queue = queue(FakeEngine(loadStatus = 7))

        queue.ensureLoaded("s")
        advanceUntilIdle()

        assertThat(events).containsExactly("loading s", "failed s NO_MEMORY").inOrder()
    }

    @Test
    fun slowLoadKeepsJobs() = runTest {
        val engine = FakeEngine(Reply("a"), loadMs = 60_000)
        val queue = queue(engine)

        queue.ensureLoaded("s")
        runCurrent()
        assertThat(engine.calls).containsExactly("load m 4") // loading until 60,000 ms
        queue.submit("s", WAV, speech(0))
        advanceTimeBy(60_001)

        assertThat(queue.engineCalls).isEqualTo(1)
        assertThat(events).containsExactly("loading s", "loaded s", "chunk s 0 [a] [a]").inOrder()
    }

    @Test
    fun cancelDropsPendingAndAborts() = runTest {
        val engine = FakeEngine(Reply("a", delayMs = 1_000), Reply("b"), Reply("c"))
        val queue = queue(engine)
        queue.submit("s", WAV, speech(0))
        queue.submit("s", WAV, speech(16_000))
        queue.submit("s", WAV, speech(32_000))
        queue.finish("s", 3)
        advanceTimeBy(500) // the first chunk runs until 1,000 ms

        queue.cancel("s")
        advanceUntilIdle()

        assertThat(engine.calls).containsExactly("load m 4", "transcribe 0-16000", "abort 1").inOrder()
        assertThat(events).containsExactly("loading s", "loaded s").inOrder()
    }

    // The abort for a cancelled chunk is a oneway call that can reach :engine after that chunk has ended. It names the
    // chunk's run, so the next take's chunk, running by then, runs on and reports its text.
    @Test
    fun lateAbortLeavesTheNextChunkAlone() = runTest {
        val engine = FakeEngine(Reply("a", delayMs = 1_000), Reply("b", delayMs = 1_000), holdAborts = true)
        val queue = queue(engine)
        queue.take("s1")
        advanceTo(500) // s1's chunk runs until 1,000 ms

        queue.cancel("s1") // its abort goes out now and lands late
        queue.take("s2")
        advanceTo(1_500) // s1's chunk has ended; s2's runs until about 2,000 ms
        engine.deliverAborts()
        advanceUntilIdle()

        assertThat(events)
            .containsExactly("loading s1", "loaded s1", "chunk s2 0 [b] [b]", "done s2 [b] [b]").inOrder()
        assertThat(engine.tokens).containsExactly(1L, 2L).inOrder()
        assertThat(engine.calls)
            .containsExactly("load m 4", "transcribe 0-16000", "abort 1", "transcribe 0-16000").inOrder()
    }

    // The same abort landing in time stops s1's chunk at once, and s2's chunk starts then, not at 1,000 ms.
    @Test
    fun abortInTimeStopsOnlyItsRun() = runTest {
        val engine = FakeEngine(Reply("a", delayMs = 1_000), Reply("b", delayMs = 1_000))
        val queue = queue(engine)
        queue.take("s1")
        advanceTo(500)

        queue.cancel("s1")
        queue.take("s2")
        advanceUntilIdle()

        assertThat(testScheduler.currentTime).isLessThan(1_600L)
        assertThat(events)
            .containsExactly("loading s1", "loaded s1", "chunk s2 0 [b] [b]", "done s2 [b] [b]").inOrder()
    }

    @Test
    fun loadsOncePerModel() = runTest {
        val engine = FakeEngine(Reply("a"), Reply("b"))
        val queue = queue(engine)

        queue.submit("s1", WAV, speech(0))
        queue.finish("s1", 1)
        advanceUntilIdle()
        queue.submit("s2", WAV, speech(0))
        queue.finish("s2", 1)
        advanceUntilIdle()

        assertThat(engine.calls).containsExactly("load m 4", "transcribe 0-16000", "transcribe 0-16000").inOrder()
        assertThat(events).containsAtLeast("done s1 [a] [a]", "done s2 [b] [b]").inOrder()
    }

    @Test
    fun resubmitAfterCancelRunsFresh() = runTest {
        val queue = queue(FakeEngine(Reply("old", delayMs = 1_000), Reply("new")))
        queue.submit("s", WAV, speech(0))
        advanceTimeBy(500)

        queue.cancel("s")
        queue.submit("s", WAV, speech(0))
        queue.finish("s", 1)
        advanceUntilIdle()

        assertThat(events)
            .containsExactly("loading s", "loaded s", "chunk s 0 [new] [new]", "done s [new] [new]")
            .inOrder()
    }

    @Test
    fun modelChangeWaitsForNextSession() = runTest {
        val engine = FakeEngine(Reply("a"), Reply("b"), Reply("c"))
        val queue = queue(engine)
        model = "a"
        queue.submit("s1", WAV, speech(0))
        advanceUntilIdle()

        model = "b"
        queue.submit("s1", WAV, speech(16_000))
        queue.finish("s1", 2)
        advanceUntilIdle()
        queue.submit("s2", WAV, speech(0))
        queue.finish("s2", 1)
        advanceUntilIdle()

        assertThat(engine.calls).containsExactly(
            "load a 4", "transcribe 0-16000", "transcribe 16000-32000", "unload", "load b 4", "transcribe 0-16000",
        ).inOrder()
        assertThat(events).containsAtLeast("done s1 [a, b] [a, b]", "done s2 [c] [c]").inOrder()
    }

    // A session's language hint comes with its model, read once at its first job that needs the engine, so every chunk
    // of a take gets the same one, and its result carries it for the text's cleanup; the multilingual model's is none
    // (null).
    @Test
    fun languageHintIsReadWithTheModelAndKeptForTheSession() = runTest {
        val engine = FakeEngine(Reply("a"), Reply("b"), Reply("c"))
        val queue = queue(engine)
        queue.submit("s1", WAV, speech(0))
        advanceUntilIdle()

        model = "v3"
        language = null
        queue.submit("s1", WAV, speech(16_000))
        queue.finish("s1", 2)
        advanceUntilIdle()
        queue.take("s2")
        advanceUntilIdle()

        assertThat(engine.languages).containsExactly("en", "en", null).inOrder()
        assertThat(doneLanguages).containsExactly("en", null).inOrder()
        assertThat(engine.calls).contains("load v3 4")
    }

    // A model switch: the old model is freed before the new one loads (one model per :engine). When that load fails the
    // take gets a clear code, nothing stays marked loaded, and the next take loads the new model again.
    @Test
    fun loadFailureAfterASwitchIsReportedAndTheNextTakeRetries() = runTest {
        val engine = FakeEngine(Reply("a"), Reply("b"))
        val queue = queue(engine)
        model = "a"
        queue.take("s1")
        advanceUntilIdle()

        model = "b"
        engine.loadStatus = 7 // out of memory
        queue.take("s2")
        advanceUntilIdle()
        engine.loadStatus = 0
        queue.take("s3")
        advanceUntilIdle()

        assertThat(engine.calls).containsExactly(
            "load a 4", "transcribe 0-16000", "unload", "load b 4", "load b 4", "transcribe 0-16000",
        ).inOrder()
        assertThat(events).containsExactly(
            "loading s1", "loaded s1", "chunk s1 0 [a] [a]", "done s1 [a] [a]",
            "loading s2", "failed s2 NO_MEMORY",
            "loading s3", "loaded s3", "chunk s3 0 [b] [b]", "done s3 [b] [b]",
        ).inOrder()
    }

    // A model switch: an engine death during the first take on the new model reloads that model, not the old one.
    @Test
    fun engineDeathAfterASwitchReloadsTheNewModel() = runTest {
        val engine = FakeEngine(Reply("a"), Reply(dies = true), Reply("b"))
        val queue = queue(engine)
        model = "a"
        queue.take("s1")
        advanceUntilIdle()

        model = "b"
        queue.take("s2")
        advanceUntilIdle()

        assertThat(engine.calls).containsExactly(
            "load a 4", "transcribe 0-16000", "unload", "load b 4", "transcribe 0-16000", "load b 4", "transcribe 0-16000",
        ).inOrder()
        assertThat(events.last()).isEqualTo("done s2 [b] [b]")
    }

    // The engine may retry an empty run without PnC only for a chunk with 30 or more gate speech frames. The public
    // noise clips where the retry invents words have at most 23.
    @Test
    fun retryNeeds30GateSpeechFrames() = runTest {
        val engine = FakeEngine(Reply("a"), Reply("b"), Reply("c"))
        val queue = queue(engine)

        queue.submit("s", WAV, Chunk(0, 16_000, true, speechFrames = 0)) // sent: it has a frame above -55 dBFS
        queue.submit("s", WAV, Chunk(16_000, 32_000, true, speechFrames = 29))
        queue.submit("s", WAV, Chunk(32_000, 48_000, true, speechFrames = 30))
        queue.finish("s", 3)
        advanceUntilIdle()

        assertThat(engine.allowRetries).containsExactly(false, false, true).inOrder()
    }

    // A cancel that lands while the model file is checked (a first hash takes seconds) loads nothing. The check below
    // cancels from inside, which is where a cancel during a blocking hash lands.
    @Test
    fun cancelDuringTheModelCheckLoadsNothing() = runTest {
        val engine = FakeEngine()
        lateinit var queue: TranscriptionQueue
        queue = queue(engine) { id ->
            queue.cancel(id)
            "m"
        }

        queue.ensureLoaded("s")
        advanceUntilIdle()

        assertThat(engine.calls).isEmpty()
        assertThat(events).isEmpty()
    }

    @Test
    fun failedLoadReportsAgainAtFinish() = runTest {
        model = null
        val queue = queue(FakeEngine())

        queue.ensureLoaded("s")
        advanceUntilIdle()
        assertThat(events).containsExactly("failed s NO_MODEL")

        queue.finish("s", 1)
        advanceUntilIdle()
        assertThat(events).containsExactly("failed s NO_MODEL", "failed s NO_MODEL")
    }

    @Test
    fun unexpectedErrorFailsOnlyItsSession() = runTest {
        var reads = 0 // the first model check throws, as ModelStore does when it cannot read its sidecar
        val queue = queue(FakeEngine(Reply("ok"))) { if (reads++ == 0) throw IOException("unreadable") else "m" }

        queue.submit("s1", WAV, speech(0))
        queue.finish("s1", 1)
        queue.submit("s2", WAV, speech(0))
        queue.finish("s2", 1)
        advanceUntilIdle()

        assertThat(events)
            .containsExactly(
                "failed s1 ENGINE_CRASHED", "loading s2", "loaded s2", "chunk s2 0 [ok] [ok]", "done s2 [ok] [ok]",
            )
            .inOrder()
    }

    @Test
    fun inputTooLongFails() = runTest {
        val queue = queue(FakeEngine(Reply(status = 17)))

        queue.submit("s", WAV, speech(0))
        queue.finish("s", 1)
        advanceUntilIdle()

        assertThat(events).containsExactly("loading s", "loaded s", "failed s INPUT_TOO_LONG").inOrder()
    }

    @Test
    fun otherTranscribeStatusIsEngineCrashed() = runTest {
        val queue = queue(FakeEngine(Reply(status = 5)))

        queue.submit("s", WAV, speech(0))
        queue.finish("s", 1)
        advanceUntilIdle()

        assertThat(events).containsExactly("loading s", "loaded s", "failed s ENGINE_CRASHED").inOrder()
    }

    @Test
    fun otherLoadStatusIsLoadFailed() = runTest {
        val queue = queue(FakeEngine(loadStatus = 4))

        queue.ensureLoaded("s")
        advanceUntilIdle()

        assertThat(events).containsExactly("loading s", "failed s LOAD_FAILED").inOrder()
    }

    @Test
    fun deathDuringLoadRetriesOnce() = runTest {
        val engine = FakeEngine(Reply("ok"), loadDeaths = 1)
        val queue = queue(engine)

        queue.submit("s", WAV, speech(0))
        queue.finish("s", 1)
        advanceUntilIdle()

        assertThat(engine.calls).containsExactly("load m 4", "load m 4", "transcribe 0-16000").inOrder()
        assertThat(events)
            .containsExactly("loading s", "loading s", "loaded s", "chunk s 0 [ok] [ok]", "done s [ok] [ok]")
            .inOrder()
    }

    @Test
    fun secondDeathDuringLoadFails() = runTest {
        val engine = FakeEngine(loadDeaths = 2)
        val queue = queue(engine)

        queue.submit("s", WAV, speech(0))
        queue.finish("s", 1)
        advanceUntilIdle()

        assertThat(engine.calls).containsExactly("load m 4", "load m 4").inOrder()
        assertThat(events).containsExactly("loading s", "loading s", "failed s ENGINE_CRASHED").inOrder()
    }

    @Test
    fun idleDeathReloadsAtPress() = runTest {
        val engine = FakeEngine(Reply("a"), Reply("b"))
        val queue = queue(engine)
        queue.submit("s1", WAV, speech(0))
        queue.finish("s1", 1)
        advanceUntilIdle()

        engine.isAlive = false // the low-memory killer ended the idle :engine
        queue.ensureLoaded("s2")
        advanceUntilIdle()
        assertThat(engine.calls).containsExactly("load m 4", "transcribe 0-16000", "load m 4").inOrder()

        queue.submit("s2", WAV, speech(0))
        queue.finish("s2", 1)
        advanceUntilIdle()
        assertThat(engine.calls.drop(3)).containsExactly("transcribe 0-16000") // loaded once, at press
        assertThat(events).containsAtLeast("loading s2", "done s2 [b] [b]").inOrder()
    }

    @Test
    fun retryWithoutCancelRunsFresh() = runTest {
        // Retry and Transcribe from history reuse the id of an ended take, without a cancel.
        val queue = queue(FakeEngine(Reply(status = 5), Reply("ok")))
        queue.submit("s", WAV, speech(0))
        queue.finish("s", 1)
        advanceUntilIdle()

        queue.ensureLoaded("s")
        queue.submit("s", WAV, speech(0))
        queue.finish("s", 1)
        advanceUntilIdle()

        assertThat(events)
            .containsExactly(
                "loading s", "loaded s", "failed s ENGINE_CRASHED", "chunk s 0 [ok] [ok]", "done s [ok] [ok]",
            )
            .inOrder()
    }

    @Test
    fun finishWithNoChunksIsDone() = runTest {
        val engine = FakeEngine()
        val queue = queue(engine)

        queue.finish("s", 0)
        advanceUntilIdle()

        assertThat(events).containsExactly("done s [] [] no-speech")
        assertThat(engine.calls).isEmpty()
    }

    @Test
    fun cancelSendsNoAbortToAnotherSession() = runTest {
        val engine = FakeEngine(Reply("a"), Reply("b", delayMs = 1_000))
        val queue = queue(engine)
        queue.submit("s1", WAV, speech(0)) // s1 stays open: no finish
        queue.submit("s2", WAV, speech(0))
        queue.finish("s2", 1)
        advanceTimeBy(500) // s2's chunk runs until 1,000 ms

        queue.cancel("s1")
        advanceUntilIdle()

        assertThat(engine.calls).containsExactly("load m 4", "transcribe 0-16000", "transcribe 0-16000").inOrder()
        assertThat(events)
            .containsExactly(
                "loading s1", "loaded s1", "chunk s1 0 [a] [a]", "chunk s2 0 [b] [b]", "done s2 [b] [b]",
            )
            .inOrder()
    }

    @Test
    fun listenerThatThrowsInTheCatchDoesNotStopTheWorker() = runTest {
        // An unexpected error fails s1 from the worker's catch, and the listener throws from that onFailed as well.
        var reads = 0
        val queue = queue(FakeEngine(Reply("ok"))) { if (reads++ == 0) throw IOException("unreadable") else "m" }
        var thrown = false
        onEvent = { if (it.startsWith("failed") && !thrown) { thrown = true; throw IllegalStateException("listener") } }

        queue.submit("s1", WAV, speech(0))
        queue.finish("s1", 1)
        queue.submit("s2", WAV, speech(0))
        queue.finish("s2", 1)
        advanceUntilIdle()

        assertThat(events)
            .containsExactly(
                "failed s1 ENGINE_CRASHED", "loading s2", "loaded s2", "chunk s2 0 [ok] [ok]", "done s2 [ok] [ok]",
            )
            .inOrder()
    }

    @Test
    fun reentrantResubmitIsNotStranded() = runTest {
        // A listener that breaks its contract: it cancels and resubmits the take inside onChunkDone.
        val queue = queue(FakeEngine(Reply("old"), Reply("new")))
        onEvent = {
            if (it == "chunk s 0 [old] [old]") {
                queue.cancel("s")
                queue.submit("s", WAV, speech(0))
                queue.finish("s", 1)
            }
        }

        queue.submit("s", WAV, speech(0))
        queue.finish("s", 1)
        advanceUntilIdle()

        assertThat(events)
            .containsExactly(
                "loading s", "loaded s", "chunk s 0 [old] [old]", "chunk s 0 [new] [new]", "done s [new] [new]",
            )
            .inOrder()
    }

    @Test
    fun unloadsAfterFiveIdleMinutes() = runTest {
        val engine = FakeEngine(Reply("a"))
        val queue = idleQueue(engine)
        queue.take("s1") // done at 0 ms

        advanceTo(299_999)
        assertThat(engine.calls).doesNotContain("unload")
        advanceTo(300_000)
        assertThat(engine.calls).containsExactly("load m 4", "transcribe 0-16000", "unload").inOrder()
    }

    @Test
    fun pressBeforeTheTimeoutCancelsIt() = runTest {
        val engine = FakeEngine(Reply("a"), Reply("b"))
        val queue = idleQueue(engine)
        queue.take("s1")
        advanceTo(200_000)

        queue.ensureLoaded("s2") // a take that records for 10 minutes keeps the model all along
        advanceTo(800_000)
        queue.take("s2")
        advanceTo(1_099_999)
        assertThat(engine.calls).doesNotContain("unload")
        advanceTo(1_100_000)
        assertThat(engine.calls)
            .containsExactly("load m 4", "transcribe 0-16000", "transcribe 0-16000", "unload")
            .inOrder()
    }

    @Test
    fun submitBeforeTheTimeoutCancelsIt() = runTest {
        val engine = FakeEngine(Reply("a"), Reply("b"))
        val queue = idleQueue(engine)
        queue.take("s1")
        advanceTo(250_000)

        queue.take("h") // a Transcribe from history
        advanceTo(549_999)
        assertThat(engine.calls).doesNotContain("unload")
        advanceTo(550_000)
        assertThat(engine.calls.last()).isEqualTo("unload")
    }

    @Test
    fun jobInFlightAtTheDeadlineDefersTheUnload() = runTest {
        val engine = FakeEngine(Reply("a"), Reply("b", delayMs = 10_000))
        val queue = idleQueue(engine)
        queue.take("s1")
        advanceTo(295_000)

        queue.take("h") // its chunk runs from 295,000 to 305,000 ms
        advanceTo(300_000)
        assertThat(engine.calls).doesNotContain("unload")
        advanceTo(604_999)
        assertThat(events.last()).isEqualTo("done h [b] [b]")
        assertThat(engine.calls).doesNotContain("unload")
        advanceTo(605_000)
        assertThat(engine.calls.last()).isEqualTo("unload")
    }

    @Test
    fun ensureLoadedAfterAnIdleUnloadLoadsAgain() = runTest {
        val engine = FakeEngine(Reply("a"), Reply("b"))
        val queue = idleQueue(engine)
        queue.take("s1")
        advanceTo(300_000)

        queue.ensureLoaded("s2")
        runCurrent()
        assertThat(engine.calls.drop(2)).containsExactly("unload", "load m 4").inOrder()
        queue.take("s2")
        runCurrent()
        assertThat(engine.calls.last()).isEqualTo("transcribe 0-16000")
        assertThat(events).containsAtLeast("loading s2", "done s2 [b] [b]").inOrder()
    }

    @Test
    fun staleIdleUnloadLeavesANewerSessionsModel() = runTest {
        val engine = FakeEngine(Reply("a"), Reply("b"))
        val queue = idleQueue(engine)
        model = "a"
        queue.take("s1") // due to unload at 300,000 ms
        advanceTo(100_000)

        model = "b"
        queue.take("s2") // loads b and ends at 100,000 ms, so b stays until 400,000 ms
        advanceTo(399_999)
        assertThat(engine.calls)
            .containsExactly("load a 4", "transcribe 0-16000", "unload", "load b 4", "transcribe 0-16000")
            .inOrder()
        advanceTo(400_000)
        assertThat(engine.calls.drop(5)).containsExactly("unload")
    }

    @Test
    fun discardedTakeUnloadsAfterTheTimeout() = runTest {
        val engine = FakeEngine()
        val queue = idleQueue(engine)
        queue.ensureLoaded("s1") // the press loads the model
        advanceTo(1_000)

        queue.cancel("s1") // a drag discards the take
        advanceTo(300_999)
        assertThat(engine.calls).containsExactly("load m 4")
        advanceTo(301_000)
        assertThat(engine.calls).containsExactly("load m 4", "unload").inOrder()
    }

    @Test
    fun loadsAndIdleUnloadsAreLogged() = runTest {
        val engine = FakeEngine(Reply(dies = true), Reply("a"), loadMs = 1_500)
        val queue = idleQueue(engine)
        queue.take("s1") // loaded at 1,500 ms; the chunk kills the engine; loaded again and done at 3,000 ms

        advanceTo(303_000)
        queue.ensureLoaded("s2")
        advanceTo(304_500)

        assertThat(logs).containsExactly(
            "engine_load ms=1500 reason=press",
            "engine_load ms=1500 reason=retry",
            "engine_unload idle_ms=300000",
            "engine_load ms=1500 reason=idle-reload",
        ).inOrder()
    }

    @Test
    fun deepSleepCountsTowardTheIdleTime() = runTest {
        val engine = FakeEngine(Reply("a"))
        val queue = idleQueue(engine)
        queue.take("s1")
        advanceTo(10_000)

        sleptMs = 3_600_000 // locked in a pocket for an hour
        advanceTo(19_999)
        assertThat(engine.calls).doesNotContain("unload")
        advanceTo(20_000) // the next 10 s check finds the deadline passed
        assertThat(engine.calls.last()).isEqualTo("unload")
        assertThat(logs.last()).isEqualTo("engine_unload idle_ms=3620000")
    }

    @Test
    fun neverStartsNoTimer() = runTest {
        val job = SupervisorJob()
        val scope = CoroutineScope(job + StandardTestDispatcher(testScheduler))
        val queue = TranscriptionQueue(FakeEngine(Reply("a")), scope, { model }, { 4 }, listener,
            unloadAfterIdleMs = Long.MAX_VALUE, clock = { testScheduler.currentTime }, log = { logs += it })

        queue.take("s1")
        runCurrent()

        assertThat(events.last()).isEqualTo("done s1 [a] [a]")
        assertThat(job.children.count()).isEqualTo(1) // the worker
    }

    @Test
    fun idleDeathBeforeTheTimeoutIsNoIdleUnload() = runTest {
        val engine = FakeEngine(Reply("a"))
        val queue = idleQueue(engine)
        queue.take("s1")
        advanceTo(100_000)

        engine.isAlive = false // the low-memory killer ended the idle :engine
        advanceTo(300_000)
        queue.ensureLoaded("s2")
        runCurrent()

        assertThat(engine.calls).containsExactly("load m 4", "transcribe 0-16000", "load m 4").inOrder()
        assertThat(logs).containsExactly("engine_load ms=0 reason=press", "engine_load ms=0 reason=press").inOrder()
    }

    @Test
    fun failedLoadIsLoggedAndStillUnloadsTheEngine() = runTest {
        val engine = FakeEngine(loadStatus = 7, loadMs = 2_000)
        val queue = idleQueue(engine)
        queue.ensureLoaded("s1") // out of memory: :engine stays up without a model
        queue.finish("s1", 0)
        advanceTo(302_000)

        assertThat(engine.calls).containsExactly("load m 4", "unload").inOrder()
        assertThat(logs)
            .containsExactly("engine_load_failed status=7 ms=2000", "engine_unload idle_ms=300000")
            .inOrder()
    }

    @Test
    fun unexpectedErrorAfterALoadStillUnloadsTheEngine() = runTest {
        val engine = FakeEngine(Reply("a"))
        val queue = idleQueue(engine)
        onEvent = { if (it.startsWith("chunk")) throw IllegalStateException("listener") }

        queue.take("s1") // the worker's catch forgets the model, but :engine still holds it
        advanceTo(300_000)

        assertThat(events.last()).isEqualTo("failed s1 ENGINE_CRASHED")
        assertThat(engine.calls).containsExactly("load m 4", "transcribe 0-16000", "unload").inOrder()
    }

    @Test
    fun deadEngineLeavesNothingToUnload() = runTest {
        val engine = FakeEngine(Reply(dies = true), Reply(dies = true))
        val queue = idleQueue(engine)

        queue.take("s1")
        advanceTo(300_000)

        assertThat(events.last()).isEqualTo("failed s1 ENGINE_CRASHED")
        assertThat(engine.calls).doesNotContain("unload")
    }

    private companion object {
        const val WAV = "recordings/s.wav"

        fun speech(from: Long) = Chunk(from, from + 16_000, true)
    }
}
