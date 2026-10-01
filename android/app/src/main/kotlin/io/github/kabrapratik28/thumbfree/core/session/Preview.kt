package io.github.kabrapratik28.thumbfree.core.session

/** Live preview: what the panel by the bubble shows while a take records and until its text is typed. */
sealed interface PreviewUi {
    data object Hidden : PreviewUi

    /** While recording: [committed] never changes once shown; [tentative] follows it and may change at every chunk. */
    data class Live(val committed: String, val tentative: String) : PreviewUi

    /** From the stop until today's path has typed the text: the words the preview had, while that path transcribes. */
    data class Finishing(val text: String) : PreviewUi
}

/**
 * The live preview: while a take records, :engine's buffered Parakeet stream turns its speech into words for a panel
 * by the bubble; at the stop the stream ends and today's offline path types its own text, once, exactly as before. The
 * stream never types anything, and a failed or late stream only hides the panel. [reduce] is pure: PreviewFeed and
 * AndroidPorts send the events and carry out the effects.
 */
object Preview {
    /** The stream's (left, chunk, right) window in ms: 70, 13 and 4 frames of 80 ms (Pixel real-time factor 0.27). */
    const val LEFT_MS = 5_600
    const val CHUNK_MS = 1_040
    const val RIGHT_MS = 320

    /** Audio the stream has not taken yet past which it is behind for good: the preview goes for the rest of the take. */
    const val BEHIND_MS = 4_000

    enum class Phase { OFF, LIVE, FINISHING, GAVE_UP }

    data class State(
        val take: String? = null,
        val phase: Phase = Phase.OFF,
        val committed: String = "",
        val tentative: String = "",
    )

    sealed interface Event {
        val take: String

        /** The take started recording with the preview on. */
        data class Start(override val take: String) : Event

        /** The stream's text after a feed. */
        data class Text(override val take: String, val committed: String, val tentative: String) : Event

        /** The stream fell behind (BEHIND_MS unfed, or its buffer filled): no preview for the rest of the take. */
        data class Behind(override val take: String) : Event

        /** The stream failed (no engine, an error, a dead :engine): no preview for the rest of the take. */
        data class Failed(override val take: String) : Event

        /** The stop: no more audio; the panel shows the words so far until the take's text is typed. */
        data class Stop(override val take: String) : Event

        /** The take was cancelled or discarded: nothing more to show. */
        data class Cancel(override val take: String) : Event

        /** The take ended (its text typed, a chip, a failure): the panel goes. */
        data class Done(override val take: String) : Event
    }

    sealed interface Effect {
        /** Close the take's stream in :engine (it may already be closed). */
        data class EndStream(val take: String) : Effect

        data class Show(val ui: PreviewUi) : Effect
    }

    fun reduce(state: State, event: Event): Pair<State, List<Effect>> {
        if (event is Event.Start) {
            // A take left open (it never ended through here) closes first.
            val close = state.take?.takeIf { state.phase == Phase.LIVE }?.let { listOf(Effect.EndStream(it)) }.orEmpty()
            return State(event.take, Phase.LIVE) to close + Effect.Show(PreviewUi.Hidden)
        }
        if (event.take != state.take) return state to emptyList() // a late event from an earlier take
        val live = state.phase == Phase.LIVE
        return when (event) {
            is Event.Start -> error("handled above")
            is Event.Text -> if (!live) state to emptyList() else {
                val next = state.copy(committed = event.committed, tentative = event.tentative)
                next to listOf(Effect.Show(liveUi(next)))
            }
            is Event.Behind, is Event.Failed -> if (!live) state to emptyList() else
                state.copy(phase = Phase.GAVE_UP) to listOf(Effect.EndStream(event.take), Effect.Show(PreviewUi.Hidden))
            is Event.Stop -> if (!live) state to emptyList() else {
                val text = state.committed + state.tentative
                val ui = if (text.isBlank()) PreviewUi.Hidden else PreviewUi.Finishing(text.trim())
                state.copy(phase = Phase.FINISHING) to listOf(Effect.EndStream(event.take), Effect.Show(ui))
            }
            is Event.Cancel, is Event.Done -> {
                val close = if (live) listOf(Effect.EndStream(event.take)) else emptyList()
                State() to close + Effect.Show(PreviewUi.Hidden)
            }
        }
    }

    private fun liveUi(state: State): PreviewUi =
        if ((state.committed + state.tentative).isBlank()) PreviewUi.Hidden else PreviewUi.Live(state.committed, state.tentative)
}
