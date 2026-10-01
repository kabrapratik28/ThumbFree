import Foundation
import Testing
@testable import TFCore

// Frames are 30 ms (480 samples): 8 s is frame 266.7, 10 s is 333.3, 11 s is 366.7, 14.5 s is 483.3.
// quiet() frames are non-speech at -60 dBFS: silence, since they are at or under -55 dBFS.
@Suite struct ChunkPlannerTests {
    func speech(_ frames: Int) -> [FrameVerdict] { Array(repeating: FrameVerdict(isSpeech: true, levelDBFS: -20), count: frames) }
    func quiet(_ frames: Int, _ dbfs: Float = -60) -> [FrameVerdict] {
        Array(repeating: FrameVerdict(isSpeech: false, levelDBFS: dbfs), count: frames)
    }

    /// Feeds the frames; returns each closed chunk keyed by the index of the frame that closed it.
    func feed(_ planner: inout ChunkPlanner, _ frames: [FrameVerdict]) -> [Int: Chunk] {
        var closed: [Int: Chunk] = [:]
        for (i, frame) in frames.enumerated() { if let chunk = planner.push(frame) { closed[i] = chunk } }
        return closed
    }

    // A second of silence at 2.4 s is before 8 s, and 600 ms at 9 s is too short: neither cuts. The 1.2 s from 10 s
    // does, 480 ms into it, once 990 ms of it have passed.
    @Test func cutsAt990msOfSilenceFrom8s() {
        var planner = ChunkPlanner()
        let closed = feed(&planner, speech(80) + quiet(34) + speech(186) + quiet(20) + speech(13) + quiet(40) + speech(10))
        #expect(closed == [365: Chunk(start: 0, end: 350 * 480, mayHoldSpeech: true)])
    }

    // 3.3 s of silence from 6 s: its cut waits for the first frame edge past 8 s.
    @Test func aLongSilenceCutsAtTheFirstFrameEdgePast8s() {
        var planner = ChunkPlanner()
        let closed = feed(&planner, speech(200) + quiet(110) + speech(10))
        #expect(closed == [282: Chunk(start: 0, end: 267 * 480, mayHoldSpeech: true)])
    }

    // A second of non-speech at -50 dBFS from 9 s, like a word under a noisy room's floor, is no silence; and before
    // 11 s no pause cuts.
    @Test func aPauseAbove55dBFSIsNoSilence() {
        var planner = ChunkPlanner()
        #expect(feed(&planner, speech(300) + quiet(34, -50) + speech(30)).isEmpty)
    }

