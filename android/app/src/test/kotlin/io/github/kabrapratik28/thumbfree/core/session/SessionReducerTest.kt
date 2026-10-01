package io.github.kabrapratik28.thumbfree.core.session

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.github.kabrapratik28.thumbfree.core.session.Effect.*
import io.github.kabrapratik28.thumbfree.core.session.Event.*
import io.github.kabrapratik28.thumbfree.core.session.State.*
import org.junit.Test

// The rows of the session's state table. "s1" is the live take.
class SessionReducerTest {
    private val M = Machine()

    private fun pressEffects(id: String) =
        arrayOf(CreateRow(id), StartForeground(id), StartCapture(id), EnsureEngineLoaded(id), PinTarget(id))

    private fun discarded(vararg message: Message) =
        arrayOf(StopCapture("s1", false), StopForeground("s1"), DeleteSession("s1"), *message)

    /** Reduces [event] from [from], checks the next state and the effects in order, and returns the next machine. */
    private fun step(from: Machine, event: Event, state: State, vararg effects: Effect): Machine {
        val (next, out) = Session.reduce(from, event)
        assertThat(next.state).isEqualTo(state)
        assertThat(out).containsExactlyElementsIn(effects).inOrder()
        return next
    }

    private fun step(from: State, event: Event, state: State, vararg effects: Effect) =
        step(M.copy(state = from), event, state, *effects)

    @Test
    fun pressInIdleArms() {
        step(Idle, Press("s1"), Arming("s1"), *pressEffects("s1"))
    }

    @Test
    fun firstBufferWhileHeldRecordsHold() {
        step(Arming("s1"), FirstBuffer("s1"), Recording("s1", Mode.HOLD), Haptic(HapticKind.TICK))
    }

    @Test
    fun tapBeforeFirstBufferLocksOnReady() {
        val locking = step(Arming("s1"), Release(120), Arming("s1", lockOnReady = true))
        step(locking, FirstBuffer("s1"), Recording("s1", Mode.LOCKED), Haptic(HapticKind.TICK))
    }

    @Test
    fun longHoldReleasedBeforeFirstBufferIsMicNotReady() {
        step(Arming("s1"), Release(400), Idle, *discarded(Message(Code.MIC_NOT_READY)))
    }

    @Test
    fun armingReleaseBelowThresholdLocksOnReady() {
        step(Arming("s1"), Release(299), Arming("s1", lockOnReady = true))
    }

    @Test
    fun armingReleaseAtThresholdIsMicNotReady() {
        step(Arming("s1"), Release(300), Idle, *discarded(Message(Code.MIC_NOT_READY)))
    }

    @Test
    fun secondPressWhileLockPendingIsMicNotReady() {
        step(Arming("s1", lockOnReady = true), Press("s2"), Idle, *discarded(Message(Code.MIC_NOT_READY)))
    }

    @Test
    fun dragInArmingDiscards() {
        step(Arming("s1"), Drag, Idle, *discarded())
    }

    @Test
    fun cancelInArmingDiscards() {
        step(Arming("s1"), Cancel, Idle, *discarded())
    }

    @Test
    fun touchCancelInArmingDiscards() {
        step(Arming("s1"), TouchCancelled, Idle, *discarded())
    }

    @Test
    fun captureFailedWhileArmingIsTyped() {
        step(Arming("s1"), CaptureFailed("s1", Code.MIC_PERMISSION), Idle, *discarded(Message(Code.MIC_PERMISSION)))
    }

    @Test
    fun armingTimeoutIsMicNotReady() {
        step(Arming("s1"), ArmingTimeout("s1"), Idle, *discarded(Message(Code.MIC_NOT_READY)))
    }

    @Test
    fun holdReleaseAtThresholdStops() {
        step(Recording("s1", Mode.HOLD), Release(300), Stopping("s1"), StartTail("s1"), Haptic(HapticKind.STOP))
    }

