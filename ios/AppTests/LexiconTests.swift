import Testing
@testable import ThumbFree

/// iOS's supplementary lexicon in ThumbFree's keyboard: the user's text replacements and contacts' words.
@MainActor @Suite struct LexiconTests {
    /// The Simulator's own lexicon, as the keyboard read it: its one text replacement and some of its contacts' words.
    private let lexicon = Lexicon(pairs: [("omw", "On my way!"), ("Zakroff", "Zakroff"), ("Kate", "Kate"), ("Appleseed", "Appleseed"),
                                          ("ty2", "thank you too")])

    // A pair whose forms differ is a replacement (found in any case), one whose forms match is a word.
    @Test func entriesAreReplacementsOrWords() {
        #expect(lexicon.replacements == ["omw": "On my way!", "ty2": "thank you too"])
        #expect(lexicon.words["zakroff"] == "Zakroff")
        #expect(lexicon.completions(of: "zak") == ["Zakroff"])
        #expect(lexicon.completions(of: "Zakroff").isEmpty)
    }

    // ThumbFree's Dictionary adds each entry's words, as written, beside iOS's.
    @Test func theDictionarysWordsJoinTheWords() {
        let words = Lexicon(pairs: [("Kate", "Kate")], dictionary: ["Dr. Nakamura", "chat gpt", "Kubernetes", "kate"]).words
        #expect(words == ["kate": "Kate", "dr": "Dr", "nakamura": "Nakamura", "chat": "chat", "gpt": "gpt", "kubernetes": "Kubernetes"])
    }

    // One word one edit away with the same first letter is the one meant; two, or none, and nothing is.
    @Test func aNearMissMeansTheOneWordItNearlyIs() {
        #expect(Edits.nearest(to: "Zakrof", in: ["Zakroff", "Kate"]) == "Zakroff")
        #expect(Edits.nearest(to: "kat", in: ["Kate", "Katz"]) == nil)       // two as near
        #expect(Edits.nearest(to: "Xakroff", in: ["Zakroff"]) == nil)        // another first letter
        #expect(Edits.nearest(to: "zakroff", in: ["Zakroff", "zakroff"]) == nil) // the word itself
        // The rule takes the user's word it nearly is, in the typed first capital, before the dictionary's guess.
        #expect(Speller.correction(for: "Vorlk", guesses: ["Volk"], startsWords: false, userWords: ["vorlak"]) == "Vorlak")
    }

    // As on Apple's keyboard: the shortcut, whole and in any case, even with a digit, becomes its phrase, lit in the
    // middle place with nothing after it ("Omw": On my way!); a shortcut the user kept (undid) stays; a contact's word is
    // never corrected, is offered, and is what a near miss becomes.
    @Test func theLexiconFeedsTheCorrections() async throws {
        let speller = try await SuggestionsTests.warmSpeller()
        speller.lexicon = lexicon
        let omw = try #require(speller.suggestions(before: "Omw", after: "", corrects: true))
        #expect(omw.word == "Omw")
        #expect(omw.slots == [Suggestion(kind: .typed, text: "Omw"), Suggestion(kind: .correction, text: "On my way!")])
        #expect(speller.suggestions(before: "ok ty2", after: "", corrects: true)?.slots.last == Suggestion(kind: .correction, text: "thank you too"))
        speller.learned.learn("omw")
        #expect(speller.suggestions(before: "omw", after: "", corrects: true)?.slots.contains { $0.kind == .correction } == false)
        #expect(speller.correction(for: "zakrof", before: "call zakrof") == "Zakroff")
        #expect(speller.correction(for: "Zakroff", before: "Zakroff") == nil)
        #expect(speller.slots(for: "Zak", before: "Zak").map(\.text).contains("Zakroff"))
        speller.learned.learn("vorlak")
        #expect(speller.correction(for: "Vorlk", before: "Vorlk") == "Vorlak") // a kept word too, in the typed capital
    }

    // A lexicon word (a contact's, or a text replacement's own word) is never corrected, even one that looks like another
    // word missing its apostrophe: Apple's checker offers "didn't" for "didnt" (same letters, same first letter, within
    // its first three guesses), but a lexicon word stops the correction before that guess is ever taken.
    @Test func aContactsWordIsNeverCorrectedNearAnApostrophe() async throws {
        let speller = try await SuggestionsTests.warmSpeller()
        speller.lexicon = Lexicon(pairs: [("Didnt", "Didnt")])
        #expect(speller.correction(for: "Didnt", before: "Didnt") == nil)
    }

    // Thousands of contacts could return tens of thousands of entries; past the cap, iOS's later contacts' words are
    // dropped so the lexicon's dictionaries stay bounded in memory, while every text replacement stays, even one iOS
    // lists after them.
    @Test func aLexiconPastTheLimitDropsTheRest() {
        let big = Lexicon(pairs: (0..<(Lexicon.limit + 500)).map { ("word\($0)", "word\($0)") } + [("zqaddr", "1 Made-up Lane")])
        #expect(big.words.count == Lexicon.limit)
        #expect(big.replacements["zqaddr"] == "1 Made-up Lane")
    }

