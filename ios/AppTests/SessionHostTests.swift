import AppIntents
import AVFoundation
import Foundation
import Testing
import TFCore
@testable import ThumbFree

// aCallEndsTheSessionAndKeepsTheTake posts its interruption to a private object (not process-wide), so it can no
// longer end another live host's session.
@MainActor @Suite(.serialized) final class SessionHostTests {
    let root: URL
    let history: HistoryStore
    let shared: SharedStore
    static let fixed = "and so my fellow americans ask not what your country can do for you"

    init() throws {
        root = try TestFiles.folder()
        history = HistoryStore(root: root.appendingPathComponent("History"))
        shared = SharedStore(directory: root.appendingPathComponent("IPC"))
    }

    deinit { try? FileManager.default.removeItem(at: root) }

    func host(engine: EngineSource = .fixed(fixed), idleTimeout: TimeInterval = 300,
              interruptionSource: AnyObject = AVAudioSession.sharedInstance(),
              source: @escaping @MainActor () throws -> AudioSource) -> SessionHost {
        SessionHost(history: history, shared: shared, engine: engine, idleTimeout: idleTimeout,
                    interruptionSource: interruptionSource) {
            (try? source()) ?? MuteSource()
        }
    }

    func jfk() throws -> FileAudioSource { try FileAudioSource(url: TestFiles.url("jfk.wav"), realTime: false) }

    /// A tap starts a take (it locks on); once `ready` holds, a second tap stops it.
    func tapTake(_ host: SessionHost, until ready: () -> Bool) async throws -> UUID {
        let take = UUID()
        host.pressInApp(take)
        host.releaseInApp(take)
        try await waitUntil { host.status.take == .recording && ready() }
        host.pressInApp(UUID())
        host.releaseInApp(take)
        return take
    }

    /// Waits for the engine to reach `.loading`, polling every scheduler tick rather than `waitUntil`'s 20 ms: a bad
    /// vocabulary file fails the load fast enough that `.loading` can flip to `.failed` within one 20 ms tick.
    func waitForLoading(_ host: SessionHost, timeout: Duration = .seconds(5)) async {
        let end = ContinuousClock.now + timeout
        while host.status.engine != .loading {
            guard ContinuousClock.now < end else {
                Issue.record("timed out waiting for .loading")
                return
            }
            await Task.yield()
        }
    }

    @Test func aTakeFromTheTryTabIsSavedThenTypedInTheApp() async throws {
        let host = host { try self.jfk() }
        var typed: [String] = []
        host.deliverInApp = { typed.append($0) }
        let take = try await tapTake(host) { host.recordedMs >= 10_900 } // all of JFK's 11 s
        try await waitUntil { typed.count == 1 && host.status.take == .idle }
        let expected = TextPipeline.run(chunkTexts: [Self.fixed], dictionary: [], language: "en").text
        #expect(typed == [expected])
        let record = try #require(try history.record(take))
        #expect(record.status == .inserted)
        #expect(record.text == expected)
        #expect(record.durationMs >= 10_900)
        #expect(try WavFile.readMono16k(url: history.audioURL(for: take)).count == record.durationMs * 16)
        #expect(try shared.outbox().last?.state == .typed)
        #expect(try shared.status()?.session == .ready) // the mic stays open for the next take
        #expect(host.historyChanges >= 3) // created, staged, typed: the History tab follows along
    }

    // Delete takes the take's outbox item with it, so no keyboard offers its text again; other takes stay.
    @Test func deletingATakeDropsItsOutboxItem() async throws {
        let host = host { MuteSource() }
        let gone = TakeRecord(id: UUID(), order: 1, startedAt: Date(), modelID: "m", status: .notInserted, text: "Gone")
        let kept = TakeRecord(id: UUID(), order: 2, startedAt: Date(), modelID: "m", status: .inserted, text: "Kept")
        try history.create(gone)
        try history.create(kept)
        try shared.write([OutboxItem(takeID: gone.id, text: "Gone", target: nil, state: .heldBack),
                          OutboxItem(takeID: kept.id, text: "Kept", target: nil, state: .typed)])
        let changes = host.historyChanges
        try host.deleteTake(gone.id)
        #expect(try history.record(gone.id) == nil)
        #expect(try shared.outbox().map(\.takeID) == [kept.id])
        #expect(host.historyChanges > changes)
    }

    // Clear all deletes every ended take and its outbox item; a take still in progress stays.
    @Test func clearAllKeepsATakeInProgress() async throws {
        let host = host { MuteSource() }
        let ended = TakeRecord(id: UUID(), order: 1, startedAt: Date(), modelID: "m", status: .noSpeech)
        let live = TakeRecord(id: UUID(), order: 2, startedAt: Date(), modelID: "m", status: .staged, text: "On its way")
        try history.create(ended)
        try history.create(live)
        try shared.write([OutboxItem(takeID: live.id, text: "On its way", target: nil, state: .pending)])
        #expect(try host.clearHistory() == 1)
        #expect(try history.all().map(\.id) == [live.id])
        #expect(try shared.outbox().map(\.takeID) == [live.id])
        try host.deleteTake(live.id) // a live take is never deleted
        #expect(try history.record(live.id) != nil)
    }

    /// A saved take with this status, text and audio, as History keeps it.
    func savedTake(_ status: TakeStatus, text: String?, audio: [Float], startedAt: Date = Date()) throws -> UUID {
        let take = TakeRecord(id: UUID(), order: 1, startedAt: startedAt, modelID: "m", status: status, text: text)
        try history.create(take)
        try FileManager.default.moveItem(at: TestFiles.wav(audio, in: root), to: history.audioURL(for: take.id))
        return take.id
    }

    var speech: [Float] { [Float](repeating: 0.1, count: 32_000) }

