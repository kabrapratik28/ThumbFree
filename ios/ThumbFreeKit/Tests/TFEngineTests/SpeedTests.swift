import Foundation
import Testing
@testable import TFEngine

extension ModelTests {
    /// Loose guard, not a benchmark (tfbench is): warm JFK through v2 in under 120 ms on the M4 Pro.
    @Test(.enabled(if: EngineTestData.v2 != nil, EngineTestData.noModels))
    func warmJFKTakesUnder120Milliseconds() async throws {
        let engine = try await ParakeetEngine(modelDirectory: #require(EngineTestData.v2), variant: .v2)
        try await engine.warmUp()
        let jfk = try EngineTestData.samples("jfk.wav")
        let clock = ContinuousClock()
        var totals: [Double] = []
        for _ in 0..<5 {
            let start = clock.now
            let result = try await engine.transcribe(jfk)
            let total = ParakeetEngine.ms(clock.now - start)
            totals.append(total)
            print(String(format: "JFK v2: preprocess %.1f, encode %.1f, decode %.1f, total %.1f ms",
                         result.preprocessMs, result.encodeMs, result.decodeMs, total))
            #expect(result.preprocessMs > 0 && result.encodeMs > 0 && result.decodeMs > 0)
        }
        #expect(totals.sorted()[2] < 120)
    }
}
