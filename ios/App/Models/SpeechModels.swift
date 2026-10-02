import Foundation
import Observation
import TFCore

/// The two speech models, which one the engine uses, and Download on Wi-Fi only. A new choice applies from
/// your next take. A first run starts with the model for the iPhone's languages.
@MainActor @Observable final class SpeechModels {
    static let choiceKey = "TFSpeechModel"
    /// The model the welcome's switch left. Its download can still end ready after the switch's Cancel, since nothing
    /// stops the move into place once the files are checked: it then never takes over from the model you chose, neither
    /// then nor at a relaunch, until you choose it or ask for its download again.
    static let leftKey = "TFSpeechModelLeft"
    /// The Multilingual model's 25 languages: language code and English name, in the order Settings lists them.
    static let languages: KeyValuePairs<String, String> = [
        "bg": "Bulgarian", "hr": "Croatian", "cs": "Czech", "da": "Danish", "nl": "Dutch", "en": "English",
        "et": "Estonian", "fi": "Finnish", "fr": "French", "de": "German", "el": "Greek", "hu": "Hungarian",
        "it": "Italian", "lv": "Latvian", "lt": "Lithuanian", "mt": "Maltese", "pl": "Polish", "pt": "Portuguese",
        "ro": "Romanian", "ru": "Russian", "sk": "Slovak", "sl": "Slovenian", "es": "Spanish", "sv": "Swedish",
        "uk": "Ukrainian",
    ]

    let english: SpeechModel
    let multilingual: SpeechModel
    private(set) var activeID: String
    /// The folder the engine loads from now on (nil: no model), after a choice, a delete or a first download.
    var onChange: (URL?) -> Void = { _ in }
    private let defaults: UserDefaults

    init(english: SpeechModel, multilingual: SpeechModel, defaults: UserDefaults = .standard,
         preferredLanguages: [String] = Locale.preferredLanguages) {
        self.english = english
        self.multilingual = multilingual
        self.defaults = defaults
        var saved = defaults.string(forKey: Self.choiceKey)
        // A first run (no choice saved, no model here or begun) starts with the model for the iPhone's languages and saves
        // it, so a later change of languages moves nothing. A model, a begun download or a saved choice (an update from
        // 1.0.x) keeps its model.
        if saved == nil, ![english, multilingual].contains(where: { $0.phase == .ready || $0.started }) {
            saved = Self.startsMultilingual(preferredLanguages) ? multilingual.id : english.id
            defaults.set(saved, forKey: Self.choiceKey)
            defaults.removeObject(forKey: Self.leftKey) // a fresh start has left nothing
        }
        activeID = saved == multilingual.id ? multilingual.id : english.id
        // A saved choice whose model is missing hands over to the other one when it is here, as Delete does, unless the
        // welcome's switch left that one.
        let left = defaults.string(forKey: Self.leftKey)
        if active.phase != .ready, let other = all.first(where: { $0.phase == .ready && $0.id != left }) {
            activeID = other.id
            defaults.set(other.id, forKey: Self.choiceKey)
        }
        for model in [english, multilingual] {
            model.onInstalled = { [weak self] _ in self?.installed(model) }
            model.onDownload = { [weak self] in self?.forgetLeft(model) }
        }
    }

    var all: [SpeechModel] { [english, multilingual] }
    /// The model the engine uses.
    var active: SpeechModel { activeID == multilingual.id ? multilingual : english }

    /// Multilingual first when any of the iPhone's preferred languages, by language code ("pt" in "pt-BR"), is one of its
    /// languages other than English; else English.
    static func startsMultilingual(_ preferredLanguages: [String]) -> Bool {
        preferredLanguages.contains { identifier in
            let code = Locale.Language(identifier: identifier).languageCode?.identifier
            return code != "en" && languages.contains { $0.key == code }
        }
    }

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
        forgetLeft(model)
        activeID = model.id
        defaults.set(model.id, forKey: Self.choiceKey)
        onChange(model.usableFolder)
    }

    /// The welcome's language choice and its Change language: the switch (`chooseToDownload`) whether `model` is here or
    /// not, so a download of the model in use always stops; then `model`'s download starts, or goes on, unless it is here.
    func chooseLanguage(_ model: SpeechModel) {
        chooseToDownload(model)
        if model.phase != .ready { model.download() }
    }

    /// The switch behind `chooseLanguage`: `model` becomes the one in use and the choice, to download (or here already:
    /// then the engine takes it). The model in use is kept as the model left (`leftKey`); its download stops, if one
    /// runs, and what it fetched is deleted (also after a failure, when nothing runs). One that is here stays here, and
    /// the engine keeps it until `model` is in, so a choice made after it ended ready still takes.
    func chooseToDownload(_ model: SpeechModel) {
        guard model.id != activeID else { return }
        active.cancel()
        defaults.set(activeID, forKey: Self.leftKey)
        activeID = model.id
        defaults.set(model.id, forKey: Self.choiceKey)
        if model.phase == .ready { onChange(model.usableFolder) }
    }

    /// The engine finished loading `folder`: the model it came from remembers it (`SpeechModel.markLoaded`). Not always the
    /// model in use: a choice made during a take waits for the take's end, so the load of the model before it can still
    /// end after the choice. No folder (a fixed engine), no mark.
    func engineLoaded(_ folder: URL?) {
        guard let folder else { return }
        all.first { $0.usableFolder == folder }?.markLoaded()
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

    /// A download finished: the engine takes it when it is the model in use, or when the model in use is not here and
    /// the welcome's switch did not leave this one.
    private func installed(_ model: SpeechModel) {
        if model.id != activeID, active.phase != .ready, model.id != defaults.string(forKey: Self.leftKey) {
            activeID = model.id
            defaults.set(model.id, forKey: Self.choiceKey)
        }
        if model.id == activeID { onChange(model.usableFolder) }
    }

    /// You chose `model` or asked for its download: if the welcome's switch left it, that is over.
    private func forgetLeft(_ model: SpeechModel) {
        if defaults.string(forKey: Self.leftKey) == model.id { defaults.removeObject(forKey: Self.leftKey) }
    }
}