    // A typed take keeps its status and what was typed; the new text is marked as transcribed again, with the Dictionary.
    @Test func transcribeAgainKeepsATypedTakesStatusAndWhatWasTyped() async throws {
        let host = host(engine: .fixed("hello chat gpt")) { MuteSource() }
        host.dictionary = ["ChatGPT"]
        let id = try savedTake(.inserted, text: "hello there", audio: speech)
        #expect(await host.transcribeAgain(id) == nil)
        let take = try #require(try history.record(id))
        #expect(take.status == .inserted)
        #expect(take.insertedText == "hello there")
        #expect(take.text == "hello ChatGPT")
        #expect(take.rawText == "hello chat gpt")
        #expect(take.retranscribed)
        #expect(HistoryView.shownText(take) == "hello ChatGPT")
    }

    // Any other take ends Not inserted with the new text.
    @Test func transcribeAgainEndsAnyOtherTakeNotInserted() async throws {
        let host = host { MuteSource() }
        let id = try savedTake(.noSpeech, text: nil, audio: speech)
        #expect(await host.transcribeAgain(id) == nil)
        let take = try #require(try history.record(id))
        #expect(take.status == .notInserted)
        #expect(take.text == TextPipeline.run(chunkTexts: [Self.fixed], dictionary: [], language: "en").text)
        #expect(take.retranscribed)
    }

    // No speech: a typed take stays as it was; any other take ends No speech with its text cleared.
    @Test func transcribeAgainWithNoSpeech() async throws {
        let host = host { MuteSource() }
        let silence = [Float](repeating: 0, count: 32_000)
        let typed = try savedTake(.inserted, text: "keep me", audio: silence)
        #expect(await host.transcribeAgain(typed) == .noSpeech)
        #expect(try history.record(typed)?.status == .inserted)
        #expect(try history.record(typed)?.text == "keep me")
        let other = try savedTake(.notInserted, text: "old words", audio: silence)
        #expect(await host.transcribeAgain(other) == .noSpeech)
        #expect(try history.record(other)?.status == .noSpeech)
        #expect(try history.record(other)?.text == nil)
    }

    // A failure changes nothing (a typed take is never downgraded) and says why.
    @Test func aFailedTranscribeAgainChangesNothing() async throws {
        struct Broken: Error {}
        let host = host(engine: .custom { _ in throw Broken() }) { MuteSource() }
        let id = try savedTake(.inserted, text: "typed", audio: speech)
        let before = try history.record(id)
        #expect(await host.transcribeAgain(id) == .engineFailed)
        #expect(try history.record(id) == before)
        try FileManager.default.removeItem(at: history.audioURL(for: id))
        #expect(await host.transcribeAgain(id) == .audioMissing)
    }

    // The new text replaces the old one: the take's outbox item goes, so no keyboard's Insert here types the old text.
    @Test func transcribeAgainDropsTheTakesOldOutboxItem() async throws {
        let host = host { MuteSource() }
        let id = try savedTake(.notInserted, text: "old words", audio: speech)
        try shared.write([OutboxItem(takeID: id, text: "old words", target: nil, state: .heldBack)])
        #expect(await host.transcribeAgain(id) == nil)
        #expect(try shared.outbox().isEmpty)
    }

    // Transcribe again drops the take's outbox item before it changes History (Delete's order): a failed rewrite
    // stops here, so a crash between the two steps, or a write that fails, never leaves the old text offerable.
    @Test func aFailedOutboxRewriteStopsTranscribeAgainBeforeHistoryChanges() async throws {
        let host = host { MuteSource() }
        let id = try savedTake(.notInserted, text: "old words", audio: speech)
        try shared.write([OutboxItem]())
        try Data("not json".utf8).write(to: root.appendingPathComponent("IPC/outbox.json"))
        let before = try history.record(id)
        #expect(await host.transcribeAgain(id) == .historyWriteFailed)
        #expect(try history.record(id) == before)
    }

    // Delete throws and keeps the take when the outbox cannot be rewritten (the same order protects it).
    @Test func deleteThrowsAndKeepsTheTakeWhenTheOutboxCannotBeRewritten() throws {
        let host = host { MuteSource() }
        let id = try savedTake(.notInserted, text: "keep me", audio: speech)
        try shared.write([OutboxItem]())
        try Data("not json".utf8).write(to: root.appendingPathComponent("IPC/outbox.json"))
        #expect(throws: (any Error).self) { try host.deleteTake(id) }
        #expect(try history.record(id) != nil)
    }

    // While a take is transcribed again, a second request does nothing and Delete leaves the take alone.
    @Test func aTakeBeingTranscribedAgainIsNeitherRunTwiceNorDeleted() async throws {
        let engine = TestEngine(text: "new words", held: true)
        let host = host(engine: engine.source) { MuteSource() }
        let id = try savedTake(.notInserted, text: "old words", audio: speech)
        let first = Task { await host.transcribeAgain(id) }
        try await waitUntil { engine.calls == 1 }
        #expect(host.retranscribing == [id])
        #expect(await host.transcribeAgain(id) == nil) // at once, with no second engine run
        try host.deleteTake(id)
        #expect(try host.clearHistory() == 0)
        #expect(try history.record(id) != nil)
        engine.release()
        #expect(await first.value == nil)
        #expect(engine.calls == 1)
        #expect(host.retranscribing.isEmpty)
        #expect(try history.record(id)?.text == "new words")
    }

    // Clear all during a run keeps the running take's outbox item too, not just its History record.
    @Test func clearAllDuringARunKeepsTheRunningTakesOutboxItem() async throws {
        let engine = TestEngine(text: "new words", held: true)
        let host = host(engine: engine.source) { MuteSource() }
        let id = try savedTake(.notInserted, text: "old words", audio: speech)
        try shared.write([OutboxItem(takeID: id, text: "old words", target: nil, state: .heldBack)])
        let run = Task { await host.transcribeAgain(id) }
        try await waitUntil { engine.calls == 1 }
        #expect(try host.clearHistory() == 0)
        #expect(try shared.outbox().map(\.takeID) == [id])
        engine.release()
        #expect(await run.value == nil)
    }

