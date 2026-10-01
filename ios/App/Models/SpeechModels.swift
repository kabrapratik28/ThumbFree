import Foundation
import Observation
import TFCore

/// The two speech models, which one the engine uses, and Download on Wi-Fi only. A new choice applies from
/// your next take.
@MainActor @Observable final class SpeechModels {
    static let choiceKey = "TFSpeechModel"

    let english: SpeechModel
    let multilingual: SpeechModel
    private(set) var activeID: String
    /// The folder the engine loads from now on (nil: no model), after a choice, a delete or a first download.
    var onChange: (URL?) -> Void = { _ in }
    private let defaults: UserDefaults

    init(english: SpeechModel, multilingual: SpeechModel, defaults: UserDefaults = .standard) {
        self.english = english
        self.multilingual = multilingual
        self.defaults = defaults
        activeID = defaults.string(forKey: Self.choiceKey) == multilingual.id ? multilingual.id : english.id
        // A saved choice whose model is missing hands over to the other one when it is here, as Delete does.
        if active.phase != .ready, let other = all.first(where: { $0.phase == .ready }) {
            activeID = other.id
            defaults.set(other.id, forKey: Self.choiceKey)
        }
        for model in [english, multilingual] {
            model.onInstalled = { [weak self] _ in self?.installed(model) }
        }
    }

    var all: [SpeechModel] { [english, multilingual] }
    /// The model the engine uses.
    var active: SpeechModel { activeID == multilingual.id ? multilingual : english }

    /// Download on Wi-Fi only: a new download waits for Wi-Fi unless this is off. On by default.
    var wifiOnly: Bool {
        get {
            access(keyPath: \.wifiOnly)
            return defaults.object(forKey: SpeechModel.wifiOnlyKey) as? Bool ?? true
        }
        set {
            withMutation(keyPath: \.wifiOnly) { defaults.set(newValue, forKey: SpeechModel.wifiOnlyKey) }
        }
    }

    /// Use this model from your next take. Only a model that is here can be chosen.
    func choose(_ model: SpeechModel) {
        guard model.phase == .ready, model.id != activeID else { return }
        activeID = model.id
        defaults.set(model.id, forKey: Self.choiceKey)
        onChange(model.usableFolder)
    }

    /// Deletes a model (Settings asks first). The model in use hands over to the other one when it is here, and the
    /// switch is kept; else there is no model until a download.
    func delete(_ model: SpeechModel) {
        model.delete()
        guard model.id == activeID, model.phase == .missing else { return }
        if let other = all.first(where: { $0.id != model.id && $0.phase == .ready }) {
            choose(other)
        } else {
            onChange(nil)
        }
    }

    /// A download finished: the engine takes it when it is the model in use, or when the model in use is not here.
    private func installed(_ model: SpeechModel) {
        if model.id != activeID, active.phase != .ready {
            activeID = model.id
            defaults.set(model.id, forKey: Self.choiceKey)
        }
        if model.id == activeID { onChange(model.usableFolder) }
    }
}
