package io.github.kabrapratik28.thumbfree.app

import com.google.common.truth.Truth.assertThat
import io.github.kabrapratik28.thumbfree.core.session.BubbleUi
import io.github.kabrapratik28.thumbfree.core.session.BubbleUi.Chip
import io.github.kabrapratik28.thumbfree.core.session.BubbleUi.Processing
import io.github.kabrapratik28.thumbfree.core.session.ChipAction.*
import io.github.kabrapratik28.thumbfree.core.session.Code.*
import io.github.kabrapratik28.thumbfree.core.session.Event.*
import io.github.kabrapratik28.thumbfree.core.session.Mode
import io.github.kabrapratik28.thumbfree.core.session.Outcome
import io.github.kabrapratik28.thumbfree.core.session.Session
import io.github.kabrapratik28.thumbfree.core.session.State
import io.github.kabrapratik28.thumbfree.core.session.TouchOutput
import org.junit.Test

// "Press" is the touch and then the service reporting foreground. "s1" is the first take.
class DictationControllerTest {
    private val ports = FakePorts()
    private var now = 0L
    private var words = emptyList<String>() // Settings.customWords
    private var wordReads = 0
    private val controller = DictationController(ports, { now }, customWords = { wordReads++; words })

    private fun render(ui: BubbleUi) = "render($ui)"

    /** The port calls [block] makes. */
    private fun callsOf(block: () -> Unit): List<String> {
        val from = ports.calls.size
        block()
        return ports.calls.drop(from)
    }

    private fun lastRender() = ports.calls.last { it.startsWith("render(") }

    private fun press(): String {
        controller.onTouch(TouchOutput.Press)
        controller.onForegroundStarted()
        return requireNotNull(Session.sessionId(controller.state))
    }

    /** A take held past the threshold, recording. */
    private fun holdTake() = press().also { controller.onEvent(FirstBuffer(it)) }

    /** A tapped take: released before the first buffer, so it records locked. */
    private fun lockedTake() = press().also {
        controller.onTouch(TouchOutput.Release(120))
        controller.onEvent(FirstBuffer(it))
    }

    /** The tap that stops a locked take. */
    private fun stopTap() {
        controller.onTouch(TouchOutput.Press)
        controller.onTouch(TouchOutput.Release(100))
    }

    /** A hold take released with speech: the queue is finishing it. */
    private fun transcribingTake() = holdTake().also {
        controller.onTouch(TouchOutput.Release(400))
        controller.onEvent(TailDone(it, true, 80_000))
    }

    private fun queueDone(id: String, vararg texts: String, language: String? = "en") =
        controller.onQueueDone(id, texts.toList(), texts.toList(), speech = true, language = language)

    private fun insertingTake() = transcribingTake().also { queueDone(it, "Hello there.") }

    @Test
    fun captureWaitsForForeground() {
        controller.onTouch(TouchOutput.Press)
        assertThat(ports.calls).containsExactly(
            "newSessionId", "createRow(s1)", "startForeground(s1)", "ensureEngineLoaded(s1)", "pinTarget(s1)",
            "schedule(s1,ARMING_TIMEOUT,2000)", render(BubbleUi.Arming),
        ).inOrder()
        assertThat(callsOf { controller.onForegroundStarted() }).containsExactly("startCapture(s1)", "requestAudioFocus").inOrder()

        // A foreground report that came before the capture effect opens the mic at once. Real ports post their
        // callbacks (see DictationPorts), so only this fake can reach that rule.
        controller.onTouch(TouchOutput.DragBy(15f, 0f, first = true))
        ports.onStartForeground = { controller.onForegroundStarted() }
        assertThat(callsOf { controller.onTouch(TouchOutput.Press) }).containsAtLeast(
            "startForeground(s2)", "startCapture(s2)", "requestAudioFocus", "ensureEngineLoaded(s2)",
        ).inOrder()
    }

    @Test
    fun createRowFailureNeverOpensTheMic() {
        ports.createRowResult = false
        controller.onTouch(TouchOutput.Press)
        controller.onForegroundStarted()
        assertThat(ports.calls.filter { it.startsWith("startForeground") || it.startsWith("startCapture") }).isEmpty()
        assertThat(lastRender()).isEqualTo(render(Chip(HISTORY_WRITE_FAILED, listOf(DISMISS))))
        assertThat(controller.state).isEqualTo(State.Idle)
    }

