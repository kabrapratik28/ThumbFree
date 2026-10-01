package io.github.kabrapratik28.thumbfree.engine

import com.google.common.truth.Truth.assertThat
import io.github.kabrapratik28.thumbfree.audio.PcmRing
import io.github.kabrapratik28.thumbfree.core.audio.PreviewGate
import io.github.kabrapratik28.thumbfree.core.session.Preview
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PreviewFeedTest {
    private val events = mutableListOf<Preview.Event>()

    /** A preview stream from a script: begin answers from [begins] (then 0), each feed from [reply]. */
    private class StreamEngine(vararg begins: Int, var reply: suspend (samples: Int) -> StreamUpdate? = { ok("") }) : Engine {
        private val beginReplies = ArrayDeque(begins.toList())
        val beginTokens = mutableListOf<Long>()
        val models = mutableListOf<String>()
        val fed = mutableListOf<Short>() // every sample given, in order
        val ended = mutableListOf<Long>()
        var endGate: CompletableDeferred<Unit>? = null // an end that returns only once completed
        var beginGate: CompletableDeferred<Unit>? = null // a begin in flight: a Binder call a cancel cannot stop
        override val isAlive = true

        override suspend fun load(modelPath: String, threads: Int) = 0
        override suspend fun transcribe(
            wavPath: String, fromSample: Long, toSample: Long, language: String?, allowRetry: Boolean,
            speechCheck: Boolean, token: Long,
        ) = error("not used")
        override fun abort(token: Long) = Unit
        override suspend fun unload() = Unit

        override suspend fun streamBegin(token: Long, modelFile: String): Int {
            beginTokens += token
            models += modelFile
            beginGate?.let { gate -> withContext(NonCancellable) { gate.await() } }
            return beginReplies.removeFirstOrNull() ?: 0
        }

        override suspend fun streamFeed(token: Long, pcm: ShortArray, n: Int): StreamUpdate? {
            assertThat(n % PreviewGate.WINDOW).isEqualTo(0) // whole Silero windows only
            val update = reply(n)
            if (update != null) for (i in 0 until n) fed += pcm[i] // :engine keeps whatever it gets, BUSY or not
            return update
        }

        override suspend fun streamEnd(token: Long) {
            ended += token
            endGate?.await()
        }
    }

    private fun TestScope.feed(engine: Engine) = PreviewFeed(engine, CoroutineScope(StandardTestDispatcher(testScheduler))) { events += it }

    /** Writes [ms] of audio into [ring] in 20 ms reads at real time, as the Recorder's writer thread does; sample k holds k. */
    private fun TestScope.speak(ring: PcmRing, ms: Int) = launch {
        repeat(ms / 20) { read ->
            ring.write(ShortArray(320) { (read * 320 + it).toShort() }, 320)
            delay(20)
        }
    }

    @Test
    fun opensTheStreamOnceTheTakesModelIsLoadedThenSendsAllTheAudioOnce() = runTest {
        // No engine up yet, then another model, then an offline call waiting, then open.
        val engine = StreamEngine(StreamUpdate.NO_ENGINE, 1, StreamUpdate.BUSY, reply = { n -> ok("words $n") })
        val ring = PcmRing(PreviewFeed.RING_SAMPLES)
        val feed = feed(engine)

        feed.start("a", "unified.gguf", ring)
        speak(ring, 2_000)
        advanceTimeBy(3_000) // the feed polls for audio until it is stopped: advance, never "until idle"

        assertThat(engine.beginTokens).hasSize(4)
        assertThat(engine.models.toSet()).containsExactly("unified.gguf")
        // Every sample from the start, once, in order, but the last part short of a whole window.
        assertThat(engine.fed.size).isAtLeast(32_000 - PreviewFeed.MIN_BATCH)
        assertThat(engine.fed).isEqualTo(List(engine.fed.size) { it.toShort() })
        assertThat(events.first()).isInstanceOf(Preview.Event.Text::class.java)
        assertThat(events.none { it is Preview.Event.Failed || it is Preview.Event.Behind }).isTrue()
        feed.stop()
    }

    @Test
    fun whileTheModelLoadsOnlyTheNewestAudioWaits() = runTest {
        val engine = StreamEngine(*IntArray(60) { 1 }) // 6 s of "not this take's model yet"
        val ring = PcmRing(PreviewFeed.RING_SAMPLES)
        val feed = feed(engine)

        feed.start("a", "unified.gguf", ring)
        speak(ring, 5_000)
        advanceTimeBy(8_000)

        // The stream opened after 6 s and got the last 3 s of the 5 s said while it waited: from sample 32,000 on.
        assertThat(engine.fed.first()).isEqualTo(32_000.toShort())
        assertThat(engine.fed).isEqualTo(List(engine.fed.size) { (32_000 + it).toShort() })
        feed.stop()
    }

    @Test
    fun aBusyEngineKeepsTheAudioSoNothingIsSentAgain() = runTest {
        var calls = 0
        val engine = StreamEngine(reply = { if (++calls <= 3) StreamUpdate.of(StreamUpdate.BUSY) else ok("") })
        val ring = PcmRing(PreviewFeed.RING_SAMPLES)
        val feed = feed(engine)

        feed.start("a", "unified.gguf", ring)
        speak(ring, 2_000)
        advanceTimeBy(3_000)

        assertThat(engine.fed).isEqualTo(List(engine.fed.size) { it.toShort() }) // no sample twice, none missing
        assertThat(events.none { it is Preview.Event.Failed || it is Preview.Event.Behind }).isTrue()
        feed.stop()
    }

    @Test
    fun textIsReportedOnlyWhenItChanges() = runTest {
        var calls = 0
        val engine = StreamEngine(reply = { ok(if (++calls < 3) "" else "And so", if (calls == 4) " my" else "") })
        val ring = PcmRing(PreviewFeed.RING_SAMPLES)
        val feed = feed(engine)

        feed.start("a", "unified.gguf", ring)
        speak(ring, 2_000)
        advanceTimeBy(3_000)

        assertThat(events.take(3)).containsExactly(
            Preview.Event.Text("a", "And so", ""), Preview.Event.Text("a", "And so", " my"), Preview.Event.Text("a", "And so", ""),
        ).inOrder()
        assertThat(events).hasSize(3)
        feed.stop()
    }

    @Test
    fun anEngineThatFailsOrDiesFailsOnlyThePreview() = runTest {
        for (engine in listOf(
            StreamEngine(2), // begin refused: a model that cannot stream (NOT_IMPLEMENTED)
            StreamEngine(StreamUpdate.NO_VAD), // Silero could not start
            StreamEngine(reply = { null }), // no engine any more
            StreamEngine(reply = { StreamUpdate.of(13) }), // aborted
            StreamEngine(reply = { StreamUpdate.of(StreamUpdate.STALE) }), // the stream went (an unload)
            StreamEngine(reply = { StreamUpdate.of(StreamUpdate.NO_VAD) }), // Silero failed mid-take
            StreamEngine(reply = { error("unexpected") }),
        )) {
            events.clear()
            val ring = PcmRing(PreviewFeed.RING_SAMPLES)
            val feed = feed(engine)
            feed.start("a", "unified.gguf", ring)
            speak(ring, 500)
            advanceUntilIdle()
            assertThat(events).containsExactly(Preview.Event.Failed("a"))
        }
    }

    @Test
    fun aStreamThatCannotKeepUpGivesUp() = runTest {
        // :engine says its gated audio passed Preview.BEHIND_MS.
        val engine = StreamEngine(reply = { StreamUpdate.of(StreamUpdate.BEHIND) })
        val ring = PcmRing(PreviewFeed.RING_SAMPLES)
        val feed = feed(engine)

        feed.start("a", "unified.gguf", ring)
        speak(ring, 500)
        advanceUntilIdle()

        assertThat(events).containsExactly(Preview.Event.Behind("a"))
    }

    @Test
    fun audioPilingUpHereGivesUpToo() = runTest {
        // Each feed takes 2 s: the audio not sent yet passes 4 s.
        val engine = StreamEngine(reply = { delay(2_000); ok("") })
        val ring = PcmRing(PreviewFeed.RING_SAMPLES)
        val feed = feed(engine)

        feed.start("a", "unified.gguf", ring)
        speak(ring, 10_000)
        advanceUntilIdle()

        assertThat(events).contains(Preview.Event.Behind("a"))
    }

    @Test
    fun aFullRingGivesUp() = runTest {
        val engine = StreamEngine()
        val ring = PcmRing(1_000)
        ring.write(ShortArray(1_200), 1_200) // overflows: the writer thread could not keep the preview's audio

        val feed = feed(engine)
        feed.start("a", "unified.gguf", ring)
        advanceUntilIdle()

        assertThat(events).containsExactly(Preview.Event.Behind("a"))
    }

    @Test
    fun stopEndsTheStreamAndAwaitEndedWaitsForEngineToFreeIt() = runTest {
        val engine = StreamEngine(reply = { ok("words") })
        val freed = CompletableDeferred<Unit>()
        engine.endGate = freed
        val ring = PcmRing(PreviewFeed.RING_SAMPLES)
        val feed = feed(engine)

        feed.start("a", "unified.gguf", ring)
        val speaking = speak(ring, 5_000)
        advanceTimeBy(1_000)
        runCurrent()
        feed.stop()
        val count = events.size
        var ended = false
        launch { feed.awaitEnded(); ended = true }
        advanceTimeBy(500)
        assertThat(ended).isFalse() // :engine has not freed it yet: the final transcribe would wait
        freed.complete(Unit)
        advanceTimeBy(100)
        assertThat(ended).isTrue()
        speaking.cancel()

        // The end, and after the feed's call in flight has returned, the idempotent second end.
        assertThat(engine.ended).containsExactly(engine.beginTokens.single(), engine.beginTokens.single())
        assertThat(events).hasSize(count) // nothing after the stop
        feed.stop() // again: nothing
        assertThat(engine.ended).hasSize(2)
    }

    @Test
    fun theEndWaitsForABeginInFlightThenEndsAgain() = runTest {
        // The stop comes while the take's begin is still in :engine. The barrier must not let the final transcribe go
        // until that call has returned and a second end has freed whatever it made.
        val engine = StreamEngine()
        val released = CompletableDeferred<Unit>()
        engine.beginGate = released
        val feed = feed(engine)
        feed.start("a", "unified.gguf", PcmRing(PreviewFeed.RING_SAMPLES))
        runCurrent() // the begin is in flight
        feed.stop()
        var ended = false
        launch { feed.awaitEnded(); ended = true }
        advanceTimeBy(1_000)

        assertThat(engine.ended).hasSize(1) // the first end went at once
        assertThat(ended).isFalse() // the barrier holds while the begin is in flight
        released.complete(Unit)
        advanceTimeBy(1_000)

        assertThat(ended).isTrue()
        assertThat(engine.ended).containsExactly(engine.beginTokens.single(), engine.beginTokens.single())
        assertThat(events).isEmpty() // nothing reported after the stop
    }

    @Test
    fun withNoPreviewAwaitEndedReturnsAtOnce() = runTest {
        val feed = feed(StreamEngine())
        var ended = false
        launch { feed.awaitEnded(); ended = true }
        runCurrent()
        assertThat(ended).isTrue()
    }

    @Test
    fun eachTakeGetsAStreamOfItsOwn() = runTest {
        val engine = StreamEngine()
        val feed = feed(engine)

        feed.start("a", "unified.gguf", PcmRing(PreviewFeed.RING_SAMPLES))
        advanceTimeBy(100)
        feed.start("b", "unified.gguf", PcmRing(PreviewFeed.RING_SAMPLES)) // ends a first
        advanceTimeBy(100)
        feed.stop()
        advanceTimeBy(100)

        assertThat(engine.beginTokens).hasSize(2)
        assertThat(engine.beginTokens.toSet()).hasSize(2)
        val (a, b) = engine.beginTokens
        assertThat(engine.ended).containsExactly(a, a, b, b).inOrder() // each stop ends its stream twice
    }

    private companion object {
        fun ok(committed: String, tentative: String = "") = StreamUpdate(0, committed, tentative, 0, 0, 1f)
    }
}
