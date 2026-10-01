import TFCore
import UIKit

/// How a text field's traits shape the inserted text (CursorFormatter).
enum FieldTraits {
    /// `UITextDocumentProxy.documentIdentifier` is declared non-optional, but it is nil between two fields and while iOS
    /// resets the keyboard's document (and could be in an app that gives none), and reading the property from Swift then
    /// traps and kills the keyboard (seen on iOS 26.5.2). Read it through Objective-C messaging instead, where a missing
    /// identifier is just nil.
    static func documentID(of proxy: NSObjectProtocol) -> UUID? {
        let getter = NSSelectorFromString("documentIdentifier")
        guard proxy.responds(to: getter) else { return nil }
        return proxy.perform(getter)?.takeUnretainedValue() as? UUID
    }

    /// URL and email keyboards get the text as is; every other field is text.
    static func field(_ type: UIKeyboardType?) -> CursorFormatter.Field {
        switch type {
        case .URL?: .url
        case .emailAddress?: .email
        default: .text
        }
    }

    /// Whether the keyboard may change words in the field (autocorrection): not where the app turned autocorrection
    /// off, and not in web address or email fields (ThumbFree's own rule: Apple's keyboard still corrects there when
    /// an app leaves autocorrection on). Secure fields never reach a custom keyboard: iOS shows its own there.
    static func corrects(_ type: UIKeyboardType?, autocorrection: UITextAutocorrectionType?) -> Bool {
        autocorrection != .no && type != .URL && type != .emailAddress
    }

    /// Whether the keyboard curls quotes and makes dashes (Apple's smart punctuation, which iOS's text views leave to a
    /// custom keyboard): where the field asks for them, and by default everywhere but web address, email and ASCII fields
    /// (Apple's keyboard types straight quotes and two hyphens in an ASCII-capable field: checked on the Simulator).
    static func smartPunctuation(quotes: UITextSmartQuotesType?, dashes: UITextSmartDashesType?, type: UIKeyboardType?) -> (quotes: Bool, dashes: Bool) {
        let byDefault = type != .URL && type != .emailAddress && type != .asciiCapable
        return (quotes == .yes || quotes != .no && byDefault, dashes == .yes || dashes != .no && byDefault)
    }

    /// Whether the field wants a capital at the cursor, from its auto-capitalization and the text before the cursor.
    /// Nil: unknown.
    static func capsExpected(_ type: UITextAutocapitalizationType?, before: String) -> Bool? {
        guard let type else { return nil }
        switch type {
        case .none: return false
        case .allCharacters: return true
        case .words: return before.last?.isWhitespace ?? true
        case .sentences:
            guard let last = before.trimmingCharacters(in: .whitespaces).last else { return true }
            return before.last?.isNewline == true || ".!?".contains(last)
        @unknown default: return nil
        }
    }

    /// The same for a typed key (the keyboard's automatic shift): a sentence ends only once a space or a new line follows
    /// its full stop, question or exclamation mark, as on Apple's keyboard, so "example." then a letter stays lowercase.
    /// An opening quote or bracket keeps the capital a sentence's start asks for (“Hi, as Apple's keyboard types it). A
    /// take's text keeps `capsExpected`, where "I said." then a take reads as a new sentence.
    static func shiftExpected(_ type: UITextAutocapitalizationType?, before: String) -> Bool? {
        let text = String(before.reversed().drop { "\"'([{\u{201C}\u{2018}".contains($0) }.reversed())
        guard type == .sentences, let last = text.last, !last.isWhitespace else { return capsExpected(type, before: text) }
        return false
    }
}
