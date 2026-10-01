import Foundation
import Testing
@testable import TFCore

fileprivate let s1 = UUID(uuidString: "00000000-0000-0000-0000-0000000000A1") ?? UUID() // the live take
fileprivate let s2 = UUID(uuidString: "00000000-0000-0000-0000-0000000000A2") ?? UUID() // a new press
fileprivate let s0 = UUID(uuidString: "00000000-0000-0000-0000-0000000000A0") ?? UUID() // an earlier take

/// Reduces `event` from `from`, checks the next state and the effects in order, and returns the next machine.
@discardableResult
fileprivate func step(_ from: TakeReducer, _ event: TakeEvent, _ state: TakeState, _ effects: TakeEffect...,
                      sourceLocation: SourceLocation = #_sourceLocation) -> TakeReducer {
    let (next, out) = from.reduce(event)
    #expect(next.state == state, sourceLocation: sourceLocation)
    #expect(out == effects, sourceLocation: sourceLocation)
    return next
}

fileprivate func at(_ state: TakeState, pressedAtMs: Int? = 0) -> TakeReducer { TakeReducer(state: state, pressedAtMs: pressedAtMs) }

// The rows of the take table (Android's state table adapted to keyboards and the app) and the contract's Delivery
// table.
@Suite struct TakeReducerTests {
    let chunk = Chunk(start: 0, end: 160_000, mayHoldSpeech: true)
    let hello = Transcript("Hello")
    let delivering = TakeState.delivering(s1, reason: nil, began: false)

    @Test func aPressCreatesTheTakeAndStartsCapture() {
        step(TakeReducer(), .press(s1, atMs: 0), .arming(s1, lockOnReady: false), .createTake(s1), .startTakeCapture(s1))
    }

    @Test func firstAudioWhileHeldRecordsAHold() {
        step(at(.arming(s1, lockOnReady: false)), .firstAudio(s1), .recording(s1, .hold, warned: false))
    }

    @Test func aTapBeforeTheFirstAudioLocksOnReady() {
        let locking = step(at(.arming(s1, lockOnReady: false)), .release(s1, atMs: 120), .arming(s1, lockOnReady: true))
        step(locking, .firstAudio(s1), .recording(s1, .locked, warned: false))
    }

    // The 300 ms rule: 299 ms is a tap, 300 ms is a hold, timed from the press.
    @Test func aHoldReleasedBeforeAnyAudioIsMicNotReady() {
        step(at(.arming(s1, lockOnReady: false)), .release(s1, atMs: 299), .arming(s1, lockOnReady: true))
        step(at(.arming(s1, lockOnReady: false)), .release(s1, atMs: 300), .idle, .discard(s1), .message(.micNotReady))
        step(at(.arming(s1, lockOnReady: false), pressedAtMs: 1_000), .release(s1, atMs: 1_400), .idle, .discard(s1), .message(.micNotReady))
    }

    @Test func aStopTapBeforeAnyAudioIsMicNotReady() {
        step(at(.arming(s1, lockOnReady: true)), .press(s2, atMs: 900), .idle, .discard(s1), .message(.micNotReady))
    }

    @Test func armingEndsWithNoTrace() {
        step(at(.arming(s1, lockOnReady: false)), .cancel(s1), .idle, .discard(s1))
        step(at(.arming(s1, lockOnReady: true)), .captureFailed(s1, .micPermission), .idle, .discard(s1), .message(.micPermission))
        step(at(.arming(s1, lockOnReady: false)), .sessionEnded(.call), .idle, .discard(s1), .message(.call))
        step(at(.arming(s1, lockOnReady: false)), .sessionEnded(nil), .idle, .discard(s1))
    }

    @Test func aHoldReleasedAt300msStops() {
        step(at(.recording(s1, .hold, warned: false)), .release(s1, atMs: 300), .stopping(s1, reason: nil), .startStopTail(s1))
        step(at(.recording(s1, .hold, warned: false)), .release(s1, atMs: 299), .recording(s1, .locked, warned: false))
    }

