import UIKit

/// One of the suggestion bar's three places, as Apple's keyboard fills them while a word is typed (read on the iOS 26.5
/// Simulator): the word as typed, in curly quotes, on the left, then the words it may become.
struct Suggestion: Equatable, Sendable {
    enum Kind: Equatable, Sendable {
        case typed       // the word as typed: a tap keeps it
        case correction  // what the word's end will type in its place: Apple's lit place
        case word        // a word it may become: a tap types it in the typed word's place
        case undo        // right after a correction's space was deleted: a tap puts back the word as it was typed
    }

    let kind: Kind
    /// What a tap types.
    let text: String

    /// What the bar shows: the typed word in curly quotes, as on Apple's bar.
    var label: String { kind == .typed ? "\u{201C}\(text)\u{201D}" : text }
    /// What VoiceOver reads: the label, and for the undo place what a tap does (Apple's undo bubble reads "Undo").
    var spoken: String { kind == .undo ? "Undo correction, \(text)" : label }
}

/// The word at the caret, as Apple's keyboard finds it for its suggestions.
enum Typing {
    static let apostrophes: Set<Character> = ["'", "\u{2019}"]

    /// `word` as the keyboard's stores keep and look up words: lowercased, with the straight apostrophe (smart quotes type
    /// ’; the checker and the stores use '), so "Zorvath’s" and "zorvath's" are one word.
    static func key(_ word: String) -> String {
        let lower = word.lowercased()
        return lower.contains("\u{2019}") ? lower.replacingOccurrences(of: "\u{2019}", with: "'") : lower
    }

    /// What a text replacement's shortcut would be: the characters before the caret back to a space or the start (a
    /// shortcut may hold digits and marks), when the caret is at their end. Nil right after a space.
    static func token(before: String, after: String) -> String? {
        if let next = after.first, !next.isWhitespace { return nil }
        let token = String(before.reversed().prefix { !$0.isWhitespace }.reversed())
        return token.isEmpty ? nil : token
    }

    /// The word being typed: the letters just before the caret, with any apostrophes between them ("don't"), when the
    /// caret is at the word's end (no letter or digit right after it, and no apostrophe with a letter after it: don|'t).
    /// Nil after a space or punctuation, inside a word, and inside a web address, an email address or a code (`isCode`).
    static func word(before: String, after: String) -> String? {
        if let next = after.first, next.isLetter || next.isNumber || apostrophes.contains(next) && after.dropFirst().first?.isLetter == true { return nil }
        var word = String(before.reversed().prefix { $0.isLetter || apostrophes.contains($0) }.reversed())
        while let first = word.first, apostrophes.contains(first) { word.removeFirst() } // an opening quote, not the word's
        guard let last = word.last, last.isLetter else { return nil }                      // "the dogs'" ends on a quote
        let around = String(before.reversed().prefix { !$0.isWhitespace }.reversed()) + after.prefix { !$0.isWhitespace }
        return isCode(around) ? nil : word
    }

    /// A web address, an email address, a handle, a tag or a code, judged whole (the characters around the caret, from
    /// a space to a space): a digit, one of @ # _ / \, or a full stop between two letters or digits anywhere in it
    /// ("www.gogle", "name@host.cmo", "12.teh", "3rd", "@name", "#tag", "a/b"). Its letters are never spell-checked as a
    /// word; a text replacement's exact shortcut still becomes its phrase. "end.teh", a sentence's end with its space
    /// left out, reads like "site.com", so it stays as typed too; "end. teh" and "so...teh" end with a word.
    static func isCode(_ token: String) -> Bool {
        let letters = Array(token)
        let alike = { (index: Int) in letters.indices.contains(index) && (letters[index].isLetter || letters[index].isNumber) }
        return letters.indices.contains { letters[$0].isNumber || "@#_/\\".contains(letters[$0]) || letters[$0] == "." && alike($0 - 1) && alike($0 + 1) }
    }

    /// Whether places made for `placed` (a text replacement's shortcut when `token`, else a word) are still for what ends
    /// at the caret: a tap types in its place only then. A shortcut is checked as a shortcut ("ty2", "@@", ";addr" are
    /// no words).
    static func stillAt(_ placed: String, token: Bool, before: String, after: String) -> Bool {
        placed == (token ? Self.token(before: before, after: after) : word(before: before, after: after))
    }
}