    @Test
    fun firstBufferSchedulesLimits() {
        press()
        assertThat(callsOf { controller.onEvent(FirstBuffer("s1")) }).containsExactly(
            "haptic(TICK)", "schedule(s1,TAKE_WARNING,840000)", "schedule(s1,TAKE_LIMIT,900000)",
            render(BubbleUi.Recording(level = 0f, locked = false, elapsedMs = 0)),
        ).inOrder()
        controller.onChip(CANCEL)

        val tapped = press()
        controller.onTouch(TouchOutput.Release(120))
        assertThat(callsOf { controller.onEvent(FirstBuffer(tapped)) }).containsExactly(
            "haptic(TICK)", "schedule($tapped,TAKE_WARNING,840000)", "schedule($tapped,TAKE_LIMIT,900000)",
            "schedule($tapped,SILENCE_CHECK,10000)", render(BubbleUi.Recording(level = 0f, locked = true, elapsedMs = 0)),
        ).inOrder()
        controller.onChip(CANCEL)

        // A tap released after the first buffer locks the take as well.
        val late = holdTake()
        assertThat(callsOf { controller.onTouch(TouchOutput.Release(120)) }).containsExactly(
            "schedule($late,SILENCE_CHECK,10000)", render(BubbleUi.Recording(level = 0f, locked = true, elapsedMs = 0)),
        ).inOrder()
    }

    @Test
    fun micClosesBeforeTranscription() {
        holdTake()
        val calls = callsOf {
            controller.onTouch(TouchOutput.Release(400))
            controller.onEvent(TailDone("s1", true, 80_000))
        }
        assertThat(calls).containsExactly(
            "startTail(s1)", "haptic(STOP)", render(Processing(false, 0, null)),
            "stopForeground(s1)", "abandonAudioFocus", "cancelTimers(s1)", "finishTranscription(s1)",
            render(Processing(false, 0, null)),
        ).inOrder()
    }

    // Silero heard no speech in any chunk of a take under 1 s: it ends as a silent tap does, row and WAV deleted.
    // A longer one keeps its audio in history as NO_SPEECH.
    @Test
    fun takeSileroHeardNoSpeechInEndsLikeASilentTake() {
        val short = holdTake()
        controller.onTouch(TouchOutput.Release(400))
        controller.onEvent(TailDone(short, true, 8_000))
        assertThat(callsOf { controller.onQueueDone(short, listOf(""), listOf(""), speech = false) }).containsExactly(
            "deleteSession(s1)", render(Chip(NO_SPEECH, listOf(DISMISS))),
        ).inOrder()

        val long = transcribingTake()
        assertThat(callsOf { controller.onQueueDone(long, listOf(""), listOf(""), speech = false) })
            .containsAtLeast("saveOutcome(s2,NO_SPEECH,null)", render(Chip(NO_SPEECH, listOf(DISMISS)))).inOrder()
    }

    @Test
    fun stagedBeforeInsert() {
        transcribingTake()
        assertThat(callsOf { queueDone("s1", "Hello there.") }).containsExactly(
            "saveStaged(s1,Hello there.,Hello there.)", "markInserting(s1)", "insert(s1,Hello there.,true)",
            render(Processing(false, 0, null)),
        ).inOrder()
    }

    @Test
    fun stageFailureHoldsBackInsert() {
        transcribingTake()
        ports.saveStagedResult = false
        val calls = callsOf { queueDone("s1", "Hello there.") }
        assertThat(calls).contains("insert(s1,Hello there.,false)")
        assertThat(calls).doesNotContain("markInserting(s1)")
    }

    // A history Transcribe whose text could not be saved keeps it on the chip, and its row as it was, Transcribe button
    // included, instead of ending NOT_INSERTED with the text gone.
    @Test
    fun unsavedHistoryTranscribeOffersItsText() {
        controller.startRetranscribe("s8", insertAfter = false)
        ports.saveRetranscriptionResult = false

        assertThat(callsOf { queueDone("s8", "Hello there.") }).containsExactly(
            "saveRetranscription(s8,Hello there.,Hello there.)", render(Chip(HISTORY_WRITE_FAILED, listOf(COPY))),
        ).inOrder()
        assertThat(callsOf { controller.onChip(COPY) }).contains("copy(Hello there.)")
    }

    // A later Transcribe of the take that saves its text clears the failed save's chip, still up, which no longer
    // holds.
    @Test
    fun savedHistoryTranscribeClearsTheTakesChip() {
        controller.startRetranscribe("s8", insertAfter = false)
        ports.saveRetranscriptionResult = false
        queueDone("s8", "Hello there.")
        controller.startRetranscribe("s8", insertAfter = false)
        ports.saveRetranscriptionResult = true

        assertThat(callsOf { queueDone("s8", "Hello there.") }).containsExactly(
            "saveRetranscription(s8,Hello there.,Hello there.)", render(BubbleUi.Idle),
        ).inOrder()
    }

    // A history Transcribe saves its text in one write that keeps a typed take's status (the ports and HistoryDb
    // decide), not the old Not inserted outcome, and shows no chip. With no speech, the chip says so, as before.
    @Test
    fun historyTranscribeSavesTheNewTextInOneWrite() {
        controller.startRetranscribe("s8", insertAfter = false)
        assertThat(callsOf { queueDone("s8", "Hello there.") }).containsExactly("saveRetranscription(s8,Hello there.,Hello there.)")

        controller.startRetranscribe("s8", insertAfter = false)
        assertThat(callsOf { controller.onQueueDone("s8", listOf(""), listOf(""), speech = false) })
            .containsExactly("saveRetranscription(s8,,)", render(Chip(NO_SPEECH, listOf(DISMISS)))).inOrder()
    }

