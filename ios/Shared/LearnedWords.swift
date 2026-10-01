import Foundation

/// The words the user kept: a correction undone, or the typed word tapped in its quotes. They are never corrected again
/// and are offered as their start is typed, as Apple's keyboard learns words. Kept in the keyboard's own defaults (its own
/// container, so no Full Access is needed) as keys (`Typing.key`: lowercased, one apostrophe), newest last, at most
/// 1,000; never logged, never in the App Group, never sent anywhere, and gone with the app. (Apple's
/// `UITextChecker.learnWord` writes to the system's keyboard folder shared with Apple's keyboard, outside ThumbFree's
/// container, so it is not used.)
struct LearnedWords: Equatable, Sendable {
    static let key = "TFLearnedWords"
    static let limit = 1_000
    #if DEBUG
    /// UI tests: a launch with `-TFResetState YES` leaves a new token here in the App Group, and the keyboard forgets its
    /// words when it next shows (its own container is out of reach of launch arguments), so each test starts clean.
    static let resetKey = "TFKeyboardReset"
    #endif

    private(set) var words: [String]

    init(stored: [String]?) { words = stored ?? [] }

    func contains(_ word: String) -> Bool { words.contains(Typing.key(word)) }

    /// Keeps `word` (again: it moves to the newest end); past the limit the oldest go.
    mutating func learn(_ word: String) {
        let key = Typing.key(word)
        words.removeAll { $0 == key }
        words.append(key)
        if words.count > Self.limit { words.removeFirst(words.count - Self.limit) }
    }

    /// The kept words that start with `prefix` and are longer, newest first, in the prefix's case for the letters typed
    /// ("Vor": Vorlak).
    func completions(of prefix: String) -> [String] {
        let lower = Typing.key(prefix)
        return words.reversed().filter { $0.count > lower.count && $0.hasPrefix(lower) }.map { prefix + $0.dropFirst(prefix.count) }
    }
}
