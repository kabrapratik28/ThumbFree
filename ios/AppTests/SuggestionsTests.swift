import Testing
import UIKit
@testable import ThumbFree

/// Apple's suggestion bar: the word at the caret and the three places, from Apple's spelling dictionary on the test
/// Simulator (iOS 26.5).
@MainActor @Suite struct SuggestionsTests {
    @Test func theWordAtTheCaretIsTheLettersBeforeIt() {
        #expect(Typing.word(before: "I said hel", after: "") == "hel")
        #expect(Typing.word(before: "don't", after: "") == "don't")
        #expect(Typing.word(before: "don\u{2019}t", after: "") == "don\u{2019}t")
        #expect(Typing.word(before: "Hel", after: " there") == "Hel")
        #expect(Typing.word(before: "Hel", after: "lo") == nil)          // inside a word
        #expect(Typing.word(before: "don", after: "'t") == nil)          // inside a contraction
        #expect(Typing.word(before: "don", after: "\u{2019}t") == nil)
        #expect(Typing.word(before: "\u{2018}hello", after: "\u{2019} she said") == "hello") // before a closing quote
        #expect(Typing.word(before: "hello ", after: "") == nil)         // after a space
        #expect(Typing.word(before: "hello,", after: "") == nil)
        #expect(Typing.word(before: "3rd", after: "") == nil)            // a number
        #expect(Typing.word(before: "@kab", after: "") == nil)           // a handle
        #expect(Typing.word(before: "'hello", after: "") == "hello")     // an opening quote is not part of the word
        #expect(Typing.word(before: "the dogs'", after: "") == nil)
        #expect(Typing.word(before: "", after: "") == nil)
    }

    // Web addresses, email addresses and codes are judged whole (the characters around the caret, space to space): their
    // letters are never spell-checked as a word, so nothing after a dot is corrected there. "end.teh" (a sentence's end
    // with its space left out) reads like "site.com", so it stays as typed too; "end. teh" and "so...teh" end with a word.
    // A text replacement's exact shortcut still becomes its phrase.
    @Test func webAddressesEmailsAndCodesAreJudgedWhole() async throws {
        for before in ["visit www.gogle", "mail name@host.cmo", "at 12.teh", "the end.teh", "a/teh", "#teh"] {
            #expect(Typing.word(before: before, after: "") == nil, "\(before) was taken for a word")
        }
        #expect(Typing.word(before: "www.gogle", after: ".com") == nil) // the token goes on after the caret
        #expect(Typing.word(before: "the end. teh", after: "") == "teh")
        #expect(Typing.word(before: "so...teh", after: "") == "teh")
        let speller = try await Self.warmSpeller()
        #expect(speller.suggestions(before: "visit www.gogle", after: "", corrects: true) == nil)
        speller.lexicon = Lexicon(pairs: [("@@", "zorvath@example.com")])
        #expect(speller.suggestions(before: "mail @@", after: "", corrects: true)?.slots.last == Suggestion(kind: .correction, text: "zorvath@example.com"))
    }

    // A text replacement's shortcut may hold digits and marks ("ty2", "@@", ";addr"): the places made for it say so, and a
    // tap checks it as a shortcut. Checked as a word ("ty2" and "@@" are none, ";addr" reads "addr"), a tap did nothing,
    // though in web address, email and autocorrection-off fields it is the only way to use one.
    @Test func aShortcutWithDigitsOrMarksCanBeTapped() async throws {
        let speller = try await Self.warmSpeller()
        speller.lexicon = Lexicon(pairs: [("ty2", "thank you too"), ("@@", "zorvath@example.com"), (";addr", "1 Made-up Lane")])
        for (before, shortcut) in [("ok ty2", "ty2"), ("@@", "@@"), ("at ;addr", ";addr")] {
            let placed = try #require(speller.suggestions(before: before, after: ""))
            #expect(placed.word == shortcut && placed.token)
            #expect(Typing.stillAt(placed.word, token: placed.token, before: before, after: ""), "a tap on \(shortcut) does nothing")
            #expect(!Typing.stillAt(placed.word, token: false, before: before, after: ""))
        }
        #expect(!Typing.stillAt("ty2", token: true, before: "ok ty2 ", after: "")) // the text moved on: a tap only refreshes
        #expect(speller.suggestions(before: "Hel", after: "")?.token == false)
    }