    // Deleted while its history Transcribe ran: its engine work stops (deleteSession cancels it and drops the ports'
    // bookkeeping), and a result that still comes (the stage finds no row) must not bring the text back on a chip.
    @Test
    fun historyTranscribeOfADeletedTakeShowsNothing() {
        controller.startRetranscribe("s8", insertAfter = false)
        assertThat(callsOf { controller.forget("s8") }).containsExactly("deleteSession(s8)")
        ports.saveStagedResult = false

        assertThat(callsOf { queueDone("s8", "Hello there.") }).isEmpty()
    }

    @Test
    fun cleanupAppliedToJoinedChunks() {
        transcribingTake()
        controller.onQueueDone(
            "s1", texts = listOf("Um, so we", "we we we should go."), rawTexts = listOf("um so we", "we we we should go"),
            speech = true,
        )
        assertThat(ports.calls).contains("saveStaged(s1,um so we we we we should go,so we should go.)")
    }

    // A take's text is cleaned in the language its result carries. The multilingual model's is unknown (null), so
    // only the fillers that are no word in any language go: German "um" stays, where English cleanup would drop it.
    @Test
    fun cleanupFollowsTheTakesLanguage() {
        transcribingTake()
        queueDone("s1", "Dieses Sediment war nötig, um Sandbänke zu bilden, ähm, hmm.", language = null)

        assertThat(ports.calls).contains(
            "saveStaged(s1,Dieses Sediment war nötig, um Sandbänke zu bilden, ähm, hmm.,Dieses Sediment war nötig, um " +
                "Sandbänke zu bilden, ähm,)",
        )
    }

    // A take on the multilingual model (no language) gets exact matches only, so "grazie" keeps its word
    // while "github" is still GitHub, and an exact entry outside ASCII (Łukasz) still applies; a take on an English model
    // still gets the near misses.
    @Test
    fun customWordsMatchExactlyOnlyOnMultilingualTakes() {
        words = listOf("Grazia", "Kubernetes", "GitHub", "Łukasz")
        transcribingTake()
        queueDone("s1", "grazie łukasz, push kubernetis to github", language = null)
        controller.onEvent(InsertDone("s1", Outcome.INSERTED, null))
        val english = transcribingTake()
        queueDone(english, "grazie łukasz, push kubernetis to github", language = "en")

        assertThat(ports.calls).containsAtLeast(
            "saveStaged(s1,grazie łukasz, push kubernetis to github,grazie Łukasz, push kubernetis to GitHub)",
            "saveStaged($english,grazie łukasz, push kubernetis to github,Grazia Łukasz, push Kubernetes to GitHub)",
        ).inOrder()
    }

    // VOCAB: the custom words correct the text a take saves and inserts; its raw text stays the model's own.
    @Test
    fun customWordsCorrectTheSavedAndInsertedText() {
        words = listOf("GitHub", "ChatGPT")
        transcribingTake()

        val calls = callsOf {
            controller.onQueueDone(
                "s1", texts = listOf("Push it to github,", "then ask chat gpt."),
                rawTexts = listOf("push it to github", "then ask chat gpt"), speech = true,
            )
        }

        assertThat(calls).containsAtLeast(
            "saveStaged(s1,push it to github then ask chat gpt,Push it to GitHub, then ask ChatGPT.)",
            "insert(s1,Push it to GitHub, then ask ChatGPT.,true)",
        ).inOrder()
    }

    // Retry and history Transcribe come back through onQueueDone too, and read the list then: an edit made while one
    // runs applies to it.
    @Test
    fun customWordsApplyToRetryAndHistoryTranscribe() {
        transcribingTake()
        controller.onEvent(TranscriptFailed("s1", ENGINE_CRASHED))
        controller.onChip(RETRY)
        words = listOf("GitHub")

        assertThat(callsOf { queueDone("s1", "on github") })
            .containsAtLeast("saveStaged(s1,on github,on GitHub)", "insert(s1,on GitHub,true)").inOrder()

        controller.startRetranscribe("s9", insertAfter = false)
        assertThat(callsOf { queueDone("s9", "on github") }).containsExactly("saveRetranscription(s9,on github,on GitHub)")
    }