/// Apple's spelling dictionary (`UITextChecker`), in English (US): this keyboard types English. UIKit keeps the checker
/// on the main thread; each answer takes a millisecond or two (measured on the iOS 26.5 Simulator). It ranks its words by
/// the text before the word too ("hello wor": world first; "wor" alone: works), so each call gets the word at the end of
/// the text before the caret.
@MainActor final class Speller {
    static let language = "en_US"
    private let checker = UITextChecker()
    private var warm = false
    /// The words the user kept (the keyboard sets them as it shows): never corrected, and offered as their start is typed.
    var learned = LearnedWords(stored: nil)
    /// iOS's text replacements and contacts' words, and ThumbFree's Dictionary's (the keyboard sets them when iOS
    /// answers): a shortcut becomes its phrase; the words are never corrected, are offered, and are what a near miss becomes.
    var lexicon = Lexicon()

    /// The user's words a near miss of `word` may be, for the rule in `correction(for:guesses:startsWords:userWords:)`:
    /// the lexicon's, then the kept ones, that start with its first letter, or with its second for a swap of the first two
    /// ("ukbernetes": Kubernetes). The lexicon's are indexed by first letter when it is made, so a typo's keystroke never
    /// looks through all of them (there can be ten thousand).
    private func userWords(near word: String) -> [String] {
        Set(Typing.key(word).prefix(2)).flatMap { letter in lexicon.words(startingWith: letter) + learned.words.filter { $0.first == letter } }
    }

    /// Whether `word` is the user's own, which nothing corrects (but a bare s to its possessive, `possessive(of:)`): a kept
    /// word or the lexicon's, also with 's or s after it ("Zakroff's", "Zakroffs": a contact's name with an ending is still
    /// that name, not a typo of it).
    func isUsers(_ word: String) -> Bool {
        let key = Typing.key(word)
        let stem = key.hasSuffix("'s") ? key.dropLast(2) : key.hasSuffix("s") ? key.dropLast() : Substring(key)
        return [key, String(stem)].contains { learned.contains($0) || lexicon.words[$0] != nil }
    }

    /// The possessive `word` leaves out the apostrophe of, when it is a user's word with a bare s after it and no word of
    /// the user's itself: "zorvaths" is "Zorvath's" as the lexicon writes the name; a kept word keeps the typed case
    /// ("Quenmirs": Quenmir's). Nil for any other word, and for a lexicon word written in capitals, an acronym whose s
    /// makes a plural ("gpus" beside the Dictionary's "GPU"). The keyboard curls the apostrophe where the field's quotes
    /// are smart.
    func possessive(of word: String) -> String? {
        let key = Typing.key(word)
        guard key.hasSuffix("s"), !key.hasSuffix("'s"), !learned.contains(key), lexicon.words[key] == nil else { return nil }
        let stem = String(key.dropLast())
        if let written = lexicon.words[stem] { return written.contains(where: \.isLowercase) ? written + "'s" : nil }
        return learned.contains(stem) ? String(word.dropLast()) + "'s" : nil
    }

    /// The first call for completions in a process loads the dictionary's ranking data and blocks for about 150 ms; a
    /// guess first starts that load in the background instead (ready about 90 ms later, the main thread never held more
    /// than 6 ms), and until then completions come back empty and guesses unranked. The keyboard calls this once it
    /// shows, so no key waits for the load.
    func warmUp() {
        guard !warm else { return }
        warm = true
        _ = guesses("teh", before: "teh")
    }

    /// Whether `word` (the word at the end of `before`) is one the user kept or Apple's dictionary knows.
    func knows(_ word: String, before: String) -> Bool {
        learned.contains(word) || lexicon.words[Typing.key(word)] != nil || isWord(word.replacingOccurrences(of: "\u{2019}", with: "'"), before: before)
    }

    /// Whether Apple's dictionary knows `word`, the word at the end of `before`.
    func isWord(_ word: String, before: String) -> Bool {
        let (text, range) = Self.query(word, before: before)
        return checker.rangeOfMisspelledWord(in: text, range: range, startingAt: range.location, wrap: false, language: Self.language).location == NSNotFound
    }

    /// The dictionary's guesses at what a misspelled `word` meant, likeliest first.
    func guesses(_ word: String, before: String) -> [String] {
        let (text, range) = Self.query(word, before: before)
        return checker.guesses(forWordRange: range, in: text, language: Self.language) ?? []
    }

    /// Words that start with `word`, likeliest first, in its case ("Hel": Hello, Help).
    func completions(_ word: String, before: String) -> [String] {
        let (text, range) = Self.query(word, before: before)
        return checker.completions(forPartialWordRange: range, in: text, language: Self.language) ?? []
    }

    /// Apple's places for the word at the caret (`before` and `after` are the text around it), and that word, which a tap
    /// or the lit correction replaces (`token` when it is a text replacement's shortcut): nil when no word ends at the
    /// caret. A text replacement's shortcut comes first, whole and in any case, even with digits or marks ("Omw", "ty2"),
    /// with its phrase in the middle place and nothing after it, as Apple's bar shows "On my way!".
    func suggestions(before: String, after: String, corrects: Bool = false) -> (word: String, token: Bool, slots: [Suggestion])? {
        if let token = Typing.token(before: before, after: after), let phrase = replacement(for: token) {
            return (token, true, [Suggestion(kind: .typed, text: token), Suggestion(kind: corrects ? .correction : .word, text: phrase)])
        }
        guard let word = Typing.word(before: before, after: after) else { return nil }
        return (word, false, slots(for: word, before: before, corrects: corrects))
    }

