import Foundation
import TFCore
import Testing
@testable import TFEngine

/// Counts engine calls and how many overlap.
private actor Calls {
    private(set) var count = 0
    private(set) var mostAtOnce = 0
    private var now = 0
    func begin() {
        count += 1
        now += 1
        mostAtOnce = max(mostAtOnce, now)
    }
    func end() { now -= 1 }
}

private func loud(_ frames: Int, _ level: Float = 0.1) -> [Float] { [Float](repeating: level, count: frames * 480) }
private func quiet(_ frames: Int) -> [Float] { [Float](repeating: 0, count: frames * 480) }

/// A one-shot gate an engine closure can await; `entered` reports that a waiter has arrived, so a test can hold off
/// until the engine call it wraps is truly in flight before it acts.
private actor Gate {
    private(set) var entered = false
    private var opened = false
    private var waiters: [CheckedContinuation<Void, Never>] = []
    func wait() async {
        entered = true
        if opened { return }
        await withCheckedContinuation { waiters.append($0) }
    }
    func open() {
        opened = true
        waiters.forEach { $0.resume() }
        waiters = []
    }
}

/// Feeds `samples` in 20 ms blocks. `keepUp`: wait after each block until the engine has caught up.
private func feed(_ transcriber: ChunkedTranscriber, _ samples: [Float], keepUp: Bool = true) async {
    for start in stride(from: 0, to: samples.count, by: 320) {
        await transcriber.append(Array(samples[start..<min(start + 320, samples.count)]))
        if keepUp { await transcriber.idle() }
    }
}

struct ChunkedTranscriberTests {
    @Test func jobsRunOneAtATime() async throws {
        let calls = Calls()
        let transcriber = ChunkedTranscriber(transcribe: { _ in
            await calls.begin()
            try await Task.sleep(for: .milliseconds(2))
            await calls.end()
            return "words"
        })
        for level in 1...4 { await feed(transcriber, loud(300, Float(level) / 10) + quiet(40), keepUp: false) }
        _ = await transcriber.stop(nowMs: 0)
        let result = try await transcriber.finish()
        #expect(await calls.mostAtOnce == 1)
        #expect(result.outcome.texts.filter { !$0.isEmpty }.count == 4)
    }

    // The speech check runs beside each job; a job it does not hear keeps empty text.
    @Test func textTheSpeechCheckDoesNotHearIsDropped() async throws {
        let transcriber = ChunkedTranscriber(transcribe: { _ in "yeah" }, hasSpeech: { _ in false })
        await feed(transcriber, loud(60))
        let result = try await transcriber.finish() // no stop: finish ends the take where the audio stopped
        #expect(result.outcome.texts == [""])
        #expect(!result.outcome.heard)
    }

    @Test func anEngineErrorFailsTheTake() async {
        let transcriber = ChunkedTranscriber(transcribe: { _ in throw EngineError.loadFailed("Encoder") })
        await feed(transcriber, loud(60))
        _ = await transcriber.stop(nowMs: 0)
        await #expect(throws: EngineError.loadFailed("Encoder")) { try await transcriber.finish() }
    }

    // After cancel, nothing more reaches the engine and finish throws.
    @Test func cancelDropsTheTake() async {
        let calls = Calls()
        let transcriber = ChunkedTranscriber(transcribe: { _ in
            await calls.begin()
            await calls.end()
            return "words"
        })
        await feed(transcriber, loud(300) + quiet(40)) // one chunk closes and runs
        #expect(await calls.count == 2) // the speculation 300 ms into the pause, then the chunk
        await transcriber.cancel()
        await feed(transcriber, loud(300, 0.2) + quiet(40))
        _ = await transcriber.stop(nowMs: 0)
        await #expect(throws: CancellationError.self) { try await transcriber.finish() }
        #expect(await calls.count == 2)
    }

