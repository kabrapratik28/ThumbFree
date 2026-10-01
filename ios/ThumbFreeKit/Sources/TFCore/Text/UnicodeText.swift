import Foundation

/// Unicode rules shared by the text pipeline, matching the Android app (whose rules match Rust's).
/// Swift compares `String`s by canonical equivalence ("é" == "e\u{301}"), so code that must see code points
/// works on `Unicode.Scalar`s and compares whole texts with `same(_:_:)`.
enum UnicodeText {
    /// Rust char::is_whitespace, the Unicode White_Space set: U+0009 to U+000D, U+0020, U+0085, U+00A0, U+1680,
    /// U+2000 to U+200A, U+2028, U+2029, U+202F, U+205F, U+3000. Not regex \s.
    static func isWhitespace(_ s: Unicode.Scalar) -> Bool {
        s.isASCII ? s.value == 0x20 || (s.value >= 0x09 && s.value <= 0x0D) : s.properties.isWhitespace
    }

    /// Rust char::is_alphanumeric: Alphabetic, or a number of any kind (Nd, Nl, No).
    static func isAlphanumeric(_ s: Unicode.Scalar) -> Bool {
        if s.isASCII {
            let v = s.value
            return (v >= 0x61 && v <= 0x7A) || (v >= 0x41 && v <= 0x5A) || (v >= 0x30 && v <= 0x39)
        }
        let p = s.properties
        if p.isAlphabetic { return true }
        switch p.generalCategory {
        case .decimalNumber, .letterNumber, .otherNumber: return true
        default: return false
        }
    }

    /// Rust regex \w, which its \b uses: Alphabetic, marks, decimal digits, connector punctuation, U+200C and U+200D.
    static func isWordChar(_ s: Unicode.Scalar) -> Bool {
        let p = s.properties
        if p.isAlphabetic || s.value == 0x200C || s.value == 0x200D { return true }
        switch p.generalCategory {
        case .nonspacingMark, .enclosingMark, .spacingMark, .decimalNumber, .connectorPunctuation: return true
        default: return false
        }
    }

    /// Rust str::split_whitespace: the words between whitespace runs, never an empty one.
    static func splitWhitespace(_ text: String) -> [String] {
        text.unicodeScalars.split(whereSeparator: isWhitespace).map { String(Substring($0)) }
    }

    /// Rust str::trim.
    static func trim(_ text: String) -> String {
        let v = text.unicodeScalars
        guard let first = v.firstIndex(where: { !isWhitespace($0) }),
              let last = v.lastIndex(where: { !isWhitespace($0) }) else { return "" }
        return String(Substring(v[first...last]))
    }

    /// Java's String.lowercase(): full case mapping with the final sigma ("ΑΘΉΝΑΣ" is "αθήνας").
    /// Swift's own lowercased() leaves the final sigma out.
    static func lowercase(_ text: String) -> String { text.lowercased(with: nil) }

    /// NFC. Text with no scalar from U+0300 up is NFC already.
    static func nfc(_ text: String) -> String {
        text.unicodeScalars.allSatisfy { $0.value < 0x300 } ? text : text.precomposedStringWithCanonicalMapping
    }

    /// Equal code point for code point (Swift's == also calls "é" and "e\u{301}" equal).
    static func same(_ a: String, _ b: String) -> Bool { a.utf8.elementsEqual(b.utf8) }
}
