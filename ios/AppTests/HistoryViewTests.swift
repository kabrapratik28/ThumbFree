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

    @Test func theTryTabSaysWhatTheMicDoes() {
        #expect(TryView.hint(for: HostStatus()) == "Tap to talk, tap again to stop. Or hold while you talk.")
        #expect(TryView.hint(for: HostStatus(micOn: true, take: .recording)) == "Listening. Tap to stop.")
        #expect(TryView.hint(for: HostStatus(take: .transcribing)) == "Transcribing")
        #expect(TryView.hint(for: HostStatus(message: "No speech heard.")) == "No speech heard.")
        #expect(TryView.hint(for: HostStatus(), modelReady: false) == "Get the speech model above to start.")
        // The first load after a download takes a while: the idle hint says so instead of "Tap to talk".
        let gettingReady = "Getting ready for this iPhone. The first time takes about half a minute. After that, ThumbFree starts in a moment."
        #expect(TryView.hint(for: HostStatus(engine: .loading)) == gettingReady)
        #expect(TryView.hint(for: HostStatus(engine: .warming)) == gettingReady)
        #expect(TryView.hint(for: HostStatus(engine: .loading, micOn: true, take: .recording)) == "Listening. Tap to stop.")
    }

    // The setup card's model row counts as done once the model is in and the engine did not fail to load it.
    @Test func theSetupCardsModelRowSaysWhatIsLeft() {
        #expect(!SetupRows.modelDone(.missing, engine: .noModel))
        #expect(!SetupRows.modelDone(.downloading(done: 1, total: 2), engine: .noModel))
        #expect(!SetupRows.modelDone(.ready, engine: .failed))
        #expect(SetupRows.modelDone(.ready, engine: .unloaded))
        #expect(SetupRows.modelLine(.missing, engine: .noModel, total: 465_000_000) == "Not downloaded · 465 MB")
        #expect(SetupRows.modelLine(.downloading(done: 1, total: 4), engine: .noModel, total: 4) == "Downloading · 25%")
        #expect(SetupRows.modelLine(.ready, engine: .readyNeuralEngine, total: 1) == "Ready")
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

    // The snapshot factory's model fact must agree with modelDone, the same rule the model row itself uses: two
    // divergent copies of "is the model done" is exactly the bug this guards against.
    @Test func theSetupCardsFactsAgreeWithModelDone() {
        let facts = SetupRows.facts(model: .ready, engine: .readyNeuralEngine, keyboard: .added)
        #expect(facts.model == SetupRows.modelDone(.ready, engine: .readyNeuralEngine))
        #expect(facts.model)
    }

    @Test func theTryTabShowsTheLastStopToTextTime() {
        #expect(TryView.speedLine(125) == "Last take: text ready 125 ms after the stop.")
    }
}
