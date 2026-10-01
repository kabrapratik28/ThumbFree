package io.github.kabrapratik28.thumbfree.app

import io.github.kabrapratik28.thumbfree.core.session.BubbleUi
import io.github.kabrapratik28.thumbfree.core.session.ChipAction
import io.github.kabrapratik28.thumbfree.core.session.ChipAction.*
import io.github.kabrapratik28.thumbfree.core.session.Code
import io.github.kabrapratik28.thumbfree.core.session.Effect
import io.github.kabrapratik28.thumbfree.core.session.Event
import io.github.kabrapratik28.thumbfree.core.session.HapticKind
import io.github.kabrapratik28.thumbfree.core.session.Machine
import io.github.kabrapratik28.thumbfree.core.session.Mode
import io.github.kabrapratik28.thumbfree.core.session.Outcome
import io.github.kabrapratik28.thumbfree.core.session.Session
import io.github.kabrapratik28.thumbfree.core.session.State
import io.github.kabrapratik28.thumbfree.core.session.TouchOutput
import io.github.kabrapratik28.thumbfree.core.text.CustomWords
import io.github.kabrapratik28.thumbfree.core.text.cleanup
import io.github.kabrapratik28.thumbfree.core.text.joinChunks

enum class TimerKind { ARMING_TIMEOUT, TAKE_WARNING, TAKE_LIMIT, SILENCE_CHECK }

/**
 * What the controller needs from Android (AndroidPorts).
 * Never call the controller from inside a port call; post to the main thread.
 */
interface DictationPorts {
    fun newSessionId(): String
    fun createRow(id: String): Boolean
    fun deleteSession(id: String)
    fun startForeground(id: String)
    fun stopForeground(id: String)
    fun startCapture(id: String)
    fun startTail(id: String)
    fun stopCapture(id: String, keepAudio: Boolean)
    fun msSinceLastSpeech(id: String): Long
    fun requestAudioFocus()
    fun abandonAudioFocus()
    fun ensureEngineLoaded(id: String)
    fun finishTranscription(id: String)
    fun abortEngine(id: String)
    fun pinTarget(id: String)
    fun saveStaged(id: String, raw: String, text: String): Boolean
    /** A history Transcribe's text, in one write that ends the request (HistoryDb.saveRetranscription); false if unsaved. */
    fun saveRetranscription(id: String, raw: String, text: String): Boolean
    fun markInserting(id: String)
    fun insert(id: String, text: String, autoInsert: Boolean)
    fun saveOutcome(id: String, outcome: Outcome, code: Code?)
    fun haptic(kind: HapticKind)
    fun render(ui: BubbleUi)
    fun schedule(id: String, kind: TimerKind, delayMs: Long)
    fun cancelTimers(id: String)
    fun copy(text: String): Boolean
    fun insertHere(id: String, text: String)
    fun retranscribe(id: String)
}

/**
 * Main-thread only. Runs Session.reduce and executes effects in order through [ports]. [customWords] is read as each
 * take, Retry or history Transcribe gets its text (Settings.customWords), so an edit applies to every text after it.
 */