    @Test func aPressStopsALockedTake() {
        step(at(.recording(s1, .locked, warned: false)), .press(s2, atMs: 5_000), .stopping(s1, reason: nil), .startStopTail(s1))
    }

    // A press during a hold means its release was lost (the keyboard went away, or the app opened from a cold start):
    // the tap stops the take instead of leaving it recording with no way to stop it.
    @Test func aPressDuringAHoldStopsIt() {
        step(at(.recording(s1, .hold, warned: false)), .press(s2, atMs: 5_000), .stopping(s1, reason: nil), .startStopTail(s1))
    }

    // The release after a stop tap does nothing while the tail records.
    @Test func theReleaseAfterAStopTapIsIgnored() {
        let stopping = step(at(.recording(s1, .locked, warned: false)), .press(s1, atMs: 5_000), .stopping(s1, reason: nil), .startStopTail(s1))
        step(stopping, .release(s1, atMs: 5_600), .stopping(s1, reason: nil))
    }

    // Command files can arrive late (another keyboard, a slow write): a press or release sent before the last one
    // handled belongs to an earlier gesture and changes nothing.
    @Test func lateTouchesAreIgnored() {
        let recording = at(.recording(s1, .locked, warned: false), pressedAtMs: 10_000)
        #expect(recording.reduce(.press(s2, atMs: 9_000)) == (recording, []))
        #expect(recording.reduce(.release(s1, atMs: 9_500)) == (recording, []))
        let hold = at(.recording(s1, .hold, warned: false), pressedAtMs: 10_000)
        #expect(hold.reduce(.release(s1, atMs: 10_000)) == (hold, [])) // the same instant: a replay
    }

    @Test func theTakeLimitWarnsAt14MinutesAndStopsAt15() {
        let warned = step(at(.recording(s1, .hold, warned: false)), .tick(s1, recordedMs: 840_000, msSinceSpeech: 0),
                          .recording(s1, .hold, warned: true), .message(.takeEndsSoon))
        step(warned, .tick(s1, recordedMs: 841_000, msSinceSpeech: 0), .recording(s1, .hold, warned: true)) // once
        step(warned, .tick(s1, recordedMs: 900_000, msSinceSpeech: 0), .stopping(s1, reason: .takeLimit),
             .startStopTail(s1), .message(.takeLimit))
        step(at(.recording(s1, .hold, warned: false)), .tick(s1, recordedMs: 839_999, msSinceSpeech: 0), .recording(s1, .hold, warned: false))
    }

    // Any recording take stops after 2 minutes without speech, a hold too: its release can be lost.
    @Test func aTakeStopsAfter2MinutesWithoutSpeech() {
        step(at(.recording(s1, .locked, warned: false)), .tick(s1, recordedMs: 130_000, msSinceSpeech: 120_000),
             .stopping(s1, reason: .lockedSilence), .startStopTail(s1), .message(.lockedSilence))
        step(at(.recording(s1, .locked, warned: false)), .tick(s1, recordedMs: 130_000, msSinceSpeech: 119_999),
             .recording(s1, .locked, warned: false))
        step(at(.recording(s1, .hold, warned: false)), .tick(s1, recordedMs: 130_000, msSinceSpeech: 120_000),
             .stopping(s1, reason: .lockedSilence), .startStopTail(s1), .message(.lockedSilence))
    }

    // The capture ends with a failure or the session: the take keeps its audio and waits for tailDone.
    @Test func aRecordingTakeKeepsItsAudioWhenCaptureEnds() {
        step(at(.recording(s1, .hold, warned: false)), .captureFailed(s1, .storageFull), .stopping(s1, reason: .storageFull),
             .message(.storageFull))
        step(at(.recording(s1, .locked, warned: false)), .sessionEnded(.call), .stopping(s1, reason: .call), .message(.call))
        step(at(.recording(s1, .locked, warned: false)), .sessionEnded(nil), .stopping(s1, reason: nil))
    }