    // The host's Dictionary fixes every take's text before it is saved and typed.
    @Test func aTakeGoesThroughTheDictionary() async throws {
        let host = host { try self.jfk() }
        host.dictionary = ["Americans", "Country"]
        var typed: [String] = []
        host.deliverInApp = { typed.append($0) }
        let take = try await tapTake(host) { host.recordedMs >= 2_000 }
        try await waitUntil { typed.count == 1 && host.status.take == .idle }
        #expect(typed == ["and so my fellow Americans ask not what your Country can do for you"])
        #expect(try history.record(take)?.rawText == Self.fixed) // the model's own text stays
    }

    // The engine runs while the user talks: 300 ms into the pause the text so far is made, before the stop,
    // and a stop after the pause needs no engine run.
    @Test func theEngineRunsWhileTheTakeRecords() async throws {
        let quietEnd = try TestFiles.wav([Float](repeating: 0.1, count: 32_000) + [Float](repeating: 0, count: 16_000), in: root)
        let engine = TestEngine(text: Self.fixed)
        let host = host(engine: engine.source) { try FileAudioSource(url: quietEnd, realTime: false) }
        var typed: [String] = []
        host.deliverInApp = { typed.append($0) }
        let take = try await tapTake(host) { host.recordedMs >= 3_000 && engine.calls == 1 }
        try await waitUntil { typed.count == 1 && host.status.take == .idle }
        #expect(engine.calls == 1)
        #expect(try history.record(take)?.status == .inserted)
    }

    // The Try tab shows the last take's time from the stop to its text, to compare a phone with the Mac's numbers.
    @Test func theLastTakesStopToTextTimeIsKept() async throws {
        let host = host { try self.jfk() }
        host.deliverInApp = { _ in }
        #expect(host.lastStopToTextMs == nil)
        _ = try await tapTake(host) { host.recordedMs >= 2_000 }
        try await waitUntil { host.status.take == .idle }
        let ms = try #require(host.lastStopToTextMs)
        #expect(ms >= 0 && ms < 2_000)
    }

    // Under 1 s of silence leaves no trace.
    @Test func aShortSilentTakeLeavesNoTrace() async throws {
        let host = host { try FileAudioSource(url: TestFiles.wav([Float](repeating: 0, count: 8_000), in: self.root), realTime: false) }
        _ = try await tapTake(host) { host.recordedMs >= 500 }
        try await waitUntil { host.status.take == .idle }
        #expect(try history.all().isEmpty)
        #expect(host.status.message == "No speech heard.")
    }

    @Test func aStopBeforeAnyAudioIsMicNotReady() async throws {
        let host = host { MuteSource() }
        let take = UUID()
        host.pressInApp(take)
        host.releaseInApp(take)
        host.pressInApp(UUID())
        #expect(host.status.take == .idle)
        #expect(host.status.message == "Microphone was not ready. Try again.")
        #expect(try history.all().isEmpty)
    }

    @Test func aMissingModelFailsTheTakeButKeepsIt() async throws {
        let host = host(engine: .parakeet(nil)) { try self.jfk() }
        let take = try await tapTake(host) { host.recordedMs >= 2_000 }
        try await waitUntil { host.status.take == .idle }
        let record = try #require(try history.record(take))
        #expect(record.status == .failed)
        #expect(record.error == TakeMessage.noModel.rawValue)
        #expect(host.status.message == "No speech model yet.")
        #expect(host.status.engine == .noModel)
    }

    // Keyboards read noModel from the first status on, and a finished download starts the engine's load at once.
    @Test func aHostWithoutAModelSaysSoUntilOneIsInPlace() async throws {
        let host = host(engine: .parakeet(nil)) { MuteSource() }
        #expect(host.status.engine == .noModel)
        let folder = root.appendingPathComponent("model", isDirectory: true)
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        try Data("not a vocabulary".utf8).write(to: folder.appendingPathComponent("parakeet_vocab.json"))
        host.useModel(folder)
        try await waitUntil { host.status.engine == .failed } // it loaded the folder (a broken one here)
        #expect(try shared.status()?.engine == .failed)
    }

    // A stricter rule says first how many takes it would delete; applying it deletes them at once.
    @Test func aStricterRuleCountsThenDeletes() throws {
        for order in 1...3 {
            try history.create(TakeRecord(id: UUID(), order: order, startedAt: Date(), modelID: "fixed-text", status: .inserted))
        }
        let host = host { MuteSource() }
        #expect(host.takesToDelete(keepDays: nil, keepCount: 1).count == 2)
        #expect(try history.all().count == 3)
        host.keepCount = 1
        host.applyRetention()
        #expect(try history.all().map(\.order) == [3])
    }

    // Retention withdraws a take's text as Delete does: its outbox item goes first, so no keyboard offers the text of a
    // take History no longer has. When the outbox cannot be rewritten, nothing is deleted.
    @Test func retentionDropsTheOutboxItemsOfTheTakesItDeletes() throws {
        let host = host { MuteSource() }
        let takes = try (1...3).map { order in
            let take = TakeRecord(id: UUID(), order: order, startedAt: Date(), modelID: "m", status: .notInserted, text: "Take")
            try history.create(take)
            return take.id
        }
        try shared.write(takes.map { OutboxItem(takeID: $0, text: "Take", target: nil, state: .heldBack) })
        host.keepCount = 2
        host.applyRetention()
        #expect(try history.all().map(\.id) == [takes[2], takes[1]])
        #expect(try shared.outbox().map(\.takeID) == [takes[1], takes[2]])
        try Data("not json".utf8).write(to: root.appendingPathComponent("IPC/outbox.json"))
        host.keepCount = 1
        host.applyRetention()
        #expect(try history.all().count == 2)
    }

