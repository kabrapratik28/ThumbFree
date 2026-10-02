import Foundation
import Testing
import TFCore
@testable import ThumbFree

/// The session host and a keyboard together, sharing one IPC folder; the test plays the Darwin notifications by calling
/// handleCommands and refresh. A take is typed only into its own field, and for the take whose press opened
/// the app, that is the field where the user tapped stop.
@MainActor @Suite final class DeliveryTests {
    let root: URL
    let shared: SharedStore
    let host: SessionHost
    let proxy = FakeProxy()
    /// The hosts' defaults (the count of takes that gave text), kept out of the test app's own.
    let suite = TestFiles.defaultsSuite("DeliveryTests", test: "host")

    init() throws {
        root = try TestFiles.folder()
        shared = SharedStore(directory: root.appendingPathComponent("IPC"))
        host = SessionHost(history: HistoryStore(root: root.appendingPathComponent("History")), shared: shared,
                           engine: .fixed(AppEnvironment.fakeText), defaults: try #require(UserDefaults(suiteName: suite))) {
            if let file = try? FileAudioSource(url: TestFiles.url("jfk.wav"), realTime: false) { return file }
            return MuteSource()
        }
    }

    deinit {
        try? FileManager.default.removeItem(at: root)
        UserDefaults.standard.removePersistentDomain(forName: suite)
    }

    func keyboard(opened: @escaping (URL) -> Void = { _ in }) -> KeyboardClient {
        KeyboardClient(shared: shared) { url, done in
            opened(url)
            done(true)
        }
    }

    /// A tap on the keyboard's mic key, then the app reads the command files.
    func tap(_ keyboard: KeyboardClient) {
        keyboard.pressDown(proxy)
        keyboard.pressUp(proxy)
        host.handleCommands()
    }

    @Test func aColdTakeTypesIntoTheFieldWhereTheUserStopped() async throws {
        var links: [URL] = []
        let keyboard = keyboard { links.append($0) }
        tap(keyboard) // in another app: no session, and ThumbFree is not on screen, so the press waits
        let press = try #require(try shared.pendingCommands().first)
        keyboard.openIfUnhandled(press) // still on disk after 150 ms: open ThumbFree with the take's link
        let take = try #require(links.first.flatMap(DictateLink.take(from:)))
        host.openLink(take) // what ThumbFreeApp does with the link
        try await waitUntil { host.status.take == .recording && host.recordedMs >= 2_000 }
        proxy.documentIdentifier = UUID() // back in the other app: the same field, with a new identity
        keyboard.appeared()
        keyboard.refresh(proxy)
        tap(keyboard) // the stop
        try await waitUntil { host.status.take == .delivering }
        keyboard.refresh(proxy) // the status notification
        #expect(proxy.text.localizedCaseInsensitiveContains("ask not what your country"))
        host.handleCommands()
        #expect(try host.history.record(take)?.status == .inserted)
        #expect(try shared.outbox().last?.state == .typed)
    }

    @Test func aWarmTakeWhoseFieldChangedIsHeldBack() async throws {
        let keyboard = keyboard()
        host.appActive = true // ThumbFree can record: the press starts the session and the take at once
        tap(keyboard)
        try await waitUntil { host.status.take == .recording && host.recordedMs >= 2_000 }
        let take = try #require(host.status.takeID)
        proxy.documentIdentifier = UUID() // the user moved to another field
        tap(keyboard) // the stop
        try await waitUntil { host.status.take == .delivering }
        keyboard.refresh(proxy)
        #expect(proxy.text.isEmpty)
        #expect(keyboard.chip?.canInsert == true)
        host.handleCommands()
        #expect(try host.history.record(take)?.status == .notInserted)
        #expect(try shared.outbox().last?.state == .heldBack)
    }
}
