import Foundation

/// The user's own words beside Apple's dictionary, from iOS's supplementary lexicon (`requestSupplementaryLexicon`): their
/// text replacements (Settings, General, Keyboard, Text Replacement) and the single words of their contacts' cards (names,
/// but also companies, titles and cities: 30 words from the Simulator's six sample contacts, with or without Full Access).
/// As on Apple's keyboard, a shortcut typed becomes its phrase at the word's end ("omw": On my way!), and the words are
/// never corrected, are offered as their start is typed, and are what a near miss becomes ("Zakrof": Zakroff). ThumbFree's
/// Dictionary (the names and terms the user spells their way) adds its words the same way. Kept in memory only, read
/// again each time the keyboard shows.
struct Lexicon: Equatable, Sendable {
    /// iOS gives roughly 5 or 6 entries per contact (first name, last name, nickname, company, job title): a cap of
    /// 10,000 contacts' words comfortably covers contact lists in the low thousands (far past what a phone holds in
    /// practice) while keeping the lexicon's dictionaries bounded in memory, as `LearnedWords.limit` bounds kept words.
    static let limit = 10_000
    /// ThumbFree's Dictionary, shared with the keyboard through the App Group: the app writes its entries here at launch and
    /// on each change; the keyboard reads them with Full Access (without it the App Group is out of its reach).
    static let dictionaryKey = "TFDictionary"

    /// A shortcut, as a key (`Typing.key`), to its phrase ("omw": "On my way!"; "iphone": "iPhone").
    private(set) var replacements: [String: String] = [:]
    /// The words, as keys, to the words as written ("zakroff": "Zakroff").
    private(set) var words: [String: String] = [:]
    /// The words' keys by their first letter, made with the lexicon: its completions and a near miss look only through
    /// the words that start like the typed one.
    private var keysByFirstLetter: [Character: [String]] = [:]

    /// iOS's entries: a pair whose typed and written forms differ is a replacement, one whose forms match is a word. Then
    /// the words of ThumbFree's Dictionary's entries ("Dr. Nakamura": Dr, Nakamura), as written. Every replacement is kept;
    /// past `limit` words, iOS's later words are dropped.
    init(pairs: [(input: String, text: String)] = [], dictionary: [String] = []) {
        for pair in pairs where !pair.input.isEmpty && !pair.text.isEmpty {
            let key = Typing.key(pair.input)
            if pair.input != pair.text { replacements[key] = pair.text } else if words.count < Self.limit { words[key] = pair.text }
        }
        for entry in dictionary {
            for word in entry.split(whereSeparator: { !$0.isLetter && !Typing.apostrophes.contains($0) }) where word.count > 1 {
                let key = Typing.key(String(word))
                words[key] = words[key] ?? String(word)
            }
        }
        keysByFirstLetter = Dictionary(grouping: words.keys) { $0.first ?? " " }
    }

    /// The words that start with `prefix` and are longer, as written ("zak": Zakroff), shortest first.
    func completions(of prefix: String) -> [String] {
        let lower = Typing.key(prefix)
        return (lower.first.flatMap { keysByFirstLetter[$0] } ?? []).filter { $0.count > lower.count && $0.hasPrefix(lower) }
            .compactMap { words[$0] }.sorted { ($0.count, $0) < ($1.count, $1) }
    }

    /// The words whose key starts with `letter`, as written.
    func words(startingWith letter: Character) -> [String] { (keysByFirstLetter[letter] ?? []).compactMap { words[$0] } }
}

/// iOS answers each of the keyboard's lexicon requests later, on its own queue, so two requests can answer out of order:
/// each request gets the next number, only the latest one's answer is taken, and nothing is corrected until it has come
/// (a contact's or the Dictionary's word must never be corrected because its lexicon was late).
struct LexiconRequests {
    private(set) var latest = 0
    /// The latest request has answered.
    private(set) var current = false

    /// A new request: its number.
    mutating func start() -> Int {
        latest += 1
        current = false
        return latest
    }

    /// Request `number` answered: true when it is the latest, whose answer is taken; an older one's is dropped.
    mutating func answered(_ number: Int) -> Bool {
        guard number == latest else { return false }
        current = true
        return true
    }
}

extension Edits {
    /// The one word in `words` a misspelling most likely meant: one edit away (two from seven letters), with the same
    /// first letter unless the first two were swapped ("ukbernetes": Kubernetes). Never a word the typed one starts with:
    /// that is the word with an ending ("Zakroffs", "Zakroff's"), not a typo of it. Nil when none, or more than one, is
    /// that close. A word listed twice in two cases counts once, in the case listed first. Both apostrophes are one.
    static func nearest(to word: String, in words: [String]) -> String? {
        let typed = Typing.key(word), limit = typed.count >= 7 ? 2 : 1
        var near: [String: String] = [:]
        for candidate in words {
            let other = Typing.key(candidate)
            guard near[other] == nil, !typed.hasPrefix(other), abs(other.count - typed.count) <= limit,
                  other.first == typed.first || isSwap(typed, other), limit > 1 || oneEditLeaves(typed, other),
                  distance(typed, other, atMost: limit) <= limit else { continue }
            near[other] = candidate
        }
        return near.count == 1 ? near.first?.value : nil
    }

    /// Whether `a` and `b` could be one edit apart, told without the table: one edit leaves at most two letters between
    /// their common start and their common end (a swap leaves two, a change one), which nearly every other word fails
    /// after a letter or two.
    private static func oneEditLeaves(_ a: String, _ b: String) -> Bool {
        var same = 0
        for (x, y) in zip(a, b) { guard x == y else { break }; same += 1 }
        for (x, y) in zip(a.reversed(), b.reversed()) { guard x == y else { break }; same += 1 }
        return same >= max(a.count, b.count) - 2
    }
}