    @Test func chunksAreTranscribedWhileTheUserTalks() {
        step(at(.recording(s1, .hold, warned: false)), .chunkClosed(s1, chunk), .recording(s1, .hold, warned: false), .transcribeChunk(s1, chunk))
        step(at(.stopping(s1, reason: nil)), .chunkClosed(s1, chunk), .stopping(s1, reason: nil), .transcribeChunk(s1, chunk))
        step(at(.transcribing(s1, reason: nil, samples: 1)), .chunkClosed(s1, chunk), .transcribing(s1, reason: nil, samples: 1))
    }

    @Test func chunkTextIsSavedAsPartialTextUntilTheTranscript() {
        let partial = Transcript("so we")
        for state in [TakeState.recording(s1, .hold, warned: false), .stopping(s1, reason: nil), .transcribing(s1, reason: nil, samples: 1)] {
            step(at(state), .chunkTextReady(s1, partial: partial, chunksDone: 1), state, .savePartial(s1, partial, chunksDone: 1))
        }
        step(at(delivering), .chunkTextReady(s1, partial: partial, chunksDone: 1), delivering)
    }

    @Test func aTailWithALoudChunkIsTranscribed() {
        step(at(.stopping(s1, reason: nil)), .tailDone(s1, samples: 80_000, mayHoldSpeech: true),
             .transcribing(s1, reason: nil, samples: 80_000), .finish(s1))
    }

    // Under 1 s (16,000 samples) a silent take leaves no trace; from 1 s it stays in history as No speech.
    @Test func aSilentTakeUnder1sLeavesNoTrace() {
        for samples in [8_000, 15_999] {
            step(at(.stopping(s1, reason: nil)), .tailDone(s1, samples: samples, mayHoldSpeech: false), .idle,
                 .discard(s1), .message(.noSpeech))
        }
        for samples in [16_000, 32_000] {
            step(at(.stopping(s1, reason: nil)), .tailDone(s1, samples: samples, mayHoldSpeech: false), .idle,
                 .saveOutcome(s1, .noSpeech, error: nil), .message(.noSpeech))
        }
    }

    // Silero decides: a take it heard no speech in ends as a take with nothing loud does.
    @Test func sileroDecidesTheShortTakeRule() {
        step(at(.transcribing(s1, reason: nil, samples: 15_999)), .transcriptReady(s1, Transcript(""), saved: false, speech: false),
             .idle, .discard(s1), .message(.noSpeech))
        step(at(.transcribing(s1, reason: nil, samples: 16_000)), .transcriptReady(s1, Transcript(""), saved: false, speech: false),
             .idle, .saveOutcome(s1, .noSpeech, error: nil), .message(.noSpeech))
    }

    @Test func aBlankTranscriptIsNoSpeech() {
        step(at(.transcribing(s1, reason: nil, samples: 80_000)), .transcriptReady(s1, Transcript("  "), saved: true, speech: true),
             .idle, .saveOutcome(s1, .noSpeech, error: nil), .message(.noSpeech))
    }

    // Text saved, keyboard told: the item is pending and history says staged.
    @Test func savedTextGoesToTheOutboxAsPending() {
        step(at(.transcribing(s1, reason: nil, samples: 80_000)), .transcriptReady(s1, hello, saved: true, speech: true),
             delivering, .writeOutbox(s1, hello, .pending))
    }

    // Text that did not reach history is only offered: the held-back row at once.
    @Test func unsavedTextIsHeldBack() {
        step(at(.transcribing(s1, reason: nil, samples: 80_000)), .transcriptReady(s1, hello, saved: false, speech: true),
             .idle, .writeOutbox(s1, hello, .heldBack), .saveOutcome(s1, .notInserted, error: .historyWriteFailed),
             .message(.historyWriteFailed))
    }