    // Only text that is kept reads the custom words (and with them Settings). A take, an Undo, a Retry and a history
    // Transcribe read them once each; a second report, a stale or deleted take, or a take Silero heard no speech in,
    // never.
    @Test
    fun customWordsAreReadOnlyForKeptText() {
        words = listOf("GitHub")
        val take = transcribingTake()
        queueDone(take, "on github")
        queueDone(take, "on github") // a second report, for a take already inserting
        queueDone("old", "on github")
        assertThat(wordReads).isEqualTo(1)
        controller.onEvent(InsertDone(take, Outcome.INSERTED, null))

        controller.onQueueDone(transcribingTake(), listOf(""), listOf(""), speech = false)
        controller.onQueueDone(transcribingTake(), listOf(" "), listOf(" "), speech = true) // heard, but no words
        assertThat(wordReads).isEqualTo(1)

        val cancelled = lockedTake()
        controller.onChip(CANCEL)
        controller.onChip(UNDO)
        queueDone(cancelled, "on github")
        val failed = transcribingTake()
        controller.onEvent(TranscriptFailed(failed, ENGINE_CRASHED))
        controller.onChip(RETRY)
        queueDone(failed, "on github")
        controller.startRetranscribe("s9", insertAfter = false)
        queueDone("s9", "on github")
        queueDone("s9", "on github")
        controller.startRetranscribe("s8", insertAfter = false)
        controller.forget("s8")
        queueDone("s8", "on github")
        assertThat(wordReads).isEqualTo(4)
        assertThat(ports.calls.count { it.startsWith("save") && it.endsWith(",on GitHub)") }).isEqualTo(4)
    }

    @Test
    fun staleQueueResultIgnored() {
        holdTake()
        assertThat(callsOf { queueDone("old", "x") }).isEmpty()
    }

    @Test
    fun chunkResultDuringRecordingIsNotLost() {
        lockedTake()
        assertThat(callsOf { controller.onEvent(ChunkDone("s1", 1, null)) }).isEmpty()
        assertThat(controller.state).isEqualTo(State.Recording("s1", Mode.LOCKED))

        stopTap()
        controller.onEvent(TailDone("s1", true, 400_000))
        assertThat(controller.state).isEqualTo(State.Transcribing("s1", done = 1, samples = 400_000))
        assertThat(ports.calls.count { it == render(Processing(false, 1, null)) }).isEqualTo(1)

        controller.onEvent(ChunkDone("s1", 2, 2))
        assertThat(controller.state).isEqualTo(State.Transcribing("s1", done = 2, total = 2, samples = 400_000))
    }

    @Test
    fun keptLoadingDroppedAfterLaterChunk() {
        lockedTake()
        controller.onEvent(ModelLoading("s1"))
        controller.onEvent(ChunkDone("s1", 1, null))
        stopTap()
        controller.onEvent(TailDone("s1", true, 400_000))
        assertThat(controller.state).isEqualTo(State.Transcribing("s1", loadingModel = false, done = 1, samples = 400_000))
        assertThat(ports.calls.filter { it.startsWith("render(Processing(loadingModel=true") }).isEmpty()
        controller.onChip(CANCEL)

        val second = lockedTake()
        controller.onEvent(ModelLoading(second)) // still loading at the stop
        stopTap()
        assertThat(callsOf { controller.onEvent(TailDone(second, true, 400_000)) })
            .contains(render(Processing(true, 0, null)))
    }

    // The first short take after an idle unload: the load ends while the user speaks, before any chunk.
    @Test
    fun keptLoadingDroppedWhenTheLoadFinishes() {
        lockedTake()
        controller.onEvent(ModelLoading("s1"))
        controller.onModelLoaded("old") // another take's load changes nothing here
        controller.onModelLoaded("s1")
        stopTap()
        controller.onEvent(TailDone("s1", true, 400_000))

        assertThat(controller.state).isEqualTo(State.Transcribing("s1", samples = 400_000))
        assertThat(ports.calls.filter { it.startsWith("render(Processing(loadingModel=true") }).isEmpty()
    }

    @Test
    fun replayRendersTheLatestEvent() {
        lockedTake()
        controller.onEvent(ChunkDone("s1", 1, null))
        controller.onEvent(ModelLoading("s1"))   // a reload after that chunk, still running at the stop
        stopTap()
        controller.onEvent(TailDone("s1", true, 400_000))
        assertThat(controller.state).isEqualTo(State.Transcribing("s1", loadingModel = true, done = 1, samples = 400_000))
        assertThat(lastRender()).isEqualTo(render(Processing(true, 1, null)))
    }

    @Test
    fun retranscribeFailureIsReportedOnce() {
        controller.startRetranscribe("s9", insertAfter = true)
        assertThat(callsOf { controller.onEvent(TranscriptFailed("s9", NO_MODEL)) }).containsExactly(
            "saveOutcome(s9,FAILED,NO_MODEL)", render(Chip(NO_MODEL, listOf(RETRY))),
        ).inOrder()
        assertThat(callsOf { controller.onEvent(TranscriptFailed("s9", NO_MODEL)) }).isEmpty()
    }

    @Test
    fun chunkFailureDuringRecordingFailsAfterStop() {
        lockedTake()
        assertThat(callsOf { controller.onEvent(TranscriptFailed("s1", ENGINE_CRASHED)) }).isEmpty()
        assertThat(controller.state).isEqualTo(State.Recording("s1", Mode.LOCKED))

        stopTap()
        val calls = callsOf { controller.onEvent(TailDone("s1", true, 400_000)) }
        assertThat(calls).containsAtLeast(
            "finishTranscription(s1)", "saveOutcome(s1,FAILED,ENGINE_CRASHED)", "haptic(REJECT)",
        ).inOrder()
        assertThat(lastRender()).isEqualTo(render(Chip(ENGINE_CRASHED, listOf(RETRY))))
    }