    // With no new take (at launch, on return to the foreground) retention runs only with a day limit: without one, only
    // a take's end or a stricter rule can have takes to delete, so History is not read at all.
    @Test func retentionWithNoNewTakeRunsOnlyWithADayLimit() throws {
        let host = host { MuteSource() }
        let old = TakeRecord(id: UUID(), order: 1, startedAt: Date().addingTimeInterval(-40 * 86_400), modelID: "m", status: .notInserted)
        let new = TakeRecord(id: UUID(), order: 2, startedAt: Date(), modelID: "m", status: .notInserted)
        try history.create(old)
        try history.create(new)
        host.keepCount = 1
        host.applyDayRetention()
        #expect(try history.all().count == 2) // no day limit: the pass does nothing
        host.keepDays = 7
        host.keepCount = nil
        host.applyDayRetention()
        #expect(try history.all().map(\.id) == [new.id])
    }

    // Retention never deletes a take being transcribed again, however old: it can go at a later pass, once the run ends.
    @Test func retentionNeverDeletesATakeBeingTranscribedAgain() async throws {
        let engine = TestEngine(text: "new words", held: true)
        let host = host(engine: engine.source) { MuteSource() }
        let id = try savedTake(.notInserted, text: "old words", audio: speech, startedAt: Date().addingTimeInterval(-20 * 86_400))
        let run = Task { await host.transcribeAgain(id) }
        try await waitUntil { engine.calls == 1 }
        host.keepDays = 7
        host.applyRetention() // a take ended meanwhile
        #expect(try history.record(id) != nil)
        engine.release()
        #expect(await run.value == nil)
        #expect(try history.record(id)?.text == "new words")
    }

    // The warning's Delete deletes exactly the takes it counted: a take whose Transcribe again ends while the warning is
    // open was not counted, so it stays, though the rule would take it now (it goes at the next pass).
    @Test func theWarningsDeleteRemovesExactlyTheTakesItCounted() async throws {
        let engine = TestEngine(text: "new words", held: true)
        let host = host(engine: engine.source) { MuteSource() }
        let old = try savedTake(.notInserted, text: "old words", audio: speech, startedAt: Date().addingTimeInterval(-40 * 86_400))
        let again = try savedTake(.notInserted, text: "old words", audio: speech, startedAt: Date().addingTimeInterval(-20 * 86_400))
        let run = Task { await host.transcribeAgain(again) }
        try await waitUntil { engine.calls == 1 }
        let counted = host.takesToDelete(keepDays: 7, keepCount: nil) // the warning opens
        #expect(counted == [old])
        engine.release()
        #expect(await run.value == nil) // it ends while the warning is open
        #expect(try host.deleteTakes(counted) == 1) // Delete
        #expect(try history.record(old) == nil)
        #expect(try history.record(again) != nil)
    }

    // Start ThumbFree turns the mic on for a session with no take, so the keyboard's next tap starts at once; with no
    // model it starts nothing. (Whether iOS lets it start from the background or the Lock Screen needs a phone.)
    @Test func startThumbFreeOpensTheMicForASession() async throws {
        let host = host { LoudSource() }
        AppEnvironment.routeStart(to: host)
        defer { StartSessionIntent.start = nil; StartSessionIntent.modelOffer = nil }
        _ = try await StartSessionIntent().perform()
        #expect(host.status.session == .ready)
        #expect(host.status.take == .idle)
        #expect(try history.all().isEmpty)
        host.endSession()
        let none = self.host(engine: .parakeet(nil)) { LoudSource() }
        #expect(await none.startIdleSession() == false)
        #expect(none.status.session == .off)
    }

    // No model: Start ThumbFree starts nothing and opens the model's offer on the Try tab, as the keyboard's no-model
    // press does.
    @Test func startThumbFreeWithNoModelOpensTheModelOffer() async throws {
        let host = host(engine: .parakeet(nil)) { LoudSource() }
        AppEnvironment.routeStart(to: host)
        defer { StartSessionIntent.start = nil; StartSessionIntent.modelOffer = nil }
        let result = try await StartSessionIntent().perform()
        #expect(Self.opens(result) == DictateLink.model)
        #expect(host.status.session == .off)
    }

    // Where the app did not set it up (iOS 26 may run the intent in the widget extension), Start ThumbFree asks iOS to
    // open ThumbFree instead of reporting a success that started nothing. Outside the App Intents runtime that throws.
    @Test func startThumbFreeOutsideTheAppOpensThumbFree() async throws {
        StartSessionIntent.start = nil
        StartSessionIntent.modelOffer = nil
        await #expect(throws: AppIntentError.self) { _ = try await StartSessionIntent().perform() }
    }

    // A mic that starts but never sends audio: false after 2 s, and that mic is stopped (a session with no take has nothing
    // else to time it out); the next Start ThumbFree starts it again.
    @Test func aStartWithNoAudioSaysFalseAndStartsOverNextTime() async throws {
        let mute = MuteSource()
        let host = host { mute }
        #expect(await host.startIdleSession() == false)
        #expect(mute.starts == 1)
        #expect(mute.stops == 1)
        #expect(host.status.session == .off)
        #expect(await host.startIdleSession() == false)
        #expect(mute.starts == 2)
        host.endSession()
    }

    // iOS refused the start (Start ThumbFree from the background): false at once rather than after 2 s, and no message
    // while ThumbFree is not on screen (the intent opens it and tries there). On screen, a failed start says why.
    @Test func aRefusedStartSaysFalseAtOnceAndQuietlyOffScreen() async throws {
        let host = host { RefusedSource() }
        let started = ContinuousClock.now
        #expect(await host.startIdleSession() == false)
        #expect(ContinuousClock.now - started < .seconds(1))
        #expect(host.status.session == .off)
        #expect(host.status.message == nil)
        host.appActive = true
        #expect(await host.startIdleSession() == false)
        #expect(host.status.message == TakeMessage.micUnavailable.text)
    }

