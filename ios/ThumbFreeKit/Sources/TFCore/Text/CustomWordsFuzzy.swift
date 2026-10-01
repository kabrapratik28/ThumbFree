// Near misses for English text: the plural s and fuzzy matches. A fuzzy match scores the keys' edit distance over the
// longer key's length, times 0.3 when their Soundex codes agree; it needs a score under 0.18 and never covers more than
// 2 edits. The guards against the traps a plain matcher falls into (short entries eating real words, Soundex turning
// "save" into "SEV" and "div" into "diff"):
// 1. an entry with a key under 4 characters, or with a letter outside ASCII, matches only exactly;
// 2. an n-gram with a common English word in it (CommonWords) matches only exactly;
// 3. the Soundex discount needs both keys to be 5 or more ASCII letters, and covers 1 edit between two 5-letter keys;
// 4. an n-gram of 2 or 3 words may not take its first word when the rest alone matches the same entry at least as well;
// 5. (exact matching, CustomWordsMatcher.swift) common words merge into a one-word entry only with no function word.
// The fuzzy score is adapted from MIT-licensed code; see THIRD_PARTY_NOTICES.md.
extension CustomWords {
    static let threshold = 0.18
    static let maxEdits = 2

    /// An entry key a near miss may match (ASCII, 4 or more characters), with its prefilters.
    struct Fuzzy {
        let key: [UInt8]
        let entry: Entry
        let soundex: UInt32
        let mask: UInt64
        let bigrams: UInt64

        init(key: [UInt8], entry: Entry) {
            (self.key, self.entry) = (key, entry)
            soundex = soundexOf(key)
            mask = CustomWords.mask(key)
            bigrams = CustomWords.bigrams(key)
        }
    }

    static func isAsciiKey(_ key: String) -> Bool {
        !key.isEmpty && key.utf8.allSatisfy { ($0 >= 0x61 && $0 <= 0x7A) || ($0 >= 0x30 && $0 <= 0x39) }
    }

    // The key without its last scalar, which is an ASCII "s" wherever this is called.
    static func dropLastByte(_ key: String) -> String { String(decoding: key.utf8.dropLast(), as: UTF8.self) }

    // Guard 3: only a key of 5 or more ASCII letters has a Soundex code to agree on; its 4 characters packed, else 0.
    static func soundexOf(_ key: [UInt8]) -> UInt32 {
        guard key.count >= 5, key.allSatisfy({ $0 >= 0x61 && $0 <= 0x7A }) else { return 0 }
        return Soundex.packed(key)
    }

    // The most edits between keys whose longer one has `longest` characters that still score under the threshold, and
    // never more than 2. Soundex keeps 4 characters, so "installation" sounds like Instagram to it: the discount alone
    // would let 60% of a word differ. Between two 5-letter keys it covers 1 edit (guard 3).
    static func mostEdits(_ longest: Int, phonetic: Bool) -> Int {
        let weight = phonetic ? 0.3 : 1.0
        var most = min(maxEdits, Int(threshold / weight * Double(longest)) + 1)
        while most > 0 && Double(most) / Double(longest) * weight >= threshold { most -= 1 }
        return phonetic && longest == 5 ? min(most, 1) : most
    }

    // The score of `key` against one fuzzy entry within `most` edits, or nil. An edit adds or drops at most 2 of the
    // letters present and 4 of the letter pairs, so the two cheap tests skip nearly every pair before any distance.
    // ponytail: two 64-bit masks, not an index. Entries built to share their letters, letter pairs and Soundex code still
    // cost one banded distance each (the speed test's Eulerian case, about 40 ms per 1,000 words); a deletion-signature
    // index cached per list would lift that ceiling if a real list ever reaches it.
    static func score(_ key: [UInt8], _ mask: UInt64, _ bigrams: UInt64, _ f: Fuzzy, _ longest: Int, _ most: Int,
                      _ phonetic: Bool) -> Double? {
        if most == 0 || (mask ^ f.mask).nonzeroBitCount > 2 * most { return nil }
        if (bigrams ^ f.bigrams).nonzeroBitCount > 4 * most { return nil }
        guard let edits = levenshtein(key, f.key, most: most) else { return nil }
        return Double(edits) / Double(longest) * (phonetic ? 0.3 : 1.0)
    }