    // 11.25 s of speech, then 21 s of non-speech at -50 dBFS: the gate heard no speech in it, but it may hold some.
    @Test func aChunkWithoutGateSpeechAbove55dBFSMayHoldSpeech() {
        var planner = ChunkPlanner()
        let closed = feed(&planner, speech(375) + quiet(700, -50)).sorted { $0.key < $1.key }.map(\.value)
        #expect(closed + [planner.finish(endSample: 1_075 * 480)] == [
            Chunk(start: 0, end: 380 * 480, mayHoldSpeech: true),
            Chunk(start: 380 * 480, end: 747 * 480, mayHoldSpeech: true),
            Chunk(start: 747 * 480, end: 1_075 * 480, mayHoldSpeech: true),
        ])
    }

    // Pauses under 990 ms cut only from 11 s. A 270 ms gap at 11.2 s is not a pause. The pause from 12.1 s is: the cut
    // goes 150 ms into it once it lasts 300 ms. The next pause is 3.3 s into the new chunk and does not cut: the 11 s
    // count restarts at a cut.
    @Test func cutsAtTheFirstPauseAfter11s() {
        var planner = ChunkPlanner()
        let closed = feed(&planner, speech(373) + quiet(9) + speech(20) + quiet(15) + speech(100) + quiet(15) + speech(50))
        #expect(closed == [411: Chunk(start: 0, end: 407 * 480, mayHoldSpeech: true)])
    }

    // Speech with no pause, only short dips: -80 dBFS at 9.3 s (before 10 s), a one-frame dropout at 11 s (one quiet
    // frame in loud speech is not a quiet window), -60 dBFS at 12 s and -50 dBFS at 13.5 s. The cut comes at the last
    // frame that fits in 14.5 s, in the middle of the -60 dBFS dip.
    @Test func aForcedCutGoesToTheQuietestWindowFrom10s() {
        var planner = ChunkPlanner()
        let closed = feed(&planner, speech(310) + quiet(4, -80) + speech(53) + quiet(1, -.infinity) + speech(32)
            + quiet(4, -60) + speech(46) + quiet(4, -50) + speech(29))
        #expect(closed == [482: Chunk(start: 0, end: 402 * 480, mayHoldSpeech: true)])
        #expect(planner.finish(endSample: 483 * 480) == Chunk(start: 402 * 480, end: 483 * 480, mayHoldSpeech: true))
    }

    // 11.25 s of speech, then 15 s of silence: the silence becomes chunks that may not hold speech, so they skip the engine.
    @Test func speechFreeChunksAreMarked() {
        var planner = ChunkPlanner()
        let closed = feed(&planner, speech(375) + quiet(500, -70)).sorted { $0.key < $1.key }.map(\.value)
        #expect(closed + [planner.finish(endSample: 875 * 480)] == [
            Chunk(start: 0, end: 380 * 480, mayHoldSpeech: true),
            Chunk(start: 380 * 480, end: 647 * 480, mayHoldSpeech: false),
            Chunk(start: 647 * 480, end: 875 * 480, mayHoldSpeech: false),
        ])
    }

    // After the cut in the pause at 12 s, the last chunk runs to the last sample, a partial frame included.
    @Test func theLastChunkClosesAtTheStop() {
        var planner = ChunkPlanner()
        _ = feed(&planner, speech(400) + quiet(12) + speech(150))
        #expect(planner.finish(endSample: 562 * 480 + 123) == Chunk(start: 405 * 480, end: 562 * 480 + 123, mayHoldSpeech: true))
        #expect(ChunkPlanner().finish(endSample: 0) == Chunk(start: 0, end: 0, mayHoldSpeech: false)) // nothing for the engine
    }

    /// 100 takes of up to 15 minutes: speech runs up to 60 s, gaps up to 1.2 s, random levels. Returns chunks and end.
    func randomTakes() -> [([Chunk], Int)] {
        (0..<100).map { seed in
            var rng = SplitMix64(seed: UInt64(seed))
            var planner = ChunkPlanner()
            var chunks: [Chunk] = []
            var speaking = Bool.random(using: &rng)
            var runLeft = 0
            let frames = Int.random(in: 1...30_000, using: &rng)
            for _ in 0..<frames {
                if runLeft == 0 {
                    speaking.toggle()
                    runLeft = Int.random(in: 1...(speaking ? 2_000 : 40), using: &rng)
                }
                runLeft -= 1
                let dbfs = speaking ? Float.random(in: -35 ... -10, using: &rng) : Float.random(in: -80 ... -50, using: &rng)
                if let chunk = planner.push(FrameVerdict(isSpeech: speaking, levelDBFS: dbfs)) { chunks.append(chunk) }
            }
            let end = frames * 480 + Int.random(in: 0..<480, using: &rng)
            return (chunks + [planner.finish(endSample: end)], end)
        }
    }

    // No chunk is longer than 14.5 s, so the stop tail's zero fill still fits the Encoder's 15 s window.
    @Test func noChunkExceeds14_5s() {
        for (chunks, _) in randomTakes() { #expect(chunks.allSatisfy { $0.end - $0.start <= 232_000 }) }
    }

    @Test func chunksTileTheTake() {
        for (chunks, end) in randomTakes() {
            #expect(chunks.first?.start == 0)
            #expect(zip(chunks, chunks.dropFirst()).allSatisfy { $0.end == $1.start })
            #expect(chunks.last?.end == end)
            #expect(chunks.allSatisfy { $0.end > $0.start })
            #expect(chunks.allSatisfy { $0.end == end || $0.end % 480 == 0 }) // every cut is on a frame edge
        }
    }

    // A whole take replayed through a fresh gate and planner (Transcribe again) is cut the same way every time.
    @Test func publicClipsAreCutWithinTheWindow() throws {
        let long = try WavFile.readMono16k(url: TestAudio.url("1272-128104-0004.wav")) // 29.4 s
        let chunks = ChunkPlanner.chunks(of: long)
        #expect(chunks.map(\.end) == [181_920, 391_200, long.count])
        #expect(chunks.allSatisfy { $0.mayHoldSpeech && $0.end - $0.start <= 232_000 })

        let jfk = try WavFile.readMono16k(url: TestAudio.url("jfk.wav")) // 11 s: one chunk
        #expect(ChunkPlanner.chunks(of: jfk) == [Chunk(start: 0, end: jfk.count, mayHoldSpeech: true)])
    }
}