    // Start ThumbFree during a take leaves it alone: a recording take keeps recording, and a take whose mic started but
    // sent no audio yet is not started over under it (its own 2 s arming timeout decides).
    @Test func startThumbFreeLeavesALiveTakeAlone() async throws {
        let host = host { LoudSource() }
        let take = UUID()
        host.pressInApp(take)
        host.releaseInApp(take)
        try await waitUntil { host.status.take == .recording && host.status.micOn }
        #expect(await host.startIdleSession())
        #expect(host.status.takeID == take)
        #expect(host.status.take == .recording)
        host.endSession()

        let mute = MuteSource()
        let arming = self.host { mute }
        let waiting = UUID()
        arming.pressInApp(waiting)
        arming.releaseInApp(waiting)
        try await waitUntil { mute.starts == 1 } // the mic started, with no audio yet
        #expect(await arming.startIdleSession() == false)
        #expect(mute.starts == 1)
        try await waitUntil { arming.status.take == .idle }
        #expect(arming.status.message == TakeMessage.micNotReady.text)
    }

    /// The link an intent's result opens (`.result(opensIntent:)`), read back by reflection: the result keeps it private.
    static func opens(_ result: some IntentResult) -> URL? {
        Mirror(reflecting: result).children.lazy.compactMap { ($0.value as? OpenURLIntent)?.url }.first
    }

    // A new choice applies from your next take: a model change during a take waits for the take to end.
    @Test func aModelChangeDuringATakeWaitsForItsEnd() async throws {
        let host = host(engine: .parakeet(root.appendingPathComponent("parakeet-tdt-0.6b-v2"))) { LoudSource() }
        let first = UUID()
        host.pressInApp(first)
        host.releaseInApp(first)
        try await waitUntil { host.status.take == .recording }
        host.useModel(root.appendingPathComponent("parakeet-tdt-0.6b-v3"))
        #expect(host.language == "en") // the take in progress keeps the English model's rules
        #expect(try history.record(first)?.modelID == ModelCatalog.v2.id)
        host.pressInApp(UUID())
        host.releaseInApp(first)
        try await waitUntil { host.status.take == .idle }
        let second = UUID()
        host.pressInApp(second)
        host.releaseInApp(second)
        try await waitUntil { host.status.take == .recording }
        #expect(try history.record(second)?.modelID == ModelCatalog.v3.id)
        host.endSession()
    }

    // A Transcribe again keeps the model it began with: a model change meanwhile waits for it to end, as for a take.
    @Test func aModelChangeDuringTranscribeAgainWaitsForItsEnd() async throws {
        let host = host(engine: .parakeet(root.appendingPathComponent("parakeet-tdt-0.6b-v2"))) { MuteSource() }
        let id = try savedTake(.notInserted, text: "old words", audio: speech)
        let run = Task { await host.transcribeAgain(id) }
        for _ in 0..<100 where host.retranscribing.isEmpty { await Task.yield() }
        try #require(host.retranscribing == [id])
        host.useModel(root.appendingPathComponent("parakeet-tdt-0.6b-v3"))
        #expect(host.language == "en") // the run keeps the English model
        _ = await run.value
        #expect(host.language == nil) // then the multilingual model applies
    }

    // Settings deletes no model while a take is live or a take is transcribed again (it asks at the tap and again at the
    // confirm): a new choice waits for them, so the model in use may be the one no longer chosen, and the other model
    // is refused too.
    @Test func noModelMayBeDeletedWhileATakeOrATranscribeAgainRuns() async throws {
        let engine = TestEngine(text: "new words", held: true)
        let host = host(engine: engine.source) { LoudSource() }
        #expect(!host.modelInUse)
        let take = UUID()
        host.pressInApp(take)
        host.releaseInApp(take)
        try await waitUntil { host.status.take == .recording }
        #expect(host.modelInUse)
        host.send(.cancel(take))
        #expect(!host.modelInUse)
        let id = try savedTake(.notInserted, text: "old words", audio: speech)
        let run = Task { await host.transcribeAgain(id) }
        try await waitUntil { engine.calls == 1 && host.retranscribing == [id] }
        #expect(host.modelInUse)
        engine.release()
        _ = await run.value
        #expect(!host.modelInUse)
        host.endSession()
    }

    // No model: a keyboard's press and the dictate link start no take (it could only fail). The press is dropped, so the
    // keyboard does not open the app again, and the status still says noModel.
    @Test func keyboardTakesNeedAModel() throws {
        let host = host(engine: .parakeet(nil)) { try self.jfk() }
        host.appActive = true
        let take = UUID()
        try shared.append(KeyboardCommand(takeID: take, kind: .press))
        host.openLink(take)
        #expect(host.status.take == .idle)
        #expect(try shared.pendingCommands().isEmpty)
        #expect(try history.all().isEmpty)
        #expect(host.status.engine == .noModel)
    }

    // A load that failed for one take must be tried again by a LATER take in the SAME session too: a
    // user who keeps dictating resets the idle timer on every take (endTake), so without this the model never gets
    // another try until they stop for 5 minutes.
    @Test func aFailedLoadIsTriedAgainByALaterTakeInTheSameSession() async throws {
        let folder = try TestFiles.folder()
        defer { try? FileManager.default.removeItem(at: folder) }
        try Data("not a vocabulary".utf8).write(to: folder.appendingPathComponent("parakeet_vocab.json"))
        let host = host(engine: .parakeet(folder)) { LoudSource() }

        let first = UUID()
        host.pressInApp(first)
        host.releaseInApp(first)
        await waitForLoading(host) // the session's own load
        try await waitUntil { host.recordedMs >= 2_000 }
        host.pressInApp(UUID())
        host.releaseInApp(first)
        try await waitUntil { host.status.take == .idle }
        #expect(try history.record(first)?.error == TakeMessage.loadFailed.rawValue)
        #expect(host.status.session == .ready) // stays up: the next take reuses it rather than opening a new session

        let second = UUID()
        host.pressInApp(second)
        host.releaseInApp(second)
        await waitForLoading(host, timeout: .seconds(2)) // the bug: never true again without the fix
        try await waitUntil { host.recordedMs >= 2_000 }
        host.pressInApp(UUID())
        host.releaseInApp(second)
        try await waitUntil { host.status.take == .idle }
        #expect(try history.record(second)?.error == TakeMessage.loadFailed.rawValue)
        host.endSession() // LoudSource never runs out: close the session so its task and the host's timer stop
    }

