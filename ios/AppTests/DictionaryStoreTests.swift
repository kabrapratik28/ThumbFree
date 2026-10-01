import Foundation
import Testing
import TFCore
@testable import ThumbFree

/// The Dictionary's list: explicit Add, Save and Paste a list, in the order added, kept across launches.
@MainActor @Suite final class DictionaryStoreTests {
    let suite = "DictionaryStoreTests-\(UUID().uuidString)"
    let defaults: UserDefaults

    init() throws { defaults = try #require(UserDefaults(suiteName: suite)) }

    deinit { UserDefaults().removePersistentDomain(forName: suite) }

    @Test func entriesKeepTheOrderAddedAndSurviveARelaunch() {
        let store = DictionaryStore(defaults: defaults)
        var told: [[String]] = []
        store.onChange = { told.append($0) }
        #expect(store.add("  Kubernetes ") == nil)
        #expect(store.add("chat gpt") == nil)
        #expect(store.entries == ["Kubernetes", "chat gpt"])
        #expect(told.last == ["Kubernetes", "chat gpt"])
        #expect(DictionaryStore(defaults: defaults).entries == ["Kubernetes", "chat gpt"])
    }

    // The keyboard reads the list from the App Group: it is shared at launch and after every change.
    @Test func theListIsSharedWithTheKeyboard() throws {
        let group = try #require(UserDefaults(suiteName: suite + "-group"))
        defer { UserDefaults().removePersistentDomain(forName: suite + "-group") }
        defaults.set("Kubernetes", forKey: DictionaryStore.key)
        let store = DictionaryStore(defaults: defaults, shared: group)
        #expect(group.stringArray(forKey: Lexicon.dictionaryKey) == ["Kubernetes"])
        store.add("Dr. Nakamura")
        #expect(group.stringArray(forKey: Lexicon.dictionaryKey) == ["Kubernetes", "Dr. Nakamura"])
        store.delete("Kubernetes")
        #expect(group.stringArray(forKey: Lexicon.dictionaryKey) == ["Dr. Nakamura"])
    }

    @Test func addAndSaveSayWhyAnEntryCantBeSaved() {
        let store = DictionaryStore(defaults: defaults)
        store.add("GitHub")
        #expect(store.add("github") == .duplicate)
        #expect(store.add("Anika, Priya") == .several)
        #expect(store.add("   ") == .blank)
        #expect(store.add(String(repeating: "a", count: CustomWords.maxChars + 1)) == .tooLong)
        #expect(store.entries == ["GitHub"])
        #expect(store.replace("GitHub", with: "Github") == nil) // a case-only change of the entry itself
        #expect(store.entries == ["Github"])
    }

    @Test func anEditStaysInItsPlaceAndDeleteRemovesOne() {
        let store = DictionaryStore(defaults: defaults)
        store.add("Anika")
        store.add("Priya")
        store.add("Maple Street")
        #expect(store.replace("Priya", with: "Anika") == .duplicate)
        #expect(store.replace("Priya", with: "Dr. Nakamura") == nil)
        #expect(store.entries == ["Anika", "Dr. Nakamura", "Maple Street"])
        store.delete("Anika")
        #expect(store.entries == ["Dr. Nakamura", "Maple Street"])
        #expect(DictionaryStore(defaults: defaults).entries == ["Dr. Nakamura", "Maple Street"])
    }

    @Test func replacingAnEntryThatIsGoneKeepsTheNewTextInstead() {
        let store = DictionaryStore(defaults: defaults)
        store.add("Anika")
        // "Ghost" is not in the list (already renamed or removed elsewhere): the edit must not be lost.
        #expect(store.replace("Ghost", with: "Priya") == nil)
        #expect(store.entries == ["Anika", "Priya"])
        // Still checked like any add: a real duplicate reports its problem instead of a silent no-op.
        #expect(store.replace("Ghost", with: "Anika") == .duplicate)
        #expect(store.entries == ["Anika", "Priya"])
    }

    @Test func pasteAListAddsTheNewOnesAfterTheOthers() {
        let store = DictionaryStore(defaults: defaults)
        store.add("GitHub")
        let addition = store.addList("Siobhan, Nguyen, github\nMenlo Park")
        #expect(addition.summary == "3 will be added. 1 duplicate will be skipped.")
        #expect(store.entries == ["GitHub", "Siobhan", "Nguyen", "Menlo Park"])
        #expect(!store.isFull)
    }
}
