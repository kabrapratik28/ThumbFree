import Foundation

/// What the checks make of a model's answer: the text to write, nothing to tidy (the answer is the take), or which
/// check failed (a code for the log, never text).
public enum CleanupVerdict: Equatable, Sendable {
    case ok(String)
    case same
    case rejected(String)
}

/// The checks a model's answer must pass before it may replace the person's words (issue #1, part 4), the Android app's
/// `CleanupCheck` with its test rows (tuned on the Pixel's Gemini Nano, 2026-10-07) and rows from Apple's on-device
/// model, which also shaped the correction check: anything that could change the message's meaning keeps the words. A
/// small on-device model sometimes answers the text, chats, adds a second version, drops a word that matters, undoes a
/// self-correction, switches alphabet, loses or adds a "not", or invents or loses a number.
public enum CleanupCheck {
    /// Openers of a reply about the text rather than the text itself.
    static let chatter = ["sure", "here is", "here's", "i can't", "i cannot", "as an ai", "certainly", "of course"]
    static let negations = ["not", "never", "no longer", "cannot", "unable", "unavailable", "nothing", "none", "nobody",
                            "nowhere", "neither"]
    /// Words Clean may drop or turn into marks and digits: function words, fillers and spoken punctuation. Every other
    /// word of the take must survive a Clean (one may go), but for what a correction replaced.
    static let functionWords: Set<String> = [
        "a", "an", "the", "and", "or", "but", "so", "to", "of", "in", "on", "at", "by", "for", "with", "from", "is", "are",
        "was", "were", "be", "been", "am", "it", "this", "that", "i", "you", "he", "she", "we", "they", "me", "my", "your",
        "our", "their", "its", "do", "does", "did", "have", "has", "had", "will", "would", "can", "could", "should",
        "just", "really", "very", "then", "there", "here", "um", "uh", "er", "erm", "hmm", "like", "no", "wait",
        "actually", "mean", "know", "comma", "period", "question", "mark", "exclamation", "colon", "new", "line",
        "paragraph", "dot",
    ]
    /// Words a correction's final version is checked for first: the dates and times a wrong pick would change.
    static let whenWords: Set<String> = [
        "monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday", "today", "tonight", "tomorrow",
        "yesterday", "morning", "afternoon", "evening", "january", "february", "march", "april", "may", "june", "july",
        "august", "september", "october", "november", "december",
    ]
    static let tens = ["twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety"]
    /// Number words and their digits: zero to nineteen, and the tens.
    static let digitOf: [String: String] = {
        let units = ["zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven",
                     "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen"]
        var map: [String: String] = [:]
        for (i, word) in units.enumerated() { map[word] = String(i) }
        for (i, word) in tens.enumerated() { map[word] = String((i + 2) * 10) }
        return map
    }()
    /// A take that asks for a new line may get one.
    static let newLines = ["new line", "newline", "new paragraph", "next line"]
    /// Words with which a speaker takes something back ("at 5, no, 6", "Marco, sorry, Luca").
    static let corrections: Set<String> = ["no", "sorry", "mean", "actually", "wait", "rather"]
    static let numberWords: Set<String> = [
        "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven", "twelve",
        "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen", "twenty", "thirty", "forty",
        "fifty", "sixty", "seventy", "eighty", "ninety", "hundred", "thousand", "million", "billion", "percent", "dollar",
        "dollars", "cents", "o'clock", "pm", "first", "second", "third", "fourth", "fifth", "sixth", "seventh", "eighth",
        "ninth", "tenth", "half", "quarter", "noon", "midnight",
        // Found tuning on Apple's model: ordinals it writes as digits ("the fifteenth" as the 15th).
        "eleventh", "twelfth", "thirteenth", "fourteenth", "fifteenth", "sixteenth", "seventeenth", "eighteenth",
        "nineteenth", "twentieth", "thirtieth", "hundredth", "thousandth",
    ]

