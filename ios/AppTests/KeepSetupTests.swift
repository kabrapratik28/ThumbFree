import Foundation
import Testing
@testable import ThumbFree

#if DEBUG
/// `-TFKeepSetup YES` (UI tests, Debug builds): the launch iOS makes with no arguments, when a keyboard's link opens the
/// closed app, gets the first launch's test setup once. None of it may stay saved, or a later ordinary launch would
/// still use the fixed engine and the test audio.
@MainActor @Suite struct KeepSetupTests {
    @Test func theSetupReachesTheNextLaunchOnlyAndIsNeverSaved() throws {
        let suite = "KeepSetupTests-\(UUID().uuidString)"
        let defaults = try #require(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        func saved() -> Set<String> { Set((defaults.persistentDomain(forName: suite) ?? [:]).keys) }
        let setup: Set<String> = ["TFAudioFile", "TFFakeEngine", WelcomeView.doneKey]
        // The UI test's launch. Launch arguments are never saved: the registration domain stands in for them here.
        defaults.register(defaults: ["TFAudioFile": "/tmp/jfk.wav", "TFFakeEngine": "YES", WelcomeView.doneKey: "YES",
                                     "TFKeepSetup": "YES"])
        AppEnvironment.keepSetup(defaults)
        #expect(saved().isDisjoint(with: setup)) // kept aside for the next launch, not saved as the app's own settings
        // The launch iOS makes, with no arguments: it uses the kept setup, then clears it.
        defaults.register(defaults: ["TFKeepSetup": "NO"])
        AppEnvironment.keepSetup(defaults)
        #expect(defaults.string(forKey: "TFAudioFile") == "/tmp/jfk.wav")
        #expect(defaults.bool(forKey: "TFFakeEngine"))
        #expect(defaults.bool(forKey: WelcomeView.doneKey))
        #expect(saved().isEmpty) // so the launch after it is an ordinary one
    }
}
#endif
