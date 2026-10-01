import Foundation
import Testing
import TFCore
@testable import ThumbFree

/// Automatic return, app side: the round-trip state, opening the host once audio flows, the swipe-back fallback for
/// every failure, and the App Group check that keeps a crafted link from opening a named app. The private host lookup
/// and the real `open()` are device-only; here the opener is injected, so the app's own logic is tested on the
/// Simulator.
@MainActor @Suite(.serialized) final class AutoReturnTests {
    let root: URL
    let history: HistoryStore
    let shared: SharedStore

    init() throws {
        root = try TestFiles.folder()
        history = HistoryStore(root: root.appendingPathComponent("History"))
        shared = SharedStore(directory: root.appendingPathComponent("IPC"))
    }

    deinit { try? FileManager.default.removeItem(at: root) }

    func host(returnDelayMs: Int = 0, fallbackDelayMs: Int = 1_500) -> SessionHost {
        let host = SessionHost(history: history, shared: shared, engine: .fixed("hi"),
                                returnDelayMs: returnDelayMs, fallbackDelayMs: fallbackDelayMs) {
            if let file = try? FileAudioSource(url: TestFiles.url("jfk.wav"), realTime: false) { return file }
            return MuteSource()
        }
        host.appActive = true
        return host
    }

    /// Our keyboard's press command for a take: proof to the app that our keyboard opened the app (an outside link has none).
    func keyboardPress(_ take: UUID) throws {
        try shared.append(KeyboardCommand(takeID: take, kind: .press, target: nil, sentAt: Date()))
    }

    // A trusted host is opened by its scheme the moment audio is flowing, and once ThumbFree has really left for it, it is
    // remembered, so the first-time line drops.
    @Test func aTrustedHostIsOpenedWhenAudioFlows() async throws {
        var opened: [URL] = []
        var marked: [String] = []
        let host = host()
        host.hasReturned = { _ in false }
        host.onReturned = { marked.append($0) }
        host.openHost = { url, done in opened.append(url); done(true) }
        let take = UUID()
        try keyboardPress(take)
        host.openLink(take, host: "net.whatsapp.WhatsApp", autoReturn: true)
        #expect(host.returnTrip == ReturnTrip(appName: "WhatsApp", phase: .leaving, firstReturn: true))
        #expect(opened.isEmpty) // nothing opens before the cold take's first audio buffer
        try await waitUntil { !opened.isEmpty }
        #expect(opened.map(\.absoluteString) == ["whatsapp-consumer://"])
        host.appActive = false
        host.inBackground = true // the switch really happened
        #expect(marked == ["net.whatsapp.WhatsApp"])
        host.endSession()
    }

    // iOS's "Open in ...?" prompt: open() reports a hit while ThumbFree is still on screen behind it (inactive). Only once
    // the scene reaches the background has ThumbFree really left, and only then is the app marked returned, so a Cancel
    // on the prompt keeps the one-time "tap Open" line for next time.
    @Test func anAppIsMarkedReturnedOnlyOnceThumbFreeReallyLeft() async throws {
        var marked: [String] = []
        var attempted = false
        let host = host()
        host.hasReturned = { _ in false }
        host.onReturned = { marked.append($0) }
        host.openHost = { _, done in attempted = true; done(true) } // a hit
        let take = UUID()
        try keyboardPress(take)
        host.openLink(take, host: "net.whatsapp.WhatsApp", autoReturn: true)
        try await waitUntil { attempted }
        host.appActive = false // only the prompt is up
        #expect(marked.isEmpty) // a hit alone is not a return
        host.inBackground = true // the switch really happened
        #expect(marked == ["net.whatsapp.WhatsApp"])
        #expect(host.returnTrip == nil)
        host.endSession()
    }

