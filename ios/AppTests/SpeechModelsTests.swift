import CryptoKit
import Foundation
import Synchronization
import Testing
import TFCore
@testable import ThumbFree

/// English, Multilingual and which one the engine uses.
@MainActor @Suite final class SpeechModelsTests {
    let root: URL
    let defaults: UserDefaults
    let suite = "SpeechModelsTests-\(UUID().uuidString)"
    let repo = "test/\(UUID().uuidString)"
    let data = Data(repeating: 7, count: 2_000)
    let transfers: ModelTransfers

    init() throws {
        root = try TestFiles.folder()
        defaults = try #require(UserDefaults(suiteName: suite))
        transfers = ModelTransfers.foreground(root: root, protocols: [StubServer.self])
    }

    deinit {
        transfers.invalidate()
        UserDefaults.standard.removePersistentDomain(forName: suite)
        try? FileManager.default.removeItem(at: root)
    }

    /// An English model of one small file that `StubServer` serves with `replies`. `freeBytes` runs where the download
    /// checks for space, before it fetches anything.
    func stubEnglish(_ replies: [StubServer.Reply], freeBytes: @escaping @Sendable () -> Int64 = { .max }) throws -> SpeechModel {
        StubServer.serve(try #require(URL(string: "https://huggingface.co/\(repo)/resolve/abc/weight.bin")), replies)
        let sha256 = SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
        let entry = ModelEntry(id: "test", displayName: "English", summary: "", repo: repo, revision: "abc",
                               files: [ModelFile(path: "weight.bin", bytes: Int64(data.count), sha256: sha256)], languageHint: "en")
        let transfers = transfers
        return SpeechModel(entries: [entry], folder: root.appendingPathComponent("test"),
                           downloader: ModelDownloader(transfers: { _ in transfers }, sleep: { _ in }, freeBytes: freeBytes),
                           ready: false, defaults: defaults)
    }

    /// Polls `condition` for up to 10 s without letting go of the main actor, so work queued for it (a download's end)
    /// waits meanwhile. Returns whether the condition came true.
    func holdMainActor(until condition: () -> Bool) -> Bool {
        let end = Date.now.addingTimeInterval(10)
        while !condition(), Date.now < end { Thread.sleep(forTimeInterval: 0.001) }
        return condition()
    }

    func model(_ entries: [ModelEntry], ready: Bool) -> SpeechModel {
        SpeechModel(entries: entries, folder: root.appendingPathComponent(entries[0].id), ready: ready, defaults: defaults)
    }

    func models(english: Bool, multilingual: Bool, languages: [String] = ["en-US"]) -> SpeechModels {
        SpeechModels(english: model(SpeechModel.english, ready: english), multilingual: model(SpeechModel.multilingual, ready: multilingual),
                     defaults: defaults, preferredLanguages: languages)
    }

    // English is the default; only a model that is here can be chosen, and the choice is kept.
    @Test func onlyAModelThatIsHereCanBeChosen() {
        let models = models(english: true, multilingual: false)
        var folders: [URL?] = []
        models.onChange = { folders.append($0) }
        #expect(models.active === models.english)
        models.choose(models.multilingual)
        #expect(models.active === models.english)
        #expect(folders.isEmpty)
        let both = self.models(english: true, multilingual: true)
        both.onChange = { folders.append($0) }
        both.choose(both.multilingual)
        #expect(both.active === both.multilingual)
        #expect(folders == [both.multilingual.folder])
        #expect(self.models(english: true, multilingual: true).active.id == ModelCatalog.v3.id) // kept
    }

    // Deleting the model in use hands over to the other one when it is here, else leaves no model.
    @Test func deletingTheModelInUseHandsOver() {
        let models = models(english: true, multilingual: true)
        var folders: [URL?] = []
        models.onChange = { folders.append($0) }
        models.delete(models.english)
        #expect(models.active === models.multilingual)
        models.delete(models.multilingual)
        #expect(models.multilingual.phase == .missing)
        #expect(folders == [models.multilingual.folder, nil])
    }

    // A saved choice whose model is missing falls back to the other model when that one is here, and the switch is kept.
    @Test func aMissingChoiceFallsBackToTheModelThatIsHere() {
        defaults.set(ModelCatalog.v3.id, forKey: SpeechModels.choiceKey)
        let models = models(english: true, multilingual: false)
        #expect(models.active === models.english)
        #expect(defaults.string(forKey: SpeechModels.choiceKey) == ModelCatalog.v2.id)
        defaults.set(ModelCatalog.v3.id, forKey: SpeechModels.choiceKey)
        let none = self.models(english: false, multilingual: false)
        #expect(none.active === none.multilingual) // neither is here: the choice stays
    }

    // The first model follows the iPhone's languages: Multilingual when any of them, by language code, is one of its
    // languages other than English; else English.
    @Test func theFirstModelFollowsTheIPhonesLanguages() {
        #expect(!SpeechModels.startsMultilingual(["en-US"]))
        #expect(!SpeechModels.startsMultilingual(["en-GB", "en"]))
        #expect(SpeechModels.startsMultilingual(["de-DE"]))
        #expect(SpeechModels.startsMultilingual(["en-US", "fr-FR"])) // English first, then French
        #expect(SpeechModels.startsMultilingual(["pt-BR"])) // a language with its region
        #expect(SpeechModels.startsMultilingual(["es-419"]))
        #expect(!SpeechModels.startsMultilingual(["ja-JP"]))
        #expect(!SpeechModels.startsMultilingual(["nb-NO", "no"])) // Norwegian is not one of them
        #expect(!SpeechModels.startsMultilingual([]))
    }

    // The one list of the Multilingual model's languages: 25, English among them, as Settings names them.
    @Test func theMultilingualModelHas25Languages() {
        #expect(SpeechModels.languages.count == 25)
        #expect(Set(SpeechModels.languages.map(\.key)).count == 25)
        #expect(SettingsView.languages == "Bulgarian, Croatian, Czech, Danish, Dutch, English, Estonian, Finnish, French, German, Greek, Hungarian, Italian, Latvian, Lithuanian, Maltese, Polish, Portuguese, Romanian, Russian, Slovak, Slovenian, Spanish, Swedish, Ukrainian")
    }

    // A first run saves the model it starts with, so it stays put when the iPhone's languages change.
    @Test func aFirstRunSavesItsModel() {
        let german = models(english: false, multilingual: false, languages: ["de-DE"])
        #expect(german.active === german.multilingual)
        #expect(defaults.string(forKey: SpeechModels.choiceKey) == ModelCatalog.v3.id)
        let later = models(english: false, multilingual: false, languages: ["en-US"])
        #expect(later.active === later.multilingual)
    }

    // An installed model, a saved choice or a download already begun wins over the iPhone's languages: an update from
    // 1.0.x keeps English, and nothing is saved for it.
    @Test func aModelOrASavedChoiceWinsOverTheLanguages() throws {
        let installed = models(english: true, multilingual: false, languages: ["de-DE"])
        #expect(installed.active === installed.english)
        #expect(defaults.string(forKey: SpeechModels.choiceKey) == nil)
        let staging = ModelDownloader.staging(for: root.appendingPathComponent(ModelCatalog.v2.id))
        try FileManager.default.createDirectory(at: staging, withIntermediateDirectories: true)
        let started = models(english: false, multilingual: false, languages: ["de-DE"])
        #expect(started.active === started.english)
        #expect(defaults.string(forKey: SpeechModels.choiceKey) == nil)
        try FileManager.default.removeItem(at: staging)
        defaults.set(ModelCatalog.v2.id, forKey: SpeechModels.choiceKey)
        let saved = models(english: false, multilingual: false, languages: ["de-DE"])
        #expect(saved.active === saved.english)
    }

    // The welcome's switch: the shown model's download stops, the other model becomes the one to download, and the choice
    // is kept. With a model here (the welcome shown again, or a download that ended while Change language's sheet was up),
    // the choice still takes: the welcome then waits for the model chosen, and the one here stays on disk, left, with the
    // engine told nothing until the new one is in; a relaunch keeps the choice.
    @Test func theWelcomeSwitchesTheModelToDownload() async throws {
        let english = try stubEnglish([.stall(data)])
        let models = SpeechModels(english: english, multilingual: model(SpeechModel.multilingual, ready: false),
                                  defaults: defaults, preferredLanguages: ["en-US"])
        english.download()
        try await waitUntil { english.started }
        models.chooseToDownload(models.multilingual)
        #expect(models.active === models.multilingual)
        #expect(defaults.string(forKey: SpeechModels.choiceKey) == ModelCatalog.v3.id)
        try await waitUntil { english.phase == .missing }
        #expect(!english.started) // Cancel deleted what came
        models.chooseToDownload(models.english)
        #expect(models.active === models.english)
        #expect(defaults.string(forKey: SpeechModels.choiceKey) == "test")
        let here = self.models(english: true, multilingual: false)
        var folders: [URL?] = []
        here.onChange = { folders.append($0) }
        here.chooseToDownload(here.multilingual)
        #expect(here.active === here.multilingual)
        #expect(here.english.phase == .ready)
        #expect(folders.isEmpty)
        #expect(defaults.string(forKey: SpeechModels.leftKey) == ModelCatalog.v2.id)
        let relaunched = self.models(english: true, multilingual: false) // English is left: no hand-over back to it
        #expect(relaunched.active === relaunched.multilingual)
    }

    // Choosing a language whose model is here stops the download of the model in use, as choosing one still to download
    // does: the engine takes the model here at once, and the model left never takes over should its download still end
    // ready. A model not here starts its download.
    @Test func choosingAModelThatIsHereStopsTheOtherDownload() async throws {
        let english = try stubEnglish([.stall(data)])
        let models = SpeechModels(english: english, multilingual: model(SpeechModel.multilingual, ready: true),
                                  defaults: defaults, preferredLanguages: ["de-DE"])
        var folders: [URL?] = []
        models.onChange = { folders.append($0) }
        models.chooseLanguage(models.english) // English: its download starts
        try await waitUntil { english.started }
        #expect(models.active === models.english)
        models.chooseLanguage(models.multilingual) // Other languages, here already
        #expect(models.active === models.multilingual)
        #expect(folders == [models.multilingual.usableFolder])
        try await waitUntil { english.phase == .missing }
        #expect(!english.started) // its Cancel deleted what came
        #expect(defaults.string(forKey: SpeechModels.leftKey) == "test")
    }

    // The welcome's switch after a failed download (nothing runs, so there is no download for its Cancel to stop) still
    // deletes what came and the network kept for it, so no later launch takes up the model left in the background.
    @Test func theSwitchDropsAFailedDownload() async throws {
        let english = try stubEnglish([.status(404), .file(data)])
        let models = SpeechModels(english: english, multilingual: model(SpeechModel.multilingual, ready: false),
                                  defaults: defaults, preferredLanguages: ["en-US"])
        english.download()
        try await waitUntil { if case .failed = english.phase { true } else { false } }
        #expect(english.started)
        models.chooseToDownload(models.multilingual)
        #expect(!english.started)
        #expect(english.phase == .missing)
        #expect(defaults.object(forKey: "\(SpeechModel.networkKey).test") == nil)
        english.resumeIfStarted() // the next launch
        #expect(english.phase == .missing)
    }

    // A download past its last cancellation check (the receipt and the move into place) ends ready even after the
    // welcome's switch cancelled it. The model you chose stays in use and saved, also at a relaunch, and the one you left
    // stays installed; going back to it hands it to the engine.
    @Test func aDownloadEndingAfterTheSwitchNeverUndoesIt() async throws {
        let parked = Mutex(false)
        let gate = DispatchSemaphore(value: 0)
        let english = try stubEnglish([.file(data)]) {
            parked.withLock { $0 = true }
            gate.wait() // until the test holds the main actor, where the download's end has to wait
            return .max
        }
        let models = SpeechModels(english: english, multilingual: model(SpeechModel.multilingual, ready: false),
                                  defaults: defaults, preferredLanguages: ["en-US"])
        var folders: [URL?] = []
        models.onChange = { folders.append($0) }
        english.download()
        try await waitUntil { parked.withLock { $0 } }
        gate.signal()
        let receipt = english.folder.appendingPathComponent(ModelDownloader.receiptName)
        #expect(holdMainActor { FileManager.default.fileExists(atPath: receipt.path) }, "the files never moved into place")
        models.chooseToDownload(models.multilingual) // Other language?, too late for the Cancel
        try await waitUntil { english.phase == .ready }
        #expect(models.active === models.multilingual)
        #expect(defaults.string(forKey: SpeechModels.choiceKey) == ModelCatalog.v3.id)
        #expect(folders.isEmpty)
        let relaunched = SpeechModels(english: SpeechModel(entries: english.entries, folder: english.folder, ready: true, defaults: defaults),
                                      multilingual: model(SpeechModel.multilingual, ready: false), defaults: defaults,
                                      preferredLanguages: ["en-US"])
        #expect(relaunched.active === relaunched.multilingual)
        models.chooseToDownload(models.english) // Use English
        #expect(models.active === models.english)
        #expect(folders == [english.folder])
    }

    // A download that ends while the model in use is not here is handed over, as Settings relies on: also the model the
    // welcome's switch left, once you ask for its download again.
    @Test func aDownloadEndingWhileTheModelInUseIsMissingHandsOver() async throws {
        let english = try stubEnglish([.file(data)])
        let models = SpeechModels(english: english, multilingual: model(SpeechModel.multilingual, ready: false),
                                  defaults: defaults, preferredLanguages: ["de-DE"])
        var folders: [URL?] = []
        models.onChange = { folders.append($0) }
        models.chooseToDownload(models.english) // Use English
        models.chooseToDownload(models.multilingual) // Other language?: English is left
        english.download() // Settings' Download
        try await waitUntil { english.phase == .ready }
        #expect(models.active === models.english)
        #expect(defaults.string(forKey: SpeechModels.choiceKey) == "test")
        #expect(folders == [english.folder])
    }

    // The engine's ready names the folder it loaded: that model is marked loaded, not the model chosen meanwhile (a choice
    // during a take waits for its end, so the load of the model before it can end after it). No folder, no mark.
    @Test func theFolderThatLoadedIsMarkedNotTheChoice() {
        let models = models(english: true, multilingual: true)
        let english = models.english.usableFolder
        models.choose(models.multilingual)
        models.engineLoaded(english)
        #expect(models.english.loadedBefore)
        #expect(!models.multilingual.loadedBefore)
        models.engineLoaded(nil)
        #expect(!models.multilingual.loadedBefore)
    }

    // At launch the model the welcome's switch left never takes over from your choice; choosing it in Settings ends that.
    @Test func choosingTheModelLeftEndsIt() {
        defaults.set(ModelCatalog.v3.id, forKey: SpeechModels.choiceKey)
        defaults.set(ModelCatalog.v2.id, forKey: SpeechModels.leftKey)
        let models = models(english: true, multilingual: false)
        #expect(models.active === models.multilingual)
        models.choose(models.english)
        #expect(models.active === models.english)
        #expect(defaults.string(forKey: SpeechModels.leftKey) == nil)
    }

    // Wi-Fi only is on until you turn it off, and a new download follows it.
    @Test func wifiOnlyIsOnByDefault() {
        let models = models(english: false, multilingual: false)
        #expect(models.wifiOnly)
        models.wifiOnly = false
        #expect(defaults.bool(forKey: SpeechModel.wifiOnlyKey) == false)
    }
}
