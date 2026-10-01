/// A word made only of letters said 3 or more times in a row, in any case, is kept once with its first spelling.
/// Then every whitespace run (newlines too) becomes one space and the ends are trimmed: the output is one line.
/// Adapted from MIT-licensed code; see THIRD_PARTY_NOTICES.md.
public enum Normalize {
    public static func apply(_ text: String) -> String {
        let words = UnicodeText.splitWhitespace(text)
        var out: [String] = []
        var i = 0
        while i < words.count {
            let lower = UnicodeText.lowercase(words[i])
            var count = 1
            if lower.unicodeScalars.allSatisfy(\.properties.isAlphabetic) {
                while i + count < words.count && UnicodeText.same(UnicodeText.lowercase(words[i + count]), lower) { count += 1 }
            }
            out.append(words[i])
            i += count >= 3 ? count : 1
        }
        return out.joined(separator: " ")
    }
}
