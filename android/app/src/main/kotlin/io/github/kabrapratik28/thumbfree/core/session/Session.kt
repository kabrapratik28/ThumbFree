package io.github.kabrapratik28.thumbfree.core.session

import io.github.kabrapratik28.thumbfree.core.session.Effect.*
import io.github.kabrapratik28.thumbfree.core.session.Event.*
import io.github.kabrapratik28.thumbfree.core.session.State.*

enum class Mode { HOLD, LOCKED }

sealed interface State {
    data object Idle : State
    data class Arming(val id: String, val lockOnReady: Boolean = false) : State
    data class Recording(val id: String, val mode: Mode) : State
    data class Stopping(val id: String, val autoInsert: Boolean = true) : State
    /** [samples]: the take's length, for the end of a take Silero heard no speech in. */
    data class Transcribing(val id: String, val autoInsert: Boolean = true, val loadingModel: Boolean = false,
                            val done: Int = 0, val total: Int? = null, val samples: Long = 0) : State
    data class Inserting(val id: String) : State
}

sealed interface Event {
    data class Press(val newId: String) : Event
    data class Release(val heldMs: Long) : Event
    data object Drag : Event
    data object TouchCancelled : Event
    data object Cancel : Event
    data object ServiceGone : Event
    data class FirstBuffer(val id: String) : Event
    data class ArmingTimeout(val id: String) : Event
    data class CaptureFailed(val id: String, val code: Code) : Event
    data class StopRequested(val id: String, val reason: Code) : Event
    /** [hasSpeech]: a chunk has a frame above -55 dBFS, so Silero's speech check decides. */
    data class TailDone(val id: String, val hasSpeech: Boolean, val samples: Long) : Event
    data class ModelLoading(val id: String) : Event
    data class ChunkDone(val id: String, val done: Int, val total: Int?) : Event
    /** [speech]: Silero heard a chunk, or its check failed open; false means no chunk and a blank [text]. */
    data class TranscriptReady(val id: String, val text: String, val staged: Boolean, val speech: Boolean = true) : Event
    data class TranscriptFailed(val id: String, val code: Code) : Event
    data class InsertDone(val id: String, val outcome: Outcome, val code: Code?) : Event
}

sealed interface Effect {
    data class CreateRow(val id: String) : Effect
    data class StartForeground(val id: String) : Effect
    data class StartCapture(val id: String) : Effect
    data class EnsureEngineLoaded(val id: String) : Effect
    /**
     * The controller pins the target from the editor state it cached at touch-down, before it dispatched the Press,
     * not from a later query.
     */
    data class PinTarget(val id: String) : Effect
    data class StartTail(val id: String) : Effect
    data class StopCapture(val id: String, val keepAudio: Boolean) : Effect
    data class StopForeground(val id: String) : Effect
    data class FinishTranscription(val id: String) : Effect
    data class AbortEngine(val id: String) : Effect
    data class Insert(val id: String, val text: String, val autoInsert: Boolean) : Effect
    data class SaveOutcome(val id: String, val outcome: Outcome, val code: Code? = null) : Effect
    data class DeleteSession(val id: String) : Effect
    data class Haptic(val kind: HapticKind) : Effect
    data class Message(val code: Code) : Effect
}

data class Machine(val state: State = State.Idle, val holdThresholdMs: Long = 300)

/**
 * The dictation state machine. [reduce] is pure: DictationController runs the effects in order and feeds their results
 * back as events.
 */