    public static func check(take: String, output: String?, style: CleanupStyle) -> CleanupVerdict {
        guard let output else { return .rejected("empty") }
        let raw = take.trimmingCharacters(in: .whitespacesAndNewlines)
        let out = tidy(output, take: raw)
        if out.isEmpty { return .rejected("empty") }
        if out == raw { return .same }
        let lowerRaw = straight(raw.lowercased()), lowerOut = straight(out.lowercased())
        let saidNo = negated(lowerRaw)
        // "I can't" opens a refusal, unless the speaker said they can't ("I won't be able to" may come back as "I cannot").
        let openers = chatter.filter { !$0.hasPrefix("i can") || !saidNo }
        if openers.contains(where: { starts(lowerOut, with: $0) && !starts(lowerRaw, with: $0) }) { return .rejected("chatter") }
        // A paragraph the speaker didn't ask for: a label and a second version, say ("Warm and casual version:").
        if out.contains("\n"), !raw.contains("\n"), !newLines.contains(where: lowerRaw.contains) { return .rejected("lines") }
        let takeWords = words(raw), outWords = words(out)
        let known = Set(takeWords)
        let added = outWords.filter { !known.contains($0) && !hasDigit($0) }.count
        let tone = style == .friendly || style == .professional || style == .simple
        // A tone may reword most of it; a hold shows its answer first. Shorter may say "can't attend" for "won't be able
        // to make it".
        let addedLimit = tone ? max(6, outWords.count * 7 / 10)
            : style == .shorter ? max(4, outWords.count / 3) : max(2, outWords.count / 5)
        if added > addedLimit { return .rejected("new_words") }
        // Clean checks the words that carry meaning instead: a self-correction can take most of a short take away
        // ("twenty five dollars no wait thirty dollars" is "$30").
        if style != .clean, Double(outWords.count) < Double(takeWords.count) * (style == .shorter ? 0.25 : 0.4) {
            return .rejected("dropped")
        }
        if style == .clean {
            // What a correction replaced may go: the three words before "no", "sorry", "actually"...
            let replaced = Set(takeWords.indices.filter { next(takeWords, after: $0).contains(where: corrections.contains) })
            let meaningful = Set(takeWords.indices.filter { i in
                let word = takeWords[i]
                return !replaced.contains(i) && !corrections.contains(word) && !functionWords.contains(word)
                    && !numberWords.contains(word) && !hasDigit(word)
            }.map { takeWords[$0] })
            let have = Set(outWords)
            let missing = meaningful.filter { !have.contains($0) }.count
            if Double(missing) > max(1, Double(meaningful.count) * 0.3) { return .rejected("dropped_words") }
        }
        if let script = mainScript(raw), let other = mainScript(out), script != other { return .rejected("script") }
        if saidNo && !negated(lowerOut) { return .rejected("negation") }
        // A "no" that corrects the speaker is no "not": "by monday no tuesday" once came back as "by Monday, not
        // Tuesday". A tone may add an idiom ("Can't wait!"), and a hold shows its answer first.
        if !tone && !saidNo && negated(lowerOut) { return .rejected("negation_added") }
        if !numbersKept(takeWords, out) { return .rejected("digits") }
        if !finalsKept(takeWords, outWords, out, anyWord: !tone, saysNo: negated(lowerOut)) {
            return .rejected("correction")
        }
        return .ok(out)
    }