    // A result from a job already in flight when cancel() lands is not this take's to keep: applying it would let
    // partialTexts grow (the app's 1 s tick could then report text for a take already saved or discarded).
    @Test func lateResultAfterCancelIsIgnored() async throws {
        let gate = Gate()
        let transcriber = ChunkedTranscriber(speculateAfterMs: nil, transcribe: { _ in
            await gate.wait()
            return "late"
        })
        await feed(transcriber, loud(300) + quiet(40), keepUp: false) // no speculation: the first job is the chunk
        while await !gate.entered { await Task.yield() } // the job is in flight, blocked in the engine closure
        _ = await transcriber.stop(nowMs: 0)
        let finishing = Task { try await transcriber.finish() }
        await transcriber.cancel()
        await gate.open()
        await #expect(throws: CancellationError.self) { try await finishing.value }
        #expect(await transcriber.partialTexts.isEmpty)
    }

    // Chunk texts come out in the order the audio was spoken, not the order jobs happen to finish.
    @Test func chunkTextsComeOutInOrder() async throws {
        // A chunk boundary falls inside a silence run, not a loud one (ChunkPlanner cuts mid-pause), so a chunk can
        // carry a few leading zero samples from the previous cycle's tail; max, not first, is each chunk's level.
        let transcriber = ChunkedTranscriber(transcribe: { samples in String(samples.max() ?? -1) })
        for level in 1...4 { await feed(transcriber, loud(300, Float(level) / 10) + quiet(40)) }
        _ = await transcriber.stop(nowMs: 0)
        let result = try await transcriber.finish()
        let levels = result.outcome.texts.compactMap(Float.init)
        #expect(levels == [0.1, 0.2, 0.3, 0.4])
    }
}

extension ModelTests {
    /// Feeds `take` up to `stopAt` keeping pace with the engine, stops, then feeds the tail until it ends.
    func replay(_ transcriber: ChunkedTranscriber, _ take: [Float], stopAt: Int) async throws -> ChunkedTranscriber.Result {
        await feed(transcriber, Array(take[..<stopAt]))
        var end = await transcriber.stop(nowMs: 0)
        var at = stopAt, nowMs = 0
        while end == nil, at < take.count {
            await transcriber.append(Array(take[at..<min(at + 320, take.count)]))
            at += 320
            nowMs += 20
            end = await transcriber.check(nowMs: nowMs)
        }
        return try await transcriber.finish()
    }

    // JFK, then a second of quiet. 300 ms into the quiet the chunk closes (it is past 11 s), so the stop needs no engine
    // run, and the text is the engine's one-shot text.
    @Test(.enabled(if: EngineTestData.v2 != nil, EngineTestData.noModels))
    func jfkThroughTheChunkedTranscriberReadsLikeOneShot() async throws {
        let engine = try await ParakeetEngine(modelDirectory: #require(EngineTestData.v2), variant: .v2)
        let jfk = try EngineTestData.samples("jfk.wav")
        let oneShot = try await engine.transcribe(jfk).text
        let transcriber = ChunkedTranscriber(transcribe: { try await engine.transcribe($0).text })
        let result = try await replay(transcriber, jfk + quiet(34), stopAt: jfk.count + 16_000)
        print("JFK: \(result.outcome.path), texts \(result.outcome.texts.count), runs \(result.outcome.engineRuns)")
        #expect(result.outcome.runsAfterStop == 0)
        #expect(ChunkJoin.join(result.outcome.texts) == oneShot)
    }

    // A 69 s take: JFK six times with 0.6 s gaps. Every copy comes out once, in order, with nothing lost or doubled.
    @Test(.enabled(if: EngineTestData.v2 != nil, EngineTestData.noModels))
    func jfkSixTimesGivesSixCopies() async throws {
        let engine = try await ParakeetEngine(modelDirectory: #require(EngineTestData.v2), variant: .v2)
        let jfk = try EngineTestData.samples("jfk.wav")
        var take: [Float] = []
        for copy in 0..<6 { take += jfk + (copy < 5 ? quiet(20) : quiet(40)) }
        let transcriber = ChunkedTranscriber(transcribe: { try await engine.transcribe($0).text })
        let result = try await replay(transcriber, take, stopAt: take.count - 9_600)
        let text = Bench.normalize(ChunkJoin.join(result.outcome.texts), english: true)
        print("JFK x6: \(result.outcome.texts.count) chunk texts, \(result.outcome.engineRuns) engine runs, \(result.outcome.path)")
        #expect(text.components(separatedBy: "ask not what your country").count - 1 == 6)
        #expect(text.components(separatedBy: "and so my fellow americans").count - 1 == 6)
    }
}