    // The user's own words start from the third letter: before that Apple's dictionary fills the places ("t": the, to),
    // though a contact list has hundreds of names starting with t (here 150 of 300 made-up ones) and a kept word too.
    @Test func theUsersWordsComeFromTheThirdLetter() async throws {
        let speller = try await Self.warmSpeller()
        let firsts = ["Ta", "Te", "Th", "Ti", "To", "Tr", "Za", "Ke", "Mo", "Ra", "Vi", "Lu"]
        let ends = ["vrinn", "qzel", "xovar", "jirth", "kkvel", "zzarn", "vyqe", "wexol", "yffik", "qarro", "zulvik", "xenq", "vothra",
                    "jyssel", "qimmo", "wazzt", "yrvok", "xaldo", "zeqqa", "vumbrik", "kwexa", "jorvq", "qessil", "xyrra", "zovvik"]
        let names = firsts.flatMap { first in ends.map { first + $0 } }
        speller.lexicon = Lexicon(pairs: names.map { ($0, $0) })
        speller.learned.learn("teh")
        for typed in ["t", "th", "T", "Th"] {
            let words = speller.slots(for: typed, before: "I said " + typed).dropFirst().map(\.text)
            #expect(!words.isEmpty && !words.contains { names.contains($0) || $0.lowercased() == "teh" }, "\(typed): \(words)")
        }
        #expect(speller.slots(for: "Tax", before: "Tax").dropFirst().map(\.text) == ["Taxenq", "Taxaldo"]) // then shortest first
    }

    // Apple's places, as read from its bar: the word as typed, in quotes, then the words that start with it, likeliest
    // first ("Hel": Hello, Help; "The": They, There), or for a misspelling that starts no word, the guesses ("teh": the).
    @Test func thePlacesAreApples() async throws {
        let speller = try await Self.warmSpeller()
        #expect(speller.suggestions(before: "Hel", after: "")?.word == "Hel")
        #expect(speller.suggestions(before: "Hel ", after: "") == nil) // no word at the caret
        #expect(speller.slots(for: "Hel", before: "Hel").map(\.label) == ["\u{201C}Hel\u{201D}", "Hello", "Help"])
        #expect(speller.slots(for: "The", before: "The").map(\.label) == ["\u{201C}The\u{201D}", "They", "There"])
        let teh = speller.slots(for: "teh", before: "I love teh")
        #expect(teh.first == Suggestion(kind: .typed, text: "teh"))
        #expect(teh.dropFirst().first == Suggestion(kind: .word, text: "the"))
    }

    // Apple's dictionary ranks by the words before: "wor" alone is "works" first, after "hello" it is "world".
    @Test func theWordsBeforeRankTheSuggestions() async throws {
        let speller = try await Self.warmSpeller()
        #expect(speller.slots(for: "wor", before: "Hello wor").dropFirst().first?.text == "world")
        #expect(speller.slots(for: "wor", before: "wor").dropFirst().first?.text == "works")
    }

    // ThumbFree's "clear misspelling", from the dictionary's guesses (likeliest first) and whether the word starts
    // other words: the same letters in another form; else the likeliest guess, one edit away (two from seven letters),
    // the first letter kept unless the first two were swapped, and only a swap for a word that starts others.
    @Test func onlyAClearMisspellingIsCorrected() {
        let fix = { (word: String, guesses: [String], startsWords: Bool) in Speller.correction(for: word, guesses: guesses, startsWords: startsWords) }
        #expect(fix("teh", ["the", "ten", "tea"], false) == "the")
        #expect(fix("dont", ["dint", "don't"], false) == "don't")        // an apostrophe left out
        #expect(fix("iphone", ["iPhone", "phone"], false) == "iPhone")   // a capital
        #expect(fix("alot", ["a lot"], false) == "a lot")                // a space
        #expect(fix("tommorow", ["tomorrow"], false) == "tomorrow")      // two edits in a long word
        #expect(fix("ot", ["to", "or"], true) == "to")                   // a swap, though "ot" starts words
        #expect(fix("hel", ["hep", "tel"], true) == nil)                 // one change in a word that starts others
        #expect(fix("alot", ["lot", "alt"], false) == nil)               // the first letter dropped
        #expect(fix("vorlak", ["volar", "polka"], false) == nil)         // too far
        #expect(fix("zzz", [], false) == nil)
    }

