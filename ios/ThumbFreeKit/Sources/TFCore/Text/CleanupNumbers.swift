import Foundation

/// The numbers a text says, in digits or in words, as Clean up's checks compare them: "seven thirty" and "7:30" are
/// both 730, "twenty five dollars" and "$25" both 25, "twelve hundred" and "1,200" both 1200, "the fifth" and "5th"
/// both 5, "three point five" and "3.5" both 3.5, "seven o'clock" and "7:00" both 7. Numbers side by side make one
/// run, so a phone number or a code read digit by digit is one ("five five five one two one two" is 5551212, as
/// "555-1212" is). Found in review: an answer could change a number's value ("two tablets" as 9) or drop it.
enum CleanupNumbers {
    /// A run of numbers: its digits as compared, its first and last token, and whether it is the word "one" alone.
    struct Run: Equatable {
        var digits: String
        var first: Int
        var last: Int
        var loneOne: Bool
    }

    /// A text's pieces: a written number (canonical: "7:30" is 730, "1,200" is 1200, "5th" is 5, "25.00" is 25), a
    /// word, or punctuation that ends a run (. ! ? ; : and a new line).
    enum Token: Equatable {
        case number(String)
        case word(String)
        case stop
    }

    static let units = ["zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine"]
    static let teens = ["ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen",
                        "nineteen"]
    static let ordinals: [String: Int] = [
        "third": 3, "fourth": 4, "fifth": 5, "sixth": 6, "seventh": 7, "eighth": 8, "ninth": 9, "tenth": 10,
        "eleventh": 11, "twelfth": 12, "thirteenth": 13, "fourteenth": 14, "fifteenth": 15, "sixteenth": 16,
        "seventeenth": 17, "eighteenth": 18, "nineteenth": 19, "twentieth": 20, "thirtieth": 30,
    ]
    static let multipliers: [String: Double] = ["hundred": 100, "thousand": 1_000, "million": 1_000_000,
                                                "billion": 1_000_000_000]

    /// The numbers the answer writes are the take's: in order for Clean, in any order for a rewrite. A number the
    /// speaker took back ("at 5, no, 6") may stay or go (the correction check judges it), and so may a lone "one" in a
    /// rewrite, which may say it another way ("the red one" as "the red item").
    static func match(take: String, output: String, ordered: Bool) -> Bool {
        let takeTokens = tokens(take)
        let takeRuns = runs(takeTokens)
        let given = runs(tokens(output)).map(\.digits).joined()
        let pieces = takeRuns.map { run in
            Piece(digits: run.digits, optional: takenBack(run, in: takeTokens, runs: takeRuns) || (!ordered && run.loneOne))
        }
        if ordered { return spells(Substring(given), pieces[...]) }
        // ponytail: subsets of the first 8 optional runs only; a take with more is rare, and then fails safe.
        let required = pieces.filter { !$0.optional }.map(\.digits).joined()
        let optional = pieces.filter(\.optional).map(\.digits).prefix(8)
        let have = given.sorted()
        return (0..<(1 << optional.count)).contains { mask in
            let chosen = optional.indices.filter { mask & (1 << $0) != 0 }.map { optional[$0] }.joined()
            return (required + chosen).sorted() == have
        }
    }

    struct Piece { let digits: String; let optional: Bool }

    /// `text` is the pieces' digits one after another, each optional piece there or not.
    static func spells(_ text: Substring, _ pieces: ArraySlice<Piece>) -> Bool {
        guard let piece = pieces.first else { return text.isEmpty }
        let rest = pieces.dropFirst()
        if text.hasPrefix(piece.digits), spells(text.dropFirst(piece.digits.count), rest) { return true }
        return piece.optional && spells(text, rest)
    }

    /// The run is taken back: a correction word within 3 tokens after it, and another number within 3 more.
    static func takenBack(_ run: Run, in tokens: [Token], runs: [Run]) -> Bool {
        let starts = Set(runs.map(\.first))
        for k in (run.last + 1)..<min(run.last + 4, tokens.count) {
            guard case .word(let word) = tokens[k], CleanupCheck.corrections.contains(word) else { continue }
            if ((k + 1)..<min(k + 4, tokens.count)).contains(where: starts.contains) { return true }
        }
        return false
    }

    static func tokens(_ text: String) -> [Token] {
        let chars = Array(CleanupCheck.straight(text.lowercased()))
        func isDigit(_ i: Int) -> Bool {
            i < chars.count && chars[i].unicodeScalars.count == 1 && chars[i].unicodeScalars.allSatisfy(CleanupCheck.isDecimal)
        }
        var tokens: [Token] = []
        var i = 0
        while i < chars.count {
            if isDigit(i) {
                var raw = ""
                while isDigit(i) || (":.,/-".contains(chars[i]) && isDigit(i + 1)) {
                    raw.append(chars[i])
                    i += 1
                }
                // An ordinal's letters (5th, 23rd) belong to the number.
                if i + 1 < chars.count, ["st", "nd", "rd", "th"].contains(String(chars[i...(i + 1)])),
                   i + 2 >= chars.count || !chars[i + 2].isLetter {
                    i += 2
                }
                tokens.append(.number(canonical(raw)))
            } else if chars[i].isLetter || chars[i] == "'" {
                var word = ""
                while i < chars.count, chars[i].isLetter || chars[i] == "'" {
                    word.append(chars[i])
                    i += 1
                }
                word = word.trimmingCharacters(in: CharacterSet(charactersIn: "'"))
                if !word.isEmpty { tokens.append(.word(word)) }
            } else {
                if ".!?;:\n".contains(chars[i]) { tokens.append(.stop) }
                i += 1
            }
        }
        return tokens
    }

