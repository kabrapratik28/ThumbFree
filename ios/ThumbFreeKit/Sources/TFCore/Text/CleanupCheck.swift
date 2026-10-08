import Foundation

/// What the checks make of a model's answer: the text to write, nothing to tidy (the answer is the take), or which
/// check failed (a code for the log, never text).
public enum CleanupVerdict: Equatable, Sendable {
    case ok(String)
    case same
    case rejected(String)
}

/// The checks a model's answer must pass before it may replace the person's words (issue #1, part 4), ported rule for
/// rule from the Android app's `CleanupCheck` with its test rows: anything that could change the message's meaning keeps
/// the words as they are. A small on-device model sometimes answers the text, chats, adds a second version, drops a
/// sentence or a word that matters, switches alphabet, loses a "not", or invents or loses a number.
public enum CleanupCheck {
    /// Openers of a reply about the text rather than the text itself.
    static let chatter = ["sure", "here is", "here's", "i can't", "i cannot", "as an ai", "certainly", "of course"]
    static let negations = ["not", "never", "no longer", "cannot"]
    /// Words Clean may drop or turn into marks and digits: function words, fillers, self-correction markers and spoken
    /// punctuation. Every other word of the take must survive a Clean (one may go, for a correction).
    static let functionWords: Set<String> = [
        "a", "an", "the", "and", "or", "but", "so", "to", "of", "in", "on", "at", "by", "for", "with", "from", "is", "are",
        "was", "were", "be", "been", "am", "it", "this", "that", "i", "you", "he", "she", "we", "they", "me", "my", "your",
        "our", "their", "its", "do", "does", "did", "have", "has", "had", "will", "would", "can", "could", "should",
        "just", "really", "very", "then", "there", "here", "um", "uh", "er", "erm", "hmm", "like", "no", "sorry", "wait",
        "actually", "mean", "know", "comma", "period", "question", "mark", "exclamation", "colon", "new", "line",
        "paragraph", "dot",
    ]
    /// A take that asks for a new line may get one.
    static let newLines = ["new line", "newline", "new paragraph", "next line"]
    /// Words with which a speaker takes a number back ("at 5, no, 6").
    static let corrections: Set<String> = ["no", "sorry", "mean", "actually", "wait", "rather"]
    static let numberWords: Set<String> = [
        "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven", "twelve",
        "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen", "twenty", "thirty", "forty",
        "fifty", "sixty", "seventy", "eighty", "ninety", "hundred", "thousand", "million", "billion", "percent", "dollar",
        "dollars", "cents", "o'clock", "pm", "first", "second", "third", "fourth", "fifth", "sixth", "seventh", "eighth",
        "ninth", "tenth", "half", "quarter",
    ]

    public static func check(take: String, output: String?, style: CleanupStyle) -> CleanupVerdict {
        guard let output else { return .rejected("empty") }
        let raw = take.trimmingCharacters(in: .whitespacesAndNewlines)
        let out = tidy(output, take: raw)
        if out.isEmpty { return .rejected("empty") }
        if out == raw { return .same }
        let lowerRaw = straight(raw.lowercased()), lowerOut = straight(out.lowercased())
        if chatter.contains(where: { starts(lowerOut, with: $0) && !starts(lowerRaw, with: $0) }) { return .rejected("chatter") }
        // A paragraph the speaker didn't ask for: a label and a second version, say ("Warm and casual version:").
        if out.contains("\n"), !raw.contains("\n"), !newLines.contains(where: lowerRaw.contains) { return .rejected("lines") }
        let takeWords = words(raw), outWords = words(out)
        let known = Set(takeWords)
        let added = outWords.filter { !known.contains($0) && !hasDigit($0) }.count
        let tone = style == .friendly || style == .professional || style == .simple
        if added > (tone ? max(4, outWords.count / 2) : max(2, outWords.count / 5)) { return .rejected("new_words") }
        if Double(outWords.count) < Double(takeWords.count) * (style == .shorter ? 0.25 : 0.4) { return .rejected("dropped") }
        if style == .clean {
            let meaningful = Set(takeWords.filter { !functionWords.contains($0) && !numberWords.contains($0) && !hasDigit($0) })
            let have = Set(outWords)
            let missing = meaningful.filter { !have.contains($0) }.count
            if Double(missing) > max(1, Double(meaningful.count) * 0.3) { return .rejected("dropped_words") }
        }
        if let script = mainScript(raw), let other = mainScript(out), script != other { return .rejected("script") }
        if negated(lowerRaw) && !negated(lowerOut) { return .rejected("negation") }
        if !numbersKept(takeWords, out) { return .rejected("digits") }
        return .ok(out)
    }