    // The Android arming timeout: a mic that never sends audio ends the press after 2 s, leaving no trace, and closes
    // the session so the next tap tries the mic again.
    @Test func aMicThatNeverStartsIsMicNotReady() async throws {
        let host = host { MuteSource() }
        host.pressInApp(UUID())
        try await waitUntil(.seconds(5)) { host.status.take == .idle }
        #expect(host.status.message == "Microphone was not ready. Try again.")
        #expect(host.status.session == .off)
        #expect(try history.all().isEmpty)
    }

    // The first run's permission prompt can stay up for seconds while the mic starts. The arming clock runs only once
    // the mic has started, and the stalled-mic watchdog only once it has sent audio, so a slow answer never fails the take.
    @Test func aSlowMicStartIsNotMicNotReady() async throws {
        let host = host { try SlowStartSource(self.jfk()) }
        let take = UUID()
        host.pressInApp(take)
        host.releaseInApp(take)
        try await waitUntil(.seconds(4)) { host.status.micOn }
        #expect(host.status.takeID == take && host.status.take == .recording)
        #expect(host.status.message == nil)
    }

    // A press that names a take that already exists (a keyboard read an old status) never touches that take.
    @Test func aPressNamingAnExistingTakeNeverDeletesIt() throws {
        let old = TakeRecord(id: UUID(), order: 1, startedAt: Date(), modelID: "parakeet-tdt-0.6b-v2", status: .inserted, text: "Hello.")
        try history.create(old)
        try Data([1, 2, 3]).write(to: history.audioURL(for: old.id))
        let host = host { try self.jfk() }
        host.pressInApp(old.id)
        #expect(host.status.take == .idle)
        #expect(host.status.message == nil)
        #expect(try history.record(old.id) == old)
        #expect(FileManager.default.fileExists(atPath: history.audioURL(for: old.id).path))
    }

    // A cancel while the engine runs drops its work: the late text never reaches history.
    @Test func aCancelledTakesLateTextNeverReachesHistory() async throws {
        let quietEnd = try TestFiles.wav([Float](repeating: 0.1, count: 32_000) + [Float](repeating: 0, count: 16_000), in: root)
        let engine = TestEngine(text: Self.fixed, held: true)
        let host = host(engine: engine.source) { try FileAudioSource(url: quietEnd, realTime: false) }
        let take = try await tapTake(host) { host.recordedMs >= 3_000 } // a quiet end: no tail
        try await waitUntil { host.status.take == .transcribing } // the tail's end comes from the live transcriber
        host.send(.cancel(take))
        engine.release()
        try await Task.sleep(for: .milliseconds(300))
        let record = try #require(try history.record(take))
        #expect(record.status == .cancelled)
        #expect(record.text == nil)
    }

    // A long transcription (the first take while the model compiles takes seconds on a device) keeps status.json fresh:
    // a keyboard reads a status older than 5 s as an app that went away, and its tap then loses the take's text.
    @Test func theStatusIsRepublishedWhileATakeIsTranscribing() async throws {
        let quietEnd = try TestFiles.wav([Float](repeating: 0.1, count: 32_000) + [Float](repeating: 0, count: 16_000), in: root)
        let engine = TestEngine(text: Self.fixed, held: true)
        let host = host(engine: engine.source) { try FileAudioSource(url: quietEnd, realTime: false) }
        _ = try await tapTake(host) { host.recordedMs >= 3_000 } // a quiet end: no tail
        try await waitUntil { host.status.take == .transcribing } // the tail's end comes from the live transcriber
        let before = try #require(try shared.status()).updatedAt
        host.onTimer(nowMs: SessionHost.wallMs() + 1_000) // a second later, still transcribing
        #expect(try #require(try shared.status()).updatedAt > before)
        engine.release()
        try await waitUntil { host.status.take == .idle }
    }

    // With the session on and no take, status.json is still rewritten about once a second: a keyboard trusts "Ready, mic
    // on" only from a status under 5 s old, so a session whose app went away is never shown as ready.
    @Test func theStatusIsRepublishedWhileASessionIsOn() async throws {
        let host = host { try FileAudioSource(url: TestFiles.url("jfk.wav"), realTime: true) }
        host.deliverInApp = { _ in }
        _ = try await tapTake(host) { host.recordedMs >= 2_000 }
        try await waitUntil { host.status.take == .idle }
        try #require(host.status.session == .ready)
        let before = try #require(try shared.status()).updatedAt
        host.onTimer(nowMs: SessionHost.wallMs() + 1_000) // a second later, still no take
        #expect(try #require(try shared.status()).updatedAt > before)
        host.endSession()
    }

    // End session while a take records: the mic closes, the take is still transcribed and typed.
    @Test func endSessionKeepsTheRecordingTake() async throws {
        let host = host { try self.jfk() }
        var typed: [String] = []
        host.deliverInApp = { typed.append($0) }
        let take = UUID()
        host.pressInApp(take)
        host.releaseInApp(take)
        try await waitUntil { host.status.take == .recording && host.recordedMs >= 5_000 }
        host.endSession()
        #expect(host.status.session == .off)
        try await waitUntil { typed.count == 1 }
        #expect(try history.record(take)?.status == .inserted)
    }