    @Test
    fun takeLimitStops() {
        holdTake()
        assertThat(callsOf { controller.onTimer("s1", TimerKind.TAKE_LIMIT) }).contains("startTail(s1)")
    }

    @Test
    fun stopReasonIsSavedWithTheOutcome() {
        holdTake()
        controller.onTimer("s1", TimerKind.TAKE_LIMIT)
        controller.onEvent(TailDone("s1", true, 80_000))
        queueDone("s1", "Hello there.")
        controller.onEvent(InsertDone("s1", Outcome.NOT_INSERTED, null))
        assertThat(ports.calls).contains("saveOutcome(s1,NOT_INSERTED,TAKE_LIMIT)")
        // The chip still describes the insert.
        assertThat(lastRender()).isEqualTo(render(Chip(MAY_NOT_HAVE_LANDED, listOf(COPY, INSERT_HERE))))

        // A capture failure is a stop reason too. An outcome's own code wins; a later result without one keeps the reason.
        holdTake()
        controller.onEvent(CaptureFailed("s2", STORAGE_FULL))
        controller.onEvent(TailDone("s2", true, 80_000))
        queueDone("s2", "Hello there.")
        controller.onEvent(InsertDone("s2", Outcome.NOT_INSERTED, TARGET_CHANGED))
        assertThat(ports.calls).contains("saveOutcome(s2,NOT_INSERTED,TARGET_CHANGED)")
        controller.onChip(INSERT_HERE)
        assertThat(callsOf { controller.onEvent(InsertDone("s2", Outcome.INSERTED, null)) })
            .contains("saveOutcome(s2,INSERTED,STORAGE_FULL)")
    }

    @Test
    fun warningKeepsRecording() {
        holdTake()
        assertThat(callsOf { controller.onTimer("s1", TimerKind.TAKE_WARNING) })
            .containsExactly(render(Chip(TAKE_ENDS_SOON, listOf(DISMISS))))
        assertThat(controller.state).isEqualTo(State.Recording("s1", Mode.HOLD))

        now = 1_000
        assertThat(callsOf { controller.onLevel("s1", 0.5f) }).isEmpty()
        now = 1_600
        assertThat(callsOf { controller.onLevel("s1", 0.5f) })
            .containsExactly(render(BubbleUi.Recording(level = 0.5f, locked = false, elapsedMs = 1_600)))
    }

    @Test
    fun silentMicWarningKeepsRecording() {
        holdTake()
        assertThat(callsOf { controller.onWarning("s1", MIC_SILENT) })
            .containsExactly(render(Chip(MIC_SILENT, listOf(DISMISS))))
        assertThat(controller.state).isEqualTo(State.Recording("s1", Mode.HOLD))
        assertThat(callsOf { controller.onWarning("old", MIC_SILENT) }).isEmpty()
    }

    @Test
    fun lockedSilenceStops() {
        lockedTake()
        ports.msSinceLastSpeech = 60_000
        val first = callsOf { controller.onTimer("s1", TimerKind.SILENCE_CHECK) }
        assertThat(first).contains("schedule(s1,SILENCE_CHECK,10000)")
        assertThat(first).doesNotContain("startTail(s1)")

        ports.msSinceLastSpeech = 120_000
        assertThat(callsOf { controller.onTimer("s1", TimerKind.SILENCE_CHECK) }).contains("startTail(s1)")
    }

    @Test
    fun foregroundDeniedFailsTheTake() {
        controller.onTouch(TouchOutput.Press)
        assertThat(callsOf { controller.onForegroundDenied() }).containsExactly(
            "stopCapture(s1,false)", "stopForeground(s1)", "abandonAudioFocus", "cancelTimers(s1)", "deleteSession(s1)",
            render(Chip(FOREGROUND_DENIED, listOf(DISMISS))),
        ).inOrder()
        assertThat(controller.state).isEqualTo(State.Idle)
        assertThat(callsOf { controller.onForegroundDenied() }).isEmpty()

        // A late report that the service started opens nothing.
        controller.onForegroundStarted()
        assertThat(ports.calls.filter { it.startsWith("startCapture") }).isEmpty()
    }

    @Test
    fun foregroundStoppedStopsTake() {
        holdTake()
        assertThat(callsOf { controller.onForegroundStopped() }).contains("startTail(s1)")
    }

