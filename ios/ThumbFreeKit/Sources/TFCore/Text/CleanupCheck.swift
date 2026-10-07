import Foundation

/// Checks what the model gave back before it may replace a take. A small on-device model sometimes answers the text,
/// chats, drops a sentence, switches alphabet, loses a "not" or a number, or (asked to "write a poem") writes one:
/// any of those keeps the person's words as they are. Returns the answer to type, trimmed, or nil.
public enum CleanupCheck {
    /// Openers of a reply about the text rather than the text itself.
    static let chatter = ["sure", "here is", "here's", "here\u{2019}s", "i can't", "i can\u{2019}t", "i cannot",
                          "as an ai"]

    public static func accept(take: String, output: String?, style: CleanupStyle) -> String? {
        let take = take.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let output = output.map({ tidy($0, take: take) }), !output.isEmpty, output != take else { return nil }
        let lowerTake = take.lowercased(), lowerOutput = output.lowercased()
        if chatter.contains(where: { starts(lowerOutput, with: $0) && !starts(lowerTake, with: $0) }) { return nil }
        let takeTokens = tokens(take), outputTokens = tokens(output)
        if Double(outputTokens.count) < 0.4 * Double(takeTokens.count) { return nil }
        if let script = mainScript(take), let other = mainScript(output), script != other { return nil }
        let takeNegations = negations(lowerTake), outputNegations = negations(lowerOutput)
        if !takeNegations.isSubset(of: outputNegations) { return nil }
        if !digitGroupsKept(take: take, output: output) { return nil }
        if style == .clean {
            let known = Set(words(lowerTake))
            let new = words(lowerOutput).filter { !known.contains($0) && !$0.contains(where: \.isNumber) }
            if Double(new.count) > max(2, 0.2 * Double(outputTokens.count)) { return nil }
        }
        return output
    }

    /// The answer without a "Cleaned text:" label the model may echo, or quotes around it that the take did not have.
    static func tidy(_ output: String, take: String) -> String {
        var text = output.trimmingCharacters(in: .whitespacesAndNewlines)
        let label = "cleaned text:"
        if text.lowercased().hasPrefix(label) {
            text = String(text.dropFirst(label.count)).trimmingCharacters(in: .whitespacesAndNewlines)
        }
        let quotes: [(Character, Character)] = [("\"", "\""), ("\u{201C}", "\u{201D}")]
        for (open, close) in quotes where text.count > 1 && text.first == open && text.last == close
            && take.first != open {
            text = String(text.dropFirst().dropLast()).trimmingCharacters(in: .whitespacesAndNewlines)
        }
        return text
    }

    /// `text` begins with `phrase` as whole words ("Sure," yes, "Surely" no).
    static func starts(_ text: String, with phrase: String) -> Bool {
        guard text.hasPrefix(phrase) else { return false }
        return text.dropFirst(phrase.count).first.map { !$0.isLetter } ?? true
    }

    /// Whitespace-separated words that hold a letter or a digit: what "40% of the take's words" counts.
    static func tokens(_ text: String) -> [Substring] {
        text.split(whereSeparator: \.isWhitespace).filter { $0.contains(where: { $0.isLetter || $0.isNumber }) }
    }

    /// Lowercased words split at anything that is not a letter or digit, "n't" read as "not": "anna.lee@example.com"
    /// is anna, lee, example and com; "don't" is do and not; "can't" is can and not.
    static func words(_ lower: String) -> [String] {
        let expanded = lower.replacingOccurrences(of: "\u{2019}", with: "'")
            .replacingOccurrences(of: "can't", with: "can not").replacingOccurrences(of: "won't", with: "will not")
            .replacingOccurrences(of: "n't", with: " not")
        return expanded.split(whereSeparator: { !$0.isLetter && !$0.isNumber }).map(String.init)
    }

    /// The kinds of negation in a lowercased text: "not" (with "n't" and "cannot"), "never", "no longer".
    static func negations(_ lower: String) -> Set<String> {
        let list = words(lower)
        var found = Set<String>()
        if list.contains(where: { $0 == "not" || $0 == "cannot" }) { found.insert("not") }
        if list.contains("never") { found.insert("never") }
        if zip(list, list.dropFirst()).contains(where: { $0 == "no" && $1 == "longer" }) { found.insert("no longer") }
        return found
    }

    /// Every run of digits in the take is in the answer's digits, in order: "555 1212" may become "555-1212", never
    /// "555" alone. Separators inside numbers (spaces, dashes, commas, colons) may change.
    static func digitGroupsKept(take: String, output: String) -> Bool {
        let digits = String(output.filter(\.isNumber))
        var from = digits.startIndex
        for group in take.split(whereSeparator: { !$0.isNumber }) {
            guard let found = digits.range(of: group, range: from..<digits.endIndex) else { return false }
            from = found.upperBound
        }
        return true
    }

    /// The alphabet most of the letters are in, or nil with no letters. Hindi said in Latin letters stays Latin.
    static func mainScript(_ text: String) -> String? {
        var counts: [String: Int] = [:]
        for scalar in text.unicodeScalars where scalar.properties.isAlphabetic {
            counts[script(scalar.value), default: 0] += 1
        }
        return counts.max { $0.value < $1.value }?.key
    }

    static func script(_ v: UInt32) -> String {
        switch v {
        case 0x0370...0x03FF, 0x1F00...0x1FFF: "greek"
        case 0x0400...0x052F, 0x1C80...0x1C8F, 0x2DE0...0x2DFF, 0xA640...0xA69F: "cyrillic"
        case 0x0590...0x05FF: "hebrew"
        case 0x0600...0x06FF, 0x0750...0x077F, 0x08A0...0x08FF: "arabic"
        case 0x0900...0x097F: "devanagari"
        case 0x0E00...0x0E7F: "thai"
        case 0x1100...0x11FF, 0x3130...0x318F, 0xAC00...0xD7AF: "hangul"
        case 0x3040...0x30FF: "kana"
        case 0x3400...0x4DBF, 0x4E00...0x9FFF: "han"
        default: "latin"
        }
    }
}