    // End session during the stop tail: the take is still transcribed and typed, and its transcriber ends the tail where
    // the audio stopped. Sound runs to the end of the clip, so the tail waits for audio that never comes.
    @Test func endSessionDuringTheStopTailKeepsTheTake() async throws {
        let loud = try TestFiles.wav([Float](repeating: 0.1, count: 48_000), in: root)
        let host = host { try FileAudioSource(url: loud, realTime: false) }
        var typed: [String] = []
        host.deliverInApp = { typed.append($0) }
        let take = try await tapTake(host) { host.recordedMs >= 3_000 }
        #expect(host.status.take == .stopping)
        host.endSession()
        try await waitUntil { typed.count == 1 }
        #expect(try history.record(take)?.status == .inserted)
    }

    // The stop tail's cap (StopTailPolicy, 350 ms) runs on the host's own clock seam, not the wall clock. Sound runs to
    // the end of the clip first (as endSessionDuringTheStopTailKeepsTheTake), so only the 50 ms timer's check can end
    // the tail here; jumping the seam past the cap ends it well under the real 350 ms, proving the timer asks the seam.
    @Test func theStopTailEndsByItsCapOnTheHostsClockSeam() async throws {
        let loud = try TestFiles.wav([Float](repeating: 0.1, count: 48_000), in: root)
        let host = host { try FileAudioSource(url: loud, realTime: false) }
        var typed: [String] = []
        host.deliverInApp = { typed.append($0) }
        let clock = ManualClock()
        host.tailClockMs = { clock.ms }
        let take = try await tapTake(host) { host.recordedMs >= 3_000 }
        #expect(host.status.take == .stopping)
        try await Task.sleep(for: .milliseconds(150)) // a few 50 ms timer checks while the seam stands still
        #expect(host.status.take == .stopping) // a timer on the wall clock would have ended the tail by now
        clock.ms = StopTailPolicy.capMs + 50 // jumps straight past the cap
        try await waitUntil(.milliseconds(250)) { host.status.take != .stopping } // far under the real 350 ms cap
        try await waitUntil { typed.count == 1 }
        #expect(try history.record(take)?.status == .inserted)
    }

    // The session ends by itself when no take starts within the idle time (5 minutes in the app). Real-time playback
    // (silence after the clip, like the mic in a quiet room) keeps the mic delivering audio throughout, so only the
    // idle end can close the session here, not the stalled-mic watchdog.
    @Test func anIdleSessionEnds() async throws {
        let host = host(idleTimeout: 0.3) { try FileAudioSource(url: TestFiles.url("jfk.wav"), realTime: true) }
        host.deliverInApp = { _ in }
        _ = try await tapTake(host) { host.recordedMs >= 2_000 }
        try await waitUntil { host.status.take == .idle }
        #expect(host.status.session == .ready)
        try await waitUntil { host.status.session == .off }
    }

    // A call ends the session; the take keeps its audio and is still transcribed, with the reason in history. Its own
    // interruption source, posted to directly: a process-wide post here must never reach another suite's live host
    // (HostKeyboardTests and DeliveryTests run in parallel with this one).
    @Test func aCallEndsTheSessionAndKeepsTheTake() async throws {
        let source = NSObject()
        let host = host(interruptionSource: source) { try self.jfk() }
        var typed: [String] = []
        host.deliverInApp = { typed.append($0) }
        let take = UUID()
        host.pressInApp(take)
        host.releaseInApp(take)
        try await waitUntil { host.status.take == .recording && host.recordedMs >= 3_000 }
        NotificationCenter.default.post(name: AVAudioSession.interruptionNotification, object: source,
                                        userInfo: [AVAudioSessionInterruptionTypeKey: AVAudioSession.InterruptionType.began.rawValue])
        try await waitUntil { host.status.session == .off }
        try await waitUntil { typed.count == 1 }
        #expect(try history.record(take)?.error == TakeMessage.call.rawValue)
    }

    // A host built with the default interruption source (production's AVAudioSession.sharedInstance()) ignores a post
    // from another object: this is what keeps aCallEndsTheSessionAndKeepsTheTake's own post from reaching it.
    @Test func aPostFromAnotherObjectIsIgnoredByDefault() async throws {
        let host = host { try self.jfk() }
        let take = UUID()
        host.pressInApp(take)
        host.releaseInApp(take)
        try await waitUntil { host.status.take == .recording && host.status.micOn }
        NotificationCenter.default.post(name: AVAudioSession.interruptionNotification, object: NSObject(),
                                        userInfo: [AVAudioSessionInterruptionTypeKey: AVAudioSession.InterruptionType.began.rawValue])
        try await Task.sleep(for: .milliseconds(200))
        #expect(host.status.session != .off)
    }

    // A block the source hands over as it stops (a tap callback still in flight) must not reopen the session. It did: the
    // mic read as on with no source, so every later take recorded nothing ("No speech heard.").
    @Test func aBlockHandedOverAtTheStopNeverReopensTheSession() async throws {
        let host = host { LastBlockSource() }
        let take = UUID()
        host.pressInApp(take)
        host.releaseInApp(take)
        try await waitUntil { host.status.take == .recording && host.status.micOn }
        host.endSession()
        try await Task.sleep(for: .milliseconds(100))
        #expect(host.status.session == .off)
        #expect(!host.status.micOn)
        let next = UUID()
        host.pressInApp(next)
        host.releaseInApp(next)
        #expect(host.status.session == .starting) // a new session, with a new source
        try await waitUntil { host.status.takeID == next && host.status.take == .recording && host.status.micOn }
    }