    // A user's own word with 's, ’s or s after it is still that word, never "corrected" to the bare one: a contact's (the
    // Simulator's sample contact Hank Zakroff), a kept one, the Dictionary's; with a bare s it becomes its possessive
    // (`aUsersWordKeepsItsPossessiveRepair`), but not a word in capitals, an acronym whose s makes a plural ("gpts"). A
    // word the typed one starts with is no near miss of it either (a nickname one letter longer than a contact's name),
    // while a real near miss still is.
    @Test func aUsersWordWithAnEndingIsNeverTheBareWord() async throws {
        let speller = try await SuggestionsTests.warmSpeller()
        speller.lexicon = Lexicon(pairs: [("Zakroff", "Zakroff")], dictionary: ["GPT"])
        speller.learned.learn("zorvath")
        for typed in ["Zakroff's", "Zakroff\u{2019}s", "Zorvath's", "gpt's", "gpts"] {
            #expect(speller.correction(for: typed, before: "with " + typed) == nil, "\(typed) changed")
        }
        for (typed, possessive) in [("Zakroffs", "Zakroff's"), ("Zorvaths", "Zorvath's")] {
            #expect(speller.correction(for: typed, before: "with " + typed) == possessive, "\(typed)")
        }
        #expect(Edits.nearest(to: "Zakroffs", in: ["Zakroff"]) == nil)
        #expect(Speller.correction(for: "Zakroff's", guesses: [], startsWords: false, userWords: ["Zakroff"]) == nil)
        #expect(Speller.correction(for: "Tavrinu", guesses: [], startsWords: false, userWords: ["Tavrin"]) == nil)
        #expect(speller.correction(for: "zakrof", before: "call zakrof") == "Zakroff")
    }

    // A user's word with a bare s after it keeps Apple's apostrophe repair, to that word's own possessive and nothing
    // else: beside a contact "Zorvath", "zorvaths" becomes "Zorvath's" as the lexicon writes the name, and a kept word's
    // possessive keeps the typed case. A word the dictionary knows ("harbors" beside a contact's company "Harbor"), one
    // typed with its apostrophe, and an acronym's plural ("vqls" beside the Dictionary's "VQL") stay as typed. The
    // keyboard curls the apostrophe where the field's quotes are smart.
    @Test func aUsersWordKeepsItsPossessiveRepair() async throws {
        let speller = try await SuggestionsTests.warmSpeller()
        speller.lexicon = Lexicon(pairs: [("Zorvath", "Zorvath"), ("Harbor", "Harbor")], dictionary: ["VQL"])
        speller.learned.learn("quenmir")
        #expect(speller.correction(for: "zorvaths", before: "ask zorvaths") == "Zorvath's")
        #expect(speller.correction(for: "Zorvaths", before: "Zorvaths") == "Zorvath's")
        #expect(speller.correction(for: "Quenmirs", before: "Quenmirs") == "Quenmir's")
        for typed in ["harbors", "zorvath's", "Zorvath\u{2019}s", "zorvath", "quenmir", "vqls", "VQLs"] {
            #expect(speller.correction(for: typed, before: "the " + typed) == nil, "\(typed) changed")
        }
    }

    // One apostrophe for matching: keys use the straight one, so a word written with smart quotes (a Dictionary entry, a
    // word kept after an undo) is the same word typed with either, and one undo teaches it.
    @Test func bothApostrophesAreOne() async throws {
        let lexicon = Lexicon(pairs: [("y\u{2019}all2", "you all too")], dictionary: ["O\u{2019}Varnoy"])
        #expect(lexicon.words["o'varnoy"] == "O\u{2019}Varnoy")
        #expect(lexicon.replacements["y'all2"] == "you all too")
        #expect(lexicon.completions(of: "O'Var") == ["O\u{2019}Varnoy"])
        let speller = try await SuggestionsTests.warmSpeller()
        speller.learned.learn("Zorvath\u{2019}s") // what an undo keeps: the word as the text has it
        for typed in ["Zorvath's", "Zorvath\u{2019}s"] {
            #expect(speller.correction(for: typed, before: typed) == nil, "\(typed) changed")
        }
    }

    // A swap of the first two letters reaches the user's word too, as it reaches Apple's ("ot": to); two as near still
    // give nothing.
    @Test func aSwapOfTheFirstTwoLettersReachesTheUsersWord() async throws {
        #expect(Edits.nearest(to: "ukbernetes", in: ["Kubernetes"]) == "Kubernetes")
        #expect(Edits.nearest(to: "ukbernetes", in: ["Kubernetes", "Ukbernetis"]) == nil)
        let speller = try await SuggestionsTests.warmSpeller()
        speller.lexicon = Lexicon(dictionary: ["Kubernetes"])
        #expect(speller.correction(for: "ukbernetes", before: "run ukbernetes") == "Kubernetes")
    }

    // iOS answers the lexicon requests later, maybe out of order: only the latest answer is taken, and until it comes the
    // keyboard corrects nothing (a contact's word is never corrected because its lexicon was late).
    @Test func onlyTheLatestLexiconAnswerIsTaken() {
        var requests = LexiconRequests()
        let first = requests.start(), second = requests.start()
        #expect(!requests.current)
        let late = requests.answered(first)
        #expect(!late && !requests.current) // an older request's late answer is dropped
        let latest = requests.answered(second)
        #expect(latest && requests.current)
        _ = requests.start() // the keyboard shows again
        #expect(!requests.current)
    }
}
