import Testing
@testable import TFCore

@Suite struct CleanupPromptTests {
    // Clean is instruction v3 as it stands: rules naming both kinds of self-correction and five examples (Android's
    // cleanIsInstructionV2WithTheTakeLast).
    @Test func cleanIsTheRulesAlone() {
        #expect(CleanupPrompt.instructions(.clean) == CleanupPrompt.rules)
        #expect(CleanupPrompt.rules.hasPrefix("You clean up text that someone dictated by voice. Reply with the cleaned text only."))
        #expect(CleanupPrompt.rules.contains(
            "When the speaker corrects themselves (\"five no six\", \"Monday no Tuesday\"), keep only what they said last."))
        #expect(CleanupPrompt.rules.hasSuffix("Text: what time does the store close\nCleaned text: What time does the store close?"))
        #expect(CleanupPrompt.rules.components(separatedBy: "Cleaned text:").count == 6) // five examples
        #expect(!CleanupPrompt.rules.contains("Style:"))
        #expect(!CleanupPrompt.rules.contains("\u{2014}"))
    }

    // Every other style is one rewrite task of its own, without Clean's examples, and its prompt ends on its own label
    // (Android's eachOtherStyleIsOneRewriteWithItsOwnLabel).
    @Test(arguments: [(CleanupStyle.shorter, "Shorter version:"), (.friendly, "Friendly version:"),
                      (.professional, "Professional version:"), (.simple, "Simple version:")])
    func eachOtherStyleIsOneRewrite(style: CleanupStyle, label: String) {
        let instructions = CleanupPrompt.instructions(style)
        #expect(instructions.hasPrefix("You rewrite text that someone dictated by voice. " + CleanupPrompt.task(style) + " "))
        #expect(instructions.hasSuffix("Reply with one rewritten version only."))
        #expect(!instructions.contains("Cleaned text:")) // Clean's examples led to two versions
        #expect(CleanupPrompt.prompt(take: "see you at seven", style: style) == "Text: see you at seven\n" + label)
        #expect(CleanupPrompt.labels.contains(label))
    }

    // The take goes in the prompt, trimmed, in the examples' own format.
    @Test func thePromptHoldsTheTake() {
        #expect(CleanupPrompt.prompt(take: "  um see you at five  \n", style: .clean) == "Text: um see you at five\nCleaned text:")
    }

    @Test func stylesKeepTheirStoredNames() {
        #expect(CleanupStyle.allCases.map(\.rawValue) == ["clean", "shorter", "friendly", "professional", "simple"])
    }
}
