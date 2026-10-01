import Testing
import TFCore
@testable import ThumbFree

/// The Dictionary tab's words, from the Android app.
@MainActor @Suite struct DictionaryViewTests {
    @Test func theFieldSaysWhyAnEntryCantBeAdded() {
        #expect(DictionaryView.check("Kubernetes", in: []).entry == "Kubernetes")
        #expect(DictionaryView.check("", in: []).entry == nil)
        #expect(DictionaryView.check("", in: []).line == nil) // blank: Add is only disabled
        #expect(DictionaryView.check("github", in: ["GitHub"]).line == "Already in your Dictionary.")
        #expect(DictionaryView.check("Anika, Priya", in: []).line == "One at a time here. Use Paste a list for several.")
        #expect(DictionaryView.check(String(repeating: "a", count: 61), in: []).line == "Too long: an entry has at most 60 characters.")
        #expect(DictionaryView.check("github", in: ["GitHub"]).isProblem)
        // A common word is allowed, with the note.
        #expect(DictionaryView.check("Will", in: []).entry == "Will")
        #expect(DictionaryView.check("Will", in: []).line == "“Will” is also a common word, so every “will” will be written “Will”.")
        #expect(!DictionaryView.check("Will", in: []).isProblem)
        // Editing an entry may keep its own spelling in another case.
        #expect(DictionaryView.check("Github", in: ["GitHub"], editing: "GitHub").entry == "Github")
    }

    @Test func theCountSaysWhenTheListIsFull() {
        #expect(DictionaryView.countLabel(1) == "1 word")
        #expect(DictionaryView.countLabel(6) == "6 words")
        #expect(DictionaryView.countLabel(CustomWords.maxEntries) == "500 words, the most it keeps")
    }

    // Near misses only in English; the multilingual model (no language) makes exact matches only.
    @Test func tryAPhraseShowsWhatTheDictionaryDoes() {
        #expect(DictionaryView.tryPhrase("i use kubernetis daily", entries: ["Kubernetes"], language: "en")
                == "ThumbFree would type: i use Kubernetes daily")
        #expect(DictionaryView.tryPhrase("i use kubernetis daily", entries: ["Kubernetes"], language: nil) == "No Dictionary changes.")
        #expect(DictionaryView.tryPhrase("grazie mille", entries: ["Grazia"], language: nil) == "No Dictionary changes.")
    }
}
