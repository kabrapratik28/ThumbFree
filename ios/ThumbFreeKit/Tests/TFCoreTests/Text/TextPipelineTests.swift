import Testing
@testable import TFCore

@Suite struct TextPipelineTests {
    static let raw = "um so I opened a Zendesk uh ticket ticket ticket"

    @Test func customWordsThenFillersThenNormalize() {
        let out = TextPipeline.run(chunkTexts: [Self.raw], dictionary: ["Zendesk"], language: "en")
        TextTest.expectSame(out.raw, Self.raw)
        TextTest.expectSame(out.text, "so I opened a Zendesk ticket")
    }

    @Test func chunksAreJoinedFirst() {
        let out = TextPipeline.run(chunkTexts: [" um so I opened\n", "", "a Zendesk uh ticket", "ticket ticket "],
                                   dictionary: ["Zendesk"], language: "en")
        TextTest.expectSame(out.raw, Self.raw)
        TextTest.expectSame(out.text, "so I opened a Zendesk ticket")
    }

    @Test func customWordsSeeTheRawTextFirst() {
        // A stub step that drops the "a" shows the order: it runs before fillers and normalize, on the raw text.
        var seen = ""
        let text = TextPipeline.clean(Self.raw, language: "en") { text in
            seen = text
            return text.replacing("a Zendesk", with: "Zendesk")
        }
        #expect(text == "so I opened Zendesk ticket")
        #expect(seen == Self.raw)
        #expect(TextPipeline.run(chunkTexts: [Self.raw], dictionary: [], language: "en").text == "so I opened a Zendesk ticket")
    }

    @Test func theModelsLanguageDecidesNearMisses() {
        func text(_ language: String?) -> String {
            TextPipeline.run(chunkTexts: ["grazie mille"], dictionary: ["Grazia"], language: language).text
        }
        #expect(text("en") == "Grazia mille")
        #expect(text(nil) == "grazie mille")
        #expect(text("it") == "grazie mille")
    }
}