    /// A written number as compared: separators go, and a whole hour or amount ("7:00", "25.00") loses its zeros. A
    /// decimal point stays, so 3.5 is never 35.
    static func canonical(_ raw: String) -> String {
        var text = raw
        if text.hasSuffix(":00") || text.hasSuffix(".00") { text.removeLast(3) }
        return text.filter { !":,/-".contains($0) }
    }

    /// The text's runs of numbers, in order.
    static func runs(_ tokens: [Token]) -> [Run] {
        var runs: [Run] = []
        var builder = Builder()
        func end() {
            builder.flush()
            if !builder.parts.isEmpty {
                runs.append(Run(digits: builder.parts.joined(), first: builder.first, last: builder.last,
                                loneOne: builder.spoken == ["one"]))
            }
            builder = Builder()
        }
        for (k, token) in tokens.enumerated() {
            switch token {
            case .number(let digits):
                builder.flush()
                builder.written = digits
                builder.active = true
                builder.kind = .written
                builder.mark(k)
            case .word(let word):
                let nextIsMultiplier: Bool = {
                    guard k + 1 < tokens.count, case .word(let next) = tokens[k + 1] else { return false }
                    return multipliers[next] != nil
                }()
                if word == "a", nextIsMultiplier, !builder.active { continue } // "a hundred" is 100
                if word == "and", builder.active, [.hundred, .big].contains(builder.kind) { continue }
                if word == "point", builder.active, builder.decimals == nil {
                    builder.decimals = ""
                    continue
                }
                guard builder.take(word) else {
                    end()
                    continue
                }
                builder.spoken.append(word)
                builder.mark(k)
            case .stop:
                end()
            }
        }
        end()
        return runs
    }

    /// One run as it is read: the numbers finished so far (`parts`) and the one being built.
    struct Builder {
        enum Kind { case none, unit, teen, tens, hundred, big, written }
        var parts: [String] = []
        var total = 0.0, group = 0.0
        var active = false
        var kind = Kind.none
        var written: String?
        var decimals: String?
        var spoken: [String] = []
        var first = -1, last = -1

        mutating func mark(_ k: Int) {
            if first < 0 { first = k }
            last = k
        }

        /// Reads one word into the run; false when the word is no number, which ends the run.
        mutating func take(_ word: String) -> Bool {
            if let digit = CleanupNumbers.units.firstIndex(of: word) {
                if decimals != nil { decimals?.append(String(digit)) } else if active, kind == .tens || kind == .hundred || kind == .big {
                    group += Double(digit)
                    kind = .unit
                } else {
                    start(Double(digit), .unit)
                }
                return true
            }
            if let index = CleanupNumbers.teens.firstIndex(of: word) {
                add(Double(index + 10), .teen)
                return true
            }
            if let index = CleanupCheck.tens.firstIndex(of: word) {
                add(Double((index + 2) * 10), .tens)
                return true
            }
            // An ordinal ends its number: "twenty third" is 23, "the fifth" 5; "first" and "second" only after a tens word.
            let late = ["first": 1, "second": 2][word].flatMap { active && kind == .tens ? $0 : nil }
            if let value = CleanupNumbers.ordinals[word] ?? late {
                if active, (kind == .tens && value < 10) || kind == .hundred || kind == .big { group += Double(value) } else { start(Double(value), .unit) }
                flush()
                return true
            }
            if word == "noon" || word == "midnight" {
                start(12, .unit)
                flush()
                return true
            }
            if let multiplier = CleanupNumbers.multipliers[word] {
                if let written, let value = Double(written) {
                    group = value
                    self.written = nil
                }
                if !active { group = 1 }
                active = true
                if multiplier == 100 {
                    group *= 100
                    kind = .hundred
                } else {
                    total += group * multiplier
                    group = 0
                    kind = .big
                }
                return true
            }
            return false
        }

        /// A teen or a tens word: added after "hundred" or "thousand", else a number of its own.
        mutating func add(_ value: Double, _ newKind: Kind) {
            if decimals == nil, active, kind == .hundred || kind == .big {
                group += value
                kind = newKind
            } else {
                start(value, newKind)
            }
        }

        mutating func start(_ value: Double, _ newKind: Kind) {
            flush()
            group = value
            active = true
            kind = newKind
        }

        /// Ends the number being built, into `parts`.
        mutating func flush() {
            guard active else { return }
            if let written {
                parts.append(written)
            } else {
                let value = total + group
                var digits = value == value.rounded() && value < 1e15 ? String(Int(value)) : String(value)
                if let decimals, !decimals.isEmpty { digits += "." + decimals }
                parts.append(digits)
            }
            total = 0
            group = 0
            active = false
            kind = .none
            written = nil
            decimals = nil
        }
    }
}