    // A crafted link from another app or a web page carries a host but no keyboard press command: the host is ignored and
    // the app shows the swipe-back screen with no app name, and never opens the named app.
    @Test func aHostWithoutOurKeyboardPressIsIgnored() async throws {
        var opened = 0
        let host = host()
        host.openHost = { _, done in opened += 1; done(true) }
        host.openLink(UUID(), host: "net.whatsapp.WhatsApp", autoReturn: true) // no keyboardPress written
        #expect(host.returnTrip == ReturnTrip(appName: nil, phase: .swipeBack, firstReturn: false))
        try await waitUntil { host.status.micOn }
        try await Task.sleep(for: .milliseconds(80))
        #expect(opened == 0)
        host.endSession()
    }

    // With the setting off the app never opens the host; it shows the swipe-back screen, naming the app when it knows it.
    @Test func withAutoReturnOffItShowsSwipeBackAndNeverOpens() async throws {
        var opened = 0
        let host = host()
        host.openHost = { _, done in opened += 1; done(true) }
        let take = UUID()
        try keyboardPress(take)
        host.openLink(take, host: "net.whatsapp.WhatsApp", autoReturn: false)
        #expect(host.returnTrip == ReturnTrip(appName: "WhatsApp", phase: .swipeBack, firstReturn: false))
        try await waitUntil { host.status.micOn }
        try await Task.sleep(for: .milliseconds(80))
        #expect(opened == 0)
        host.endSession()
    }

    // An unknown or untrusted host has no target: the swipe-back screen with no app name, and nothing opened.
    @Test func anUnknownHostFallsBackAndNeverOpens() async throws {
        var opened = 0
        let host = host()
        host.openHost = { _, done in opened += 1; done(true) }
        let take = UUID()
        try keyboardPress(take)
        host.openLink(take, host: "com.apple.mobilesafari", autoReturn: true)
        #expect(host.returnTrip == ReturnTrip(appName: nil, phase: .swipeBack, firstReturn: false))
        try await waitUntil { host.status.micOn }
        try await Task.sleep(for: .milliseconds(80))
        #expect(opened == 0)
        host.endSession()
    }

    // open() returning false (the app is not installed, or iOS refused) falls back to the swipe-back screen, still listening.
    @Test func aFailedOpenFallsBackToSwipeBack() async throws {
        var opened: [URL] = []
        let host = host()
        host.hasReturned = { _ in true }
        host.openHost = { url, done in opened.append(url); done(false) }
        let take = UUID()
        try keyboardPress(take)
        host.openLink(take, host: "com.apple.MobileSMS", autoReturn: true)
        try await waitUntil { host.returnTrip?.phase == .swipeBack }
        #expect(opened.map(\.absoluteString) == ["ichat://"])
        #expect(host.status.take == .recording) // it keeps listening
        host.endSession()
    }

    // A host we have returned to before does not show the first-time "tap Open" line.
    @Test func aKnownReturnAppSkipsTheFirstTimeLine() throws {
        let host = host()
        host.hasReturned = { _ in true }
        host.openHost = { _, done in done(true) }
        let take = UUID()
        try keyboardPress(take)
        host.openLink(take, host: "net.whatsapp.WhatsApp", autoReturn: true)
        #expect(host.returnTrip?.firstReturn == false)
        host.endSession()
    }

    // The keyboard's press already ran through handleCommands() (the .active phase handler beat onOpenURL to it, or the
    // link names a take a keyboard command already started): the app still trusts the host, because it remembers every
    // press handleCommands() has read this launch, not just what is still on disk by the time the link is handled.
    @Test func aPressAlreadyConsumedByHandleCommandsStillTrustsTheHost() throws {
        let host = host()
        let take = UUID()
        try keyboardPress(take)
        host.handleCommands() // starts the take and removes the press before openLink ever runs
        #expect(try shared.pendingCommands().isEmpty)
        host.openLink(take, host: "net.whatsapp.WhatsApp", autoReturn: true)
        #expect(host.returnTrip?.phase == .leaving)
        host.endSession()
    }

