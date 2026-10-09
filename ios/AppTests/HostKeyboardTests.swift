import Foundation
import Testing
import TFCore
@testable import ThumbFree

// The app's side of the keyboard protocol: command files in, status.json and outbox.json out (docs/contract.md,
// "IPC" and "Delivery"). The test plays the keyboard by writing command files.
@MainActor @Suite final class HostKeyboardTests {
    let root: URL
    let history: HistoryStore
    let shared: SharedStore
    let target = InsertTarget(documentID: UUID(), contextHash: InsertTarget.contextHash(before: nil, after: nil))
    /// The hosts' defaults (the count of takes that gave text), kept out of the test app's own.
    let suite = TestFiles.defaultsSuite("HostKeyboardTests", test: "host")
    let defaults: UserDefaults

    init() throws {
        root = try TestFiles.folder()
        history = HistoryStore(root: root.appendingPathComponent("History"))
        shared = SharedStore(directory: root.appendingPathComponent("IPC"))
        defaults = try #require(UserDefaults(suiteName: suite))
    }

    deinit {
        try? FileManager.default.removeItem(at: root)
        UserDefaults.standard.removePersistentDomain(forName: suite)
    }

    func host(deliveryTimeoutMs: Int = 3_000) -> SessionHost {
        let host = SessionHost(history: history, shared: shared, engine: .fixed("hello world"), deliveryTimeoutMs: deliveryTimeoutMs,
                               defaults: defaults) {
            if let file = try? FileAudioSource(url: TestFiles.url("jfk.wav"), realTime: false) { return file }
            return MuteSource()
        }
        host.appActive = true
        return host
    }

    func keyboard(_ kind: KeyboardCommand.Kind, _ take: UUID, target: InsertTarget? = nil, at sentAt: Date) throws {
        try shared.append(KeyboardCommand(takeID: take, kind: kind, target: target, sentAt: sentAt))
    }

    /// A keyboard tap starts the take, a second tap stops it; returns once the text waits for the keyboard.
    func recordUntilDelivering(_ host: SessionHost, _ take: UUID, from t0: Date) async throws {
        try keyboard(.press, take, target: target, at: t0)
        try keyboard(.release, take, at: t0 + 0.1)
        host.handleCommands()
        try await waitUntil { host.status.take == .recording && host.recordedMs >= 2_000 }
        try keyboard(.press, take, target: target, at: t0 + 3)
        try keyboard(.release, take, at: t0 + 3.1)
        host.handleCommands()
        try await waitUntil { host.status.take == .delivering }
    }

    @Test func aKeyboardTakeIsTypedThroughTheOutbox() async throws {
        let host = host()
        let take = UUID(), t0 = Date()
        try await recordUntilDelivering(host, take, from: t0)
        let item = try #require(try shared.outbox().last)
        #expect(item.takeID == take && item.state == .pending && item.target == target)
        #expect(try history.record(take)?.status == .staged) // saved before the keyboard hears of it
        #expect(try shared.status()?.take == .delivering)
        try keyboard(.insertionBegan, take, at: t0 + 4)
        try keyboard(.insertionConfirmed, take, at: t0 + 4.1)
        host.handleCommands()
        #expect(host.status.take == .idle)
        #expect(try history.record(take)?.status == .inserted)
        #expect(try shared.outbox().last?.state == .typed)
        #expect(try shared.pendingCommands().isEmpty)
    }

    @Test func aPressFromBeforeTheLaunchIsDropped() throws {
        let host = host()
        try keyboard(.press, UUID(), target: target, at: host.launchedAt - 1)
        host.handleCommands()
        #expect(host.status.take == .idle)
        #expect(try shared.pendingCommands().isEmpty)
    }

    // In the background with no session the app cannot record: the press waits (the keyboard opens the app), then
    // goes after 30 s.
    @Test func aPressWaitsWhileTheAppCannotRecord() throws {
        let host = host()
        host.appActive = false
        try keyboard(.press, UUID(), target: target, at: Date())
        host.handleCommands()
        #expect(host.status.take == .idle)
        #expect(try shared.pendingCommands().count == 1)
        host.handleCommands(now: Date() + 31)
        #expect(try shared.pendingCommands().isEmpty)
    }

