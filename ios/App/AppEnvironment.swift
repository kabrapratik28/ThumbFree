import Foundation
import os
import TFCore
import TFEngine
import UIKit

/// Builds the app's SessionHost, SpeechModels and Dictionary. Launch arguments for tests and the Simulator: `-TFAudioFile <path>`
/// replays that WAV in real time instead of the microphone; `-TFFakeEngine YES` uses a fixed text instead of Parakeet,
/// which needs no model (`-TFFakeText <text>` picks the text); `-TFResetState YES` starts with no history (and no take
/// counted as having given text), no IPC files, no downloaded model and the welcome flow not begun (`-TFWelcomeDone YES`
/// or `NO` then decides whether it shows), and an empty Dictionary;
/// `-TFModelFixture <file>` makes that one file the model to download, read from disk, so UI tests stay offline. On a
/// phone the engine uses the downloaded model; on the Simulator the Mac's cached model (FluidAudio folder) comes first.
/// `-TFKeepSetup YES` (Debug builds) keeps this launch's audio file, engine and welcome answer for the next launch only:
/// the one iOS makes with no arguments when a keyboard's dictate link opens the closed app. These test arguments exist only
/// in Debug builds: a Release (store) build compiles them out.
enum AppEnvironment {
    #if DEBUG
    static let fakeText = "and so my fellow americans ask not what your country can do for you"
    #endif
    /// Where downloaded models live: Application Support/Models (excluded from backups by the downloader).
    static let modelsRoot = URL.applicationSupportDirectory.appendingPathComponent("Models", isDirectory: true)
    private static let log = Logger(subsystem: Brand.bundleID, category: "startup")
    /// `-TFKeepSetup`'s saved setup for the next launch only (`keepSetup(_:)`).
    private static let keptSetupKey = "TFKeptSetup"