    // A leftover press from before this launch is dropped before it can start anything, but handleCommands() still
    // remembers seeing it: a later link naming the same take is still trusted (the cold launch: iOS starts ThumbFree
    // only after the keyboard wrote its press).
    @Test func aPressDroppedBeforeThisLaunchStillTrustsALaterLink() throws {
        let host = host()
        let take = UUID()
        try shared.append(KeyboardCommand(takeID: take, kind: .press, target: nil, sentAt: host.launchedAt - 1))
        host.handleCommands() // drops it (from before this launch); never starts a take
        #expect(host.status.take == .idle)
        host.openLink(take, host: "net.whatsapp.WhatsApp", autoReturn: true)
        #expect(host.returnTrip?.phase == .leaving)
        host.endSession()
    }

    // A link naming a take from before this launch starts a fresh take in its place (openLink's stale-take rule), but
    // the round trip must still trust the keyboard's press for the link's own (stale) id, since that is what our
    // keyboard actually wrote, never the fresh substituted id, which the keyboard never knew about.
    @Test func aStaleTakeLinkTrustsThePressKeyedToItsOwnId() async throws {
        let host = host()
        host.hasReturned = { _ in true }
        host.openHost = { _, done in done(true) }
        let stale = UUID()
        try history.create(TakeRecord(id: stale, order: 0, startedAt: host.launchedAt - 10, modelID: "test", status: .interrupted))
        try keyboardPress(stale) // our keyboard's own press, keyed to the link's stale id
        let opened = host.openLink(stale, host: "net.whatsapp.WhatsApp", autoReturn: true)
        try await waitUntil { host.status.micOn }
        let fresh = try #require(host.status.takeID)
        #expect(fresh != stale)
        #expect(opened == fresh)
        #expect(host.returnTrip?.phase == .leaving) // trusted via the stale id's own press, not the fresh substituted one
        host.endSession()
    }

    // A press is proof for a short while only: one sent over 60 s ago is not trusted, whether handleCommands() already
    // read it or it still sits on disk, so an old take id in a replayed link cannot open a named app.
    @Test func anOldPressIsNotTrusted() throws {
        let host = host()
        let seen = UUID()
        try shared.append(KeyboardCommand(takeID: seen, kind: .press, target: nil, sentAt: Date() - 61))
        host.handleCommands() // reads it (and drops it: it is from before this launch)
        host.openLink(seen, host: "net.whatsapp.WhatsApp", autoReturn: true)
        #expect(host.returnTrip == ReturnTrip(appName: nil, phase: .swipeBack, firstReturn: false))
        host.endSession()
    }

    @Test func anOldPressOnDiskIsNotTrusted() throws {
        let host = host()
        let take = UUID()
        try shared.append(KeyboardCommand(takeID: take, kind: .press, target: nil, sentAt: Date() - 61))
        host.openLink(take, host: "net.whatsapp.WhatsApp", autoReturn: true) // still on disk when the trip is set up
        #expect(host.returnTrip == ReturnTrip(appName: nil, phase: .swipeBack, firstReturn: false))
        host.endSession()
    }

    // A press vouches for one round trip only. The same stale link again, once its first take ended, starts a second
    // fresh take (the stale-take rule), but its press was already used, so that trip shows the swipe-back screen and
    // never opens the named app. (A repeat while the take lives never sets up a second trip at all: the coldTake gate.)
    @Test func aUsedPressIsNotTrustedAgain() throws {
        let host = host()
        host.hasReturned = { _ in true }
        host.openHost = { _, done in done(false) }
        let stale = UUID()
        try history.create(TakeRecord(id: stale, order: 0, startedAt: host.launchedAt - 10, modelID: "test", status: .interrupted))
        try keyboardPress(stale)
        host.openLink(stale, host: "net.whatsapp.WhatsApp", autoReturn: true)
        #expect(host.returnTrip?.phase == .leaving) // the first trip uses the press
        let first = try #require(host.status.takeID)
        host.send(.cancel(first)) // that take ends, and its trip with it
        #expect(host.returnTrip == nil)
        host.openLink(stale, host: "net.whatsapp.WhatsApp", autoReturn: true) // the same link again: a second fresh take
        #expect(host.status.takeID != first)
        #expect(host.returnTrip == ReturnTrip(appName: nil, phase: .swipeBack, firstReturn: false))
        host.endSession()
    }