    // A press whose link never opened the app must not start a take when the user opens ThumbFree minutes later.
    @Test func aPressOlderThan30sNeverStartsATake() throws {
        let host = host()
        try keyboard(.press, UUID(), target: target, at: Date())
        host.handleCommands(now: Date() + 31)
        #expect(host.status.take == .idle)
        #expect(try shared.pendingCommands().isEmpty)
    }

    // The keyboards count a take's time from its start in the status: set when the take's audio clock starts (here the
    // mic, since no session was live), the same in every status while it records, and gone once the take ends.
    @Test func theStatusCarriesTheTakesStart() async throws {
        let host = host()
        let take = UUID(), t0 = Date()
        try keyboard(.press, take, target: target, at: t0)
        try keyboard(.release, take, at: t0 + 0.1)
        host.handleCommands()
        try await waitUntil { host.status.take == .recording && host.status.micOn }
        let start = try #require(try shared.status()?.takeStartedAt)
        #expect(start.timeIntervalSince(t0) > -0.01 && start.timeIntervalSince(t0) < 5)
        try await waitUntil { host.recordedMs >= 2_000 }
        host.publish() // the once-a-second rewrite
        #expect(try shared.status()?.takeStartedAt == start)
        try keyboard(.press, take, target: target, at: t0 + 3)
        try keyboard(.release, take, at: t0 + 3.1)
        host.handleCommands()
        try await waitUntil { host.status.take == .delivering }
        try keyboard(.insertionBegan, take, at: t0 + 4)
        try keyboard(.insertionConfirmed, take, at: t0 + 4.1)
        host.handleCommands()
        #expect(host.status.take == .idle)
        #expect(try shared.status()?.takeStartedAt == nil)
    }

    // The link starts its take as a tap: a later release does not stop it, and the keyboard's older press is dropped.
    @Test func theLinkStartsItsTakeLockedOn() async throws {
        let host = host()
        host.appActive = false
        let take = UUID()
        try keyboard(.press, take, target: target, at: Date())
        host.openLink(take)
        try await waitUntil { host.status.take == .recording && host.status.micOn }
        #expect(host.status.takeID == take)
        #expect(try shared.pendingCommands().isEmpty)
        try keyboard(.release, take, at: Date() + 2) // would end a hold held 2 s
        host.handleCommands()
        #expect(host.status.take == .recording)
        host.openLink(take) // the same link again changes nothing
        #expect(host.status.take == .recording)
    }

    // The link's tap is timed before the press's work runs (take folder, WAV file, making the mic source). A slow
    // start must not make the tap a hold, which ends a take that has no audio yet ("Microphone was not ready.").
    @Test func theLinksTapSurvivesASlowStart() async throws {
        let host = SessionHost(history: history, shared: shared, engine: .fixed("hello world"), defaults: defaults) {
            Thread.sleep(forTimeInterval: 0.4) // making the source takes 400 ms
            if let file = try? FileAudioSource(url: TestFiles.url("jfk.wav"), realTime: false) { return file }
            return MuteSource()
        }
        let take = UUID()
        host.openLink(take)
        #expect(host.status.message == nil)
        try #require(host.status.take == .recording)
        try await waitUntil { host.status.take == .recording && host.status.micOn }
        try keyboard(.release, take, at: Date() + 2) // would end a hold held 2 s
        host.handleCommands()
        #expect(host.status.take == .recording)
    }

