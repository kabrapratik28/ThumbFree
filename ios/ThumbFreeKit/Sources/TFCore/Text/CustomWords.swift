/// Custom words (the Dictionary): names, brands and jargon spelled the user's way. The match keys, the n-grams and the
/// fuzzy score are adapted from MIT-licensed code; see THIRD_PARTY_NOTICES.md.
public enum CustomWords {
    /// The most entries `parse` keeps.
    public static let maxEntries = 500
    /// The most characters (code points) an entry may have.
    public static let maxChars = 60

    /// The list from what the user typed: split at commas and line breaks, each entry in NFC, trimmed, inner whitespace
    /// runs as one space. Empty entries, entries over `maxChars` code points and repeats that differ only in case go
    /// (the first spelling stays), and at most `maxEntries` are kept. The stored list is always read back through here.
    public static func parse(_ raw: String) -> [String] { parse(raw, limit: maxEntries) }

    /// `parse`, keeping at most `limit` entries instead of `maxEntries`. `addList` parses a paste alone with no
    /// limit, so it can count the paste's own duplicates apart from what does not fit once merged.
    static func parse(_ raw: String, limit: Int) -> [String] {
        var seen = Set<String>()
        var out: [String] = []
        for piece in raw.unicodeScalars.split(omittingEmptySubsequences: false, whereSeparator: isSeparator) {
            let entry = UnicodeText.splitWhitespace(UnicodeText.nfc(String(Substring(piece)))).joined(separator: " ")
            guard !entry.isEmpty, entry.unicodeScalars.count <= maxChars,
                  seen.insert(UnicodeText.lowercase(entry)).inserted else { continue }
            out.append(entry)
            if out.count == limit { break }
        }
        return out
    }

    /// The one-word entries that are common English words ("Will", "IT"): an exact match always applies, so every
    /// "will" is then written "Will". The Dictionary warns about them.
    public static func riskyEntries(_ entries: [String]) -> [String] {
        entries.filter {
            let word = UnicodeText.nfc($0)
            return UnicodeText.splitWhitespace(word).count == 1 && CommonWords.all.contains(key(word.unicodeScalars))
        }
    }

    /// `text` with `entries` (a list as `parse` gives it) applied, over n-grams of 1 to 3 words compared in NFC. A key is
    /// a word's letters and digits, lowercased: an exact match is an equal key ("github" is GitHub, "chat gpt" is
    /// ChatGPT). Words no entry matches, and all spacing, stay exactly as they were. An empty list returns `text`.
    /// Correcting the result again changes nothing.
    public static func correct(_ text: String, entries: [String], exactOnly: Bool) -> String {
        if entries.isEmpty { return text }
        let matcher = Matcher(entries, exactOnly: exactOnly)
        // A pass can set up a match for the next ("kubernetis engine" gives "Kubernetes engine", an exact "Kubernetes
        // Engine"), so passes repeat until one changes nothing. Some lists loop (an entry splits a word another merges
        // back) or chain for long: a pass that brings back an earlier text, or the 64th, gives back the text as it came,
        // which correcting again does too. Texts are compared as scalars: Swift's == would call NFD and NFC equal.
        var seen = Set<[Unicode.Scalar]>()
        var out = Array(text.unicodeScalars)
        while seen.insert(out).inserted && seen.count <= maxPasses {
            let next = matcher.pass(out)
            if next == out { return seen.count == 1 ? text : String(String.UnicodeScalarView(out)) }
            out = next
        }
        return text
    }

    static let maxPasses = 64

    /// False only for English ("en", "en-US"...); true for every other language and for nil (the multilingual model):
    /// the guards against near misses know English only. With near misses on, 44 realistic entries changed 6 of 800
    /// FLEURS sentences in 8 other languages ("grazie mille" became "Grazia mille").
    public static func exactOnly(language: String?) -> Bool {
        guard let language else { return true }
        return Fillers.code(language) != "en"
    }

    // Scalars, not Characters: "\r\n" is one Character in Swift.
    static func isSeparator(_ s: Unicode.Scalar) -> Bool { s == "," || s == "\n" || s == "\r" }

    /// A word's key: its alphanumeric scalars, each lowercased on its own (full mapping, no context).
    static func key<C: Collection<Unicode.Scalar>>(_ scalars: C) -> String {
        var out = String.UnicodeScalarView()
        for s in scalars {
            if s.isASCII {
                switch s.value {
                case 0x61...0x7A, 0x30...0x39: out.append(s)
                case 0x41...0x5A: out.append(Unicode.Scalar(UInt8(s.value + 32)))
                default: break
                }
            } else if UnicodeText.isAlphanumeric(s) {
                out.append(contentsOf: s.properties.lowercaseMapping.unicodeScalars)
            }
        }
        return String(out)
    }
}