    /// The phrase a text replacement's shortcut becomes, unless the user kept the shortcut as typed (an undo teaches it).
    func replacement(for token: String) -> String? {
        learned.contains(token) ? nil : lexicon.replacements[Typing.key(token)]
    }

    /// Apple's three places for `word`, the word at the end of `before` (the text before the caret): the word as typed,
    /// in quotes; then its autocorrection, if any, and the likeliest word that starts with that ("teh": the, they), as on
    /// Apple's bar; else the two likeliest words it may become: from the third letter the user's own words that start
    /// with it (kept, contacts', the Dictionary's), then Apple's completions, or, when Apple's dictionary does not know it
    /// and it starts no word, the dictionary's guesses at what was meant. A word of the user's own (`isUsers`) is never
    /// corrected, except to its own possessive when the dictionary does not know it (`possessive(of:)`: "zorvaths").
    /// The correction is lit only where the word's end will type it (`corrects`); elsewhere it is a plain word, as on
    /// Apple's bar in a field with autocorrection off.
    func slots(for word: String, before: String, corrects: Bool = false) -> [Suggestion] {
        let plain = word.replacingOccurrences(of: "\u{2019}", with: "'") // the checker knows the straight apostrophe
        var asked: [String]?
        /// Apple's completions of the word ("hel": hello, help), asked only when the rule or an empty place needs them:
        /// a typo's correction and that word's own completions usually fill the places ("teh": the, they).
        func starts() -> [String] {
            if let asked { return asked }
            let found = completions(plain, before: before)
            asked = found
            return found
        }
        let known = lexicon.words[Typing.key(plain)] != nil || isWord(plain, before: before)
        let guessed = known ? [] : guesses(plain, before: before).filter { !$0.contains("-") }
        let fix = isUsers(plain) ? (known ? nil : possessive(of: plain))
            : correction(for: plain, known: known, guesses: guessed, startsWords: !starts().isEmpty, before: before)
        let fixStarts = fix.map { $0.contains(" ") ? [] : completions($0, before: String(before.dropLast(word.count)) + $0) } ?? []
        // The user's own words from the third letter: at "t" and "th" Apple's words ("the", "to") are the likely ones,
        // and thousands of contacts' words would fill both places for every start.
        let mine = word.count < 3 ? [] : learned.completions(of: word) + lexicon.completions(of: word)
        let first = (fix.map { [$0] } ?? []) + fixStarts + mine
        let words = Self.slots(typed: word, words: first).count == 3 ? first : first + (starts().isEmpty ? guessed : starts())
        return Self.slots(typed: word, words: words, correction: corrects ? fix : nil)
    }

    /// Apple's autocorrection for `word` (the word at the end of `before`), from what the dictionary said about it: the
    /// word a clear misspelling becomes at the word's end, or nil to leave it as typed (ThumbFree's own rule). A word
    /// of the user's own never gets here (`isUsers`). A lone "i" becomes "I"; a single letter, and a word in capitals
    /// (NASA) or with a capital inside (McDonald), stays; a word the dictionary knows stays, except that a sentence's
    /// capital can hide a missing apostrophe ("Im": I'm); a word it does not know follows the rule below, with the
    /// user's own words (contacts', kept and the Dictionary's words).
    func correction(for word: String, known: Bool, guesses: [String], startsWords: @autoclosure () -> Bool, before: String) -> String? {
        if word == "i" { return "I" }
        guard word.count > 1, !word.dropFirst().contains(where: \.isUppercase) else { return nil }
        guard known else {
            return Self.correction(for: word, guesses: guesses, startsWords: startsWords(), userWords: userWords(near: word))
        }
        let lower = word.lowercased()
        guard word != lower, !isWord(lower, before: before) else { return nil }
        return self.guesses(lower, before: before).prefix(3).first { $0.contains("'") && Self.sameLetters($0, word) }
    }