    // The contract's Delivery table, row by row.
    @Test func theDeliveryTable() {
        #expect(DeliveryTable.row(for: .began) == DeliveryTable.Row(status: .inserting, outbox: .pending, ends: false))
        #expect(DeliveryTable.row(for: .confirmed) == DeliveryTable.Row(status: .inserted, outbox: .typed, ends: true))
        #expect(DeliveryTable.row(for: .unverified) == DeliveryTable.Row(status: .unverified, outbox: .unverified, ends: true))
        #expect(DeliveryTable.row(for: .heldBack) == DeliveryTable.Row(status: .notInserted, outbox: .heldBack, ends: true))
        #expect(DeliveryTable.noAnswer(began: false) == DeliveryTable.Row(status: .notInserted, outbox: .pending, ends: true))
        #expect(DeliveryTable.noAnswer(began: true) == DeliveryTable.Row(status: .needsReview, outbox: .unverified, ends: true))
        #expect(DeliveryTable.timeoutMs == 3_000)
    }

    @Test func theKeyboardsReportsEndTheTake() {
        let began = step(at(delivering), .insertion(s1, .began), .delivering(s1, reason: nil, began: true), .markInserting(s1))
        step(began, .insertion(s1, .confirmed), .idle, .setOutboxState(s1, .typed), .saveOutcome(s1, .inserted, error: nil))
        step(began, .insertion(s1, .unverified), .idle, .setOutboxState(s1, .unverified), .saveOutcome(s1, .unverified, error: nil))
        step(at(delivering), .insertion(s1, .heldBack), .idle, .setOutboxState(s1, .heldBack), .saveOutcome(s1, .notInserted, error: nil))
    }

    // No answer within 3 s: nothing began, so not inserted and the item stays pending; began, so it may already be in
    // the field.
    @Test func noAnswerEndsTheTake() {
        step(at(delivering), .deliveryTimedOut(s1), .idle, .setOutboxState(s1, .pending), .saveOutcome(s1, .notInserted, error: nil))
        step(at(.delivering(s1, reason: nil, began: true)), .deliveryTimedOut(s1), .idle,
             .setOutboxState(s1, .unverified), .saveOutcome(s1, .needsReview, error: nil))
    }

    // An outcome without a message of its own keeps the reason the take stopped, so history shows it.
    @Test func theStopReasonGoesToHistory() {
        step(at(.stopping(s1, reason: .takeLimit)), .tailDone(s1, samples: 32_000, mayHoldSpeech: false), .idle,
             .saveOutcome(s1, .noSpeech, error: .takeLimit), .message(.noSpeech))
        let transcribing = step(at(.stopping(s1, reason: .call)), .tailDone(s1, samples: 32_000, mayHoldSpeech: true),
                                .transcribing(s1, reason: .call, samples: 32_000), .finish(s1))
        let told = step(transcribing, .transcriptReady(s1, hello, saved: true, speech: true), .delivering(s1, reason: .call, began: false),
                        .writeOutbox(s1, hello, .pending))
        step(told, .insertion(s1, .confirmed), .idle, .setOutboxState(s1, .typed), .saveOutcome(s1, .inserted, error: .call))
    }

    @Test func aFailedTranscriptionIsSavedAsFailed() {
        step(at(.transcribing(s1, reason: nil, samples: 80_000)), .transcriptFailed(s1, .engineFailed), .idle,
             .saveOutcome(s1, .failed, error: .engineFailed), .message(.engineFailed))
    }

    // A cancelled take keeps its audio and its history entry; while a keyboard may be typing, cancel does nothing.
    @Test func cancelKeepsTheAudio() {
        step(at(.recording(s1, .locked, warned: false)), .cancel(s1), .idle, .saveOutcome(s1, .cancelled, error: nil))
        step(at(.stopping(s1, reason: .takeLimit)), .cancel(s1), .idle, .saveOutcome(s1, .cancelled, error: .takeLimit))
        step(at(.transcribing(s1, reason: nil, samples: 1)), .cancel(s1), .idle, .saveOutcome(s1, .cancelled, error: nil))
        step(at(delivering), .cancel(s1), delivering)
    }

