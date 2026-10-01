import Testing
@testable import TFCore

/// Some rows are adapted from MIT-licensed code; see THIRD_PARTY_NOTICES.md.
@Suite struct FillersTests {
    /// Filler removal then normalize, as the pipeline runs them. `custom` replaces both built-in lists.
    struct Row: Sendable, CustomTestStringConvertible {
        let text: String
        let language: String?
        let custom: [String]?
        let expected: String
        var testDescription: String { "\(text) [\(language ?? "nil")]" }

        init(_ text: String, _ language: String?, custom: [String]? = nil, _ expected: String) {
            self.text = text
            self.language = language
            self.custom = custom
            self.expected = expected
        }

        func run() -> String {
            Normalize.apply(custom.map { Fillers.remove(text, fillers: $0) } ?? Fillers.remove(text, language: language))
        }
    }

    static let goldens: [Row] = [
        Row("So uhm I was thinking uh about this", "en", "So I was thinking about this"),
        Row("UHM this is UH a test", "en", "this is a test"),
        Row("Well, uhm, I think, uh. that's right", "en", "Well, I think, that's right"),
        Row("Hello    world   test", "en", "Hello world test"),
        Row("  Hello world  ", "en", "Hello world"),
        Row("  Uhm, so I was, uh, thinking about this  ", "en", "so I was, thinking about this"),
        Row("This is a completely normal sentence.", "en", "This is a completely normal sentence."),
        Row("w wh wh wh wh wh wh wh wh wh why", "en", "w wh why"),
        Row("I I I I think so so so so", "en", "I think so"),
        Row("Check data doc doc doc doc documentation.", "en", "Check data doc documentation."),
        Row("No NO no NO no", "en", "No"),
        Row("no no is fine", "en", "no no is fine"),
        Row("um I think um this is good", "en", "I think this is good"),
        Row("um gato bonito", "pt", "um gato bonito"),
        Row("ha sido un buen día", "es", "ha sido un buen día"),
        Row("um gato bonito", "pt-BR", "um gato bonito"),
        Row("okay so I think right this works", "en", custom: ["okay", "right"], "so I think this works"),
        Row("So uhm I was thinking uh about this", "en", custom: [], "So uhm I was thinking uh about this"),
        Row("uh I think uhm this works", "xx", "I think this works"),
        Row("um I think this works", "xx", "um I think this works"),
        Row("uhh bueno hmm creo que um ha llegado", nil, "bueno creo que um ha llegado"),
        Row("хм я думаю ммм это работает", nil, "я думаю это работает"),
        Row("äh ich glaube ähm das passt", nil, "äh ich glaube ähm das passt"),
        Row("äh ich glaube ähm das passt", "de", "ich glaube das passt"),
        Row("the screw is 5 mm long", "en", "the screw is 5 mm long"),
        Row("um I think this works", "en", "I think this works"),
        Row("euh je pense que ça marche", "fr", "je pense que ça marche"),
        Row("customword should be removed but um should remain", nil, custom: ["customword"],
            "should be removed but um should remain"),
        // Worked out from the word-boundary rule.
        Row("That's it uh.", "en", "That's it"), // the filler takes the period
        Row("Yes, um.", "en", "Yes,"),
        Row("Really hmm?", "en", "Really ?"), // ? is not taken
        Row("I think uh, yes", "en", "I think yes"), // the filler takes the comma
        Row("Uh-huh, I agree", "en", "-huh, I agree"), // "uh" is a whole word before "-"
        Row("(um) okay", "en", "() okay"), // brackets stay
    ]

    @Test(arguments: goldens)
    func golden(_ row: Row) {
        TextTest.expectSame(row.run(), row.expected)
    }

    @Test(.timeLimit(.minutes(1)))
    func oddUnicodeNeverCrashesOrHangs() {
        // Fillers, whitespace only Unicode counts, marks, joiners, replacement characters (Swift has no lone surrogates)
        // and emoji, with odd custom lists: an empty filler would loop forever in a naive matcher.
        let pieces = ["uh", "UHM", "um", "Äh", "хм", "ММ", "euh", ",", ".", "?", "-", "_", " ", "\n",
                      "\u{85}", "\u{A0}", "\u{1C}", "\u{301}", "\u{200D}", "\u{FFFD}", "😀", "你好", "²"]
        let customs: [[String]?] = [nil, [], [""], [" "], ["\u{FFFD}"], ["a."]]
        var random = TextTest.Random(seed: 7)
        for _ in 0..<20_000 {
            let text = (0..<random.int(10)).map { _ in random.pick(pieces) }.joined()
            let row = Row(text, random.pick([nil, "en", "de", "fr"]), custom: random.pick(customs), "")
            let out = row.run()
            #expect(out == UnicodeText.splitWhitespace(out).joined(separator: " ")) // one line, single spaces
        }
    }
}