    @Test
    fun holdReleaseBelowThresholdLocks() {
        step(Recording("s1", Mode.HOLD), Release(299), Recording("s1", Mode.LOCKED))
    }

    @Test
    fun dragInEarlyHoldDiscards() {
        // The gesture classifier sends Drag only before the hold threshold: the touch was a drag, not speech.
        step(Recording("s1", Mode.HOLD), Drag, Idle, *discarded())
    }

    @Test
    fun pressWhileLockedStops() {
        step(Recording("s1", Mode.LOCKED), Press("s2"), Stopping("s1"), StartTail("s1"), Haptic(HapticKind.STOP))
    }

    @Test
    fun touchCancelledDuringHoldHoldsBackInsert() {
        step(Recording("s1", Mode.HOLD), TouchCancelled, Stopping("s1", autoInsert = false), StartTail("s1"))
    }

    @Test
    fun stopRequestedStops() {
        step(
            Recording("s1", Mode.LOCKED), StopRequested("s1", Code.TAKE_LIMIT), Stopping("s1"),
            StartTail("s1"), Message(Code.TAKE_LIMIT),
        )
    }

    @Test
    fun captureFailedWhileRecordingStops() {
        step(
            Recording("s1", Mode.HOLD), CaptureFailed("s1", Code.STORAGE_FULL), Stopping("s1"),
            Message(Code.STORAGE_FULL),
        )
    }

    // Without the accessibility service there is no bubble to stop a take or show its chip.
    @Test
    fun serviceGoneDiscardsAnArmingTake() {
        step(Arming("s1"), ServiceGone, Idle, *discarded())
        step(Arming("s1", lockOnReady = true), ServiceGone, Idle, *discarded())
    }

    // It stops now and keeps its audio, and its text waits in history instead of being typed in.
    @Test
    fun serviceGoneStopsARecordingTakeAndHoldsBackItsText() {
        for (mode in Mode.entries) {
            step(Recording("s1", mode), ServiceGone, Stopping("s1", autoInsert = false), StartTail("s1"))
        }
        step(Stopping("s1"), ServiceGone, Stopping("s1", autoInsert = false))
        step(Transcribing("s1", done = 1), ServiceGone, Transcribing("s1", autoInsert = false, done = 1))
        step(Inserting("s1"), ServiceGone, Inserting("s1")) // its one write is already under way
        step(Idle, ServiceGone, Idle)
    }

    @Test
    fun tailDoneWithSpeechTranscribes() {
        step(
            Stopping("s1"), TailDone("s1", true, 80_000), Transcribing("s1", samples = 80_000),
            StopForeground("s1"), FinishTranscription("s1"),
        )
    }

    // The take decision is Silero's. TailDone's hasSpeech only says a chunk was loud enough to check; a take Silero
    // heard no speech in ends as a take with nothing to check does: under 1 s its row and WAV go.
    @Test
    fun shortTakeSileroHeardNoSpeechInIsDeleted() {
        step(
            Transcribing("s1", samples = 15_999), TranscriptReady("s1", "", false, speech = false), Idle,
            DeleteSession("s1"), Message(Code.NO_SPEECH),
        )
    }

    @Test
    fun longTakeSileroHeardNoSpeechInIsKept() {
        step(
            Transcribing("s1", samples = 16_000), TranscriptReady("s1", "", false, speech = false), Idle,
            SaveOutcome("s1", Outcome.NO_SPEECH), Message(Code.NO_SPEECH),
        )
    }

    @Test
    fun shortTakeWithoutSpeechIsDeleted() {
        // Under 1 s (16,000 samples) there is nothing worth keeping.
        for (samples in listOf(8_000L, 15_999L)) {
            step(
                Stopping("s1"), TailDone("s1", false, samples), Idle,
                StopForeground("s1"), DeleteSession("s1"), Message(Code.NO_SPEECH),
            )
        }
    }