class DictationController(
    private val ports: DictationPorts,
    private val clock: () -> Long,
    holdThresholdMs: Long = 300,
    private val customWords: () -> List<String> = ::emptyList,
) {
    private var machine = Machine(holdThresholdMs = holdThresholdMs)
    val state: State get() = machine.state

    // Kept only while a take can still use them (see prune). Internal for tests.
    internal val staged = mutableMapOf<String, String>()
    internal val stopReasons = mutableMapOf<String, Code>()
    private val retranscribing = mutableMapOf<String, Boolean>()   // id to insertAfter
    private val inserting = mutableSetOf<String>()   // ended takes whose Insert here, or insert after Undo or Retry, runs
    private var recordingSinceMs = 0L
    private var warningUntilMs = 0L
    private var chipId: String? = null
    private var foregroundTake: String? = null   // the live take when the service last reported foreground
    private var captureWaiting: String? = null   // the take whose mic opens once the service is foreground
    // Queue events for the live take that came while it still recorded, replayed once it reaches Transcribing.
    private var keptLoading: Event.ModelLoading? = null
    private var keptChunk: Event.ChunkDone? = null
    private var keptFailure: Event.TranscriptFailed? = null

    fun onTouch(out: TouchOutput) {
        when (out) {
            TouchOutput.Press -> onEvent(Event.Press(ports.newSessionId()))
            is TouchOutput.Release -> onEvent(Event.Release(out.heldMs))
            is TouchOutput.DragBy -> if (out.first) onEvent(Event.Drag)
            TouchOutput.Cancelled -> onEvent(Event.TouchCancelled)
            TouchOutput.None, is TouchOutput.DragEnd -> Unit
        }
    }

    fun onEvent(event: Event) {
        val state = machine.state
        val live = Session.sessionId(state)
        val recording = state is State.Arming || state is State.Recording || state is State.Stopping
        when (event) {
            // Insert here, Undo and Retry end takes that are no longer live. Such a result is always saved, but it
            // shows (and buzzes) only when no other take is live, so a new take's bubble stays as it is.
            is Event.InsertDone -> if (event.id == live) dispatch(event) else {
                inserting -= event.id
                saveOutcome(event.id, event.outcome, event.code)
                if (live == null) {
                    ports.haptic(if (event.outcome == Outcome.INSERTED) HapticKind.CONFIRM else HapticKind.REJECT)
                    show(outcomeUi(event.outcome, event.code), event.id)
                }
            }
            is Event.TranscriptFailed -> when {
                event.id == live && recording -> {
                    if (keptFailure == null) keptFailure = event
                    keptLoading = null
                }
                event.id == live -> dispatch(event)
                // The queue reports a failure from before finish again at finish; only the first report counts.
                retranscribing.remove(event.id) != null -> {
                    saveOutcome(event.id, Outcome.FAILED, event.code)
                    if (live == null) show(BubbleUi.Chip(event.code, listOf(RETRY)), event.id)
                }
            }
            is Event.ModelLoading -> if (event.id == live && recording) keptLoading = event else dispatch(event)
            // A chunk after a kept ModelLoading also means that load has finished (onModelLoaded says it first).
            is Event.ChunkDone -> if (event.id == live && recording) {
                keptChunk = event
                keptLoading = null
            } else dispatch(event)
            else -> dispatch(event)
        }
    }

    fun onLevel(id: String, unit: Float) {
        val state = machine.state
        // A warning chip stays up for 1,500 ms before the level ring comes back.
        if (state is State.Recording && state.id == id && clock() >= warningUntilMs) {
            show(BubbleUi.Recording(unit, state.mode == Mode.LOCKED, clock() - recordingSinceMs))
        }
    }

    fun onTimer(id: String, kind: TimerKind) {
        when (kind) {
            TimerKind.ARMING_TIMEOUT -> onEvent(Event.ArmingTimeout(id))
            TimerKind.TAKE_WARNING -> onWarning(id, Code.TAKE_ENDS_SOON)
            TimerKind.TAKE_LIMIT -> onEvent(Event.StopRequested(id, Code.TAKE_LIMIT))
            TimerKind.SILENCE_CHECK -> {
                val state = machine.state
                if (state !is State.Recording || state.id != id || state.mode != Mode.LOCKED) return
                if (ports.msSinceLastSpeech(id) >= 120_000) onEvent(Event.StopRequested(id, Code.LOCKED_SILENCE))
                else ports.schedule(id, TimerKind.SILENCE_CHECK, 10_000)
            }
        }
    }

    /** The queue loaded the model for [id]: a ModelLoading kept while the take records is not replayed. */
    fun onModelLoaded(id: String) {
        if (keptLoading?.id == id) keptLoading = null
    }

    /**
     * [speech] and [language]: TranscriptionQueue.Listener.onDone's; a history Transcribe without speech ends NO_SPEECH on
     * its blank text. The text is cleaned in the take's [language] (null: unknown), and the custom words correct the text
     * that is saved and inserted, exactly only unless it is English (CustomWords.exactOnlyFor); raw keeps the model's own.
     */
    fun onQueueDone(id: String, texts: List<String>, rawTexts: List<String>, speech: Boolean, language: String? = "en") {
        val state = machine.state
        val live = state is State.Transcribing && state.id == id
        if (!live && id !in retranscribing) return // a stale or deleted take's result, or a second report: nothing reads it
        val raw = joinChunks(rawTexts)
        // Only text that is kept gets the custom words: a take Silero heard no speech in brings blank text.
        val text = cleanup(joinChunks(texts), language) {
            if (speech && it.isNotBlank()) CustomWords.correct(it, customWords(), CustomWords.exactOnlyFor(language)) else it
        }
        if (live) {
            // The text is saved before any insert; text that could not be saved is only offered on the chip.
            if (text.isNotBlank()) staged[id] = text
            val saved = text.isNotBlank() && ports.saveStaged(id, raw, text)
            onEvent(Event.TranscriptReady(id, text, saved, speech))
            return
        }
        val insertAfter = retranscribing.remove(id) ?: return
        if (!insertAfter) {
            // A history Transcribe types nothing: one write keeps a typed take's status with the new text marked as
            // transcribed again, or ends another take Not inserted (No speech on a blank result). It shows no chip, and a
            // chip still up for its take (a failed save's, a failure's) no longer holds; unless this save fails, when only
            // the chip holds the text: shown, as for any take that is no longer live, when no other take is.
            val saved = ports.saveRetranscription(id, raw, text)
            when {
                text.isBlank() -> if (state == State.Idle) show(BubbleUi.Chip(Code.NO_SPEECH, listOf(DISMISS)), id)
                saved -> if (chipId == id) show(BubbleUi.Idle)
                state == State.Idle -> {
                    staged[id] = text
                    show(BubbleUi.Chip(Code.HISTORY_WRITE_FAILED, listOf(COPY)), id)
                }
            }
            return
        }
        if (text.isBlank()) {
            saveOutcome(id, Outcome.NO_SPEECH, null)
            if (state == State.Idle) show(BubbleUi.Chip(Code.NO_SPEECH, listOf(DISMISS)), id)
            return
        }
        staged[id] = text // before the write: for the Copy or Insert here of the chip the take may end with
        val saved = ports.saveStaged(id, raw, text)
        inserting += id
        if (saved) ports.markInserting(id)
        ports.insert(id, text, saved)
    }

    fun onChip(action: ChipAction) {
        val id = chipId
        val text = id?.let { staged[it] }
        when (action) {
            COPY -> if (text != null) {
                show(BubbleUi.Chip(if (ports.copy(text)) Code.COPIED else Code.COPY_FAILED, listOf(DISMISS)), id)
            }
            // The chip stays drawn but inert until its result redraws it, so a second tap never writes the text twice.
            INSERT_HERE -> if (id != null && text != null) {
                chipId = null
                inserting += id
                ports.insertHere(id, text)
            }
            // Refused while a history Transcribe of the take runs: the chip stays live, since that result draws nothing.
            UNDO, RETRY -> if (id != null && id !in retranscribing) {
                chipId = null
                startRetranscribe(id, insertAfter = true)
            }
            CANCEL -> onEvent(Event.Cancel)
            // Every chip is shown with an id, so a drawn chip without one is inert: its own timer must not take it down.
            DISMISS -> if (id != null && machine.state == State.Idle) show(BubbleUi.Idle)
        }
    }

    fun onWarning(id: String, code: Code) {
        val state = machine.state
        if (state !is State.Recording || state.id != id) return
        warningUntilMs = clock() + 1_500
        show(BubbleUi.Chip(code, listOf(DISMISS)), id)
    }

    fun onForegroundStarted() {
        val state = machine.state
        foregroundTake = Session.sessionId(state)
        if (state is State.Arming && captureWaiting == state.id) {
            captureWaiting = null
            openMic(state.id)
        }
    }

    /** Only a take still in Arming fails; one that already records keeps going until onForegroundStopped. */
    fun onForegroundDenied() {
        val state = machine.state
        if (state is State.Arming) onEvent(Event.CaptureFailed(state.id, Code.FOREGROUND_DENIED))
    }

    /** The service ended: a take in Arming fails before it records; a recording take stops and keeps its audio. */
    fun onForegroundStopped() {
        val state = machine.state
        if (state is State.Arming) {
            // Only this take's service counts: an earlier take's service can end just after a new press.
            if (foregroundTake == state.id) onEvent(Event.CaptureFailed(state.id, Code.FOREGROUND_STOPPED))
        } else {
            Session.sessionId(state)?.let { onEvent(Event.StopRequested(it, Code.FOREGROUND_STOPPED)) }
        }
    }

    /**
     * The bubble went with the accessibility service, and with it the only stop control: a take in Arming is discarded,
     * a recording one stops and keeps its audio. Nothing is typed in; the text waits in history.
     */
    fun onServiceGone() = onEvent(Event.ServiceGone)

    /**
     * A delete from history: the take's text and stop reason go, and so does its chip, so neither Copy nor Insert here
     * can bring the deleted text back. Nor can a history Transcribe of it still running: the ports stop its queue work
     * (a result that still comes is dropped) and drop what they keep for it.
     */
    fun forget(id: String) {
        staged -= id
        stopReasons -= id
        retranscribing -= id
        ports.deleteSession(id)
        if (chipId == id) show(BubbleUi.Idle)
    }

    /** A second request for an id already in flight (history asking again) is ignored until the first reports. */
    fun startRetranscribe(id: String, insertAfter: Boolean) {
        if (id in retranscribing) return
        retranscribing[id] = insertAfter
        ports.retranscribe(id)
    }

    private fun dispatch(event: Event) {
        val before = machine.state
        val (next, effects) = Session.reduce(machine, event)
        machine = next
        val after = next.state
        val armed = after is State.Arming && before !is State.Arming
        if (armed) {
            keptLoading = null
            keptChunk = null
            keptFailure = null
        }
        var chip: BubbleUi? = null
        for (effect in effects) when (effect) {
            // Without a row nothing starts: the rest of the batch is skipped, so the service and the mic never open.
            is Effect.CreateRow -> if (!ports.createRow(effect.id)) {
                return dispatch(Event.CaptureFailed(effect.id, Code.HISTORY_WRITE_FAILED))
            }
            is Effect.StartForeground -> ports.startForeground(effect.id)
            // The mic opens only once the service is foreground (a background app records silence).
            is Effect.StartCapture -> if (foregroundTake == effect.id) openMic(effect.id) else captureWaiting = effect.id
            is Effect.EnsureEngineLoaded -> ports.ensureEngineLoaded(effect.id)
            is Effect.PinTarget -> ports.pinTarget(effect.id)
            is Effect.StartTail -> ports.startTail(effect.id)
            is Effect.StopCapture -> ports.stopCapture(effect.id, effect.keepAudio)
            is Effect.StopForeground -> {
                ports.stopForeground(effect.id)
                ports.abandonAudioFocus()
                ports.cancelTimers(effect.id)
            }
            is Effect.FinishTranscription -> ports.finishTranscription(effect.id)
            is Effect.AbortEngine -> ports.abortEngine(effect.id)
            is Effect.Insert -> {
                if (effect.autoInsert) ports.markInserting(effect.id)
                ports.insert(effect.id, effect.text, effect.autoInsert)
            }
            is Effect.SaveOutcome -> {
                saveOutcome(effect.id, effect.outcome, effect.code)
                chip = outcomeUi(effect.outcome, effect.code)
            }
            is Effect.DeleteSession -> ports.deleteSession(effect.id)
            is Effect.Haptic -> ports.haptic(effect.kind)
            is Effect.Message -> {
                // A take leaves Recording with a message only when it stopped for a reason: limit, call, capture failure.
                if (before is State.Recording) stopReasons[before.id] = effect.code
                if (chip == null) chip = BubbleUi.Chip(effect.code, listOf(DISMISS))
            }
        }
        // The arming timeout also bounds the wait for the service.
        if (armed) ports.schedule(after.id, TimerKind.ARMING_TIMEOUT, 2_000)
        if (after is State.Recording) {
            if (before !is State.Recording) {
                recordingSinceMs = clock()
                warningUntilMs = 0
                ports.schedule(after.id, TimerKind.TAKE_WARNING, 840_000)
                ports.schedule(after.id, TimerKind.TAKE_LIMIT, 900_000)
            }
            // A hold released under the threshold after the first buffer locks the take too.
            if (after.mode == Mode.LOCKED && (before as? State.Recording)?.mode != Mode.LOCKED) {
                ports.schedule(after.id, TimerKind.SILENCE_CHECK, 10_000)
            }
        }
        when {
            chip != null -> show(chip, Session.sessionId(before))
            after != before -> show(ui(after))
        }
        // Arrival order: a ModelLoading still kept came after the last chunk, so the model is still loading.
        if (after is State.Transcribing && before !is State.Transcribing) {
            listOfNotNull(keptChunk, keptLoading, keptFailure).forEach(::dispatch)
        }
    }

    /** An outcome without a code of its own keeps the reason its take stopped, so the history row shows it. */
    private fun saveOutcome(id: String, outcome: Outcome, code: Code?) =
        ports.saveOutcome(id, outcome, code ?: stopReasons[id])

    private fun openMic(id: String) {
        ports.startCapture(id)
        ports.requestAudioFocus()
    }

    private fun show(ui: BubbleUi, id: String? = null) {
        chipId = id.takeIf { ui is BubbleUi.Chip }
        ports.render(ui)
        prune()
    }

    /**
     * Drops the text and stop reason of every take nothing can use any more. What stays: the live take, the chip's take
     * (Copy, Insert here, Undo, Retry), and takes whose retranscription or insert is still running.
     */
    private fun prune() {
        if (staged.isEmpty() && stopReasons.isEmpty()) return // most renders, the level ring's too
        val live = Session.sessionId(machine.state)
        val keep = { id: String -> id == live || id == chipId || id in retranscribing || id in inserting }
        staged.keys.retainAll(keep)
        stopReasons.keys.retainAll(keep)
    }

    private fun ui(state: State): BubbleUi = when (state) {
        State.Idle -> BubbleUi.Idle
        is State.Arming -> BubbleUi.Arming
        is State.Recording -> BubbleUi.Recording(0f, state.mode == Mode.LOCKED, clock() - recordingSinceMs)
        is State.Stopping, is State.Inserting -> BubbleUi.Processing(false, 0, null)
        is State.Transcribing -> BubbleUi.Processing(state.loadingModel, state.done, state.total)
    }

    private fun outcomeUi(outcome: Outcome, code: Code?): BubbleUi = when (outcome) {
        Outcome.INSERTED -> BubbleUi.Idle
        Outcome.UNVERIFIED -> BubbleUi.Chip(code ?: Code.MAY_NOT_HAVE_LANDED, listOf(COPY))
        Outcome.NOT_INSERTED -> when (code) {
            Code.PASSWORD_TARGET -> BubbleUi.Chip(Code.PASSWORD_TARGET, listOf(COPY))
            Code.TARGET_CHANGED -> BubbleUi.Chip(Code.TARGET_CHANGED, listOf(INSERT_HERE, COPY))
            else -> BubbleUi.Chip(code ?: Code.MAY_NOT_HAVE_LANDED, listOf(COPY, INSERT_HERE))
        }
        Outcome.CANCELLED -> BubbleUi.Chip(Code.CANCELLED, listOf(UNDO))
        // A failure always carries its code; the fallback only keeps this total.
        Outcome.FAILED -> BubbleUi.Chip(code ?: Code.ENGINE_CRASHED, listOf(RETRY))
        Outcome.NO_SPEECH -> BubbleUi.Chip(Code.NO_SPEECH, listOf(DISMISS))
    }
}
