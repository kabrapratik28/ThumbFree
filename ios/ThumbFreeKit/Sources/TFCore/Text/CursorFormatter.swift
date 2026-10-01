/// Formats the text ThumbFree is about to insert at the cursor: spacing it against what is already there, and
/// capitalizing it at a sentence start. Never lowercases.
public enum CursorFormatter {
    public enum Field: Sendable, Equatable { case text, url, email }

    static let openPunctuation: Set<Unicode.Scalar> = ["(", "[", "{", "\"", "'", "“", "‘"]
    static let closePunctuation: Set<Unicode.Scalar> = [".", ",", ";", ":", "!", "?", ")", "]", "}"]

    /// before/after nil means unreadable. capsExpected nil means unknown. URL and email fields get the trimmed text as is.
    public static func payload(text: String, before: String?, after: String?, capsExpected: Bool?,
                               field: Field, trailingSpace: Bool) -> String {
        let trimmed = UnicodeText.trim(text)
        if field != .text { return trimmed }
        let beforeLast = before?.unicodeScalars.last
        let afterFirst = after?.unicodeScalars.first
        // Letters or digits touching on both sides: a mid-word insertion, leave it alone.
        if let b = beforeLast, UnicodeText.isAlphanumeric(b), let a = afterFirst, UnicodeText.isAlphanumeric(a) { return trimmed }
        let textFirst = trimmed.unicodeScalars.first
        var leading = false
        if let b = beforeLast, !UnicodeText.isWhitespace(b), !openPunctuation.contains(b) {
            leading = textFirst.map { !closePunctuation.contains($0) } ?? true
        }
        let afterIsAlphanumeric = afterFirst.map(UnicodeText.isAlphanumeric) ?? false
        let afterIsWhitespace = afterFirst.map(UnicodeText.isWhitespace) ?? false
        let trailing = after != nil && (afterIsAlphanumeric || (trailingSpace && !afterIsWhitespace))
        let body = capsExpected == true ? capitalizeFirst(trimmed) : trimmed
        return (leading ? " " : "") + body + (trailing ? " " : "")
    }

    // Java's Character.toUpperCase on the first code point when it is lowercase: the one-scalar (simple) mapping, so
    // "ß" and "ﬁ" stay as they are.
    private static func capitalizeFirst(_ text: String) -> String {
        guard let first = text.unicodeScalars.first, first.properties.isLowercase else { return text }
        var out = String.UnicodeScalarView([simpleUppercase(first)])
        out.append(contentsOf: text.unicodeScalars.dropFirst())
        return String(out)
    }

    private static func simpleUppercase(_ s: Unicode.Scalar) -> Unicode.Scalar {
        for mapping in [s.properties.uppercaseMapping, s.properties.titlecaseMapping] {
            let scalars = Array(mapping.unicodeScalars)
            if scalars.count == 1 { return scalars[0] }
        }
        return s
    }
}