    @Test
    fun longTakeWithoutSpeechIsKept() {
        // Its queue session, opened at the press, ends too: nothing will finish it.
        for (samples in listOf(16_000L, 32_000L)) {
            step(
                Stopping("s1"), TailDone("s1", false, samples), Idle,
                StopForeground("s1"), AbortEngine("s1"), SaveOutcome("s1", Outcome.NO_SPEECH), Message(Code.NO_SPEECH),
            )
        }
    }

    @Test
    fun transcriptReadyInserts() {
        step(Transcribing("s1"), TranscriptReady("s1", "Hello", true), Inserting("s1"), Insert("s1", "Hello", true))
    }

    @Test
    fun unstagedTranscriptIsHeldBack() {
        step(Transcribing("s1"), TranscriptReady("s1", "Hello", false), Inserting("s1"), Insert("s1", "Hello", false))
    }

    @Test
    fun touchCancelledTakeIsHeldBack() {
        step(
            Transcribing("s1", autoInsert = false), TranscriptReady("s1", "Hello", true), Inserting("s1"),
            Insert("s1", "Hello", false),
        )
    }

    @Test
    fun blankTranscriptIsNoSpeech() {
        step(
            Transcribing("s1"), TranscriptReady("s1", "  ", true), Idle,
            SaveOutcome("s1", Outcome.NO_SPEECH), Message(Code.NO_SPEECH),
        )
    }

    @Test
    fun transcriptFailedOffersRetry() {
        step(
            Transcribing("s1"), TranscriptFailed("s1", Code.ENGINE_CRASHED), Idle,
            SaveOutcome("s1", Outcome.FAILED, Code.ENGINE_CRASHED),
            Haptic(HapticKind.REJECT),
            Message(Code.ENGINE_CRASHED),
        )
    }

    @Test
    fun cancelWhileRecordingKeepsAudio() {
        step(
            Recording("s1", Mode.LOCKED), Cancel, Idle,
            StopCapture("s1", true), StopForeground("s1"), AbortEngine("s1"), SaveOutcome("s1", Outcome.CANCELLED),
        )
    }

    @Test
    fun cancelWhileStoppingKeepsAudio() {
        step(
            Stopping("s1"), Cancel, Idle,
            StopCapture("s1", true), StopForeground("s1"), AbortEngine("s1"), SaveOutcome("s1", Outcome.CANCELLED),
        )
    }

    @Test
    fun pressDuringTailIsRejected() {
        step(Stopping("s1"), Press("s2"), Stopping("s1"), Haptic(HapticKind.REJECT))
    }

    @Test
    fun cancelWhileTranscribingAborts() {
        step(Transcribing("s1"), Cancel, Idle, AbortEngine("s1"), SaveOutcome("s1", Outcome.CANCELLED))
    }

    @Test
    fun cancelWhileInsertingIsIgnored() {
        step(Inserting("s1"), Cancel, Inserting("s1"))
    }

    @Test
    fun modelLoadingShows() {
        step(Transcribing("s1"), ModelLoading("s1"), Transcribing("s1", loadingModel = true))
    }

    @Test
    fun chunkDoneCounts() {
        step(Transcribing("s1", loadingModel = true), ChunkDone("s1", 2, 5), Transcribing("s1", done = 2, total = 5))
    }

    @Test
    fun insertedConfirms() {
        step(
            Inserting("s1"), InsertDone("s1", Outcome.INSERTED, null), Idle,
            SaveOutcome("s1", Outcome.INSERTED), Haptic(HapticKind.CONFIRM),
        )
    }

    @Test
    fun notInsertedRejects() {
        step(
            Inserting("s1"), InsertDone("s1", Outcome.NOT_INSERTED, Code.TARGET_CHANGED), Idle,
            SaveOutcome("s1", Outcome.NOT_INSERTED, Code.TARGET_CHANGED), Haptic(HapticKind.REJECT),
        )
    }

