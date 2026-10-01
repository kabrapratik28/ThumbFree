import Foundation
import Testing
@testable import TFEngine

struct ParakeetEngineTests {
    @Test func missingModelFolderIsReported() async {
        let nowhere = FileManager.default.temporaryDirectory.appendingPathComponent("no-such-models")
        await #expect(throws: EngineError.modelMissing("no-such-models")) {
            try await ParakeetEngine(modelDirectory: nowhere, variant: .v2)
        }
    }
}

/// Tests that run the real models. One at a time: they share the Neural Engine and some of them time it.
@Suite(.serialized) struct ModelTests {}

extension ModelTests {
    @Test(.enabled(if: EngineTestData.v2 != nil, EngineTestData.noModels))
    func jfkComesOutWordForWord() async throws {
        let engine = try await ParakeetEngine(modelDirectory: #require(EngineTestData.v2), variant: .v2)
        let result = try await engine.transcribe(EngineTestData.samples("jfk.wav"))
        #expect(result.text == "And so, my fellow Americans, ask not what your country can do for you, ask what you can do for your country.")
    }

    @Test(.enabled(if: EngineTestData.v2 != nil, EngineTestData.noModels))
    func inputLongerThanOneWindowIsRefused() async throws {
        let engine = try await ParakeetEngine(modelDirectory: #require(EngineTestData.v2), variant: .v2)
        await #expect(throws: EngineError.inputTooLong(samples: 240_001)) {
            try await engine.transcribe([Float](repeating: 0, count: 240_001))
        }
        #expect(try await engine.transcribe([]).text == "")
    }

    @Test(.enabled(if: EngineTestData.v2 != nil, EngineTestData.noModels))
    func v2GivesFluidAudiosText() async throws { try await expectFluidAudioText(.v2) }

    @Test(.enabled(if: EngineTestData.v3 != nil, EngineTestData.noModels))
    func v3GivesFluidAudiosText() async throws { try await expectFluidAudioText(.v3) }

    /// No public clip is short enough on its own to exercise `EnginePadding.pad` inside `transcribe`
    /// (every one is well over 16_000 samples), so this slices one down: the first 8_000 samples
    /// (500 ms) of "he asked the handler...". Confirmed by temporarily bypassing the `EnginePadding.pad`
    /// call in `ParakeetEngine.transcribe`: this same slice then comes out as "Yeah." (hallucinated).
    /// With padding restored (production behavior, asserted here) it comes out as the correct prefix.
    @Test(.enabled(if: EngineTestData.v2 != nil, EngineTestData.noModels))
    func paddingRescuesAShortUtterance() async throws {
        let engine = try await ParakeetEngine(modelDirectory: #require(EngineTestData.v2), variant: .v2)
        let slice = Array(try EngineTestData.samples("1272-141231-0017.wav").prefix(8_000))
        let result = try await engine.transcribe(slice)
        #expect(result.text == "He asked")
    }

    /// FluidAudio drops a token whose duration step reaches the end of the audio; our decoder keeps it (standard
    /// greedy TDT). On the public clips that is one final period, on exactly these two clip and model pairs.
    static let finalTokenFluidAudioDrops = ["v2 1272-128104-0003.wav": ".", "v3 1272-128104-0002.wav": "."]

    /// Every clip that fits one window must come out as FluidAudio wrote it, plus the final token above where
    /// FluidAudio drops it. The three clips over 15 s (1272-128104-0004, -0009, -0011) went through FluidAudio's
    /// own chunking, so they are left out.
    func expectFluidAudioText(_ variant: ModelVariant) async throws {
        let engine = try await ParakeetEngine(modelDirectory: #require(EngineTestData.directory(variant)), variant: variant)
        var compared = 0
        for (name, oracle) in try EngineTestData.oracle(variant).sorted(by: { $0.key < $1.key }) {
            let samples = try EngineTestData.samples(name)
            guard samples.count <= ParakeetEngine.maxSamples else { continue }
            let expected = oracle + (Self.finalTokenFluidAudioDrops["\(variant) \(name)"] ?? "")
            #expect(try await engine.transcribe(samples).text == expected, "\(variant) \(name)")
            compared += 1
        }
        #expect(compared == 10)
    }
}