object Session {
    fun reduce(machine: Machine, event: Event): Pair<Machine, List<Effect>> {
        val state = machine.state
        val same = machine to emptyList<Effect>()
        // A late timer or engine answer for an earlier take must not touch this one.
        if (takeOf(event).let { it != null && it != sessionId(state) }) return same
        return when (state) {
            Idle -> if (event is Press) machine.go(Arming(event.newId), *started(event.newId)) else same
            is Arming -> when (event) {
                is FirstBuffer -> {
                    val mode = if (state.lockOnReady) Mode.LOCKED else Mode.HOLD
                    machine.go(Recording(state.id, mode), Haptic(HapticKind.TICK))
                }
                is Release -> when {
                    state.lockOnReady -> same
                    event.heldMs < machine.holdThresholdMs -> machine.go(state.copy(lockOnReady = true))
                    else -> machine.go(Idle, *discarded(state.id), Message(Code.MIC_NOT_READY))
                }
                // After a tap the next press is the stop, and the mic never got ready.
                is Press -> when {
                    state.lockOnReady -> machine.go(Idle, *discarded(state.id), Message(Code.MIC_NOT_READY))
                    else -> same
                }
                // Nothing was said yet: a drag, a cancel, a touch the system took or the service going discards the take.
                Drag, Cancel, TouchCancelled, ServiceGone -> machine.go(Idle, *discarded(state.id))
                is ArmingTimeout -> machine.go(Idle, *discarded(state.id), Message(Code.MIC_NOT_READY))
                is CaptureFailed -> machine.go(Idle, *discarded(state.id), Message(event.code))
                else -> same
            }
            is Recording -> when (event) {
                is Release -> when {
                    state.mode == Mode.LOCKED -> same
                    event.heldMs < machine.holdThresholdMs -> machine.go(state.copy(mode = Mode.LOCKED))
                    else -> machine.go(Stopping(state.id), StartTail(state.id), Haptic(HapticKind.STOP))
                }
                is Press -> when (state.mode) {
                    Mode.LOCKED -> machine.go(Stopping(state.id), StartTail(state.id), Haptic(HapticKind.STOP))
                    Mode.HOLD -> same
                }
                // The gesture classifier sends Drag only before the hold threshold: the touch was a drag, not speech.
                Drag -> when (state.mode) {
                    Mode.HOLD -> machine.go(Idle, *discarded(state.id))
                    Mode.LOCKED -> same
                }
                // The system took the touch: keep the take, but do not type into a field the user may have left.
                TouchCancelled -> when (state.mode) {
                    Mode.HOLD -> machine.go(Stopping(state.id, autoInsert = false), StartTail(state.id))
                    Mode.LOCKED -> same
                }
                is StopRequested -> machine.go(Stopping(state.id), StartTail(state.id), Message(event.reason))
                is CaptureFailed -> machine.go(Stopping(state.id), Message(event.code))
                Cancel -> machine.go(Idle, *cancelled(state.id))
                // The bubble went with the service: nothing else could stop a locked take, and no chip could offer its
                // text. It stops and keeps its audio; its text waits in history.
                ServiceGone -> machine.go(Stopping(state.id, autoInsert = false), StartTail(state.id))
                else -> same
            }
            is Stopping -> when (event) {
                is TailDone -> when {
                    event.hasSpeech -> machine.go(
                        Transcribing(state.id, state.autoInsert, samples = event.samples),
                        StopForeground(state.id),
                        FinishTranscription(state.id),
                    )
                    // A silent take under 1 s is dropped; a longer one keeps its audio in history. Either way the queue
                    // session opened at the press ends here: DeleteSession cancels it, and so does AbortEngine.
                    event.samples < 16_000 ->
                        machine.go(Idle, StopForeground(state.id), DeleteSession(state.id), Message(Code.NO_SPEECH))
                    else -> machine.go(
                        Idle,
                        StopForeground(state.id),
                        AbortEngine(state.id),
                        SaveOutcome(state.id, Outcome.NO_SPEECH),
                        Message(Code.NO_SPEECH),
                    )
                }
                Cancel -> machine.go(Idle, *cancelled(state.id))
                // A press while busy (the tail, transcribing, inserting) only buzzes. A queued press lost its release
                // and later started a HOLD take that kept recording.
                is Press -> machine.go(state, Haptic(HapticKind.REJECT))
                ServiceGone -> machine.go(state.copy(autoInsert = false))
                else -> same
            }
            is Transcribing -> when (event) {
                is ModelLoading -> machine.go(state.copy(loadingModel = true))
                is ChunkDone -> machine.go(state.copy(loadingModel = false, done = event.done, total = event.total))
                is TranscriptReady -> when {
                    // Silero heard no speech in any chunk: the end of a take with nothing loud enough to check.
                    !event.speech && state.samples < 16_000 ->
                        machine.go(Idle, DeleteSession(state.id), Message(Code.NO_SPEECH))
                    event.text.isBlank() ->
                        machine.go(Idle, SaveOutcome(state.id, Outcome.NO_SPEECH), Message(Code.NO_SPEECH))
                    // Text not saved to history, or from a cancelled touch, goes on the chip instead of into the field.
                    else -> {
                        val autoInsert = event.staged && state.autoInsert
                        machine.go(Inserting(state.id), Insert(state.id, event.text, autoInsert))
                    }
                }
                is TranscriptFailed -> machine.go(
                    Idle,
                    SaveOutcome(state.id, Outcome.FAILED, event.code),
                    Haptic(HapticKind.REJECT),
                    Message(event.code),
                )
                Cancel -> machine.go(Idle, AbortEngine(state.id), SaveOutcome(state.id, Outcome.CANCELLED))
                is Press -> machine.go(state, Haptic(HapticKind.REJECT))
                ServiceGone -> machine.go(state.copy(autoInsert = false))
                else -> same
            }
            is Inserting -> when (event) {
                is InsertDone -> {
                    val haptic = if (event.outcome == Outcome.INSERTED) HapticKind.CONFIRM else HapticKind.REJECT
                    machine.go(Idle, SaveOutcome(state.id, event.outcome, event.code), Haptic(haptic))
                }
                is Press -> machine.go(state, Haptic(HapticKind.REJECT))
                else -> same
            }
        }
    }

    fun sessionId(state: State): String? = when (state) {
        Idle -> null
        is Arming -> state.id
        is Recording -> state.id
        is Stopping -> state.id
        is Transcribing -> state.id
        is Inserting -> state.id
    }

    /** The take an event is for. Touches and Cancel carry none: they act on the live take. */
    private fun takeOf(event: Event): String? = when (event) {
        is Press, is Release, Drag, TouchCancelled, Cancel, ServiceGone -> null
        is FirstBuffer -> event.id
        is ArmingTimeout -> event.id
        is CaptureFailed -> event.id
        is StopRequested -> event.id
        is TailDone -> event.id
        is ModelLoading -> event.id
        is ChunkDone -> event.id
        is TranscriptReady -> event.id
        is TranscriptFailed -> event.id
        is InsertDone -> event.id
    }

    private fun Machine.go(state: State, vararg effects: Effect) = copy(state = state) to effects.toList()

    private fun started(id: String) =
        arrayOf<Effect>(CreateRow(id), StartForeground(id), StartCapture(id), EnsureEngineLoaded(id), PinTarget(id))

    // The take ended before any speech worth keeping (in Arming, or a drag early in a hold): its row and file go.
    private fun discarded(id: String) =
        arrayOf<Effect>(StopCapture(id, keepAudio = false), StopForeground(id), DeleteSession(id))

    // A cancelled take keeps its audio and its history row.
    private fun cancelled(id: String) = arrayOf<Effect>(
        StopCapture(id, keepAudio = true), StopForeground(id), AbortEngine(id), SaveOutcome(id, Outcome.CANCELLED),
    )
}
