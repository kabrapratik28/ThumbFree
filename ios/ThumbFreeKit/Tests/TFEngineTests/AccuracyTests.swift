import Foundation
import Testing
@testable import TFEngine

struct AccuracyTests {
    @Test func normalizationFollowsTheAndroidHarness() {
        #expect(Bench.normalize(" Mister Quilter's  manner, isn't it? ", english: true) == "mister quilters manner isnt it")
        #expect(Bench.normalize("Dieses Sediment war nötig, um Sandbänke", english: false) == "dieses sediment war nötig um sandbänke")
        #expect(Bench.normalize("up-guards", english: false) == "up guards")
    }

    @Test func wordErrorsCountSubstitutionsInsertionsAndDeletions() {
        let one = Bench.wordErrors(reference: "a b c d", hypothesis: "A x, c d e", english: true)
        #expect(one.errors == 2 && one.words == 4)
        let none = Bench.wordErrors(reference: "a b", hypothesis: "", english: true)
        #expect(none.errors == 2 && none.words == 2)
    }

    @Test func windowsCutLongAudioAtTheQuietestSpot() {
        var samples = [Float](repeating: 0.5, count: 470_400)
        for i in 200_000..<201_600 { samples[i] = 0 }       // 100 ms of silence 12.5 s in
        let pieces = Bench.windows(samples)
        #expect(pieces.map(\.count).reduce(0, +) == 470_400)
        #expect(pieces.allSatisfy { $0.count <= ParakeetEngine.maxSamples })
        #expect(pieces.first?.count == 200_800)              // the middle of the silence
        #expect(Bench.windows([Float](repeating: 0, count: 240_000)).count == 1)
    }
}

extension ModelTests {
    @Test(.enabled(if: EngineTestData.v2 != nil, EngineTestData.noModels))
    func v2EnglishWERIsAtMostFourPercent() async throws {
        #expect(try await pooledWER(.v2, english: true) <= 0.04)
    }

    @Test(.enabled(if: EngineTestData.v3 != nil, EngineTestData.noModels))
    func v3EnglishWERIsAtMostFivePercent() async throws {
        #expect(try await pooledWER(.v3, english: true) <= 0.05)
    }

    @Test(.enabled(if: EngineTestData.v3 != nil, EngineTestData.noModels))
    func v3GermanWERIsAtMostFifteenPercent() async throws {
        #expect(try await pooledWER(.v3, english: false) <= 0.15)
    }

    /// English: the 11 LibriSpeech clips plus JFK. Not English: fleurs-de.wav. Clips over 15 s are cut with
    /// `Bench.windows` and their texts joined with a space.
    func pooledWER(_ variant: ModelVariant, english: Bool) async throws -> Double {
        let engine = try await ParakeetEngine(modelDirectory: #require(EngineTestData.directory(variant)), variant: variant)
        let references = try Bench.references(in: EngineTestData.audio)
        var errors = 0, words = 0
        for name in references.keys.sorted() where (name != "fleurs-de.wav") == english {
            var texts: [String] = []
            for piece in Bench.windows(try EngineTestData.samples(name)) {
                texts.append(try await engine.transcribe(piece).text)
            }
            let counts = Bench.wordErrors(reference: try #require(references[name]),
                                          hypothesis: texts.joined(separator: " "), english: english)
            errors += counts.errors
            words += counts.words
        }
        print("\(variant) \(english ? "English" : "German") WER: \(errors) errors in \(words) words")
        return Double(errors) / Double(words)
    }
}
