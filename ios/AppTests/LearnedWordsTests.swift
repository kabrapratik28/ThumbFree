import Testing
@testable import ThumbFree

/// The words the user kept: the keyboard's own store of words never to correct again.
@Suite struct LearnedWordsTests {
    // Kept lowercased, once, newest last; a word kept again moves to the newest end.
    @Test func aKeptWordIsStoredOnceNewestLast() {
        var learned = LearnedWords(stored: ["teh"])
        learned.learn("Wrod")
        learned.learn("TEH")
        #expect(learned.words == ["wrod", "teh"])
        #expect(learned.contains("Teh"))
        #expect(!learned.contains("the"))
    }

    // Kept with the straight apostrophe: a word kept as smart quotes type it ("Zorvath’s", after an undo) is the same word
    // typed with either apostrophe.
    @Test func bothApostrophesAreOneWord() {
        var learned = LearnedWords(stored: nil)
        learned.learn("Zorvath\u{2019}s")
        #expect(learned.words == ["zorvath's"])
        #expect(learned.contains("Zorvath's") && learned.contains("zorvath\u{2019}s"))
        #expect(learned.completions(of: "Zorvath\u{2019}") == ["Zorvath\u{2019}s"])
    }

    // At most 1,000: past that the oldest go.
    @Test func theOldestGoPastTheLimit() {
        var learned = LearnedWords(stored: (0..<LearnedWords.limit).map { "word\($0)" })
        learned.learn("newest")
        #expect(learned.words.count == LearnedWords.limit)
        #expect(!learned.contains("word0"))
        #expect(learned.words.last == "newest")
    }

    // Offered as their start is typed, newest first, in the case of the letters typed.
    @Test func keptWordsCompleteWhatIsTyped() {
        var learned = LearnedWords(stored: nil)
        learned.learn("vorlak")
        learned.learn("vortex")
        #expect(learned.completions(of: "Vor") == ["Vortex", "Vorlak"])
        #expect(learned.completions(of: "vorlak").isEmpty) // the word itself is not a completion
    }
}
