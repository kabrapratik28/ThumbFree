import Foundation
import Testing
import TFCore
@testable import ThumbFree

/// English, Multilingual and which one the engine uses.
@MainActor @Suite final class SpeechModelsTests {
    let root: URL
    let defaults: UserDefaults
    let suite = "SpeechModelsTests-\(UUID().uuidString)"

    init() throws {
        root = try TestFiles.folder()
        defaults = try #require(UserDefaults(suiteName: suite))
    }

    deinit {
        UserDefaults.standard.removePersistentDomain(forName: suite)
        try? FileManager.default.removeItem(at: root)
    }

    func models(english: Bool, multilingual: Bool) -> SpeechModels {
        func model(_ entries: [ModelEntry], _ ready: Bool) -> SpeechModel {
            SpeechModel(entries: entries, folder: root.appendingPathComponent(entries[0].id), ready: ready, defaults: defaults)
        }
        return SpeechModels(english: model(SpeechModel.english, english), multilingual: model(SpeechModel.multilingual, multilingual),
                            defaults: defaults)
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

    // Wi-Fi only is on until you turn it off, and a new download follows it.
    @Test func wifiOnlyIsOnByDefault() {
        let models = models(english: false, multilingual: false)
        #expect(models.wifiOnly)
        models.wifiOnly = false
        #expect(defaults.bool(forKey: SpeechModel.wifiOnlyKey) == false)
    }
}