    @MainActor static func make(defaults: UserDefaults = .standard)
        -> (host: SessionHost, models: SpeechModels, dictionary: DictionaryStore, settings: AppSettings) {
        #if targetEnvironment(simulator)
        let environment = ProcessInfo.processInfo.environment
        if environment["TF_MODELS_DIR"] == nil, let home = environment["SIMULATOR_HOST_HOME"] {
            setenv("TF_MODELS_DIR", home + "/Library/Application Support/FluidAudio/Models", 0) // read by DevModels
        }
        #endif
        let historyRoot = URL.applicationSupportDirectory.appendingPathComponent("History", isDirectory: true)
        let ipc: URL
        do {
            ipc = try AppGroup.ipcDirectory()
        } catch {
            let code = error as NSError // domain and code only: never user text
            log.error("No App Group folder (\(code.domain, privacy: .public) \(code.code, privacy: .public)); the keyboard cannot reach this app")
            ipc = URL.temporaryDirectory.appendingPathComponent("IPC", isDirectory: true)
        }
        #if DEBUG
        if defaults.bool(forKey: "TFResetState") {
            try? FileManager.default.removeItem(at: historyRoot)
            try? FileManager.default.removeItem(at: ipc)
            try? FileManager.default.removeItem(at: Self.modelsRoot)
            defaults.removeObject(forKey: WelcomeView.stepKey)
            defaults.removeObject(forKey: WelcomeView.tripKey)
            defaults.removeObject(forKey: "TFAudioFile") // an older -TFKeepSetup saved these two with the welcome answer
            defaults.removeObject(forKey: "TFFakeEngine")
            defaults.removeObject(forKey: WelcomeView.doneKey)
            defaults.removeObject(forKey: DictionaryStore.key)
            defaults.removeObject(forKey: SpeechModels.choiceKey)
            defaults.removeObject(forKey: SpeechModel.loadedKey)
            defaults.removeObject(forKey: SpeechModel.wifiOnlyKey)
            defaults.removeObject(forKey: SessionHost.textTakesKey)
            for key in [AppSettings.sessionMinutesKey, AppSettings.keepDaysKey, AppSettings.keepCountKey] { defaults.removeObject(forKey: key) }
            #if DEBUG
            UserDefaults(suiteName: Brand.appGroupID)?.set(UUID().uuidString, forKey: LearnedWords.resetKey) // the keyboard's words too
            #endif
            // Runs before keepSetup below, so a keep-setup launch that follows this reset is unaffected: without this, a
            // keep-setup UI test that fails before iOS relaunches the app would leave its fake engine and test audio to
            // register on the next launch of any kind.
            defaults.removeObject(forKey: keptSetupKey)
        }
        keepSetup(defaults)
        #endif
        let history = HistoryStore(root: historyRoot)
        let shared = SharedStore(directory: ipc)
        do {
            _ = try Recovery.run(history, shared: shared) // before the first take
        } catch {
            let code = error as NSError
            log.error("Startup recovery failed (\(code.domain, privacy: .public) \(code.code, privacy: .public)); takes cut off last time keep their old status")
        }
        let models = speechModels(root: Self.modelsRoot, defaults: defaults)
        #if DEBUG
        let audioFile = defaults.string(forKey: "TFAudioFile").map { URL(fileURLWithPath: $0) }
        let engine: EngineSource = defaults.bool(forKey: "TFFakeEngine")
            ? .fixed(defaults.string(forKey: "TFFakeText") ?? fakeText) : .parakeet(models.active.usableFolder)
        #else
        let audioFile: URL? = nil
        let engine = EngineSource.parakeet(models.active.usableFolder)
        #endif
        let host = SessionHost(history: history, shared: shared, engine: engine, returnDelayMs: returnDelayMs(defaults),
                               defaults: defaults) {
            if let audioFile, let file = try? FileAudioSource(url: audioFile, realTime: true) { return file }
            return MicAudioSource()
        }
        // Automatic return: the app opens the host's URL scheme and remembers which apps it returned to.
        // UIApplication.shared.open is the app's, not the keyboard's, so it is fine here.
        host.openHost = { url, completion in UIApplication.shared.open(url, options: [:]) { completion($0) } }
        host.onReturned = { AutoReturn.markReturned(to: $0) }
        host.hasReturned = { AutoReturn.hasReturned(to: $0) }
        connect(models, to: host)
        routeStart(to: host)
        for model in models.all { model.resumeIfStarted() }
        #if DEBUG
        // `-TFHoldDownload <percent>`: the model's download shows held at that point (`SpeechModel.holdDownload`).
        if defaults.object(forKey: "TFHoldDownload") != nil { models.active.holdDownload(at: defaults.integer(forKey: "TFHoldDownload")) }
        // `-TFHoldSpeech wifi|connection|checking|paused|noSpace|checkFailed|missing`: the model shows held so
        // (`SpeechModel.hold`); `-TFHoldEngine loading|failed`: the engine's phase, which then never loads.
        if let state = defaults.string(forKey: "TFHoldSpeech") { models.active.hold(state) }
        if let phase = defaults.string(forKey: "TFHoldEngine").flatMap(EnginePhase.init(rawValue:)) { host.holdEngine(phase) }
        #endif
        let dictionary = DictionaryStore(defaults: defaults, shared: UserDefaults(suiteName: Brand.appGroupID))
        host.dictionary = dictionary.entries
        dictionary.onChange = { host.dictionary = $0 }
        let settings = AppSettings(defaults: defaults)
        settings.apply(to: host)
        settings.onChange = { $0.apply(to: host) }
        host.applyDayRetention() // a day limit also applies with no new take (and each time the app becomes active)
        host.publish() // keyboards learn at once whether there is a model
        return (host, models, dictionary, settings)
    }

    /// The model in use drives the engine, and each finished load marks the model whose folder loaded.
    @MainActor static func connect(_ models: SpeechModels, to host: SessionHost) {
        models.onChange = { host.useModel($0) }
        host.onEngineReady = { [weak models] folder in models?.engineLoaded(folder) }
    }

    /// Start ThumbFree (Control Center, the Action Button, Shortcuts) acts on this host: it starts the host's session, or
    /// with no model opens the model's offer on Home.
    @MainActor static func routeStart(to host: SessionHost) {
        StartSessionIntent.start = { await host.startIdleSession() }
        StartSessionIntent.modelOffer = { host.hasModel ? nil : DictateLink.model }
    }