    // A press while busy never starts a second take, and its release is dropped.
    @Test func aBusyPressNeverStartsASecondTake() {
        var machine = step(at(.transcribing(s1, reason: nil, samples: 80_000)), .press(s2, atMs: 10_000), .transcribing(s1, reason: nil, samples: 80_000))
        machine = step(machine, .release(s2, atMs: 10_050), .transcribing(s1, reason: nil, samples: 80_000))
        machine = step(machine, .transcriptReady(s1, hello, saved: true, speech: true), delivering, .writeOutbox(s1, hello, .pending))
        machine = step(machine, .press(s2, atMs: 11_000), delivering)
        machine = step(machine, .release(s2, atMs: 11_050), delivering)
        machine = step(machine, .insertion(s1, .confirmed), .idle, .setOutboxState(s1, .typed), .saveOutcome(s1, .inserted, error: nil))
        #expect(machine.state == .idle)
    }

    // The final report wins: a began that arrives after it (a slower file) changes nothing.
    @Test func aLateBeganAfterTheFinalReportIsIgnored() {
        let (ended, _) = at(delivering).reduce(.insertion(s1, .confirmed))
        #expect(ended.reduce(.insertion(s1, .began)) == (ended, []))
    }

    // A tap right after the text lands can still name the take (the keyboard has not read the new status). Starting it
    // again would fail in history and then discard the ended take's folder, so the press changes nothing.
    @Test func aPressNamingAnEndedTakeChangesNothing() {
        let (ended, _) = at(delivering).reduce(.insertion(s1, .confirmed))
        #expect(ended.state == .idle)
        #expect(ended.reduce(.press(s1, atMs: 7_000)) == (ended, []))
        step(ended, .press(s2, atMs: 7_000), .arming(s2, lockOnReady: false), .createTake(s2), .startTakeCapture(s2))
    }

    // A crash before the app removes a command file replays it. After every new command, every command so far is
    // handled again, and none of them acts a second time.
    @Test func replayedCommandsAreHarmless() throws {
        let store = SharedStore(directory: FileManager.default.temporaryDirectory.appendingPathComponent("replay-\(UUID().uuidString)"))
        defer { try? FileManager.default.removeItem(at: store.directory) }
        var machine = TakeReducer()
        func handle(_ kind: KeyboardCommand.Kind, atMs ms: Double) throws {
            try store.append(KeyboardCommand(takeID: s1, kind: kind, sentAt: Date(timeIntervalSince1970: 1_790_000_000 + ms / 1_000)))
            let pending = try store.pendingCommands() // nothing is removed: the app crashed each time
            if let newest = pending.last?.event { machine = machine.reduce(newest).0 }
            for replay in pending.compactMap(\.event) {
                #expect(machine.reduce(replay) == (machine, []), "a replay of \(replay) acted")
            }
        }
        try handle(.press, atMs: 0)
        machine = machine.reduce(.firstAudio(s1)).0
        try handle(.release, atMs: 120)
        #expect(machine.state == .recording(s1, .locked, warned: false))
        try handle(.press, atMs: 5_000)
        try handle(.release, atMs: 5_100)
        #expect(machine.state == .stopping(s1, reason: nil))
        machine = machine.reduce(.tailDone(s1, samples: 80_000, mayHoldSpeech: true)).0
        machine = machine.reduce(.transcriptReady(s1, hello, saved: true, speech: true)).0
        try handle(.insertionBegan, atMs: 6_000)
        #expect(machine.state == .delivering(s1, reason: nil, began: true))
        try handle(.insertionConfirmed, atMs: 6_100)
        try handle(.cancel, atMs: 9_000)
        #expect(machine.state == .idle)
    }