    // Apple's dictionary on the test Simulator (iOS 26.5), end to end: what it makes clear enough to correct, and what
    // stays (a word it knows, a name, a word that starts others, capitals, a single letter). A new iOS may rank its
    // guesses differently: update these with the new Simulator.
    @Test func applesDictionaryCorrectsTheClearOnes() async throws {
        let speller = try await Self.warmSpeller()
        #expect(speller.correction(for: "teh", before: "I love teh") == "the")
        #expect(speller.correction(for: "Teh", before: "Teh") == "The")          // a sentence's capital stays
        #expect(speller.correction(for: "recieve", before: "I will recieve") == "receive")
        #expect(speller.correction(for: "wierd", before: "that is so wierd") == "weird")
        #expect(speller.correction(for: "dont", before: "I dont") == "don't")
        #expect(speller.correction(for: "Im", before: "Im") == "I'm")           // an apostrophe hidden by a sentence's capital
        #expect(speller.correction(for: "i", before: "so i") == "I")
        for (word, before) in [("thx", "thx"), ("hel", "I said hel"), ("alot", "thanks alot"), ("vorlak", "Derek vorlak"),
                               ("u", "see u"), ("NSAA", "the NSAA"), ("McDonal", "McDonal"), ("Derek", "Derek")] {
            #expect(speller.correction(for: word, before: before) == nil, "\(word) changed")
        }
    }

