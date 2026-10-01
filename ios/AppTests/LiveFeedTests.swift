import Foundation
import Testing
import TFCore
import TFEngine
@testable import ThumbFree

@MainActor @Suite struct LiveFeedTests {
    func level(_ value: Float, seconds: Double) -> [Float] { [Float](repeating: value, count: Int(seconds * 16_000)) }

    /// 20 ms blocks, as the host hands them over.
    func feed(_ live: LiveFeed, _ samples: [Float]) {
        for start in stride(from: 0, to: samples.count, by: 320) {
            live.append(Array(samples[start..<min(start + 320, samples.count)]))
        }
    }

    // Every block fed before the stop reaches the transcriber before the stop: the run at the stop hears all 2 s
    // (32,000 samples, then the tail's 5,760 zeros), and with no sound after it, its text stands.
    @Test func theStopLandsAfterEveryBlockFedBeforeIt() async throws {
        let inputs = EngineInputs()
        let tailEnds = Counter()
        let live = LiveFeed(ChunkedTranscriber(transcribe: { await inputs.add($0.count); return "words" })) { tailEnds.value += 1 }
        feed(live, level(0.1, seconds: 2))
        live.stop(nowMs: 0)
        live.check(nowMs: 400) // past the 350 ms cap: the tail ends
        live.check(nowMs: 450) // a later check never ends it twice
        let result = try await live.finish()
        #expect(tailEnds.value == 1)
        #expect(result.outcome.path == .optimistic)
        #expect(result.outcome.texts == ["words"])
        #expect(await inputs.lengths == [32_000 + 5_760])
    }

    // The user had paused: the tail ends at the stop.
    @Test func aQuietEndEndsTheTailAtTheStop() async throws {
        let tailEnds = Counter()
        let live = LiveFeed(ChunkedTranscriber(transcribe: { _ in "words" })) { tailEnds.value += 1 }
        feed(live, level(0.1, seconds: 1) + level(0, seconds: 0.6))
        live.stop(nowMs: 0)
        try await waitUntil { tailEnds.value == 1 }
        #expect(try await live.finish().outcome.texts == ["words"])
    }

    // A call or End session: finish() with no stop still hears every block fed before it.
    @Test func finishWithoutAStopHearsEveryBlock() async throws {
        let inputs = EngineInputs()
        let live = LiveFeed(ChunkedTranscriber(transcribe: { await inputs.add($0.count); return "words" })) {}
        feed(live, level(0.1, seconds: 2))
        let result = try await live.finish()
        #expect(result.outcome.texts == ["words"])
        #expect(await inputs.lengths == [32_000 + 5_760])
    }

    @Test func aCancelledTakeNeverReachesTheEngine() async {
        let inputs = EngineInputs()
        let live = LiveFeed(ChunkedTranscriber(transcribe: { await inputs.add($0.count); return "words" })) {}
        live.cancel()
        feed(live, level(0.1, seconds: 2))
        await #expect(throws: CancellationError.self) { try await live.finish() }
        #expect(await inputs.lengths.isEmpty)
    }
}

/// The length of each engine input.
private actor EngineInputs {
    private(set) var lengths: [Int] = []
    func add(_ length: Int) { lengths.append(length) }
}

@MainActor private final class Counter {
    var value = 0
}