    // The mic can stop sending audio with no error (AVAudioEngine stops on a route or configuration change or a media
    // services reset, or another app takes the input). About 1 s later the session ends with a message; the recording
    // take keeps what it captured and is still transcribed.
    @Test func aMicThatStopsSendingAudioEndsTheSession() async throws {
        let host = host { try self.jfk() } // the whole clip at once, then nothing
        var typed: [String] = []
        host.deliverInApp = { typed.append($0) }
        let take = UUID()
        host.pressInApp(take)
        host.releaseInApp(take)
        try await waitUntil { host.status.take == .recording && host.recordedMs >= 10_900 }
        let quietFrom = ContinuousClock.now
        try await waitUntil(.seconds(3)) { host.status.session == .off }
        #expect(ContinuousClock.now - quietFrom >= .milliseconds(800))
        #expect(host.status.message == TakeMessage.captureStalled.text)
        try await waitUntil { typed.count == 1 }
        let record = try #require(try history.record(take))
        #expect(record.status == .inserted)
        #expect(record.error == TakeMessage.captureStalled.rawValue)
        #expect(record.durationMs >= 10_900)
    }

    // History keeps the closed chunks' text while the take runs, so a cancelled take keeps it. JFK's
    // chunk closes 300 ms into the half second after it; the 2 s after that stay open.
    @Test func aCancelledTakeKeepsTheTextOfItsClosedChunks() async throws {
        let jfk = try WavFile.readMono16k(url: TestFiles.url("jfk.wav"))
        let host = host { ClipThenSilence(jfk + [Float](repeating: 0, count: 8_000) + jfk.prefix(32_000)) }
        let take = UUID()
        host.pressInApp(take)
        host.releaseInApp(take)
        try await waitUntil { try self.history.record(take)?.chunksDone == 1 }
        #expect(host.status.take == .recording)
        host.send(.cancel(take))
        host.endSession()
        let record = try #require(try history.record(take))
        #expect(record.status == .cancelled)
        #expect(record.partialText == TextPipeline.run(chunkTexts: [Self.fixed], dictionary: [], language: "en").text)
    }

    // The host reads the next order from history once, at startup; each take it creates then takes the next one.
    @Test func twoTakesInARowGetTheNextOrders() async throws {
        try history.create(TakeRecord(id: UUID(), order: 5, startedAt: Date(), modelID: "fixed-text", status: .inserted))
        let host = host { try self.jfk() }
        host.deliverInApp = { _ in }
        let first = try await tapTake(host) { host.recordedMs >= 2_000 }
        try await waitUntil { host.status.take == .idle }
        host.endSession() // the next take opens a new session, with fresh audio
        let second = try await tapTake(host) { host.recordedMs >= 2_000 }
        try await waitUntil { host.status.take == .idle }
        #expect(try history.record(first)?.order == 6)
        #expect(try history.record(second)?.order == 7)
    }
}

/// A microphone iOS will not start, as from the background: start() throws AVAudioSession's cannot-start-recording error.
@MainActor private final class RefusedSource: AudioSource {
    func start(_ onSamples: @escaping @Sendable ([Float]) -> Void) async throws {
        throw NSError(domain: NSOSStatusErrorDomain, code: AVAudioSession.ErrorCode.cannotStartRecording.rawValue)
    }

    func stop() {}
}

/// A microphone that never stops sending loud sound: two takes in one session both get enough of it, with no clip to
/// run out of.
@MainActor private final class LoudSource: AudioSource {
    private var task: Task<Void, Never>?

    func start(_ onSamples: @escaping @Sendable ([Float]) -> Void) async throws {
        let block = [Float](repeating: 0.1, count: FileAudioSource.blockSamples)
        task = Task {
            while !Task.isCancelled {
                onSamples(block)
                try? await Task.sleep(for: .milliseconds(20)) // real-time pacing (as ClipThenSilence): a busy loop starves the load task
            }
        }
    }

    func stop() {
        task?.cancel()
        task = nil
    }
}

/// A microphone whose start waits past the arming timeout, like the first run's permission prompt, then plays a file.
@MainActor private final class SlowStartSource: AudioSource {
    private let file: FileAudioSource
    init(_ file: FileAudioSource) { self.file = file }

    func start(_ onSamples: @escaping @Sendable ([Float]) -> Void) async throws {
        try await Task.sleep(for: .milliseconds(SessionHost.armingTimeoutMs + 500))
        try await file.start(onSamples)
    }

    func stop() { file.stop() }
}

/// A microphone whose stop() hands over one last block, like a tap callback still in flight as the tap is removed.
@MainActor private final class LastBlockSource: AudioSource {
    private var onSamples: (@Sendable ([Float]) -> Void)?

    func start(_ onSamples: @escaping @Sendable ([Float]) -> Void) async throws {
        self.onSamples = onSamples
        onSamples([Float](repeating: 0, count: FileAudioSource.blockSamples))
    }

    func stop() {
        onSamples?([Float](repeating: 0, count: FileAudioSource.blockSamples))
        onSamples = nil
    }
}

/// A microphone that hands over a clip at once, then 20 ms of silence every 20 ms, like a quiet room: the session stays
/// up while the take runs on.
@MainActor private final class ClipThenSilence: AudioSource {
    private let clip: [Float]
    private var silence: Task<Void, Never>?
    init(_ clip: [Float]) { self.clip = clip }

    func start(_ onSamples: @escaping @Sendable ([Float]) -> Void) async throws {
        let size = FileAudioSource.blockSamples
        for start in stride(from: 0, to: clip.count, by: size) { onSamples(Array(clip[start..<min(start + size, clip.count)])) }
        silence = Task {
            while !Task.isCancelled {
                try? await Task.sleep(for: .milliseconds(20))
                onSamples([Float](repeating: 0, count: size))
            }
        }
    }

    func stop() { silence?.cancel() }
}

/// A stub clock for the stop tail's seam: the test moves `ms` directly, instead of waiting on the real clock.
@MainActor private final class ManualClock {
    var ms = 0
}