    // A keyboard opens the link when its press is still on disk 150 ms after writing it. The press leaves the disk before
    // its work runs (take folder, WAV file, making the mic source), so a slow start never opens ThumbFree for a take that
    // already started.
    @Test func aPressIsRemovedBeforeItsWorkRuns() throws {
        var pressesOnDisk: [Int] = []
        let host = SessionHost(history: history, shared: shared, engine: .fixed("hello world"), defaults: defaults) { [shared] in
            pressesOnDisk.append((try? shared.pendingCommands().count) ?? -1)
            return MuteSource()
        }
        host.appActive = true
        try keyboard(.press, UUID(), target: target, at: Date())
        host.handleCommands()
        #expect(pressesOnDisk == [0])
    }

    // ThumbFree died during a take, and for a few seconds its last status still named it, so the keyboard's press and link
    // name that take. The new launch leaves it as startup recovery marked it, and starts a fresh take as the cold take.
    @Test func aLinkForATakeFromBeforeThisLaunchStartsAFreshTake() async throws {
        let old = UUID()
        try history.create(TakeRecord(id: old, order: 0, startedAt: Date() - 10, modelID: "test", status: .interrupted))
        let host = host()
        try keyboard(.press, old, target: target, at: host.launchedAt - 1) // written before this launch
        let opened = host.openLink(old)
        try await waitUntil { host.status.take == .recording && host.status.micOn }
        let take = try #require(host.status.takeID)
        #expect(take != old)
        #expect(opened == take) // the fresh take is returned, not the link's id
        #expect(try history.record(old)?.status == .interrupted)
        #expect(try history.record(take)?.status == .recording)
        // A repeated link (onOpenURL firing again) for the same stale id reaches the fresh take through coldLink (the
        // stale id's substitution), not nil (nil would close the session screen while the take keeps recording); the
        // coldTake gate stops openLink running its round-trip setup twice, so the live take is left undisturbed.
        #expect(host.openLink(old) == take)
        #expect(host.status.takeID == take) // still the same live take, not disturbed
    }

    // A link for another take while one records leaves it alone: only an idle host starts the link's take.
    @Test func aLinkForAnotherTakeLeavesTheLiveTakeAlone() async throws {
        let host = host()
        let take = UUID()
        host.pressInApp(take)
        host.releaseInApp(take)
        try await waitUntil { host.status.take == .recording && host.status.micOn }
        let opened = host.openLink(UUID())
        #expect(opened == nil) // a link for another take while one records starts nothing
        #expect(host.status.takeID == take)
        #expect(host.status.take == .recording)
    }

    // The take whose link opened the app goes to the field where the user stopped it, because the app switch gave the
    // field of its press a new identity. The last second is silence, so the stop needs no tail and the take is already
    // transcribing when the stop press has been handled.
    @Test func theColdTakeGoesToTheFieldWhereTheUserStopped() async throws {
        let quietEnd = try TestFiles.wav([Float](repeating: 0.1, count: 32_000) + [Float](repeating: 0, count: 16_000), in: root)
        let host = SessionHost(history: history, shared: shared, engine: .fixed("hello world"), defaults: defaults) {
            if let file = try? FileAudioSource(url: quietEnd, realTime: false) { return file }
            return MuteSource()
        }
        let take = UUID()
        host.openLink(take)
        try await waitUntil { host.status.take == .recording && host.recordedMs >= 3_000 }
        let whereStopped = InsertTarget(documentID: UUID(), contextHash: InsertTarget.contextHash(before: "Hi", after: nil))
        let stoppedAt = Date()
        try keyboard(.press, take, target: whereStopped, at: stoppedAt)
        try keyboard(.release, take, target: whereStopped, at: stoppedAt + 0.1)
        host.handleCommands()
        try await waitUntil { host.status.take == .delivering }
        #expect(try shared.outbox().last?.target == whereStopped)
        #expect(try shared.outbox().last?.pinnedAt == stoppedAt) // the stop press pinned it, for the keyboard's own check
    }