    // openLink's coldTake gate stops it running its round-trip setup twice for the same URL (onOpenURL firing again for
    // a link it already opened): the same link again changes nothing (matches the keyboard-side contract).
    @Test func aRepeatedLinkKeepsTheTrip() async throws {
        let host = host()
        host.hasReturned = { _ in true }
        host.openHost = { _, done in done(false) } // a miss, once audio flows
        let take = UUID()
        try keyboardPress(take)
        host.openLink(take, host: "net.whatsapp.WhatsApp", autoReturn: true)
        let first = host.returnTrip
        host.openLink(take, host: "net.whatsapp.WhatsApp", autoReturn: true) // the same link again, before the open ran
        #expect(host.returnTrip == first)
        try await waitUntil { host.returnTrip?.phase == .swipeBack } // the attempt ran and missed
        host.openLink(take, host: "net.whatsapp.WhatsApp", autoReturn: true) // repeated again after the miss
        #expect(host.returnTrip?.phase == .swipeBack) // the coldTake gate still holds: no re-arm back to .leaving
        host.endSession()
    }

    // open() reports success, but the OS never actually switches (a cancelled "Open in ...?" prompt, or a scheme that
    // silently no-ops): the fallback window still elapses while we stay in front, and the screen falls back to
    // swipe-back without losing the take.
    @Test func aSuccessfulOpenStillFallsBackIfWeStayInFront() async throws {
        let host = host(fallbackDelayMs: 30)
        host.hasReturned = { _ in true }
        host.openHost = { _, done in done(true) } // "hit", but we are still here when the watchdog checks
        let take = UUID()
        try keyboardPress(take)
        host.openLink(take, host: "net.whatsapp.WhatsApp", autoReturn: true)
        try await waitUntil { host.returnTrip?.phase == .swipeBack }
        #expect(host.status.take == .recording) // it keeps listening
        host.endSession()
    }

    // A system "Open in ...?" prompt reads as inactive but is not a departure (the scene never reaches .background):
    // a hit reported while it is up must not be resolved by it. Cancel it and we are back to active with the trip
    // still unresolved, so the watchdog (never wrongly cancelled by the prompt) falls back on its own schedule. The
    // app is never marked returned, not even when the user then swipes back by hand.
    @Test func aHitWhileOnlyInactiveStillFallsBackOnceBackToActive() async throws {
        var marked: [String] = []
        var pendingCompletion: ((Bool) -> Void)?
        let host = host(fallbackDelayMs: 300)
        host.hasReturned = { _ in true }
        host.onReturned = { marked.append($0) }
        host.openHost = { _, done in pendingCompletion = done } // the completion comes later, while the prompt is up
        let take = UUID()
        try keyboardPress(take)
        host.openLink(take, host: "net.whatsapp.WhatsApp", autoReturn: true)
        try await waitUntil { pendingCompletion != nil } // the attempt ran; the 300 ms watchdog started with it
        let hit = try #require(pendingCompletion)
        host.appActive = false // the "Open in ...?" prompt is up
        hit(true) // the hit arrives while only the prompt is up: inBackground stays false
        host.appActive = true // Cancel tapped: back to active, the trip is still unresolved
        #expect(host.returnTrip?.phase == .leaving) // the prompt never resolved it, and the watchdog has not fired yet
        try await waitUntil { host.returnTrip?.phase == .swipeBack } // the watchdog (never cancelled) still falls back
        #expect(host.status.take == .recording) // it keeps listening
        host.appActive = false
        host.inBackground = true // the user swipes back by hand: no return to mark
        #expect(marked.isEmpty)
        host.endSession()
    }

