import Foundation
import Testing
@testable import TFEngine

private let noSilero: Comment = "No Silero model: run python3 tools/fetch-models.py silero-vad-coreml"

struct SpeechCheckTests {
    @Test func speechRuleIsTheAndroidRuleOn256MillisecondOutputs() {
        #expect(SpeechCheck.isSpeech([0.1, 0.28, 0.1]))          // two windows at 0.15 inside one call
        #expect(SpeechCheck.isSpeech([0.1, 0.16, 0.16]))         // two windows at 0.15 across two calls
        #expect(!SpeechCheck.isSpeech([0.16, 0.1, 0.16, 0.1, 0.27]))
        #expect(!SpeechCheck.isSpeech([]))
    }

    @Test func failsOpenWithoutAModel() async {
        let check = await SpeechCheck(modelDirectory: FileManager.default.temporaryDirectory.appendingPathComponent("no-silero"))
        #expect(await check.hasSpeech([Float](repeating: 0, count: 48_000)))
    }

    @Test(.enabled(if: DevModels.sileroDirectory() != nil, noSilero))
    func sileroMatchesOfficialSileroOnJFK() async throws {
        // Official Silero v6.2 on ONNX Runtime: one probability per 512-sample window (344 for JFK). The Core ML
        // model returns the noisy-OR of 8 windows, so compare with the noisy-OR of each 8 reference windows.
        let text = try String(contentsOf: EngineTestData.audio.appendingPathComponent("jfk-silero-v6.2-onnx.txt"), encoding: .utf8)
        let reference = text.split(separator: "\n").filter { !$0.hasPrefix("#") }.compactMap { Double($0) }
        #expect(reference.count == 344)
        let expected = stride(from: 0, to: reference.count, by: 8).map { i in
            1 - reference[i..<min(i + 8, reference.count)].reduce(1) { $0 * (1 - $1) }
        }
        let check = await SpeechCheck(modelDirectory: try #require(DevModels.sileroDirectory()))
        let got = try await check.probabilities(EngineTestData.samples("jfk.wav"))
        #expect(got.count == expected.count)
        let errors = zip(got, expected).map { abs(Double($0) - $1) }
        let mean = errors.reduce(0, +) / Double(errors.count)
        print(String(format: "Silero parity on JFK: mean error %.4f, max %.4f over %d calls", mean, errors.max() ?? 0, errors.count))
        #expect(mean <= 0.01)
        #expect((errors.max() ?? 1) <= 0.08)
    }

    @Test(.enabled(if: DevModels.sileroDirectory() != nil, noSilero))
    func jfkHasSpeechButSilenceAndLowNoiseDoNot() async throws {
        let check = await SpeechCheck(modelDirectory: try #require(DevModels.sileroDirectory()))
        #expect(await check.hasSpeech(try EngineTestData.samples("jfk.wav")))
        #expect(await !check.hasSpeech([Float](repeating: 0, count: 48_000)))
        #expect(await !check.hasSpeech(gaussianNoise(count: 48_000, sigma: 300 / 32_768, seed: 1)))
    }
}

/// Seeded Gaussian noise (SplitMix64 and Box-Muller), the same on every run.
private func gaussianNoise(count: Int, sigma: Float, seed: UInt64) -> [Float] {
    var state = seed
    func uniform() -> Double {
        state &+= 0x9E37_79B9_7F4A_7C15
        var z = state
        z = (z ^ (z >> 30)) &* 0xBF58_476D_1CE4_E5B9
        z = (z ^ (z >> 27)) &* 0x94D0_49BB_1331_11EB
        return Double((z ^ (z >> 31)) >> 11) / Double(1 << 53)
    }
    return (0..<count).map { _ in
        Float((-2 * log(max(uniform(), 1e-12))).squareRoot() * cos(2 * .pi * uniform())) * sigma
    }
}