    /// Levenshtein distance (every edit costs 1) within `most` cells of the diagonal: nil once it must exceed `most`.
    static func levenshtein(_ a: [UInt8], _ b: [UInt8], most: Int) -> Int? {
        if a.count - b.count > most || b.count - a.count > most { return nil }
        let over = most + 1
        let width = b.count + 2
        return withUnsafeTemporaryAllocation(of: Int.self, capacity: 2 * width) { rows -> Int? in
            rows.initialize(repeating: over)
            var previous = 0, current = width // offsets of the two rows in `rows`
            for j in 0...b.count { rows[previous + j] = j <= most ? j : over }
            for i in 1..<(a.count + 1) {
                let lo = max(1, i - most), hi = min(b.count, i + most)
                rows[current + lo - 1] = lo == 1 && i <= most ? i : over
                var least = rows[current + lo - 1]
                for j in lo..<(hi + 1) {
                    var v = rows[previous + j - 1] + (a[i - 1] == b[j - 1] ? 0 : 1)
                    if rows[previous + j] + 1 < v { v = rows[previous + j] + 1 }
                    if rows[current + j - 1] + 1 < v { v = rows[current + j - 1] + 1 }
                    rows[current + j] = min(v, over)
                    if v < least { least = v }
                }
                if hi < b.count { rows[current + hi + 1] = over } // the next row reads one cell past this band
                if least > most { return nil }
                swap(&previous, &current)
            }
            return rows[previous + b.count] <= most ? rows[previous + b.count] : nil
        }
    }

    // The ASCII letters and digits present in a key, one bit each.
    static func mask(_ key: [UInt8]) -> UInt64 {
        key.reduce(0) { $0 | 1 << UInt64($1 <= 0x39 ? Int($1) - 0x30 + 26 : Int($1) - 0x61) }
    }

    // The pairs of adjacent characters in a key, each hashed to one of 64 bits.
    static func bigrams(_ key: [UInt8]) -> UInt64 {
        var bits: UInt64 = 0
        for i in key.indices.dropFirst() {
            let h = (Int32(key[i - 1]) &* 131 &+ Int32(key[i])) &* -0x61c8_8647
            bits |= 1 << UInt64(UInt32(bitPattern: h) >> 26)
        }
        return bits
    }
}

extension CustomWords.Token {
    var ascii: Bool { CustomWords.isAsciiKey(key) }
    var mask: UInt64 { ascii ? CustomWords.mask(Array(key.utf8)) : 0 }
}

extension CustomWords.Matcher {
    // The best fuzzy match of an n-gram of uncommon ASCII words: the lowest score, a tie going to the earlier entry.
    func fuzzy(_ words: [CustomWords.Token], _ key: [UInt8]) -> (CustomWords.Entry, Double)? {
        let soundex = CustomWords.soundexOf(key)
        let mask = words.reduce(0) { $0 | $1.mask }
        let bigrams = CustomWords.bigrams(key)
        let rest = Array(words.dropFirst())
        var restKey: String? // built on first need
        var best: CustomWords.Fuzzy?
        var bestScore = CustomWords.threshold
        let edits = CustomWords.maxEdits
        for length in max(0, key.count - edits)...(key.count + edits) where length < fuzzyByLength.count {
            let longest = max(key.count, length)
            let plain = CustomWords.mostEdits(longest, phonetic: false)
            let alike = CustomWords.mostEdits(longest, phonetic: true)
            for f in fuzzyByLength[length] {
                let phonetic = soundex != 0 && soundex == f.soundex
                guard let score = CustomWords.score(key, mask, bigrams, f, longest, phonetic ? alike : plain, phonetic)
                else { continue }
                if let b = best, score > bestScore || (score == bestScore && f.entry.index > b.entry.index) { continue }
                // A fuzzy merge also changes keys, so it must drop words: "ab cd" never becomes "Abc De".
                if words.count > 1 && f.entry.wordKeys.count >= words.count { continue }
                // Guard 4: "b zendesk" is no near miss for Zendesk when "zendesk" alone matches it at least as well.
                if !rest.isEmpty {
                    let r = restKey ?? rest.map(\.key).joined()
                    restKey = r
                    if exact[r]?.contains(where: { $0 === f.entry }) == true { continue }
                    if let restScore = restScore(Array(r.utf8), rest, f), restScore <= score { continue }
                }
                best = f
                bestScore = score
            }
        }
        return best.map { ($0.entry, bestScore) }
    }

    private func restScore(_ key: [UInt8], _ words: [CustomWords.Token], _ f: CustomWords.Fuzzy) -> Double? {
        let longest = max(key.count, f.key.count)
        let soundex = CustomWords.soundexOf(key)
        let phonetic = soundex != 0 && soundex == f.soundex
        return CustomWords.score(key, words.reduce(0) { $0 | $1.mask }, CustomWords.bigrams(key), f, longest,
                                 CustomWords.mostEdits(longest, phonetic: phonetic), phonetic)
    }
}
