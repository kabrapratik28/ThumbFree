import Testing
@testable import TFCore

@Suite struct CleanupPromptTests {
    // Clean is instruction v2 as it stands: rules and five examples, nothing appended (Android's
    // cleanIsInstructionV2WithTheTakeLast).
    @Test func cleanIsTheRulesAlone() {
        #expect(CleanupPrompt.instructions(.clean) == CleanupPrompt.rules)
        #expect(CleanupPrompt.rules.hasPrefix("You clean up text that someone dictated by voice. Reply with the cleaned text only."))
        #expect(CleanupPrompt.rules.contains("When the speaker corrects themselves, keep only the final version."))
        #expect(CleanupPrompt.rules.hasSuffix("Text: what time does the store close\nCleaned text: What time does the store close?"))
        #expect(CleanupPrompt.rules.components(separatedBy: "Cleaned text:").count == 6) // five examples
        #expect(!CleanupPrompt.rules.contains("Style:"))
        #expect(!CleanupPrompt.rules.contains("\u{2014}"))
    }

    // Every other style adds its line after the examples, asks for one unlabelled version, and its prompt ends on its
    // own label (Android's eachOtherStyleAddsItsLineBeforeTheTake).
    @Test(arguments: CleanupStyle.allCases.filter { $0 != .clean })
    func eachStyleAddsItsLine(style: CleanupStyle) {
        let instructions = CleanupPrompt.instructions(style)
        #expect(instructions.hasPrefix(CleanupPrompt.rules))
        #expect(instructions.hasSuffix("\n\nStyle: " + CleanupPrompt.styleLine(style)
            + " Reply with the rewritten text only: one version, no label, no notes."))
        #expect(CleanupPrompt.styleLine(style).hasSuffix("fact, name and number."))
        #expect(CleanupPrompt.prompt(take: "see you at seven", style: style) == "Text: see you at seven\nRewritten text:")
    }

    // The take goes in the prompt, trimmed, in the examples' own format.
    @Test func thePromptHoldsTheTake() {
        #expect(CleanupPrompt.prompt(take: "  um see you at five  \n", style: .clean) == "Text: um see you at five\nCleaned text:")
    }

    @Test func stylesKeepTheirStoredNames() {
        #expect(CleanupStyle.allCases.map(\.rawValue) == ["clean", "shorter", "friendly", "professional", "simple"])
    }
}
