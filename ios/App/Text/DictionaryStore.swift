import Foundation
import Observation
import TFCore

/// The Dictionary: names, brands and terms spelled your way, in the order you added them. Stored in
/// the app's defaults as one entry per line and always read back through `CustomWords.parse`, so a list edited by any
/// path keeps the editor's rules. Add, Save and Paste a list are explicit: nothing is saved while you type.
@MainActor @Observable final class DictionaryStore {
    static let key = "TFDictionary"

    private(set) var entries: [String]
    /// Runs after every change with the new list: the session host transcribes with it.
    var onChange: ([String]) -> Void = { _ in }
    private let defaults: UserDefaults
    /// The App Group's defaults, where the keyboard reads the entries (it never corrects them and offers them). The
    /// app passes it; nil keeps a list to itself (the tests).
    private let shared: UserDefaults?

    init(defaults: UserDefaults = .standard, shared: UserDefaults? = nil) {
        self.defaults = defaults
        self.shared = shared
        entries = CustomWords.parse(defaults.string(forKey: Self.key) ?? "")
        shared?.set(entries, forKey: Lexicon.dictionaryKey) // a list from before the keyboard read it
    }

    /// The list holds `CustomWords.maxEntries`: Add and Paste a list add nothing more.
    var isFull: Bool { entries.count >= CustomWords.maxEntries }

    /// Adds one entry at the end, or says why it can't.
    @discardableResult
    func add(_ raw: String) -> CustomWords.EntryProblem? {
        switch CustomWords.checkEntry(raw, existing: entries, editing: nil) {
        case .success(let entry):
            save(entries + [entry]) // parse keeps at most maxEntries: the tab disables Add when the list is full
            return nil
        case .failure(let problem):
            return problem
        }
    }

    /// Saves an edit in the entry's place, or says why it can't. If `old` is no longer in the list (already changed
    /// elsewhere), stores `raw` through `add` instead of losing it silently: the duplicate check and the cap still
    /// apply, and success here always means the text was actually stored.
    @discardableResult
    func replace(_ old: String, with raw: String) -> CustomWords.EntryProblem? {
        guard let index = entries.firstIndex(of: old) else { return add(raw) }
        switch CustomWords.checkEntry(raw, existing: entries, editing: old) {
        case .success(let entry):
            var list = entries
            list[index] = entry
            save(list)
            return nil
        case .failure(let problem):
            return problem
        }
    }

    func delete(_ entry: String) { save(entries.filter { $0 != entry }) }

    /// Adds a pasted list after the entries, and returns what it added and skipped.
    @discardableResult
    func addList(_ raw: String) -> CustomWords.ListAddition {
        let addition = CustomWords.addList(raw, to: entries)
        save(addition.entries)
        return addition
    }

    private func save(_ list: [String]) {
        entries = CustomWords.parse(list.joined(separator: "\n"))
        defaults.set(entries.joined(separator: "\n"), forKey: Self.key)
        shared?.set(entries, forKey: Lexicon.dictionaryKey)
        onChange(entries)
    }
}
