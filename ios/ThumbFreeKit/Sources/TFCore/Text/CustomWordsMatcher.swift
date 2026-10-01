// The matcher over n-grams of 1 to 3 words. Its match keys and n-grams are adapted from MIT-licensed code; see
// THIRD_PARTY_NOTICES.md.
extension CustomWords {
    /// Guard 5's function words: pronouns, articles, prepositions, conjunctions, auxiliaries and modals, number words.
    static let functionWords: Set<String> = Set(("i you he she it we they me him her us them my your his its our their this " +
        "that these those who which what a an the about after against around at before between by down during for from in " +
        "into of off on onto out over through to under until up with within without and or but nor so yet if because though " +
        "while as than am is are was were be been being have has had do does did can could will would shall should may " +
        "might must zero one two three four five six seven eight nine ten twelve hundred thousand").split(separator: " ").map(String.init))

    /// What ends a sentence, for the capital of an all-lowercase entry.
    static let terminators: Set<Unicode.Scalar> = [".", "!", "?", "…", "。", "！", "？"]

    /// An entry: `lead` + `core` + `trail`, its marks before its first and after its last letter or digit split off.
    /// A class, so the matcher's loops copy one reference per entry.
    final class Entry: Sendable {
        let index: Int // list order, the tie-break
        let text: String // NFC
        let lead: [Unicode.Scalar]
        let core: [Unicode.Scalar]
        let trail: [Unicode.Scalar]
        let lowercase: Bool
        let wordKeys: [String]
        var oneWord: Bool { wordKeys.count == 1 }

        init(_ word: String, index: Int) {
            self.index = index
            text = UnicodeText.nfc(word)
            let s = Array(text.unicodeScalars)
            let coreStart = firstAlphanumeric(s)
            let coreEnd = lastAlphanumeric(s, from: coreStart)
            lead = Array(s[..<coreStart])
            core = Array(s[coreStart..<coreEnd])
            trail = Array(s[coreEnd...])
            lowercase = !core.contains { $0.properties.isUppercase }
            wordKeys = core.split(whereSeparator: UnicodeText.isWhitespace).map { key($0) }
        }
    }

    /// A word of the text between whitespace: `start` to `end` in the text's scalars, `s` its NFC scalars. Its letters and
    /// digits run from `coreStart` to `coreEnd` (both s.count when it has none); `stemEnd` is `coreEnd`, or where a
    /// possessive 's starts. `key` is the stem's, or "" when the word must not match: no letters or digits, or a symbol,
    /// emoji, format character or loose mark among them ("sealed"), which a match would drop.
    struct Token {
        let start: Int
        let end: Int
        let s: [Unicode.Scalar]
        let coreStart: Int
        let coreEnd: Int
        let stemEnd: Int
        let key: String
        var lexical: Bool { coreStart < coreEnd }
        let common: Bool

        init(start: Int, end: Int, s: [Unicode.Scalar], coreStart: Int, coreEnd: Int, stemEnd: Int, key: String) {
            (self.start, self.end, self.s) = (start, end, s)
            (self.coreStart, self.coreEnd, self.stemEnd, self.key) = (coreStart, coreEnd, stemEnd, key)
            common = CommonWords.all.contains(key)
        }
    }

    /// `n` words matched `entry`; the last word's text goes on from `resume`.
    struct Match {
        let n: Int
        let entry: Entry
        let score: Double
        let resume: Int
    }

    struct Matcher {
        let exactOnly: Bool // no plural s, no fuzzy match: only an n-gram whose key equals an entry's changes
        var exact: [String: [Entry]] = [:] // every entry with the key, in list order
        var fuzzyByLength: [[Fuzzy]] = [] // near-miss candidates, indexed by key length

        init(_ words: [String], exactOnly: Bool) {
            self.exactOnly = exactOnly
            var fuzzyKeys = Set<String>() // the first entry with a key is its only fuzzy candidate; a later entry with the same key is never tried
            for (index, word) in words.enumerated() {
                let entry = Entry(word, index: index)
                let keys = keys(of: entry)
                for k in keys { exact[k, default: []].append(entry) }
                // Guard 1 holds for the whole entry, or "R&D" would match "rained" through randd.
                guard keys.allSatisfy({ $0.utf8.count >= 4 && isAsciiKey($0) }) else { continue }
                for k in keys where fuzzyKeys.insert(k).inserted {
                    let f = Fuzzy(key: Array(k.utf8), entry: entry)
                    while fuzzyByLength.count <= f.key.count { fuzzyByLength.append([]) }
                    fuzzyByLength[f.key.count].append(f)
                }
            }
        }

