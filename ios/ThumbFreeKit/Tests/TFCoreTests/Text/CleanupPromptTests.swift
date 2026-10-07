import Testing
@testable import TFCore

@Suite struct CleanupPromptTests {
    // Clean is instruction v2 as it stands: rules and five examples, nothing appended.
    @Test func cleanIsTheRulesAlone() {
        #expect(CleanupPrompt.instructions(.clean) == CleanupPrompt.rules)
        #expect(CleanupPrompt.rules.hasPrefix("You clean up text that someone dictated by voice. Reply with the cleaned text only."))
        #expect(CleanupPrompt.rules.hasSuffix("Text: what time does the store close\nCleaned text: What time does the store close?"))
        #expect(CleanupPrompt.rules.components(separatedBy: "Cleaned text:").count == 6) // five examples
        #expect(!CleanupPrompt.rules.contains("\u{2014}"))
    }

    // Every other style adds its one line, and keeps the facts.
    @Test(arguments: [
        (CleanupStyle.shorter, "Then say it in fewer words."),
        (.friendly, "Then make it warm and casual."),
        (.professional, "Then make it polished and formal."),
        (.simple, "Then use plain words and short sentences."),
    ])
    func eachStyleAddsItsLine(style: CleanupStyle, line: String) {
        let instructions = CleanupPrompt.instructions(style)
        #expect(instructions.hasPrefix(CleanupPrompt.rules))
        #expect(instructions.contains(line))
        #expect(instructions.contains("Keep the facts, names and numbers."))
    }

    // Only the tone styles may add words, and only for tone.
    @Test func onlyToneStylesMayAddWords() {
        for style in CleanupStyle.allCases {
            let allows = CleanupPrompt.instructions(style).contains("You may add a few words for tone.")
            #expect(allows == (style == .friendly || style == .professional), "\(style)")
        }
    }

    // The take goes in the prompt, trimmed, in the examples' own format.
    @Test func thePromptHoldsTheTake() {
        #expect(CleanupPrompt.prompt(take: "  um see you at five  \n") == "Text: um see you at five\nCleaned text:")
    }

    @Test func stylesKeepTheirStoredNames() {
        #expect(CleanupStyle.allCases.map(\.rawValue) == ["clean", "shorter", "friendly", "professional", "simple"])
    }
}
