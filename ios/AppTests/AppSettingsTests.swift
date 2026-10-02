import Foundation
import Testing
import TFCore
@testable import ThumbFree

/// Session length and History retention, as Settings keeps them and the host uses them.
@MainActor @Suite final class AppSettingsTests {
    let suite = "AppSettingsTests-\(UUID().uuidString)"
    let defaults: UserDefaults
    let root: URL

    init() throws {
        defaults = try #require(UserDefaults(suiteName: suite))
        root = try TestFiles.folder()
    }

    deinit {
        UserDefaults.standard.removePersistentDomain(forName: suite)
        try? FileManager.default.removeItem(at: root)
    }

    // The Android defaults: 5 minutes, keep forever, at most 200 takes.
    @Test func theDefaultsAre5MinutesForeverAnd200() {
        let settings = AppSettings(defaults: defaults)
        #expect(settings.sessionMinutes == 5)
        #expect(settings.keepDays == nil)
        #expect(settings.keepCount == 200)
    }

    // "Forever" and "No limit" are kept too, and every change reaches the host at once.
    @Test func choicesAreKeptAndReachTheHost() {
        let settings = AppSettings(defaults: defaults)
        let host = SessionHost(history: HistoryStore(root: root), shared: SharedStore(directory: root), engine: .fixed("hi"),
                               defaults: defaults) { MuteSource() }
        settings.onChange = { $0.apply(to: host) }
        settings.sessionMinutes = 2
        settings.keepDays = 30
        settings.keepCount = nil
        #expect(host.idleTimeout == 120)
        #expect(host.keepDays == 30)
        #expect(host.keepCount == nil)
        let again = AppSettings(defaults: defaults)
        #expect(again.sessionMinutes == 2)
        #expect(again.keepDays == 30)
        #expect(again.keepCount == nil)
    }

    // UI tests (Debug builds): -TFEndSessions ends each session with its take, whatever length is chosen; a test of the
    // keyboard's bar relies on it, since a live session refreshes the bar every second.
    @Test func endSessionsEndsEachSessionWithItsTake() {
        defaults.set(true, forKey: "TFEndSessions")
        let host = SessionHost(history: HistoryStore(root: root), shared: SharedStore(directory: root), engine: .fixed("hi"),
                               defaults: defaults) { MuteSource() }
        AppSettings(defaults: defaults).apply(to: host)
        #expect(host.idleTimeout == 0)
    }

    @Test func theWarningSpeaksPlainEnglish() {
        #expect(AppSettings.deleteWarning(1) == "The new rule deletes 1 take and its recording now. It can't be undone.")
        #expect(AppSettings.deleteWarning(2) == "The new rule deletes 2 takes and their recordings now. It can't be undone.")
    }
}