    // Events for an earlier take never touch the live one.
    @Test func staleEventsAreIgnored() {
        let cases: [(TakeState, [TakeEvent])] = [
            (.arming(s1, lockOnReady: false), [.firstAudio(s0), .captureFailed(s0, .micPermission), .release(s0, atMs: 1),
                                               .cancel(s0)]),
            (.recording(s1, .locked, warned: false), [.tick(s0, recordedMs: 900_000, msSinceSpeech: 900_000),
                                                     .captureFailed(s0, .storageFull), .chunkClosed(s0, chunk),
                                                     .chunkTextReady(s0, partial: hello, chunksDone: 1), .cancel(s0)]),
            (.stopping(s1, reason: nil), [.tailDone(s0, samples: 8_000, mayHoldSpeech: false), .chunkClosed(s0, chunk)]),
            (.transcribing(s1, reason: nil, samples: 1), [.transcriptReady(s0, hello, saved: true, speech: true),
                                                         .transcriptFailed(s0, .engineFailed)]),
            (delivering, [.insertion(s0, .confirmed), .insertion(s0, .began), .deliveryTimedOut(s0)]),
        ]
        for (state, events) in cases {
            let machine = at(state)
            for event in events { #expect(machine.reduce(event) == (machine, [])) }
        }
    }

    // The queue answers finish; transcript events before it are ignored.
    @Test func queueEventsBeforeFinishAreIgnored() {
        for state in [TakeState.recording(s1, .locked, warned: false), .stopping(s1, reason: nil)] {
            step(at(state), .transcriptReady(s1, hello, saved: true, speech: true), state)
            step(at(state), .transcriptFailed(s1, .engineFailed), state)
        }
    }

    @Test func armingShowsAsRecording() {
        #expect(TakeState.arming(s1, lockOnReady: false).phase == .recording)
        #expect([TakeState.idle, .recording(s1, .hold, warned: false), .stopping(s1, reason: nil),
                 .transcribing(s1, reason: nil, samples: 1), delivering].map(\.phase)
                == [.idle, .recording, .stopping, .transcribing, .delivering])
    }

    // Logging an effect or an event shows how long the text is, never the text.
    @Test func transcriptsNeverPrintTheirText() {
        let effect = TakeEffect.writeOutbox(s1, Transcript("meet me at noon"), .pending)
        var dumped = ""
        dump(effect, to: &dumped)
        for printed in ["\(effect)", String(reflecting: effect), dumped, "\(TakeEvent.transcriptReady(s1, Transcript("meet me at noon"), saved: true, speech: true))"] {
            #expect(!printed.contains("noon"))
            #expect(printed.contains("<15 characters>"))
        }
    }

    @Test func keyboardCommandsBecomeEvents() {
        let sentAt = Date(timeIntervalSince1970: 1_790_000_000.25)
        func event(_ kind: KeyboardCommand.Kind) -> TakeEvent? { KeyboardCommand(takeID: s1, kind: kind, sentAt: sentAt).event }
        #expect(event(.press) == .press(s1, atMs: 1_790_000_000_250))
        #expect(event(.release) == .release(s1, atMs: 1_790_000_000_250))
        #expect(event(.cancel) == .cancel(s1))
        #expect(event(.insertionBegan) == .insertion(s1, .began))
        #expect(event(.insertionConfirmed) == .insertion(s1, .confirmed))
        #expect(event(.insertionUnverified) == .insertion(s1, .unverified))
        #expect(event(.insertionHeldBack) == .insertion(s1, .heldBack))
        #expect(event(.ping) == nil)
    }

    @Test func messagesArePlainEnglish() {
        for message in TakeMessage.allCases {
            #expect(!message.text.isEmpty && !message.text.contains("\u{2014}") && !message.text.contains("\u{2013}"))
            #expect(message.text.hasSuffix(".") || message.text.hasSuffix("?"))
        }
    }
}