        /// One pass over the text: at each word the best n-gram match, if any, then on after its last word.
        func pass(_ text: [Unicode.Scalar]) -> [Unicode.Scalar] {
            let tokens = tokenize(text)
            var out: [Unicode.Scalar] = []
            out.reserveCapacity(text.count)
            var copied = 0 // the text before this index is in out
            var i = 0
            while i < tokens.count {
                guard let match = best(tokens, i) else {
                    i += 1
                    continue
                }
                let first = tokens[i], last = tokens[i + match.n - 1]
                out.append(contentsOf: text[copied..<first.start])
                out.append(contentsOf: render(match, first, last, sentenceStart(tokens, i)))
                copied = last.end
                i += match.n
            }
            out.append(contentsOf: text[copied...])
            return out
        }

        // The n-grams of 1 to 3 words at i: the lowest score wins, and a tie goes to the longer n-gram.
        private func best(_ tokens: [Token], _ i: Int) -> Match? {
            var best: Match?
            for n in 1...3 {
                if i + n > tokens.count { break }
                let last = tokens[i + n - 1]
                if last.key.isEmpty { break }
                // Never across punctuation or a possessive: in "chat, gpt" the comma ends the n-gram at "chat,".
                if n > 1 {
                    let before = tokens[i + n - 2]
                    if before.coreEnd < before.s.count || before.stemEnd < before.coreEnd || last.coreStart > 0 { break }
                }
                guard let match = match(tokens, i, n) else { continue }
                if let b = best, match.score > b.score { continue }
                best = match
            }
            return best
        }

        private func match(_ tokens: [Token], _ i: Int, _ n: Int) -> Match? {
            let words = Array(tokens[i..<(i + n)])
            let last = words[n - 1]
            let keys = words.map(\.key)
            let key = n == 1 ? last.key : keys.joined()
            if let entries = exact[key] {
                // The first entry with the key that guard 5 allows and that fits: "Mac Book Pro" listed before
                // "MacBook Pro" still lets "macbook pro" take the second.
                let function = n > 1 && words.allSatisfy(\.common) && words.contains { functionWords.contains($0.key) }
                guard let entry = entries.first(where: { (!function || !$0.oneWord) && fits($0, keys) }) else { return nil }
                return Match(n: n, entry: entry, score: 0, resume: last.stemEnd)
            }
            if exactOnly { return nil }
            // A plural keeps its s: "gpus" is GPUs. Never a common word's s, or "this" would become an entry "Thi" and an s.
            if !last.common && last.stemEnd == last.coreEnd && (last.s[last.coreEnd - 1] == "s" || last.s[last.coreEnd - 1] == "S"),
               let entries = exact[dropLastByte(key)] {
                let stems = Array(keys.dropLast()) + [dropLastByte(keys[n - 1])]
                guard let entry = entries.first(where: { fits($0, stems) }) else { return nil }
                return Match(n: n, entry: entry, score: 0, resume: last.coreEnd - 1)
            }
            // Guard 2; and a 50-character cap on a key to score.
            if words.contains(where: { $0.common || !$0.ascii }) || key.utf8.count > 50 { return nil }
            guard let (entry, score) = fuzzy(words, Array(key.utf8)) else { return nil }
            return Match(n: n, entry: entry, score: score, resume: last.stemEnd)
        }

        // A merge never makes more words than it takes, and a merge into as many words keeps each word's key: only its
        // case changes. One word may still become an entry of several ("macbookpro" is MacBook Pro).
        func fits(_ entry: Entry, _ keys: [String]) -> Bool {
            keys.count == 1 || entry.wordKeys.count < keys.count || entry.wordKeys == keys
        }

        /// The entry in place of the n-gram, between the first word's leading and the last word's trailing marks. An entry's
        /// own marks are written once: "#" before "hashtag" and "." after "U.S" reuse the text's, if it has them there.
        /// An all-lowercase entry takes the capital of a word that starts a sentence.
        private func render(_ match: Match, _ first: Token, _ last: Token, _ sentenceStart: Bool) -> [Unicode.Scalar] {
            let entry = match.entry
            let before = first.s[..<first.coreStart]
            let after = last.s[match.resume...]
            var core = entry.core
            if entry.lowercase && sentenceStart && first.s[first.coreStart].properties.isUppercase, let head = core.first {
                core = Array(head.properties.uppercaseMapping.unicodeScalars) + core.dropFirst()
            }
            return Array(before.dropLast(sharedEnd(before, entry.lead))) + entry.lead + core + entry.trail
                + Array(after.dropFirst(sharedStart(after, entry.trail)))
        }

