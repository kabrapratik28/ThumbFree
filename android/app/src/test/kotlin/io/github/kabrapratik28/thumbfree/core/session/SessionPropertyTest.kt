package io.github.kabrapratik28.thumbfree.core.session

import com.google.common.truth.Truth.assertThat
import io.github.kabrapratik28.thumbfree.core.session.Effect.*
import io.github.kabrapratik28.thumbfree.core.session.Event.*
import io.github.kabrapratik28.thumbfree.core.session.State.*
import kotlin.random.Random
import org.junit.Test

// The state machine's invariants over 10,000 seeded random event sequences.
class SessionPropertyTest {
    @Test
    fun randomSequencesKeepInvariants() {
        val visited = mutableSetOf<Class<*>>()
        for (seed in 0 until 10_000) {
            val random = Random(seed)
            val events = mutableListOf<Event>()
            fun check(ok: Boolean, what: () -> String) {
                if (!ok) throw AssertionError("seed $seed: ${what()}\nevents: $events")
            }

            var machine = Machine()
            var fresh = 0
            val capturing = mutableSetOf<String>() // ids whose capture is open
            var foreground: String? = null
            val cancelled = mutableSetOf<String>()
            val created = mutableSetOf<String>()
            val ended = mutableMapOf<String, Int>() // DeleteSession and SaveOutcome count per id
            val recorded = mutableSetOf<String>() // ids that reached Recording
            val engineEnded = mutableSetOf<String>() // ids whose queue session was finished, aborted or deleted
            val serviceGone = mutableSetOf<String>() // ids that were live when the accessibility service went

            repeat(60) {
                val before = machine.state
                val live = Session.sessionId(before)
                val (event, stale) = randomEvent(random, live ?: "old") { "s${++fresh}" }
                events += event
                val (next, effects) = Session.reduce(machine, event)
                check(!stale || next == machine && effects.isEmpty()) { "8. $event for another take changed $machine" }
                machine = next
                val state = next.state
                visited += state.javaClass

                // The tail's capture closes when the machine leaves Stopping.
                if (before is Stopping && state !is Stopping) capturing -= before.id
                if (event == ServiceGone && live != null) serviceGone += live
                for (effect in effects) when (effect) {
                    is StartCapture -> {
                        check(capturing.isEmpty()) { "1. $effect while $capturing captures" }
                        // A press while busy must never start a second take.
                        check(listOf(before, state).none { it is Transcribing || it is Inserting }) {
                            "7. $effect in $before -> $state"
                        }
                        capturing += effect.id
                    }
                    is StopCapture -> capturing -= effect.id
                    is StartForeground -> {
                        check(foreground == null) { "2. $effect before StopForeground($foreground)" }
                        foreground = effect.id
                    }
                    is StopForeground -> {
                        check(foreground == effect.id) { "2. $effect while the foreground is $foreground" }
                        foreground = null
                    }
                    is Insert -> {
                        check(effect.id !in cancelled) { "3. $effect after a Cancel ended it" }
                        // Without the service no field can be checked, and no chip can offer the text instead.
                        check(!effect.autoInsert || effect.id !in serviceGone) { "10. $effect after the service went" }
                    }
                    is CreateRow -> created += effect.id
                    is FinishTranscription -> engineEnded += effect.id
                    is AbortEngine -> engineEnded += effect.id
                    is DeleteSession -> {
                        val shortSilentTake =
                            event is TailDone && event.id == effect.id && !event.hasSpeech && event.samples < 16_000 ||
                                event is TranscriptReady && event.id == effect.id && !event.speech &&
                                (before as? Transcribing)?.samples?.let { it < 16_000 } == true
                        val earlyHoldDrag = event == Drag && before == Recording(effect.id, Mode.HOLD)
                        check(effect.id !in recorded || shortSilentTake || earlyHoldDrag) {
                            "5. $effect after it reached Recording"
                        }
                        ended.merge(effect.id, 1, Int::plus)
                        engineEnded += effect.id // deleteSession cancels the queue session
                    }
                    is SaveOutcome -> {
                        // Check 3 keys on the saved CANCELLED, so it still fires if a buggy machine stays in the take.
                        if (event == Cancel && effect.outcome == Outcome.CANCELLED) cancelled += effect.id
                        ended.merge(effect.id, 1, Int::plus)
                    }
                    else -> {}
                }
                if (state is Recording) recorded += state.id
                if (state == Idle) check(ended == created.associateWith { 1 }) { "4. rows $created ended $ended" }
                // EnsureEngineLoaded opens a queue session at every press; an ended take must not leave it open.
                if (state == Idle) check(engineEnded.containsAll(created)) { "9. queue sessions of ${created - engineEnded} never end" }
                val id = when (state) {
                    Idle -> null
                    is Arming -> state.id
                    is Recording -> state.id
                    is Stopping -> state.id
                    is Transcribing -> state.id
                    is Inserting -> state.id
                }
                check(Session.sessionId(state) == id) { "6. sessionId($state) is ${Session.sessionId(state)}" }
            }
        }
        // Every state is reached, so the invariants are not checked on a machine that never leaves Idle.
        assertThat(visited).hasSize(6)
    }

    /** A random event, and whether it carries the stale take id "old". */
    private fun randomEvent(random: Random, live: String, freshId: () -> String): Pair<Event, Boolean> {
        val id = if (random.nextInt(4) > 0) live else "old" // mostly the live take, so more runs get past Arming
        val kind = random.nextInt(16)
        val event = when (kind) {
            0 -> Press(freshId())
            1 -> Release(random.nextLong(0, 1_001))
            2 -> Drag
            3 -> TouchCancelled
            4 -> Cancel
            5 -> ServiceGone
            6 -> FirstBuffer(id)
            7 -> ArmingTimeout(id)
            8 -> CaptureFailed(id, Code.MIC_UNAVAILABLE)
            9 -> StopRequested(id, Code.TAKE_LIMIT)
            10 -> TailDone(id, random.nextBoolean(), random.nextLong(0, 100_001))
            11 -> ModelLoading(id)
            12 -> ChunkDone(id, 1, 2)
            13 -> random.nextBoolean().let { speech -> // a take Silero heard nothing in has no text
                TranscriptReady(id, if (speech) listOf("", "hi").random(random) else "", random.nextBoolean(), speech)
            }
            14 -> TranscriptFailed(id, Code.ENGINE_CRASHED)
            else -> InsertDone(id, Outcome.entries.random(random), null)
        }
        return event to (kind >= 6 && id == "old") // kinds from 6 on carry a take id
    }
}