    // Cancel on iOS's prompt, then a quick swipe back by hand before the watchdog: coming back to active after the hit
    // shows it took ThumbFree nowhere, so the app is not marked returned.
    @Test func aCancelThenAQuickSwipeBackMarksNothing() async throws {
        var marked: [String] = []
        var pendingCompletion: ((Bool) -> Void)?
        let host = host()
        host.hasReturned = { _ in true }
        host.onReturned = { marked.append($0) }
        host.openHost = { _, done in pendingCompletion = done }
        let take = UUID()
        try keyboardPress(take)
        host.openLink(take, host: "net.whatsapp.WhatsApp", autoReturn: true)
        try await waitUntil { pendingCompletion != nil }
        let hit = try #require(pendingCompletion)
        host.appActive = false // the prompt is up
        hit(true)
        host.appActive = true // Cancel
        host.appActive = false
        host.inBackground = true // swiped back by hand, well inside the 1.5 s watchdog
        #expect(marked.isEmpty)
        host.endSession()
    }

    // A late hit, after the watchdog already fell back (ThumbFree was still in front), took ThumbFree nowhere: the app is
    // not marked returned, and the swipe-back screen stays, even once the user swipes back by hand.
    @Test func aLateHitAfterTheFallbackMarksNothing() async throws {
        var marked: [String] = []
        var pendingCompletion: ((Bool) -> Void)?
        let host = host(fallbackDelayMs: 30)
        host.hasReturned = { _ in true }
        host.onReturned = { marked.append($0) }
        host.openHost = { _, done in pendingCompletion = done } // the completion comes late
        let take = UUID()
        try keyboardPress(take)
        host.openLink(take, host: "net.whatsapp.WhatsApp", autoReturn: true)
        try await waitUntil { pendingCompletion != nil }
        let hit = try #require(pendingCompletion)
        try await waitUntil { host.returnTrip?.phase == .swipeBack } // still in front: the watchdog fell back
        hit(true) // the late hit
        host.appActive = false
        host.inBackground = true // swiped back by hand
        #expect(marked.isEmpty)
        #expect(host.returnTrip?.phase == .swipeBack) // not resolved as a return
        host.endSession()
    }

    // After a hit, actually reaching the background and coming back mid-take resolves the trip: the plain listening
    // screen, not a stale "leaving" one mistaken for a failure (which would show swipe-back and log a fallback).
    @Test func returningActiveAfterAHitShowsPlainListeningNotFallback() async throws {
        let host = host()
        host.hasReturned = { _ in true }
        host.openHost = { _, done in done(true) }
        let take = UUID()
        try keyboardPress(take)
        host.openLink(take, host: "net.whatsapp.WhatsApp", autoReturn: true)
        try await waitUntil { host.status.micOn } // the hit has already run synchronously by the time this is true
        host.appActive = false // the switch really happened
        host.inBackground = true // the scene actually reached .background: this is what resolves the trip
        host.inBackground = false
        host.appActive = true // mid-take, back in ThumbFree
        #expect(host.returnTrip == nil) // resolved: plain listening, not a stale "leaving" mistaken for a failure
        #expect(host.status.take == .recording) // it kept listening the whole time
        host.endSession()
    }

    // -TFReturnDelayMs (the Debug open-delay): the open waits for it, so other timings can be tried on a phone; nothing
    // opens until it elapses, and it still opens once it does.
    @Test func aDebugDelayWaitsBeforeOpening() async throws {
        var opened = 0
        let host = host(returnDelayMs: 500)
        host.openHost = { _, done in opened += 1; done(true) }
        let take = UUID()
        try keyboardPress(take)
        host.openLink(take, host: "net.whatsapp.WhatsApp", autoReturn: true)
        try await waitUntil { host.status.micOn }
        try await Task.sleep(for: .milliseconds(20)) // well inside the 500 ms delay
        #expect(opened == 0)
        try await waitUntil { opened == 1 }
        host.endSession()
    }