    /// The answer without an echoed label ("Cleaned text:", "Shorter version:"...), or quotes around it the take did
    /// not have.
    static func tidy(_ output: String, take: String) -> String {
        var text = output.trimmingCharacters(in: .whitespacesAndNewlines)
        for label in CleanupPrompt.labels.map({ $0.lowercased() }) where text.lowercased().hasPrefix(label) {
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

    /// The (up to) three words after `index`.
    static func next(_ words: [String], after index: Int) -> ArraySlice<String> {
        words[min(index + 1, words.count)..<min(index + 4, words.count)]
    }

    static func hasDigit(_ word: String) -> Bool { !digitGroups(word).isEmpty }

    static func isDecimal(_ scalar: Unicode.Scalar) -> Bool { scalar.properties.numericType == .decimal }

    /// Runs of decimal digits (`\p{Nd}+`).
    static func digitGroups(_ text: String) -> [String] {
        var groups: [String] = [], run = ""
        for scalar in text.unicodeScalars {
            if isDecimal(scalar) {
                run.unicodeScalars.append(scalar)
            } else if !run.isEmpty {
                groups.append(run)
                run = ""
            }
        }
        if !run.isEmpty { groups.append(run) }
        return groups
    }

    /// How many numbers a text writes in digits, a time or an amount once however it is written (10:00, 7.30, 1,200).
    static func numberCount(_ text: String) -> Int {
        let scalars = Array(text.unicodeScalars)
        var count = 0, i = 0
        while i < scalars.count {
            guard isDecimal(scalars[i]) else { i += 1; continue }
            count += 1
            while i < scalars.count {
                if isDecimal(scalars[i]) {
                    i += 1
                } else if ":.,".unicodeScalars.contains(scalars[i]), i + 1 < scalars.count, isDecimal(scalars[i + 1]) {
                    i += 1
                } else {
                    break
                }
            }
        }
        return count
    }

    /// The text (lowercased, straight apostrophes) holds a negation: "n't", or one of `negations` as whole words.
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
    /// took back ("at 5, no, 6"); and the answer writes no more numbers than the take said in digits or number words.
    static func numbersKept(_ takeWords: [String], _ out: String) -> Bool {
        let kept = takeWords.indices.flatMap { corrected(takeWords, $0) ? [] : digitGroups(takeWords[$0]) }
        let given = digitGroups(out)
        let said = takeWords.reduce(0) { $0 + (numberWords.contains($1) ? 1 : digitGroups($1).count) }
        var next = 0
        for group in given where next < kept.count && kept[next] == group { next += 1 }
        return next == kept.count && numberCount(out) <= said
    }

    /// What the speaker said last stays, and a date or number it replaced goes. After a correction word, the first date,
    /// time or number among the next three words that isn't among the three before ("tango seven seven no tango seven
    /// eight" ends in 8), else (`anyWord`, Clean and Shorter; a tone may reword it) the first word that carries meaning,
    /// must be in the answer: as itself, a number word as its digits ("forty five no fifty five" may be $55), a negation
    /// as any negation ("I won't" may be "I can't"). The date or number just before the correction word, of the same
    /// kind, must be gone: "two no three bags" came back from Apple's model as "2-3 bags".
    static func finalsKept(_ takeWords: [String], _ outWords: [String], _ out: String, anyWord: Bool,
                           saysNo: Bool) -> Bool {
        let have = Set(outWords)
        let digits = digitGroups(out)
        func written(_ word: String, exactly: Bool) -> Bool {
            if have.contains(word) { return true }
            if let d = digitOf[word] {
                // A tens word starts a number of two digits or more: "fifty five" is 55.
                return digits.contains {
                    (exactly ? $0 == d : $0.hasPrefix(d)) || (tens.contains(word) && $0.count >= 2 && $0.first == d.first)
                }
            }
            if hasDigit(word) { return digitGroups(word).allSatisfy(digits.contains) }
            return saysNo && (word.contains("n't") || negations.contains(word))
        }
        for (i, word) in takeWords.enumerated() where corrections.contains(word) {
            let before = takeWords[max(0, i - 3)..<i], after = next(takeWords, after: i)
            let fresh = after.filter { !before.contains($0) }
            let dated = fresh.first(where: isDated)
            let plain = anyWord ? fresh.first(where: carriesMeaning) : nil
            guard let last = dated ?? plain else { continue }
            if !written(last, exactly: false) { return false }
            guard let dated else { continue }
            // What it replaced: a day for a day, a number for a number, not said again after the correction word.
            let replaced = before.last {
                isDated($0) && whenWords.contains($0) == whenWords.contains(dated) && !after.contains($0)
            }
            if let replaced, written(replaced, exactly: true) { return false }
        }
        return true
    }

    /// A date, a time or a number: the words a wrong pick between two versions would change.
    static func isDated(_ word: String) -> Bool { whenWords.contains(word) || digitOf[word] != nil || hasDigit(word) }

    /// Not a function word, a correction word or a number word.
    static func carriesMeaning(_ word: String) -> Bool {
        !functionWords.contains(word) && !corrections.contains(word) && !numberWords.contains(word)
    }

    /// The number at `index` is taken back: a correction word follows within 3 words, and another number within 3 more.
    static func corrected(_ words: [String], _ index: Int) -> Bool {
        guard hasDigit(words[index]), let marker = next(words, after: index).firstIndex(where: corrections.contains) else {
            return false
        }
        return next(words, after: marker).contains { numberWords.contains($0) || hasDigit($0) }
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