    @Test
    fun foregroundStoppedInArmingFailsTheTake() {
        press()
        assertThat(callsOf { controller.onForegroundStopped() }).containsExactly(
            "stopCapture(s1,false)", "stopForeground(s1)", "abandonAudioFocus", "cancelTimers(s1)", "deleteSession(s1)",
            render(Chip(FOREGROUND_STOPPED, listOf(DISMISS))),
        ).inOrder()
        assertThat(controller.state).isEqualTo(State.Idle)

        // An earlier take's service can end after a new press, before the new one reports: not this take's service.
        controller.onTouch(TouchOutput.Press)
        assertThat(callsOf { controller.onForegroundStopped() }).isEmpty()
        assertThat(callsOf { controller.onForegroundStarted() }).containsExactly("startCapture(s2)", "requestAudioFocus").inOrder()
    }

    // The bubble goes with the service, so nothing else could stop a locked take. It stops now and keeps its audio; its
    // text is saved and waits in history, never typed in. A take still arming is discarded.
    @Test
    fun serviceGoneStopsTheTakeAndHoldsBackItsText() {
        lockedTake()
        assertThat(callsOf { controller.onServiceGone() })
            .containsExactly("startTail(s1)", render(Processing(false, 0, null))).inOrder()
        assertThat(callsOf { controller.onEvent(TailDone("s1", true, 80_000)) })
            .containsAtLeast("stopForeground(s1)", "finishTranscription(s1)").inOrder()
        assertThat(callsOf { queueDone("s1", "Hello there.") })
            .containsAtLeast("saveStaged(s1,Hello there.,Hello there.)", "insert(s1,Hello there.,false)").inOrder()
        controller.onEvent(InsertDone("s1", Outcome.NOT_INSERTED, HELD_BACK))

        press()
        assertThat(callsOf { controller.onServiceGone() }).containsExactly(
            "stopCapture(s2,false)", "stopForeground(s2)", "abandonAudioFocus", "cancelTimers(s2)", "deleteSession(s2)",
            render(BubbleUi.Idle),
        ).inOrder()
    }

    @Test
    fun emptyTakeCleansUp() {
        holdTake()
        controller.onTouch(TouchOutput.Release(400))
        assertThat(callsOf { controller.onEvent(TailDone("s1", false, 8_000)) }).containsExactly(
            "stopForeground(s1)", "abandonAudioFocus", "cancelTimers(s1)", "deleteSession(s1)",
            render(Chip(NO_SPEECH, listOf(DISMISS))),
        ).inOrder()
        assertThat(controller.state).isEqualTo(State.Idle)
    }

    @Test
    fun chipCopyUsesStagedText() {
        insertingTake()
        controller.onEvent(InsertDone("s1", Outcome.NOT_INSERTED, TARGET_CHANGED))
        assertThat(lastRender()).isEqualTo(render(Chip(TARGET_CHANGED, listOf(INSERT_HERE, COPY))))
        assertThat(callsOf { controller.onChip(COPY) })
            .containsExactly("copy(Hello there.)", render(Chip(COPIED, listOf(DISMISS)))).inOrder()
    }

    @Test
    fun chipListsFollowTheOutcome() {
        insertingTake()
        controller.onEvent(InsertDone("s1", Outcome.UNVERIFIED, MAY_NOT_HAVE_LANDED))
        assertThat(lastRender()).isEqualTo(render(Chip(MAY_NOT_HAVE_LANDED, listOf(COPY))))

        insertingTake()
        controller.onEvent(InsertDone("s2", Outcome.NOT_INSERTED, PASSWORD_TARGET))
        assertThat(lastRender()).isEqualTo(render(Chip(PASSWORD_TARGET, listOf(COPY))))

        insertingTake()
        controller.onEvent(InsertDone("s3", Outcome.NOT_INSERTED, NO_TARGET))
        assertThat(lastRender()).isEqualTo(render(Chip(NO_TARGET, listOf(COPY, INSERT_HERE))))
    }

    @Test
    fun insertHereResultIsSaved() {
        insertingTake()
        controller.onEvent(InsertDone("s1", Outcome.NOT_INSERTED, TARGET_CHANGED))
        assertThat(callsOf { controller.onChip(INSERT_HERE) }).containsExactly("insertHere(s1,Hello there.)")

        assertThat(controller.state).isEqualTo(State.Idle)
        assertThat(callsOf { controller.onEvent(InsertDone("s1", Outcome.INSERTED, null)) }).containsExactly(
            "saveOutcome(s1,INSERTED,null)", "haptic(CONFIRM)", render(BubbleUi.Idle),
        ).inOrder()
    }

    @Test
    fun lateResultLeavesTheLiveTakeAlone() {
        insertingTake()
        controller.onEvent(InsertDone("s1", Outcome.NOT_INSERTED, TARGET_CHANGED))
        controller.onChip(INSERT_HERE)
        controller.startRetranscribe("s9", insertAfter = true)
        controller.startRetranscribe("s8", insertAfter = false)
        holdTake()

        assertThat(callsOf { controller.onEvent(InsertDone("s1", Outcome.INSERTED, null)) })
            .containsExactly("saveOutcome(s1,INSERTED,null)")
        assertThat(callsOf { controller.onEvent(TranscriptFailed("s9", NO_MODEL)) })
            .containsExactly("saveOutcome(s9,FAILED,NO_MODEL)")
        assertThat(callsOf { queueDone("s8", "") }).containsExactly("saveRetranscription(s8,,)") // no chip while s2 records
        assertThat(controller.state).isEqualTo(State.Recording("s2", Mode.HOLD))
        assertThat(callsOf { controller.onLevel("s2", 0.5f) })
            .containsExactly(render(BubbleUi.Recording(level = 0.5f, locked = false, elapsedMs = 0)))
    }