    /// The rule for a word nobody knows, from the dictionary's guesses (likeliest first), whether the word starts other
    /// words, and the user's own words: one of the first three guesses with the same letters in another form (dont:
    /// don't; iphone: iPhone), else the user's word it nearly is (a contact's, "Zakrof": Zakroff, or a kept one, in the
    /// typed first capital) or the likeliest guess, only when it is clear: one edit away (a letter added, dropped or
    /// changed, or two neighbors swapped), two for a word of seven letters or more; the first letter kept unless the first
    /// two were swapped; and, for a word that starts other words ("hel": hello, help, so it may be one still being
    /// typed), only a swap ("ot": to). Whether it starts other words is asked only when that last check needs it.
    static func correction(for word: String, guesses: [String], startsWords: @autoclosure () -> Bool, userWords: [String] = []) -> String? {
        if let fix = guesses.prefix(3).first(where: { sameLetters($0, word) }) { return fix }
        let mine = Edits.nearest(to: word, in: userWords).map { cased($0, like: word) }
        guard let best = mine ?? guesses.first, !best.contains(" ") else { return nil }
        let typed = Typing.key(word), guess = Typing.key(best), limit = typed.count >= 7 ? 2 : 1
        let swap = Edits.isSwap(typed, guess)
        guard Edits.distance(typed, guess, atMost: limit) <= limit, swap || typed.first == guess.first,
              swap || !startsWords() else { return nil }
        return best
    }

    /// `word` with the typed word's first capital, if it has one and `word` does not ("Vorlk": vorlak becomes Vorlak).
    static func cased(_ word: String, like typed: String) -> String {
        guard typed.first?.isUppercase == true, let first = word.first, first.isLowercase else { return word }
        return first.uppercased() + word.dropFirst()
    }

    /// The same letters in another form: another case, or with apostrophes or a space (dont and don't, im and I'm,
    /// iphone and iPhone).
    static func sameLetters(_ guess: String, _ word: String) -> Bool {
        guess != word && guess.lowercased().filter(\.isLetter) == word.lowercased().filter(\.isLetter)
    }

    /// The autocorrection for `word`, the word at the end of `before`: what its lit place would be.
    func correction(for word: String, before: String) -> String? {
        slots(for: word, before: before, corrects: true).first { $0.kind == .correction }?.text
    }

    /// The places in order: the typed word, then the words (which the dictionary gives in the typed word's case), none
    /// equal to the typed word, none twice in any case, and no hyphenated guess ("alot": a-lot), which Apple's bar does
    /// not offer. The `correction` is the lit place.
    static func slots(typed: String, words: [String], correction: String? = nil) -> [Suggestion] {
        var seen: Set<String> = []
        let others = words.filter { $0 != typed && !$0.contains("-") && seen.insert($0.lowercased()).inserted }.prefix(2)
        return [Suggestion(kind: .typed, text: typed)] + others.map { Suggestion(kind: $0 == correction ? .correction : .word, text: $0) }
    }

    /// The text the checker reads: the last 100 characters before the word (enough for its ranking), then `word` in place
    /// of the word `before` ends with (the same word in another form: lowercase, or with straight apostrophes, the ones
    /// the checker knows), and the word's range at its end.
    private static func query(_ word: String, before: String) -> (String, NSRange) {
        let text = (String(before.dropLast(word.count).suffix(100)) + word).replacingOccurrences(of: "\u{2019}", with: "'")
        let length = (word as NSString).length
        return (text, NSRange(location: (text as NSString).length - length, length: length))
    }
}

/// How far apart two words are, for the autocorrection's "clear misspelling" test.
enum Edits {
    /// The fewest single edits that turn `a` into `b`: a letter added, dropped or changed, or two neighbors swapped. Past
    /// `limit` it stops as soon as every way is longer and returns more than `limit` (a row's smallest count never
    /// shrinks). Three rows of the table at a time, so a check against many words stays cheap.
    static func distance(_ a: String, _ b: String, atMost limit: Int = .max) -> Int {
        let a = Array(a), b = Array(b)
        guard !a.isEmpty, !b.isEmpty else { return max(a.count, b.count) }
        var older = [Int](repeating: 0, count: b.count + 1), old = Array(0...b.count), row = old
        for i in 1...a.count {
            row[0] = i
            var smallest = i
            for j in 1...b.count {
                row[j] = min(old[j] + 1, row[j - 1] + 1, old[j - 1] + (a[i - 1] == b[j - 1] ? 0 : 1))
                if i > 1, j > 1, a[i - 1] == b[j - 2], a[i - 2] == b[j - 1] { row[j] = min(row[j], older[j - 2] + 1) }
                smallest = min(smallest, row[j])
            }
            if smallest > limit { return smallest }
            (older, old, row) = (old, row, older)
        }
        return old[b.count]
    }

    /// `b` is `a` with two neighboring letters swapped ("teh" and "the").
    static func isSwap(_ a: String, _ b: String) -> Bool {
        let a = Array(a), b = Array(b)
        guard a.count == b.count else { return false }
        let differ = a.indices.filter { a[$0] != b[$0] }
        return differ.count == 2 && differ[1] == differ[0] + 1 && a[differ[0]] == b[differ[1]] && a[differ[1]] == b[differ[0]]
    }
}
