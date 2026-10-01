import Foundation
import Testing
@testable import TFEngine

struct EngineBasicsTests {
    @Test func devModelsFindsEitherFolderName() throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: root) }
        #expect(DevModels.directory(for: .v2, root: root) == nil)
        let hubName = root.appendingPathComponent("parakeet-tdt-0.6b-v2-coreml")
        try FileManager.default.createDirectory(at: hubName.appendingPathComponent("Encoder.mlmodelc"), withIntermediateDirectories: true)
        #expect(DevModels.directory(for: .v2, root: root)?.lastPathComponent == "parakeet-tdt-0.6b-v2-coreml")
        let cacheName = root.appendingPathComponent("parakeet-tdt-0.6b-v2")
        try FileManager.default.createDirectory(at: cacheName.appendingPathComponent("Encoder.mlmodelc"), withIntermediateDirectories: true)
        #expect(DevModels.directory(for: .v2, root: root)?.lastPathComponent == "parakeet-tdt-0.6b-v2")
        #expect(DevModels.directory(for: .v3, root: root) == nil)
    }

    @Test func blankIDsAreOnePastTheLastToken() {
        #expect(ModelVariant.v2.blankID == 1024)
        #expect(ModelVariant.v3.blankID == 8192)
    }

    @Test func detokenizerTurnsWordMarksIntoSpaces() {
        let vocabulary = Vocabulary(pieces: ["<unk>", "\u{2581}And", "\u{2581}so", ",", "\u{2581}my", "\u{2581}fel", "low"])
        #expect(vocabulary.text([1, 2, 3, 4, 5, 6]) == "And so, my fellow")
        #expect(vocabulary.text([]) == "")
        #expect(vocabulary.text([99, -1]) == "")
    }

    @Test func vocabularyKeepsOnlyIdsBelowTheBlank() throws {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString + ".json")
        defer { try? FileManager.default.removeItem(at: url) }
        try Data(#"{"0": "<unk>", "2": "b", "1": "▁a", "3": "▁stray"}"#.utf8).write(to: url)
        #expect(try Vocabulary(url: url, blankID: 3).pieces == ["<unk>", "\u{2581}a", "b"])
        #expect(throws: EngineError.loadFailed("\(url.lastPathComponent) lacks some of the ids below 5")) {
            try Vocabulary(url: url, blankID: 5)
        }
        #expect(throws: EngineError.modelMissing("nope.json")) {
            try Vocabulary(url: url.deletingLastPathComponent().appendingPathComponent("nope.json"), blankID: 3)
        }
    }

    @Test(.enabled(if: DevModels.directory(for: .v2) != nil, "No v2 models: set TF_MODELS_DIR or cache them under ~/Library/Application Support/FluidAudio/Models"))
    func realV2VocabularyHas1024Tokens() throws {
        let directory = try #require(DevModels.directory(for: .v2))
        let vocabulary = try Vocabulary(url: directory.appendingPathComponent("parakeet_vocab.json"), blankID: ModelVariant.v2.blankID)
        #expect(vocabulary.pieces.count == 1024)
        #expect(vocabulary.pieces[5] == "\u{2581}the")
    }

    @Test func paddingFollowsTheAndroidRule() {
        let padded = EnginePadding.pad([Float](repeating: 1, count: 100))
        #expect(padded.count == 20_000)
        #expect(padded[..<8_000].allSatisfy { $0 == 0 })
        #expect(padded[8_000..<8_100].allSatisfy { $0 == 1 })
        #expect(padded[8_100...].allSatisfy { $0 == 0 })
        #expect(EnginePadding.pad([Float](repeating: 1, count: 5_000)).count == 21_000)
        #expect(EnginePadding.pad([Float](repeating: 1, count: 15_999)).count == 31_999)
        let long = [Float](repeating: 1, count: 16_000)
        #expect(EnginePadding.pad(long) == long)
        #expect(EnginePadding.pad([]).isEmpty)
    }
}