    // Inside a live session the strict rule stands: the take keeps the field of its press.
    @Test func aWarmTakeKeepsTheFieldOfItsPress() async throws {
        let host = host()
        let take = UUID(), t0 = Date()
        try keyboard(.press, take, target: target, at: t0)
        try keyboard(.release, take, target: target, at: t0 + 0.1)
        host.handleCommands()
        try await waitUntil { host.status.take == .recording && host.recordedMs >= 2_000 }
        let elsewhere = InsertTarget(documentID: UUID(), contextHash: target.contextHash)
        try keyboard(.press, take, target: elsewhere, at: t0 + 3)
        try keyboard(.release, take, target: elsewhere, at: t0 + 3.1)
        host.handleCommands()
        try await waitUntil { host.status.take == .delivering }
        #expect(try shared.outbox().last?.target == target)
        #expect(try shared.outbox().last?.pinnedAt == t0) // pinned by the press that started it
    }

    @Test func noAnswerIn3sLeavesTheTextPending() async throws {
        let host = host(deliveryTimeoutMs: 200)
        let take = UUID()
        try await recordUntilDelivering(host, take, from: Date())
        try await waitUntil { host.status.take == .idle }
        #expect(try history.record(take)?.status == .notInserted)
        #expect(try shared.outbox().last?.state == .pending)
    }

    @Test func aWriteThatBeganAndWentSilentMayAlreadyBeInTheField() async throws {
        let host = host(deliveryTimeoutMs: 200)
        let take = UUID(), t0 = Date()
        try await recordUntilDelivering(host, take, from: t0)
        try keyboard(.insertionBegan, take, at: t0 + 4)
        host.handleCommands()
        #expect(try history.record(take)?.status == .inserting)
        try await waitUntil { host.status.take == .idle }
        #expect(try history.record(take)?.status == .needsReview)
        #expect(try shared.outbox().last?.state == .unverified)
    }

    // insertionBegan restarts the 3 s wait: the text may be on its way into the field.
    @Test func insertionBeganRestartsTheDeliveryTimer() async throws {
        let host = host(deliveryTimeoutMs: 600)
        let take = UUID(), t0 = Date()
        try await recordUntilDelivering(host, take, from: t0)
        try await Task.sleep(for: .milliseconds(300))
        try keyboard(.insertionBegan, take, at: t0 + 4)
        host.handleCommands()
        try await Task.sleep(for: .milliseconds(450)) // 750 ms after the outbox write, 450 ms after insertionBegan
        #expect(host.status.take == .delivering)
        try await waitUntil { host.status.take == .idle }
        #expect(try history.record(take)?.status == .needsReview)
    }

    // Insert here on a take that already ended goes straight to history and the outbox.
    @Test func aLaterInsertHereIsRecorded() async throws {
        let host = host(deliveryTimeoutMs: 200)
        let take = UUID(), t0 = Date()
        try await recordUntilDelivering(host, take, from: t0)
        try await waitUntil { host.status.take == .idle }
        try keyboard(.insertionBegan, take, at: t0 + 10)
        try keyboard(.insertionConfirmed, take, at: t0 + 10.1)
        host.handleCommands()
        #expect(try history.record(take)?.status == .inserted)
        #expect(try shared.outbox().last?.state == .typed)
    }

    // Late reports apply only to a pending or held-back item (a later Insert here, as the contract says): a replayed
    // insertionBegan never moves a typed take back to inserting.
    @Test func aReplayedLateReportNeverUndoesATypedTake() async throws {
        let host = host()
        let take = UUID(), t0 = Date()
        try await recordUntilDelivering(host, take, from: t0)
        try keyboard(.insertionBegan, take, at: t0 + 4)
        try keyboard(.insertionConfirmed, take, at: t0 + 4.1)
        host.handleCommands()
        #expect(try history.record(take)?.status == .inserted)
        try keyboard(.insertionBegan, take, at: t0 + 5) // replayed after the take ended
        host.handleCommands()
        #expect(try history.record(take)?.status == .inserted)
        #expect(try shared.outbox().last?.state == .typed)
    }

    @Test func aPingRewritesTheStatus() throws {
        let host = host()
        #expect(try shared.status() == nil)
        try keyboard(.ping, UUID(), at: Date())
        host.handleCommands()
        #expect(try shared.status()?.session == .off)
    }

