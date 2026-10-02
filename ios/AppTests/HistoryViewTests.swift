import AVFoundation
import Foundation
import Testing
import TFCore
@testable import ThumbFree

@MainActor @Suite struct HistoryViewTests {
    @Test func everyStatusHasItsWord() {
        #expect(TakeStatus.allCases.map(HistoryView.label(for:)) == [
            "In progress", "In progress", "In progress", "In progress",
            "Typed", "Check the field", "Not inserted", "No speech", "Cancelled", "Failed", "Interrupted",
            "May already be in the field",
        ])
    }

    @Test func onlyConfirmedTextIsShown() {
        func record(_ status: TakeStatus) -> TakeRecord {
            TakeRecord(id: UUID(), order: 1, startedAt: Date(), modelID: "parakeet-tdt-0.6b-v2", status: status, text: "Hello.")
        }
        #expect(HistoryView.shownText(record(.inserted)) == "Hello.")
        #expect(HistoryView.shownText(record(.notInserted)) == "Hello.")
        #expect(HistoryView.shownText(record(.needsReview)) == "Hello.")
        #expect(HistoryView.shownText(record(.staged)) == nil)
        #expect(HistoryView.shownText(record(.noSpeech)) == nil)
        #expect(HistoryView.shownText(record(.interrupted)) == nil)
    }

    // The single guard shownText and copyText both use (Android's HistoryScreen has the same mayShowText): a take
    // still live, or one that ended without confirmed speech, never shows or copies text.
    @Test func mayShowTextKeepsLiveNoSpeechAndInterruptedOut() {
        func record(_ status: TakeStatus) -> TakeRecord { TakeRecord(id: UUID(), order: 1, startedAt: Date(), modelID: "m", status: status) }
        #expect(TakeStatus.allCases.map { HistoryView.mayShowText(record($0)) } == [
            false, false, false, false, // recording, transcribing, staged, inserting: live
            true, true, true, // inserted, unverified, notInserted
            false, // noSpeech
            true, true, // cancelled, failed
            false, // interrupted
            true, // needsReview
        ])
    }

    // Android's rule: a typed take shows what was typed; a take transcribed again shows its new text.
    @Test func aTypedTakeShowsWhatWasTypedAndCopyFallsBackToThePartialText() {
        var typed = TakeRecord(id: UUID(), order: 1, startedAt: Date(), modelID: "m", status: .inserted, text: "Hello there.",
                               insertedText: " Hello there. ")
        #expect(HistoryView.shownText(typed) == "Hello there.")
        typed.text = "Hello there, friend."
        typed.retranscribed = true
        #expect(HistoryView.shownText(typed) == "Hello there, friend.")
        let cut = TakeRecord(id: UUID(), order: 2, startedAt: Date(), modelID: "m", status: .failed, partialText: "So far")
        #expect(HistoryView.shownText(cut) == nil)
        #expect(HistoryView.copyText(cut) == "So far")
        let heardNothing = TakeRecord(id: UUID(), order: 3, startedAt: Date(), modelID: "m", status: .noSpeech, partialText: "um")
        #expect(HistoryView.copyText(heardNothing) == nil)
    }

    @Test func searchLooksOnlyAtTextARowShows() {
        let shown = TakeRecord(id: UUID(), order: 1, startedAt: Date(), modelID: "m", status: .notInserted, text: "Book the dentist")
        let hidden = TakeRecord(id: UUID(), order: 2, startedAt: Date(), modelID: "m", status: .interrupted, text: "dentist")
        #expect(HistoryView.matches(shown, " DENTIST "))
        #expect(!HistoryView.matches(hidden, "dentist"))
        #expect(HistoryView.matches(hidden, "  "))
    }

    @Test func takesAreGroupedByDayWithAndroidsTitles() throws {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = try #require(TimeZone(identifier: "America/Los_Angeles"))
        let now = try #require(calendar.date(from: DateComponents(year: 2026, month: 9, day: 27, hour: 10)))
        let yesterday = now.addingTimeInterval(-86_400)
        let older = now.addingTimeInterval(-3 * 86_400)
        func take(_ order: Int, _ date: Date) -> TakeRecord { TakeRecord(id: UUID(), order: order, startedAt: date, modelID: "m") }
        let groups = HistoryView.days([take(4, now), take(3, now.addingTimeInterval(-60)), take(2, yesterday), take(1, older)],
                                      calendar: calendar)
        #expect(groups.map(\.takes.count) == [2, 1, 1])
        #expect(HistoryView.dayTitle(now, now: now, calendar: calendar) == "Today")
        #expect(HistoryView.dayTitle(yesterday, now: now, calendar: calendar) == "Yesterday")
        #expect(HistoryView.dayTitle(older, now: now, calendar: calendar)
                == older.formatted(.dateTime.weekday(.abbreviated).month(.abbreviated).day()))
    }

    @Test func aRowSaysWhenAndHowLong() {
        let start = Date()
        let time = start.formatted(date: .omitted, time: .shortened)
        #expect(HistoryView.timeLine(TakeRecord(id: UUID(), order: 1, startedAt: start, modelID: "m", durationMs: 3_400)) == "\(time) · 0:03")
        #expect(HistoryView.timeLine(TakeRecord(id: UUID(), order: 1, startedAt: start, modelID: "m", durationMs: 3_725_000)) == "\(time) · 1:02:05")
        #expect(HistoryView.timeLine(TakeRecord(id: UUID(), order: 1, startedAt: start, modelID: "m")) == time)
    }

    @Test func clearAllAndTranscribeAgainSayWhatHappens() {
        #expect(HistoryView.clearMessage(1) == "This deletes 1 take and its recording from this iPhone. It can't be undone.")
        #expect(HistoryView.clearMessage(3) == "This deletes 3 takes and their recordings from this iPhone. It can't be undone.")
        #expect(HistoryView.retranscribedNote(.notInserted) == "Transcribed again")
        #expect(HistoryView.retranscribedNote(.inserted) == "Transcribed again. This text wasn't typed in.")
    }

    // The setup card's model row counts as done only once the engine has loaded the model: ready means usable now, so a
    // model still loading or warming up is not done, and its row says so.
    @Test func theSetupCardsModelRowSaysWhatIsLeft() {
        #expect(!SetupRows.modelDone(.missing, engine: .noModel))
        #expect(!SetupRows.modelDone(.downloading(done: 1, total: 2), engine: .noModel))
        #expect(!SetupRows.modelDone(.ready, engine: .failed))
        for engine in [EnginePhase.unloaded, .loading, .warming] { #expect(!SetupRows.modelDone(.ready, engine: engine), "\(engine)") }
        #expect(SetupRows.modelDone(.ready, engine: .readyNeuralEngine))
        #expect(SetupRows.modelDone(.ready, engine: .readyCPU))
        #expect(SetupRows.modelLine(.missing, engine: .noModel, total: 465_000_000) == "Not downloaded · 465 MB")
        #expect(SetupRows.modelLine(.downloading(done: 1, total: 4), engine: .noModel, total: 4) == "Downloading · 25%")
        #expect(SetupRows.modelLine(.ready, engine: .readyNeuralEngine, total: 1) == "Ready")
        #expect(SetupRows.modelLine(.ready, engine: .loading, total: 1) == "Getting ready for this iPhone")
        #expect(SetupRows.modelLine(.ready, engine: .failed, total: 1) == "Could not load the model.")
    }

    // The setup card's header count and its rows must never disagree, so both read one Facts snapshot: doneCount
    // is a pure function of it, not a fresh read of its own.
    @Test func theSetupCardsCountComesFromOneSnapshotNotFreshReads() {
        let allDone = SetupRows.Facts(mic: .granted, keyboard: .ready, model: true)
        #expect(SetupRows.doneCount(allDone) == 3)
        let noneDone = SetupRows.Facts(mic: .denied, keyboard: .notAdded, model: false)
        #expect(SetupRows.doneCount(noneDone) == 0)
        let mixedDone = SetupRows.Facts(mic: .granted, keyboard: .added, model: true)
        #expect(SetupRows.doneCount(mixedDone) == 2)
    }

    // Ready means usable now, on the welcome's last step and on Home alike: the microphone allowed, the keyboard seen with
    // Full Access, and the engine loaded.
    @Test func readyNeedsAllThreeUsableNow() {
        #expect(SetupRows.ready(SetupRows.Facts(mic: .granted, keyboard: .ready, model: true)))
        #expect(!SetupRows.ready(SetupRows.Facts(mic: .undetermined, keyboard: .ready, model: true)))
        #expect(!SetupRows.ready(SetupRows.Facts(mic: .denied, keyboard: .ready, model: true)))
        #expect(!SetupRows.ready(SetupRows.Facts(mic: .granted, keyboard: .added, model: true)))
        #expect(!SetupRows.ready(SetupRows.Facts(mic: .granted, keyboard: .ready, model: false)))
        #expect(!SetupRows.facts(model: .ready, engine: .warming, keyboard: .ready).model) // still warming up
    }

    // Home's top while only the engine's load is left: a quick load of a model loaded before gets the quiet "Getting
    // ready" in the ready chip's place, so the card never flashes; the first load after a download keeps the card, which
    // says it takes a while. Anything else left is the card.
    @Test func homeShowsAQuietLoadInsteadOfFlashingTheCard() {
        func top(mic: AVAudioApplication.recordPermission = .granted, keyboard: KeyboardStatus = .ready,
                 phase: SpeechModel.Phase = .ready, engine: EnginePhase, loadedBefore: Bool = true) -> HomeView.Top {
            HomeView.top(mic: mic, keyboard: keyboard, phase: phase, waiting: nil, engine: engine, loadedBefore: loadedBefore)
        }
        for engine in [EnginePhase.unloaded, .loading, .warming] {
            #expect(top(engine: engine) == .gettingReady, "\(engine)")
            #expect(top(engine: engine, loadedBefore: false) == .blocker(.loading), "\(engine)")
        }
        #expect(top(engine: .readyCPU) == .ready)
        #expect(top(engine: .failed) == .blocker(.loadFailed)) // its fix
        #expect(top(phase: .downloading(done: 1, total: 4), engine: .noModel) == .blocker(.downloading(percent: 25)))
        #expect(top(mic: .denied, engine: .loading) == .blocker(.micOff)) // something else is left
        // A microphone iOS has not asked about is asked in the try: the quick load alone still gets the quiet chip.
        #expect(top(mic: .undetermined, engine: .loading) == .gettingReady)
        #expect(top(keyboard: .notAdded, engine: .loading) == .blocker(.keyboardOff))
        // A keyboard in iOS's list but not yet seen waits for speech: the quick load alone still gets the quiet chip.
        #expect(top(keyboard: .added, engine: .loading) == .gettingReady)
    }

    // Home when only what the try finishes is left (the keyboard in iOS's list but not yet seen, or the microphone not
    // asked yet, with speech usable): one card, "Try ThumbFree" with Try it, whose box is where the keyboard first comes up
    // and where iOS asks for the microphone; no trip to Settings, which could not confirm it, and no "Ready" yet.
    @Test func homeLeavesWhatOnlyTheTryFinishesToTheTry() {
        for (mic, keyboard) in [(AVAudioApplication.recordPermission.granted, KeyboardStatus.added), (.undetermined, .ready),
                                (.undetermined, .added)] {
            let top = HomeView.top(mic: mic, keyboard: keyboard, phase: .ready, waiting: nil, engine: .readyCPU, loadedBefore: true)
            #expect(top == .blocker(.untried), "\(mic) \(keyboard)")
        }
        #expect(SetupBlocker.untried.words(.home, model: "English", size: 1) == SetupBlocker.Words(title: "Try ThumbFree", detail: nil,
                                                                                               action: .tryKeyboard))
    }

    // The snapshot factory's model fact must agree with modelDone, the same rule the model row itself uses: two
    // divergent copies of "is the model done" is exactly the bug this guards against.
    @Test func theSetupCardsFactsAgreeWithModelDone() {
        let facts = SetupRows.facts(model: .ready, engine: .readyNeuralEngine, keyboard: .added)
        #expect(facts.model == SetupRows.modelDone(.ready, engine: .readyNeuralEngine))
        #expect(facts.model)
    }
}
