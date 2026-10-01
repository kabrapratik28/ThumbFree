package io.github.kabrapratik28.thumbfree.core.session

import com.google.common.truth.Truth.assertThat
import io.github.kabrapratik28.thumbfree.core.session.Preview.Effect.EndStream
import io.github.kabrapratik28.thumbfree.core.session.Preview.Effect.Show
import io.github.kabrapratik28.thumbfree.core.session.Preview.Event
import io.github.kabrapratik28.thumbfree.core.session.Preview.Phase
import org.junit.Test

class PreviewTest {
    private var state = Preview.State()

    private fun send(event: Event): List<Preview.Effect> = Preview.reduce(state, event).let { (next, effects) ->
        state = next
        effects
    }

    @Test
    fun startShowsNothingUntilThereAreWords() {
        assertThat(send(Event.Start("a"))).containsExactly(Show(PreviewUi.Hidden))
        assertThat(state).isEqualTo(Preview.State("a", Phase.LIVE))
        assertThat(send(Event.Text("a", "", " "))).containsExactly(Show(PreviewUi.Hidden)) // blank: still nothing
    }

    @Test
    fun updatesShowTheSettledWordsAndTheTail() {
        send(Event.Start("a"))

        assertThat(send(Event.Text("a", "And so", ", my"))).containsExactly(Show(PreviewUi.Live("And so", ", my")))
        assertThat(send(Event.Text("a", "And so, my fellow", " Ameri"))).containsExactly(Show(PreviewUi.Live("And so, my fellow", " Ameri")))
        assertThat(state.committed).isEqualTo("And so, my fellow")
    }

    @Test
    fun stopEndsTheStreamAndKeepsTheWordsUntilTheTextIsTyped() {
        send(Event.Start("a"))
        send(Event.Text("a", "And so, my fellow", " Americans"))

        assertThat(send(Event.Stop("a"))).containsExactly(EndStream("a"), Show(PreviewUi.Finishing("And so, my fellow Americans"))).inOrder()
        assertThat(state.phase).isEqualTo(Phase.FINISHING)
        assertThat(send(Event.Text("a", "late", ""))).isEmpty() // a feed that was still running
        assertThat(send(Event.Done("a"))).containsExactly(Show(PreviewUi.Hidden)) // the stream already ended
        assertThat(state).isEqualTo(Preview.State())
    }

    @Test
    fun stopWithNoWordsHidesThePanel() {
        send(Event.Start("a"))

        assertThat(send(Event.Stop("a"))).containsExactly(EndStream("a"), Show(PreviewUi.Hidden)).inOrder()
    }

    @Test
    fun cancelEndsTheStreamAndHides() {
        send(Event.Start("a"))
        send(Event.Text("a", "And so", ""))

        assertThat(send(Event.Cancel("a"))).containsExactly(EndStream("a"), Show(PreviewUi.Hidden)).inOrder()
        assertThat(state).isEqualTo(Preview.State())
    }

    @Test
    fun aFailureHidesThePreviewForTheRestOfTheTake() {
        send(Event.Start("a"))
        send(Event.Text("a", "And so", ""))

        assertThat(send(Event.Failed("a"))).containsExactly(EndStream("a"), Show(PreviewUi.Hidden)).inOrder()
        assertThat(state.phase).isEqualTo(Phase.GAVE_UP)
        assertThat(send(Event.Text("a", "And so, my", ""))).isEmpty()
        assertThat(send(Event.Stop("a"))).isEmpty() // nothing comes back at the stop
        assertThat(send(Event.Done("a"))).containsExactly(Show(PreviewUi.Hidden))
    }

    @Test
    fun aStreamThatFallsBehindGivesUpTheSameWay() {
        send(Event.Start("a"))

        assertThat(send(Event.Behind("a"))).containsExactly(EndStream("a"), Show(PreviewUi.Hidden)).inOrder()
        assertThat(state.phase).isEqualTo(Phase.GAVE_UP)
        assertThat(send(Event.Behind("a"))).isEmpty()
    }

    @Test
    fun anotherTakesEventsChangeNothing() {
        send(Event.Start("b"))
        send(Event.Text("b", "hello", ""))

        for (late in listOf(Event.Text("a", "old", ""), Event.Stop("a"), Event.Cancel("a"), Event.Done("a"), Event.Failed("a"))) {
            assertThat(send(late)).isEmpty()
        }
        assertThat(state).isEqualTo(Preview.State("b", Phase.LIVE, "hello", ""))
    }

    @Test
    fun aNewTakeClosesAStreamLeftOpen() {
        send(Event.Start("a"))

        assertThat(send(Event.Start("b"))).containsExactly(EndStream("a"), Show(PreviewUi.Hidden)).inOrder()
        assertThat(state.take).isEqualTo("b")
    }

    @Test
    fun theWindowIsTheSpikesPick() {
        // 70-13-4: 1,040 ms chunks with 320 ms of lookahead; the hangover (PreviewGate) must cover chunk + lookahead.
        assertThat(listOf(Preview.LEFT_MS, Preview.CHUNK_MS, Preview.RIGHT_MS)).containsExactly(5_600, 1_040, 320).inOrder()
        val gate = io.github.kabrapratik28.thumbfree.core.audio.PreviewGate
        assertThat(gate.HANGOVER * gate.WINDOW / 16).isAtLeast(Preview.CHUNK_MS + Preview.RIGHT_MS)
    }
}