    @Test
    fun busyTapNeverStartsASecondTake() {
        // A tap while busy only buzzes. Queued, it lost its release and later started a HOLD take that kept recording.
        // Each step checks every effect, so nothing for "s2" (row, foreground, capture) is ever emitted.
        var machine = step(Transcribing("s1"), Press("s2"), Transcribing("s1"), Haptic(HapticKind.REJECT))
        machine = step(machine, Release(50), Transcribing("s1"))
        machine = step(machine, TranscriptReady("s1", "hi", true), Inserting("s1"), Insert("s1", "hi", true))
        machine = step(machine, Press("s2"), Inserting("s1"), Haptic(HapticKind.REJECT))
        machine = step(machine, Release(50), Inserting("s1"))
        machine = step(
            machine, InsertDone("s1", Outcome.INSERTED, null), Idle,
            SaveOutcome("s1", Outcome.INSERTED), Haptic(HapticKind.CONFIRM),
        )

        assertThat(machine).isEqualTo(M)
    }

    @Test
    fun liveQueueEventsIgnoredWhileRecording() {
        // ModelLoading, ChunkDone and TranscriptFailed only apply in Transcribing. The controller buffers them for a
        // queued take instead; Recording and Stopping ignore them outright for the live take.
        for (state in listOf(Recording("s1", Mode.LOCKED), Stopping("s1"))) {
            step(state, ModelLoading("s1"), state)
            step(state, ChunkDone("s1", 1, null), state)
            step(state, TranscriptFailed("s1", Code.ENGINE_CRASHED), state)
        }
    }

    @Test
    fun endedSessionStartsNothing() {
        // A session that ends must never start another: step's exact-match effects assertion also proves no
        // CreateRow sneaks in, since CreateRow only ever comes from Idle + Press.
        step(
            Transcribing("s1"), TranscriptReady("s1", "  ", true), Idle,
            SaveOutcome("s1", Outcome.NO_SPEECH), Message(Code.NO_SPEECH),
        )
        step(
            Transcribing("s1"), TranscriptFailed("s1", Code.ENGINE_CRASHED), Idle,
            SaveOutcome("s1", Outcome.FAILED, Code.ENGINE_CRASHED), Haptic(HapticKind.REJECT),
            Message(Code.ENGINE_CRASHED),
        )
        step(Transcribing("s1"), Cancel, Idle, AbortEngine("s1"), SaveOutcome("s1", Outcome.CANCELLED))
    }

    @Test
    fun staleEventsIgnored() {
        // Events for an earlier take "s0" never touch the live take "s1". Recording(HOLD) ignores its three even with
        // id "s1"; every other event here would act if its id matched.
        val cases = listOf(
            Recording("s1", Mode.HOLD) to
                listOf(FirstBuffer("s0"), TailDone("s0", true, 1), TranscriptReady("s0", "x", true)),
            Arming("s1") to listOf(FirstBuffer("s0"), ArmingTimeout("s0"), CaptureFailed("s0", Code.MIC_PERMISSION)),
            Recording("s1", Mode.LOCKED) to
                listOf(StopRequested("s0", Code.TAKE_LIMIT), CaptureFailed("s0", Code.STORAGE_FULL)),
            Stopping("s1") to listOf(TailDone("s0", false, 8_000)),
            Transcribing("s1") to listOf(
                ModelLoading("s0"), ChunkDone("s0", 1, 2), TranscriptReady("s0", "x", true),
                TranscriptFailed("s0", Code.ENGINE_CRASHED),
            ),
            Inserting("s1") to listOf(InsertDone("s0", Outcome.INSERTED, null)),
        )
        for ((state, events) in cases) {
            val machine = M.copy(state = state)
            for (event in events) {
                assertWithMessage("$event in $state").that(Session.reduce(machine, event))
                    .isEqualTo(machine to emptyList<Effect>())
            }
        }
    }
}