        // A sentence starts at the text's start, or after . ! ? … 。 ！ ？ in the marks since the last word, looking past
        // words with no letters or digits ("Done. 😀 Kubectl").
        private func sentenceStart(_ tokens: [Token], _ i: Int) -> Bool {
            for j in stride(from: i - 1, through: 0, by: -1) {
                let t = tokens[j]
                if t.s[(t.lexical ? t.coreEnd : 0)...].contains(where: terminators.contains) { return true }
                if t.lexical { return false }
            }
            return true
        }
    }

    /// An entry's keys: its key and, for "&", the key with "&" read as "and" ("R&D" gives rd and randd).
    static func keys(of entry: Entry) -> [String] {
        let and = entry.text.unicodeScalars.flatMap { $0 == "&" ? Array(" and ".unicodeScalars) : [$0] }
        var keys: [String] = []
        for k in [key(entry.text.unicodeScalars), key(and)] where !k.isEmpty && !keys.contains(k) { keys.append(k) }
        return keys
    }

    static func tokenize(_ text: [Unicode.Scalar]) -> [Token] {
        var tokens: [Token] = []
        var end = 0
        while end < text.count {
            if UnicodeText.isWhitespace(text[end]) {
                end += 1
                continue
            }
            let start = end
            while end < text.count && !UnicodeText.isWhitespace(text[end]) { end += 1 }
            let word = text[start..<end]
            let s = word.allSatisfy { $0.value < 0x300 }
                ? Array(word) : Array(UnicodeText.nfc(String(String.UnicodeScalarView(word))).unicodeScalars)
            let coreStart = firstAlphanumeric(s)
            let coreEnd = lastAlphanumeric(s, from: coreStart)
            let possessive = coreEnd - coreStart >= 3 && (s[coreEnd - 2] == "'" || s[coreEnd - 2] == "’")
                && (s[coreEnd - 1] == "s" || s[coreEnd - 1] == "S")
            let stemEnd = possessive ? coreEnd - 2 : coreEnd
            let wordKey = sealed(s, coreStart, coreEnd) ? "" : key(s[coreStart..<stemEnd])
            tokens.append(Token(start: start, end: end, s: s, coreStart: coreStart, coreEnd: coreEnd, stemEnd: stemEnd, key: wordKey))
        }
        return tokens
    }

    // A symbol, emoji, format character or loose mark among the word's letters and digits, or a mark on its last one.
    private static func sealed(_ s: [Unicode.Scalar], _ from: Int, _ to: Int) -> Bool {
        for c in s[from..<to] where !UnicodeText.isAlphanumeric(c) && !isPunctuation(c) { return true }
        guard to < s.count else { return false }
        switch s[to].properties.generalCategory {
        case .nonspacingMark, .enclosingMark, .spacingMark: return true
        default: return false
        }
    }

    private static func isPunctuation(_ c: Unicode.Scalar) -> Bool {
        switch c.properties.generalCategory {
        case .connectorPunctuation, .dashPunctuation, .openPunctuation, .closePunctuation,
             .initialPunctuation, .finalPunctuation, .otherPunctuation: true
        default: false
        }
    }

    private static func firstAlphanumeric(_ s: [Unicode.Scalar]) -> Int {
        s.firstIndex(where: UnicodeText.isAlphanumeric) ?? s.count
    }

    // After the last letter or digit: s.count when there is none from `from` on.
    private static func lastAlphanumeric(_ s: [Unicode.Scalar], from: Int) -> Int {
        if from == s.count { return from }
        var i = s.count
        while i > from && !UnicodeText.isAlphanumeric(s[i - 1]) { i -= 1 }
        return i
    }

    // How many scalars at the end of `text` are also the end of `marks`: the marks the text already has there.
    private static func sharedEnd(_ text: ArraySlice<Unicode.Scalar>, _ marks: [Unicode.Scalar]) -> Int {
        for k in stride(from: min(text.count, marks.count), through: 1, by: -1)
        where text.suffix(k).elementsEqual(marks.suffix(k)) { return k }
        return 0
    }

    // How many scalars at the start of `text` are also the start of `marks`.
    private static func sharedStart(_ text: ArraySlice<Unicode.Scalar>, _ marks: [Unicode.Scalar]) -> Int {
        for k in stride(from: min(text.count, marks.count), through: 1, by: -1)
        where text.prefix(k).elementsEqual(marks.prefix(k)) { return k }
        return 0
    }
}
