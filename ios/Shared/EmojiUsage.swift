import Foundation

/// Frequently Used, ranked as Apple's keyboard ranks it: by how often and how recently each emoji was picked. Apple does
/// not publish its formula; this is the plain one that behaves the same way. Each pick adds one to the emoji's score and
/// every score halves each week, so an emoji picked many times last month falls behind one picked a few times this week.
/// A new keyboard starts from Apple's starting set, each worth less than one pick, so the first pick of anything leads.
/// The keyboard keeps it in its own defaults (`UserDefaults.standard` in the keyboard is its own container, which needs no
/// Full Access): only emoji text and numbers, and it is never logged.
struct EmojiUsage: Equatable {
    static let key = "TFEmojiUsage"
    static let shown = 30          // as many as Apple's Frequently Used holds
    static let kept = 60           // a few more, so one that dropped out of sight can climb back
    static let halfLife: TimeInterval = 7 * 24 * 60 * 60

    /// Emoji text to [score at the last pick, the last pick in seconds since 2001]: the shape the defaults store.
    private(set) var scores: [String: [Double]]

    /// `stored` is what the defaults hold (nil before the first pick, which starts from Apple's set, `starting`).
    init(stored: [String: [Double]]?, starting: [String], now: Date) {
        if let stored {
            scores = stored
        } else {
            let start = now.timeIntervalSinceReferenceDate
            scores = Dictionary(starting.enumerated().map { ($1, [0.5 - Double($0) * 0.01, start]) }, uniquingKeysWith: { first, _ in first })
        }
    }

    /// A pick: the emoji's score as it stands now, plus one. Past `kept` emoji, the lowest score now goes.
    mutating func pick(_ text: String, at now: Date) {
        scores[text] = [score(text, at: now) + 1, now.timeIntervalSinceReferenceDate]
        while scores.count > Self.kept, let lowest = scores.keys.min(by: { score($0, at: now) < score($1, at: now) }) {
            scores[lowest] = nil
        }
    }

    /// The score now: the stored one, halved for every week since the emoji's last pick.
    func score(_ text: String, at now: Date) -> Double {
        guard let entry = scores[text], entry.count == 2 else { return 0 }
        return entry[0] * pow(0.5, max(0, now.timeIntervalSinceReferenceDate - entry[1]) / Self.halfLife)
    }

    /// Frequently Used, first to last: the highest scores now, the later pick first on a tie.
    func ranked(at now: Date) -> [String] {
        let last = { (text: String) in self.scores[text]?.last ?? 0 }
        return Array(scores.keys.sorted { (score($0, at: now), last($0)) > (score($1, at: now), last($1)) }.prefix(Self.shown))
    }

    /// The emoji picked at least once (a pick stores a score of one or more; Apple's starting set starts below one), in
    /// Frequently Used's order: what an empty search lists first, as Apple's does.
    func picked(at now: Date) -> [String] { ranked(at: now).filter { (scores[$0]?.first ?? 0) >= 1 } }
}

extension EmojiCatalog {
    /// Apple's Frequently Used before any pick.
    static var recentsDefault: [String] { EmojiData.recentsDefault.split(separator: " ").map(String.init) }

    /// What the picker shows: Frequently Used first (when there is any), then the fixed categories. `recents` is emoji
    /// text, first to last; an emoji this catalog does not show (gone from Apple's list, or too new for this iOS) is dropped.
    static func sections(recents: [String]) -> [EmojiSection] {
        let used = recents.compactMap(emoji)
        return (used.isEmpty ? [] : [EmojiSection(category: .recents, emoji: used)]) + categories
    }

    /// The emoji that types `text`, to name what Frequently Used stores as text.
    static func emoji(_ text: String) -> Emoji? { byText[text] }

    private static let byText = Dictionary(categories.flatMap(\.emoji).map { ($0.text, $0) }, uniquingKeysWith: { first, _ in first })
}
