import Foundation

/// Apple's smart punctuation, which a custom keyboard does itself: iOS's text views leave a custom keyboard's quotes and
/// hyphens as they come (checked on the iOS 26.5 Simulator: ThumbFree's `"hi" don't --` arrived straight), while Apple's
/// keyboard types “hi”, don’t, and a dash for two hyphens. Where it applies is the field's (`FieldTraits.smartPunctuation`).
enum SmartPunctuation {
    /// A typed straight quote as Apple's keyboard types it: opening (“ ‘) at the start, after a space or an opening
    /// bracket or quote, closing (” ’) elsewhere, so an apostrophe inside a word is ’ (don’t).
    static func quote(_ mark: Character, after before: String) -> Character {
        let opens = before.last.map { $0.isWhitespace || "([{\u{201C}\u{2018}".contains($0) } ?? true
        switch mark {
        case "\"": return opens ? "\u{201C}" : "\u{201D}"
        case "'": return opens ? "\u{2018}" : "\u{2019}"
        default: return mark
        }
    }

    /// A hyphen right after a hyphen: Apple's keyboard turns the two into its dash (an em dash) as the second is typed.
    static func makesDash(_ mark: Character, after before: String) -> Bool { mark == "-" && before.last == "-" }

    /// A suggested word's apostrophes, curly where the field's quotes are ("don't": don’t), as Apple's bar shows them.
    static func curly(_ word: String) -> String { word.replacingOccurrences(of: "'", with: "\u{2019}") }
}