    // The outbox holds transcript text: the IPC folder never goes into a device backup.
    @Test func theIPCFolderIsExcludedFromBackups() throws {
        let values = try AppGroup.ipcDirectory().resourceValues(forKeys: [.isExcludedFromBackupKey])
        #expect(values.isExcludedFromBackup == true)
    }

    @Test func theSessionScreenFollowsTheStatus() {
        let swipe = ReturnTrip(appName: nil, phase: .swipeBack, firstReturn: false)
        func primary(_ status: HostStatus) -> String { SessionScreen.primaryLine(status: status, returnTrip: swipe) }
        #expect(primary(HostStatus(micOn: true, take: .recording)) == "Listening.")
        #expect(primary(HostStatus(take: .recording)) == "Starting the microphone")
        #expect(primary(HostStatus(take: .transcribing)) == "Transcribing")
        #expect(primary(HostStatus(session: .ready, micOn: true)) == "Ready, mic on.")
        #expect(primary(HostStatus(message: "No speech heard.")) == "No speech heard.")
        // The swipe-back sub-line names the app when known, else no app.
        #expect(SessionScreen.subLine(status: HostStatus(), returnTrip: swipe, way: .swipe) == "Swipe right along the bottom edge to go back.")
        let named = ReturnTrip(appName: "WhatsApp", phase: .swipeBack, firstReturn: false)
        #expect(SessionScreen.subLine(status: HostStatus(), returnTrip: named, way: .swipe) == "Swipe right along the bottom edge to go back to WhatsApp.")
        // While leaving, the big line is short and the sub-line names the app; no cue yet. But the words and
        // the bubble art must not disagree: the trip goes `.leaving` before the mic delivers any audio, so "Listening."
        // shows only once the mic art itself would show listening; until then it is still the status line.
        let leaving = ReturnTrip(appName: "WhatsApp", phase: .leaving, firstReturn: false)
        #expect(SessionScreen.primaryLine(status: HostStatus(micOn: true, take: .recording), returnTrip: leaving) == "Listening.")
        #expect(SessionScreen.primaryLine(status: HostStatus(take: .recording), returnTrip: leaving) == "Starting the microphone")
        #expect(SessionScreen.subLine(status: HostStatus(micOn: true, take: .recording), returnTrip: leaving, way: .swipe) == "Taking you back to WhatsApp\u{2026}")
        #expect(SessionScreen.showsCue(leaving, status: HostStatus()) == false)
        #expect(SessionScreen.showsCue(swipe, status: HostStatus()) == true)
        // The first time for an app, iOS may ask before it opens it: "tap Open" shows while leaving, and goes once the
        // trip swipes back (a miss, the fallback or a Cancel), where there is nothing to tap.
        let firstTime = ReturnTrip(appName: "WhatsApp", phase: .leaving, firstReturn: true)
        #expect(SessionScreen.firstReturnLine(returnTrip: firstTime) == "If iOS asks, tap Open. It asks once.")
        var firstTimeSwipedBack = firstTime
        firstTimeSwipedBack.phase = .swipeBack
        #expect(SessionScreen.firstReturnLine(returnTrip: firstTimeSwipedBack) == nil)
        #expect(SessionScreen.firstReturnLine(returnTrip: leaving) == nil) // not the first time
        #expect(SessionScreen.firstReturnLine(returnTrip: nil) == nil)
        // After a successful automatic return, returnTrip goes nil (`resolveIfOpened()`). Coming back mid-take
        // must still show the generic swipe-back line and its cue, not silently drop them; but nothing once the
        // take and the session are both over (no trip to name, and nothing left to swipe back to anyway).
        let stillLive = HostStatus(micOn: true, take: .recording)
        #expect(SessionScreen.subLine(status: stillLive, returnTrip: nil, way: .swipe) == "Swipe right along the bottom edge to go back.")
        #expect(SessionScreen.showsCue(nil, status: stillLive) == true)
        #expect(SessionScreen.subLine(status: HostStatus(), returnTrip: nil, way: .swipe) == nil)
        #expect(SessionScreen.showsCue(nil, status: HostStatus()) == false)
        // Its mic art says the same as its words: the keyboard's red stop key only while listening, the turning arc while
        // transcribing.
        #expect(SessionScreen.mode(for: HostStatus(micOn: true, take: .recording)) == .stop)
        #expect(SessionScreen.mode(for: HostStatus(take: .recording)) == .idle)
        #expect(SessionScreen.mode(for: HostStatus(take: .stopping)) == .busy)
        #expect(SessionScreen.mode(for: HostStatus(take: .transcribing)) == .busy)
        #expect(SessionScreen.mode(for: HostStatus(session: .ready, micOn: true)) == .idle)
        #expect(SessionScreen.mode(for: HostStatus(message: "No speech heard.")) == .idle)
    }