    // -TFReturnDelayMs (Debug): going inactive and back to active while the delay is still pending must not show
    // swipe-back prematurely (the delay task itself has not given up: appActive is true again by the time it checks).
    @Test func returningActiveDuringTheDebugDelayDoesNotFallBackEarly() async throws {
        var opened = 0
        let host = host(returnDelayMs: 500)
        host.openHost = { _, done in opened += 1; done(true) }
        let take = UUID()
        try keyboardPress(take)
        host.openLink(take, host: "net.whatsapp.WhatsApp", autoReturn: true)
        try await waitUntil { host.status.micOn }
        host.appActive = false
        try await Task.sleep(for: .milliseconds(20)) // well inside the 500 ms delay
        host.appActive = true // back well before the 500 ms delay elapses
        #expect(host.returnTrip?.phase == .leaving) // not swipe-back: the delay task has not given up yet
        #expect(opened == 0)
        try await waitUntil { opened == 1 } // the delay task still runs its attempt once it elapses
        host.endSession()
    }

    // Ending the take (not just the session) drops the round trip and cancels its watchdog: a fallback that would have
    // fired later never revives it for a take that is already gone.
    @Test func endingTheTakeCancelsTheRoundTripAndItsWatchdog() async throws {
        let host = host(fallbackDelayMs: 200)
        host.hasReturned = { _ in true }
        host.openHost = { _, done in done(true) }
        let take = UUID()
        try keyboardPress(take)
        host.openLink(take, host: "net.whatsapp.WhatsApp", autoReturn: true)
        try await waitUntil { host.status.micOn }
        let watchdog = host.returnTask // the fallback watchdog scheduled after the (synchronous) open attempt
        host.send(.cancel(take)) // the take ends; the session stays alive
        #expect(host.returnTrip == nil)
        #expect(watchdog?.isCancelled == true) // the ended take's watchdog is actually cancelled, not just orphaned
        try await Task.sleep(for: .milliseconds(300)) // past the 200 ms watchdog's window
        #expect(host.returnTrip == nil) // the cancelled watchdog never revived it
        host.endSession()
    }

    // Ending the session drops the round trip and cancels its watchdog too: it never comes back once the session is over.
    @Test func endingTheSessionCancelsTheRoundTripAndItsWatchdog() async throws {
        let host = host(fallbackDelayMs: 200)
        host.hasReturned = { _ in true }
        host.openHost = { _, done in done(true) }
        let take = UUID()
        try keyboardPress(take)
        host.openLink(take, host: "net.whatsapp.WhatsApp", autoReturn: true)
        try await waitUntil { host.status.micOn }
        let watchdog = host.returnTask
        host.endSession()
        #expect(host.returnTrip == nil)
        #expect(watchdog?.isCancelled == true)
        try await Task.sleep(for: .milliseconds(300)) // past the 200 ms watchdog's window
        #expect(host.returnTrip == nil)
    }

    // A late "miss" for a take that already ended, after a new cold take has started its own trip, must not touch
    // the new take's trip: the coldTake gate applies to a miss just as much as a hit.
    @Test func aLateMissAfterTheTakeEndedLeavesTheNewTakesTripUntouched() async throws {
        var pendingCompletion: ((Bool) -> Void)?
        let host = host()
        host.hasReturned = { _ in true }
        host.openHost = { _, done in pendingCompletion = done } // never resolves on its own: a stale open stays in flight
        let takeA = UUID()
        try keyboardPress(takeA)
        host.openLink(takeA, host: "net.whatsapp.WhatsApp", autoReturn: true)
        try await waitUntil { pendingCompletion != nil } // A's attempt ran; its completion is still pending
        let missA = try #require(pendingCompletion)
        host.send(.cancel(takeA)) // A ends before its own open ever resolves
        let takeB = UUID()
        try keyboardPress(takeB)
        host.openLink(takeB, host: "net.whatsapp.WhatsApp", autoReturn: true) // a new cold take, its own fresh trip
        #expect(host.returnTrip?.phase == .leaving) // B's own trip, before A's stale miss arrives
        missA(false) // A's stale miss finally arrives
        #expect(host.returnTrip?.phase == .leaving) // still B's trip, not clobbered to .swipeBack by A's stale miss
        host.endSession()
    }
}