    @Test
    fun insertHereActsOnce() {
        insertingTake()
        controller.onEvent(InsertDone("s1", Outcome.NOT_INSERTED, TARGET_CHANGED))
        assertThat(callsOf {
            controller.onChip(INSERT_HERE)
            controller.onChip(INSERT_HERE)
        }).containsExactly("insertHere(s1,Hello there.)")
    }

    // The chip stays drawn until its result redraws it; a tap in between does nothing.
    @Test
    fun undoAndRetryActOnceUntilTheResultRedraws() {
        lockedTake()
        controller.onChip(CANCEL)
        val undo = callsOf {
            controller.onChip(UNDO)
            queueDone("s1", "Hi.")
            controller.onChip(UNDO)
        }
        assertThat(undo.filter { it.startsWith("retranscribe") }).containsExactly("retranscribe(s1)")

        val failed = transcribingTake()
        controller.onEvent(TranscriptFailed(failed, ENGINE_CRASHED))
        val retry = callsOf {
            controller.onChip(RETRY)
            queueDone(failed, "Hi.")
            controller.onChip(RETRY)
        }
        assertThat(retry.filter { it.startsWith("retranscribe") }).containsExactly("retranscribe($failed)")
    }

    // After Undo the chip stays drawn but inert until the result redraws it. Its own dismissal timer, still running, must
    // not take it down: with no field focused that hides the bubble, and the result chip would show in a hidden window.
    @Test
    fun inertChipIgnoresItsOwnDismissal() {
        lockedTake()
        controller.onChip(CANCEL)
        controller.onChip(UNDO)

        assertThat(callsOf { controller.onChip(DISMISS) }).isEmpty() // the Undo chip's 5 s timer, before the result
        queueDone("s1", "Hi.")
        assertThat(callsOf { controller.onEvent(InsertDone("s1", Outcome.UNVERIFIED, MAY_NOT_HAVE_LANDED)) })
            .contains(render(Chip(MAY_NOT_HAVE_LANDED, listOf(COPY))))
        // The result chip is active: its dismissal still clears the bubble.
        assertThat(callsOf { controller.onChip(DISMISS) }).containsExactly(render(BubbleUi.Idle))
    }

    // History asked to transcribe a failed take while its Retry chip still shows. A Retry tapped then is refused, and the
    // chip must stay live, or it would stay up for good: the saved result clears the chip of its take, whose
    // "Transcription stopped" no longer holds.
    @Test
    fun refusedRetryKeepsItsChipLive() {
        transcribingTake()
        controller.onEvent(TranscriptFailed("s1", ENGINE_CRASHED))
        controller.startRetranscribe("s1", insertAfter = false) // History Transcribe of the same take
        assertThat(callsOf { controller.onChip(RETRY) }).isEmpty() // refused: that transcription still runs

        assertThat(callsOf { queueDone("s1", "Hi.") }).contains(render(BubbleUi.Idle))
    }

    @Test
    fun chipCancelCancels() {
        lockedTake()
        assertThat(callsOf { controller.onChip(CANCEL) }).containsExactly(
            "stopCapture(s1,true)", "stopForeground(s1)", "abandonAudioFocus", "cancelTimers(s1)", "abortEngine(s1)",
            "saveOutcome(s1,CANCELLED,null)", render(Chip(CANCELLED, listOf(UNDO))),
        ).inOrder()
    }

    @Test
    fun undoRetranscribesAndInserts() {
        lockedTake()
        controller.onChip(CANCEL)
        assertThat(lastRender()).isEqualTo(render(Chip(CANCELLED, listOf(UNDO))))

        assertThat(callsOf { controller.onChip(UNDO) }).containsExactly("retranscribe(s1)")
        assertThat(callsOf { queueDone("s1", "Hi.") })
            .containsExactly("saveStaged(s1,Hi.,Hi.)", "markInserting(s1)", "insert(s1,Hi.,true)").inOrder()
    }

    @Test
    fun retryRetranscribes() {
        transcribingTake()
        controller.onEvent(TranscriptFailed("s1", ENGINE_CRASHED))
        assertThat(lastRender()).isEqualTo(render(Chip(ENGINE_CRASHED, listOf(RETRY))))

        assertThat(callsOf { controller.onChip(RETRY) }).containsExactly("retranscribe(s1)")
        assertThat(controller.state).isEqualTo(State.Idle)
        assertThat(callsOf { controller.onEvent(TranscriptFailed("s1", ENGINE_CRASHED)) }).containsExactly(
            "saveOutcome(s1,FAILED,ENGINE_CRASHED)", render(Chip(ENGINE_CRASHED, listOf(RETRY))),
        ).inOrder()
    }