    // The bar lights the correction where the word's end will type it, and shows it plain where the field turns
    // autocorrection off, as Apple's does; the place after it is the likeliest word that starts with it: "teh" shows
    // "teh", the, they, as Apple's bar does (after "I love", "them" comes first: the words before rank it).
    @Test func theCorrectionIsLitWhereTheEndTypesIt() async throws {
        let speller = try await Self.warmSpeller()
        #expect(speller.slots(for: "teh", before: "teh", corrects: true)
                == [Suggestion(kind: .typed, text: "teh"), Suggestion(kind: .correction, text: "the"), Suggestion(kind: .word, text: "they")])
        #expect(speller.slots(for: "teh", before: "teh", corrects: false).map(\.kind) == [.typed, .word, .word])
        #expect(speller.slots(for: "teh", before: "I love teh", corrects: true).last?.text == "them")
    }

    // A word the user kept is never corrected again and is offered as its start is typed, in the case typed.
    @Test func aKeptWordIsNeverCorrectedAndIsOffered() async throws {
        let speller = try await Self.warmSpeller()
        #expect(speller.correction(for: "wrod", before: "a wrod") == "word")
        speller.learned.learn("wrod")
        speller.learned.learn("Vorlak")
        #expect(speller.correction(for: "wrod", before: "a wrod") == nil)
        #expect(speller.knows("Wrod", before: "Wrod"))
        #expect(speller.slots(for: "Vor", before: "Vor").map(\.text).contains("Vorlak"))
    }

    // Typing stays instant: the suggestions run on the main thread after each key (UIKit keeps Apple's checker there), so
    // each must stay well inside a frame. Typed letter by letter through the keyboard's entry point, at the stores'
    // limits: 10,000 lexicon entries (contacts' words and a few text replacements), 1,000 kept words and a Dictionary of
    // 300 entries, all different made-up words that share first letters and lengths with the passage's misspellings (the
    // user's words a near miss is checked against). The bounds leave room for a busy Mac; the times go to the test log.
    @Test func typingStaysInstant() async throws {
        let speller = try await Self.warmSpeller()
        let letters = Array("abcdefghijklmnopqrstuvwxyz"), firsts = Array("tsrpzw")
        let made = { (n: Int) -> String in // a different word for each number: a first letter, then the number's letters
            var word = String(firsts[n % firsts.count]), rest = n / firsts.count
            repeat { word.append(letters[rest % 26]); rest /= 26 } while rest > 0 || word.count < 4 + n % 4
            return word
        }
        let replacements = [("omw", "On my way!")] + (0..<20).map { ("zq\($0)", "A made-up phrase \($0)") }
        speller.lexicon = Lexicon(pairs: replacements + (0..<(Lexicon.limit - replacements.count)).map { (made($0).capitalized, made($0).capitalized) },
                                  dictionary: (0..<300).map { made($0 + 30_000).capitalized })
        for index in 0..<LearnedWords.limit { speller.learned.learn(made(index + 20_000)) }
        let passage = "I think teh meeting went well and we shoud recieve the notes tomorow so pleas send them to Zakrof when you can"
        var text = "", times: [Duration] = []
        for word in passage.split(separator: " ") {
            for letter in word {
                text.append(letter)
                let start = ContinuousClock.now
                _ = speller.suggestions(before: text, after: "", corrects: true)
                times.append(ContinuousClock.now - start)
            }
            text.append(" ")
        }
        times.sort()
        let ms = { (time: Duration) in String(format: "%.2f ms", Double(time.components.seconds) * 1_000 + Double(time.components.attoseconds) / 1e15) }
        let (median, slow, worst) = (times[times.count / 2], times[times.count * 95 / 100], times[times.count - 1])
        print("typingStaysInstant: \(times.count) keys, median \(ms(median)), 95th percentile \(ms(slow)), worst \(ms(worst))")
        #expect(median < .milliseconds(6), "median \(ms(median))")
        #expect(slow < .milliseconds(16), "95th percentile \(ms(slow))")
    }

    @Test func editsCountLettersAddedDroppedChangedOrSwapped() {
        #expect(Edits.distance("teh", "the") == 1)
        #expect(Edits.distance("tommorow", "tomorrow") == 2)
        #expect(Edits.distance("hel", "hello") == 2)
        #expect(Edits.distance("", "ab") == 2)
        #expect(Edits.distance("abcdefgh", "hgfedcba", atMost: 1) > 1)   // it stops early past the limit
        #expect(Edits.distance("recieve", "receive", atMost: 1) == 1)
        #expect(Edits.isSwap("teh", "the"))
        #expect(Edits.isSwap("ot", "to"))
        #expect(!Edits.isSwap("hel", "hep"))
        #expect(!Edits.isSwap("abc", "cba"))
        #expect(Speller.sameLetters("don't", "dont"))
        #expect(Speller.sameLetters("iPhone", "iphone"))
        #expect(!Speller.sameLetters("dont", "dont"))
    }

    // The typed word is not offered again, no word shows twice in any case, and no hyphenated guess shows.
    @Test func noWordShowsTwice() {
        #expect(Speller.slots(typed: "the", words: ["the", "they", "They", "then"]).map(\.text) == ["the", "they", "then"])
        #expect(Speller.slots(typed: "alot", words: ["a-lot", "lot"]).map(\.text) == ["alot", "lot"])
        #expect(Speller.slots(typed: "zzz", words: []).map(\.text) == ["zzz"])
    }

    /// The dictionary as the keyboard's answers once warm: after `warmUp`, its ranking data loads in the background.
    static func warmSpeller() async throws -> Speller {
        let speller = Speller()
        speller.warmUp()
        for _ in 0..<60 where speller.completions("the", before: "the").isEmpty { try await Task.sleep(for: .milliseconds(50)) }
        try #require(!speller.completions("the", before: "the").isEmpty, "the dictionary's ranking data did not load")
        return speller
    }
}