    #if DEBUG
    /// `-TFKeepSetup YES` (UI tests): this launch's test setup waits in the defaults for the next launch, which uses it
    /// and clears it. It goes into the registration domain there, which is never saved: the launch after that is an
    /// ordinary one, never in test mode.
    @MainActor static func keepSetup(_ defaults: UserDefaults) {
        if defaults.bool(forKey: "TFKeepSetup") {
            let setup = ["TFAudioFile", "TFFakeEngine", WelcomeView.doneKey].compactMap { key in defaults.object(forKey: key).map { (key, $0) } }
            defaults.set(Dictionary(uniqueKeysWithValues: setup), forKey: keptSetupKey)
        } else if let setup = defaults.dictionary(forKey: keptSetupKey) {
            defaults.removeObject(forKey: keptSetupKey)
            defaults.register(defaults: setup)
        }
    }
    #endif

    /// The Debug open-delay for automatic return (`-TFReturnDelayMs <n>`), for trying 250, 500, 1000 and 2000 ms on a
    /// phone. 0 (the default and every Release build) opens as soon as audio is flowing.
    private static func returnDelayMs(_ defaults: UserDefaults) -> Int {
        #if DEBUG
        return max(0, defaults.integer(forKey: "TFReturnDelayMs"))
        #else
        return 0
        #endif
    }

    /// English and Multilingual: each ready when, on the Simulator, the Mac has a cached copy, else once its download
    /// passes the launch check. `-TFFakeEngine` needs none, unless a fixture is set; `-TFModelFixture` stands in for
    /// English.
    @MainActor private static func speechModels(root: URL, defaults: UserDefaults) -> SpeechModels {
        #if DEBUG
        let fixturePath = defaults.string(forKey: "TFModelFixture"), fakeEngine = defaults.bool(forKey: "TFFakeEngine")
        let fixture = fixturePath.flatMap(Self.fixture)
        #else
        let fixturePath: String? = nil, fakeEngine = false, fixture: (entry: ModelEntry, url: URL)? = nil
        #endif
        var downloader = ModelDownloader()
        if fixture != nil { // a fixture is a file URL, and a background session takes only HTTP and HTTPS
            let transfers = ModelTransfers.foreground(root: root)
            downloader.transfers = { _ in transfers }
        }
        if let fixture {
            downloader.source = { _, _ in fixture.url }
        } else if fixturePath != nil { // a test run never reaches the network: every download fails at once instead
            log.error("-TFModelFixture: the file could not be read or hashed; downloads fail without using the network")
            downloader.source = { _, _ in nil }
        }
        let network = NetworkWatch()
        func model(_ entries: [ModelEntry], _ variant: ModelVariant) -> SpeechModel {
            let folder = root.appendingPathComponent(entries[0].id, isDirectory: true)
            #if targetEnvironment(simulator)
            let cached = fixture == nil ? DevModels.directory(for: variant) : nil
            #else
            let cached: URL? = nil
            #endif
            let ready = cached != nil || ModelDownloader.isInstalled(entries, in: folder)
                || (fixture == nil && fakeEngine)
            return SpeechModel(entries: entries, folder: folder, downloader: downloader, ready: ready, cached: cached,
                               network: network, defaults: defaults)
        }
        return SpeechModels(english: model(fixture.map { [$0.entry] } ?? SpeechModel.english, .v2),
                            multilingual: model(SpeechModel.multilingual, .v3), defaults: defaults)
    }

    #if DEBUG
    /// `-TFModelFixture <file>`: that one file is the model, fetched from disk and checked by its own size and SHA-256.
    private static func fixture(_ path: String) -> (entry: ModelEntry, url: URL)? {
        let url = URL(fileURLWithPath: path)
        guard let bytes = ModelDownloader.size(of: url), let sha256 = try? ModelVerifier.sha256(of: url) else { return nil }
        let file = ModelFile(path: url.lastPathComponent, bytes: bytes, sha256: sha256)
        return (ModelEntry(id: "test-fixture", displayName: "English", summary: "", repo: "", revision: "", files: [file],
                           languageHint: "en"), url)
    }
    #endif
}