    /// The answer without an echoed "Cleaned text:" or "Rewritten text:" label, or quotes around it the take did not have.
    static func tidy(_ output: String, take: String) -> String {
        var text = output.trimmingCharacters(in: .whitespacesAndNewlines)
        for label in ["cleaned text:", "rewritten text:"] where text.lowercased().hasPrefix(label) {
            text = String(text.dropFirst(label.count)).trimmingCharacters(in: .whitespacesAndNewlines)
        }
        let quotes: [(Character, Character)] = [("\"", "\""), ("\u{201C}", "\u{201D}")]
        for (open, close) in quotes where text.count > 1 && text.first == open && text.last == close
            && take.first != open {
            text = String(text.dropFirst().dropLast()).trimmingCharacters(in: .whitespacesAndNewlines)
        }
        return text
    }

    /// Typographic apostrophes as straight ones, so "can’t" and "can't" are one word.
    static func straight(_ text: String) -> String { text.replacingOccurrences(of: "\u{2019}", with: "'") }

    /// `text` begins with `phrase` as whole words ("Sure," yes, "Surely" no).
    static func starts(_ text: String, with phrase: String) -> Bool {
        guard text.hasPrefix(phrase) else { return false }
        return text.dropFirst(phrase.count).first.map { !$0.isLetter } ?? true
    }

    /// Lowercased words: runs of letters, digits and apostrophes, without apostrophes at either end ("Lucia's" is one
    /// word, "7:30" is 7 and 30, "anna.lee@example.com" is anna, lee, example and com).
    static func words(_ text: String) -> [String] {
        straight(text.lowercased())
            .split(whereSeparator: { !$0.isLetter && !$0.isNumber && $0 != "'" })
            .map { $0.trimmingCharacters(in: CharacterSet(charactersIn: "'")) }
            .filter { !$0.isEmpty }
    }

    static func hasDigit(_ word: String) -> Bool { !digitGroups(word).isEmpty }

    /// Runs of decimal digits (`\p{Nd}+`).
    static func digitGroups(_ text: String) -> [String] {
        var groups: [String] = [], run = ""
        for scalar in text.unicodeScalars {
            if scalar.properties.numericType == .decimal { run.unicodeScalars.append(scalar) } else if !run.isEmpty {
                groups.append(run)
                run = ""
            }
        }
        if !run.isEmpty { groups.append(run) }
        return groups
    }

    /// The text (lowercased, straight apostrophes) says "not", "n't", "never", "no longer" or "cannot".
    static func negated(_ lower: String) -> Bool {
        lower.contains("n't") || negations.contains { word in
            var from = lower.startIndex
            while let found = lower.range(of: word, range: from..<lower.endIndex) {
                let before = found.lowerBound == lower.startIndex || !lower[lower.index(before: found.lowerBound)].isLetter
                let after = found.upperBound == lower.endIndex || !lower[found.upperBound].isLetter
                if before && after { return true }
                from = found.upperBound
            }
            return false
        }
    }

    /// The take's digit groups all stay, in order (555 1212 may become 555-1212, never 555 alone), except one the speaker
    /// took back ("at 5, no, 6"); and the answer has no more digit groups than the take said in digits or number words.
    static func numbersKept(_ takeWords: [String], _ out: String) -> Bool {
        let kept = takeWords.indices.flatMap { corrected(takeWords, $0) ? [] : digitGroups(takeWords[$0]) }
        let given = digitGroups(out)
        let said = takeWords.reduce(0) { $0 + (numberWords.contains($1) ? 1 : digitGroups($1).count) }
        var next = 0
        for group in given where next < kept.count && kept[next] == group { next += 1 }
        return next == kept.count && given.count <= said
    }

    /// The number at `index` is taken back: a correction word follows within 3 words, and another number within 3 more.
    static func corrected(_ words: [String], _ index: Int) -> Bool {
        guard hasDigit(words[index]), index < words.count - 1 else { return false }
        guard let marker = words[(index + 1)...min(index + 3, words.count - 1)].firstIndex(where: corrections.contains),
              marker < words.count - 1 else { return false }
        return words[(marker + 1)...min(marker + 3, words.count - 1)].contains { numberWords.contains($0) || hasDigit($0) }
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