    @Test
    fun doubleRetryRetranscribesOnce() {
        transcribingTake()
        controller.onEvent(TranscriptFailed("s1", ENGINE_CRASHED))
        assertThat(callsOf {
            controller.onChip(RETRY)
            controller.onChip(RETRY)
        }).containsExactly("retranscribe(s1)")

        // The first report clears the id, so the next Retry runs again.
        controller.onEvent(TranscriptFailed("s1", ENGINE_CRASHED))
        assertThat(callsOf { controller.onChip(RETRY) }).containsExactly("retranscribe(s1)")

        // History asks through startRetranscribe: a second request for an id in flight is ignored.
        controller.startRetranscribe("s9", insertAfter = false)
        assertThat(callsOf { controller.startRetranscribe("s9", insertAfter = false) }).isEmpty()
    }

    @Test
    fun historyRetranscribeDoesNotInsert() {
        controller.startRetranscribe("s9", insertAfter = false)
        queueDone("s9", "Hi.")
        assertThat(ports.calls).containsExactly("retranscribe(s9)", "saveRetranscription(s9,Hi.,Hi.)").inOrder()
    }

    // A long-lived accessibility process must not keep every transcript: only the last chip's take can still use its text.
    @Test
    fun finishedTakesLeaveAtMostOneText() {
        repeat(20) {
            val id = holdTake()
            controller.onTimer(id, TimerKind.TAKE_LIMIT) // a stop reason as well
            controller.onEvent(TailDone(id, true, 80_000))
            queueDone(id, "Hello there.")
            controller.onEvent(InsertDone(id, if (it % 2 == 0) Outcome.INSERTED else Outcome.NOT_INSERTED, null))
        }

        assertThat(controller.staged.size).isAtMost(1)
        assertThat(controller.stopReasons.size).isAtMost(1)
        assertThat(callsOf { controller.onChip(COPY) }).contains("copy(Hello there.)")
    }

    // Insert here, or the insert after an Undo, still runs when the chip's timer dismisses the chip: its result keeps
    // the take's text and stop reason.
    @Test
    fun actionInFlightKeepsItsTake() {
        holdTake()
        controller.onTimer("s1", TimerKind.TAKE_LIMIT)
        controller.onEvent(TailDone("s1", true, 80_000))
        queueDone("s1", "Hello there.")
        controller.onEvent(InsertDone("s1", Outcome.NOT_INSERTED, TARGET_CHANGED))
        controller.onChip(INSERT_HERE)
        controller.onChip(DISMISS)
        assertThat(callsOf { controller.onEvent(InsertDone("s1", Outcome.UNVERIFIED, null)) })
            .contains("saveOutcome(s1,UNVERIFIED,TAKE_LIMIT)")
        assertThat(callsOf { controller.onChip(COPY) }).contains("copy(Hello there.)")

        val cancelled = lockedTake()
        controller.onChip(CANCEL)
        controller.onChip(UNDO)
        queueDone(cancelled, "Hi.")
        controller.onChip(DISMISS)
        controller.onEvent(InsertDone(cancelled, Outcome.UNVERIFIED, null))
        assertThat(callsOf { controller.onChip(COPY) }).contains("copy(Hi.)")
    }

    // A delete from history: the take's chip goes, so neither Copy nor Insert here can bring the deleted text back.
    @Test
    fun forgetDropsTheTakeAndItsChip() {
        holdTake()
        controller.onTimer("s1", TimerKind.TAKE_LIMIT)
        controller.onEvent(TailDone("s1", true, 80_000))
        queueDone("s1", "Hello there.")
        controller.onEvent(InsertDone("s1", Outcome.NOT_INSERTED, NO_TARGET))
        assertThat(lastRender()).isEqualTo(render(Chip(NO_TARGET, listOf(COPY, INSERT_HERE))))

        // The ports drop the session too, its queue work included.
        assertThat(callsOf { controller.forget("s1") }).containsExactly("deleteSession(s1)", render(BubbleUi.Idle)).inOrder()
        assertThat(callsOf {
            controller.onChip(COPY)
            controller.onChip(INSERT_HERE)
        }).isEmpty()
        assertThat(controller.staged).isEmpty()
        assertThat(controller.stopReasons).isEmpty()
        assertThat(callsOf { controller.forget("s9") }).containsExactly("deleteSession(s9)") // no chip of its own
    }

    @Test
    fun busyPressIsRejected() {
        transcribingTake()
        assertThat(callsOf { controller.onTouch(TouchOutput.Press) }).containsExactly("newSessionId", "haptic(REJECT)").inOrder()

        controller.onTouch(TouchOutput.Release(120))
        queueDone("s1", "Hi.")
        controller.onEvent(InsertDone("s1", Outcome.INSERTED, null))
        assertThat(ports.calls).containsNoneOf("createRow(s2)", "startForeground(s2)")
    }
}
