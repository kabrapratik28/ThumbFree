/// Emoji search, as Apple's Search Emoji: every word typed must begin a word of an emoji's name or of its CLDR keywords
/// (English). Apple ranks with its own keywords and its own usage data, neither public; with the public ones, the best
/// matches come first by these rules, in this order (checked against Apple's on the iPhone 16 Simulator):
/// 1. Whole words: an emoji where every word typed is a whole word of its name or keywords comes before one where a word
///    only begins one ("car" gives the cars before the carrot).
/// 2. Apple's starting Frequently Used (the emoji Apple's keyboard starts with, its most used), in its order ("love" gives
///    the red heart first).
/// 3. The emoji named by the query, or by the query and "face" ("sun" gives the sun; "cat", the cat face and the cat).
/// 4. The picker's order.
/// An empty query lists the emoji you picked (`used`, as Frequently Used ranks them), then the whole picker, as Apple's
/// shows before anything is typed.
enum EmojiSearch {
    static func results(for query: String, used: [String] = []) -> [Emoji] {
        let query = normalize(query)
        let words = query.split(separator: " ").map { " " + $0 }
        guard !words.isEmpty else { return used.compactMap(EmojiCatalog.emoji) + index.map(\.emoji) }
        return index.enumerated().compactMap { order, entry -> (rank: (Int, Int, Int, Int), emoji: Emoji)? in
            guard words.allSatisfy(entry.words.contains) else { return nil }
            let whole = words.allSatisfy { entry.words.contains($0 + " ") }
            let named = entry.name == query || entry.name == query + " face"
            return ((whole ? 0 : 1, entry.popular, named ? 0 : 1, order), entry.emoji)
        }
        .sorted { $0.rank < $1.rank }
        .map(\.emoji)
    }

    /// Lowercased, a hyphen folded to the space a name or keyword's other words sit on ("heart-eyes", "upside-down") and
    /// Unicode's curly apostrophe ("woman’s hat") folded to the straight one this keyboard's own key types, so a query
    /// typed either way still finds them; whitespace collapsed last, in case folding a hyphen ever leaves two spaces.
    private static func normalize(_ text: some StringProtocol) -> String {
        text.lowercased().replacing("-", with: " ").replacing("\u{2019}", with: "'")
            .split(whereSeparator: \.isWhitespace).joined(separator: " ")
    }

    private struct Entry {
        let emoji: Emoji
        let name: String    // normalized
        let words: String   // " name and keywords ", normalized, so " " + a word typed finds a word it begins
        let popular: Int    // its place in Apple's starting Frequently Used, Int.max when not in it
    }

    /// Built on the first search, not before: the keyboard carries the keywords only once search is used.
    private static let index: [Entry] = {
        let keywords = Dictionary(EmojiData.keywords.split(separator: "\n").map { line in
            let parts = line.split(separator: " ", maxSplits: 1)
            return (String(parts[0]), parts.count > 1 ? parts[1].replacing("|", with: " ") : "")
        }, uniquingKeysWith: { first, _ in first })
        let popular = Dictionary(EmojiCatalog.recentsDefault.enumerated().map { ($1, $0) }, uniquingKeysWith: min)
        return EmojiCatalog.categories.flatMap(\.emoji).map { emoji in
            let name = normalize(emoji.name)
            return Entry(emoji: emoji, name: name, words: " \(name) \(normalize(keywords[emoji.text] ?? "")) ",
                         popular: popular[emoji.text] ?? .max)
        }
    }()
}