    // The way back fits the screen: the swipe along the bottom edge on an iPhone with a home indicator; iOS's link at the
    // top left, ringed, on one with a Home button (no edge to swipe) or with VoiceOver on (a real control is easier than
    // an edge gesture); on an iPad, the App Switcher in words only (iOS writes no app's name there, and this iPhone app's
    // window has neither of the iPad's edges).
    @Test func theWayBackFitsTheScreenAndVoiceOver() {
        #expect(SessionScreen.way(homeButton: false, voiceOver: false, iPad: false) == .swipe)
        #expect(SessionScreen.way(homeButton: true, voiceOver: false, iPad: false) == .backLink)
        #expect(SessionScreen.way(homeButton: false, voiceOver: true, iPad: false) == .backLink)
        #expect(SessionScreen.way(homeButton: true, voiceOver: true, iPad: false) == .backLink)
        #expect(SessionScreen.way(homeButton: false, voiceOver: false, iPad: true) == .appSwitcher)
        #expect(SessionScreen.way(homeButton: false, voiceOver: true, iPad: true) == .appSwitcher)
    }

    // The line under the title says the way back, and names the app only when known (a Debug build's fallback).
    @Test func theLineUnderTheTitleSaysTheWayBack() {
        let listening = HostStatus(micOn: true, take: .recording)
        let swipe = ReturnTrip(appName: nil, phase: .swipeBack, firstReturn: false)
        let named = ReturnTrip(appName: "Notes", phase: .swipeBack, firstReturn: false)
        func line(_ trip: ReturnTrip?, _ way: SessionScreen.Way) -> String? {
            SessionScreen.subLine(status: listening, returnTrip: trip, way: way)
        }
        #expect(line(swipe, .swipe) == "Swipe right along the bottom edge to go back.")
        #expect(line(nil, .swipe) == "Swipe right along the bottom edge to go back.")
        #expect(line(named, .swipe) == "Swipe right along the bottom edge to go back to Notes.")
        #expect(line(swipe, .backLink) == "Tap your app\u{2019}s name at the top left to go back, or use the App Switcher.")
        #expect(line(nil, .backLink) == "Tap your app\u{2019}s name at the top left to go back, or use the App Switcher.")
        #expect(line(named, .backLink) == "Tap Notes at the top left to go back, or use the App Switcher.")
        // An iPad writes no app's name at the top left (this iPhone app opens there in a window of its own): the App
        // Switcher alone, named app or not.
        for trip in [swipe, nil, named] {
            #expect(line(trip, .appSwitcher) == "Use the App Switcher to go back to your app.")
        }
        // Leaving for the app (Debug automatic return) keeps its words whatever the way, also before the mic is on, and
        // the first time its "tap Open" line, which goes once the trip falls back to the way back.
        let leaving = ReturnTrip(appName: "WhatsApp", phase: .leaving, firstReturn: true)
        for status in [listening, HostStatus(take: .recording)] {
            #expect(SessionScreen.subLine(status: status, returnTrip: leaving, way: .backLink) == "Taking you back to WhatsApp\u{2026}")
        }
        #expect(SessionScreen.firstReturnLine(returnTrip: leaving) == "If iOS asks, tap Open. It asks once.")
        var fellBack = leaving
        fellBack.phase = .swipeBack
        #expect(SessionScreen.firstReturnLine(returnTrip: fellBack) == nil)
        #expect(SessionScreen.subLine(status: listening, returnTrip: fellBack, way: .swipe) == "Swipe right along the bottom edge to go back to WhatsApp.")
        #expect(SessionScreen.subLine(status: HostStatus(), returnTrip: nil, way: .backLink) == nil) // nothing live
    }

