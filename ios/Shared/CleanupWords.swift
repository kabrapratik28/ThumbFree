import Foundation
import TFCore

/// Clean up's words, shared by the keyboard's bar and Settings so both say the same thing. Localizable, like every
/// string the app shows (a String Catalog can take them later).
enum CleanupWords {
    static let working = String(localized: "Cleaning up…")
    static let failed = String(localized: "Couldn't tidy this one. Your words are unchanged.")
    static let nothing = String(localized: "Nothing to tidy. Your words are unchanged.")
    static let changed = String(localized: "The text changed, so it was left as is.")
    static let unverified = String(localized: "Couldn't confirm the change. Check the text.")
    static let paused = String(localized: "Apple paused Clean up for a moment")
    /// Apple's rate limit with its end: "Apple paused Clean up. Ready in 0:40".
    static func paused(ready clock: String) -> String { String(localized: "Apple paused Clean up. Ready in \(clock)") }
    static let chooseStyle = String(localized: "Tidy as")
    /// The first time the sparkle shows, beside it.
    static let hint = String(localized: "Tap to tidy · Hold for styles")
    static let cancel = String(localized: "Cancel")
}

extension CleanupStyle {
    /// The style's name on screen.
    var title: String {
        switch self {
        case .clean: String(localized: "Clean")
        case .shorter: String(localized: "Shorter")
        case .friendly: String(localized: "Friendly")
        case .professional: String(localized: "Professional")
        case .simple: String(localized: "Simple")
        }
    }
}