    // The cue shows while there is something to go back to and it is safe to leave: not while the mic starts on a manual
    // return (iOS won't start it from the background, so leaving then could lose the take), not while leaving for the
    // app, and not once nothing is live.
    @Test func theCueWaitsForTheMicAndShowsWhileTheSessionIsLive() {
        let swipe = ReturnTrip(appName: nil, phase: .swipeBack, firstReturn: false)
        #expect(!SessionScreen.showsCue(nil, status: HostStatus(take: .recording)))
        #expect(!SessionScreen.showsCue(swipe, status: HostStatus(take: .recording)))
        #expect(SessionScreen.showsCue(nil, status: HostStatus(micOn: true, take: .recording)))
        #expect(SessionScreen.showsCue(nil, status: HostStatus(session: .ready, micOn: true)))
        for take in [TakePhase.stopping, .transcribing, .delivering] {
            #expect(SessionScreen.showsCue(nil, status: HostStatus(session: .ready, micOn: true, take: take)))
        }
        let outcome = HostStatus(session: .ready, micOn: true, message: "No speech heard.")
        #expect(SessionScreen.showsCue(nil, status: outcome))
        #expect(SessionScreen.primaryLine(status: outcome, returnTrip: nil) == "No speech heard.")
        #expect(!SessionScreen.showsCue(nil, status: HostStatus()))
        let leaving = ReturnTrip(appName: "WhatsApp", phase: .leaving, firstReturn: false)
        #expect(!SessionScreen.showsCue(leaving, status: HostStatus(micOn: true, take: .recording)))
    }

    // The cue's motion: 3 rounds of 1.65 s, then the still. In each round's beat the ring fades in, lands with its ripple,
    // slides right from 0.3 to 0.85 s (eased, its trail behind it), holds, and fades out over its last 0.15 s; nothing is
    // drawn for the 0.35 s after it.
    @Test func theCuePlaysThreeRoundsThenRestsOnItsStill() throws {
        func near(_ value: Double?, _ expected: Double) -> Bool { value.map { abs($0 - expected) < 1e-9 } ?? false }
        func shot(_ time: Double) throws -> CueTimeline.Shot { try #require(CueTimeline.shot(at: time)) }
        #expect(near(CueTimeline.length, 4.95))
        #expect(try shot(0).opacity == 0)
        #expect(near(try shot(0.06).opacity, 0.5))
        #expect(near(try shot(0.06).ripple, 0))
        #expect(near(try shot(0.21).ripple, 0.5))
        #expect(try shot(0.36).ripple == nil)
        #expect(try shot(0.3).slide == 0)
        #expect(near(try shot(0.575).slide, 0.5))
        #expect(try shot(0.85).slide == 1)
        #expect(try shot(1.15).opacity == 1)
        #expect(near(try shot(1.225).opacity, 0.5))
        #expect(try !shot(1.2).still)
        #expect(CueTimeline.shot(at: 1.3) == nil)
        #expect(CueTimeline.shot(at: 1.6) == nil)
        #expect(near(try shot(1.65 + 0.575).slide, 0.5)) // the second round
        #expect(near(try shot(3.3 + 0.575).slide, 0.5)) // the third
        #expect(CueTimeline.shot(at: 4.9) == nil) // its rest
        for time in [4.95, 6, 600] {
            let still = try shot(time)
            #expect(still.still && still.slide == 1 && still.opacity == 1 && still.ripple == nil)
        }
    }
}
