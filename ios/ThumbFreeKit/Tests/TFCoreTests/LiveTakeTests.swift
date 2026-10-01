import Testing
@testable import TFCore

// Frames are 30 ms (480 samples). A loud segment is a constant level: 0.1 is -20 dBFS, so every frame has sound
// (above -55 dBFS). Quiet is digital silence. The fake engine names each run of equal nonzero samples by its level,
// 0.1 as "w1", 0.2 as "w2", so a text shows exactly which audio a job heard.
@Suite struct LiveTakeTests {
    static func loud(_ frames: Int, _ level: Float = 0.1) -> [Float] { [Float](repeating: level, count: frames * 480) }
    static func quiet(_ frames: Int) -> [Float] { [Float](repeating: 0, count: frames * 480) }

    static func words(_ samples: [Float]) -> String {
        var words: [String] = []
        var previous: Float = 0
        for sample in samples where sample != previous {
            if sample != 0 { words.append("w\(Int((sample * 10).rounded()))") }
            previous = sample
        }
        return words.joined(separator: " ")
    }

    /// An engine that is never behind: every job it is handed finishes at once. Returns the jobs it ran.
    @discardableResult
    static func drain(_ take: inout LiveTake) -> [LiveTake.Job] {
        var ran: [LiveTake.Job] = []
        while let job = take.nextJob() {
            #expect(job.samples.count == job.end - job.start)
            ran.append(job)
            take.finished(job, text: words(job.samples), heard: true)
        }
        return ran
    }

    /// Feeds `samples` in 20 ms blocks, as the microphone does, draining the engine after each block.
    @discardableResult
    static func feed(_ take: inout LiveTake, _ samples: [Float]) -> [LiveTake.Job] {
        var ran: [LiveTake.Job] = []
        for start in stride(from: 0, to: samples.count, by: 320) {
            take.append(Array(samples[start..<min(start + 320, samples.count)]))
            ran += drain(&take)
        }
        return ran
    }

    // Six 9 s segments with 1.2 s pauses: each pause closes a chunk (the 8 s rule: the middle of 990 ms of silence).
    @Test func closedChunksRunInOrderAndTheTextsCoverTheTakeOnce() throws {
        var take = LiveTake()
        var chunkStarts: [Int] = []
        for level in 1...6 {
            let ran = Self.feed(&take, Self.loud(300, Float(level) / 10) + Self.quiet(40))
            chunkStarts += ran.filter { $0.kind == .chunk }.map(\.start)
        }
        #expect(chunkStarts.count == 6)
        #expect(chunkStarts == chunkStarts.sorted())
        #expect(take.partialTexts == ["w1", "w2", "w3", "w4", "w5", "w6"])
        #expect(take.stop(nowMs: 0) == .quiet)
        Self.drain(&take)
        let outcome = try #require(take.outcome)
        #expect(outcome.texts == ["w1", "w2", "w3", "w4", "w5", "w6", ""]) // the last chunk is the pause after "w6"
        #expect(outcome.path == .silent)
    }

    @Test func jobsGoOutOneAtATimeInOrder() throws {
        var take = LiveTake()
        take.append(Self.loud(300) + Self.quiet(40) + Self.loud(300, 0.2) + Self.quiet(40))
        let next = take.nextJob()
        let first = try #require(next)
        #expect(first.kind == .chunk && first.start == 0)
        #expect(take.nextJob() == nil) // the first is still out
        take.finished(first, text: Self.words(first.samples), heard: true)
        let later = take.nextJob()
        let second = try #require(later)
        #expect(second.kind == .chunk && second.start == first.end)
        #expect(Self.words(second.samples) == "w2")
    }

    @Test func aChunkWithoutSoundNeverReachesTheEngine() throws {
        var take = LiveTake()
        var ran = Self.feed(&take, Self.loud(60) + Self.quiet(600) + Self.loud(60, 0.2))
        _ = take.stop(nowMs: 0)
        ran += Self.feed(&take, Self.quiet(10)) // the tail
        #expect(take.check(nowMs: 400) != nil)
        ran += Self.drain(&take)
        #expect(ran.allSatisfy { job in job.samples.contains { $0 != 0 } })
        let outcome = try #require(take.outcome)
        #expect(outcome.texts == ["w1", "", "w2"])
    }

    // A last word after the stop: once the tail ends, the final window runs with the tail and the policy's zero fill.
    @Test func theFinalWindowRunsWithTheTailAndItsZeroFill() throws {
        var take = LiveTake()
        Self.feed(&take, Self.loud(60))
        _ = take.stop(nowMs: 1_000)
        Self.drain(&take)
        Self.feed(&take, Self.loud(3, 0.2) + Self.quiet(4))
        #expect(take.check(nowMs: 1_210) == .hangover)
        let next = take.nextJob()
        let final = try #require(next)
        #expect(final.kind == .afterTail)
        #expect(final.start == 0 && final.end == 67 * 480 && final.zeros == 60 * 480 + 5_760 - 67 * 480)
        take.finished(final, text: Self.words(final.samples), heard: true)
        let outcome = try #require(take.outcome)
        #expect(outcome.path == .afterTail)
        #expect(outcome.texts == ["w1 w2"])
    }

    // 9 s, a 1.2 s pause (a chunk closes), then 1.8 s: both chunks have sound and fit in 14.5 s, so the final window
    // holds both and the first chunk's own text is dropped.
    @Test func theLastTwoChunksMergeWhenBothHoldSpeechAndFit() throws {
        var take = LiveTake()
        Self.feed(&take, Self.loud(300) + Self.quiet(40) + Self.loud(60, 0.2))
        _ = take.stop(nowMs: 0)
        var ran = Self.drain(&take)
        ran += Self.feed(&take, Self.quiet(4))
        _ = take.check(nowMs: 120)
        ran += Self.drain(&take)
        #expect(ran.last?.start == 0) // the final window starts where the first chunk does
        let outcome = try #require(take.outcome)
        #expect(outcome.texts == ["w1 w2"])
    }

    @Test func chunksThatDoNotFitIn14_5sStaySeparate() throws {
        var take = LiveTake()
        Self.feed(&take, Self.loud(300) + Self.quiet(40) + Self.loud(300, 0.2))
        _ = take.stop(nowMs: 0)
        Self.drain(&take)
        Self.feed(&take, Self.quiet(4))
        _ = take.check(nowMs: 120)
        Self.drain(&take)
        let outcome = try #require(take.outcome)
        #expect(outcome.texts == ["w1", "w2"])
    }

    @Test func aSilentTakeRunsNothing() {
        var take = LiveTake()
        #expect(Self.feed(&take, Self.quiet(70)).isEmpty)
        #expect(take.stop(nowMs: 0) == .quiet)
        #expect(take.nextJob() == nil)
        #expect(take.outcome == LiveTake.Outcome(texts: [""], heard: false, path: .silent, engineRuns: 0, runsAfterStop: 0))
    }

    // An engine that falls behind: chunk 1 is still out while chunks 2 and 3 close (the trim keeps chunk 2, which
    // waits) and a short burst merges with chunk 3 at the stop. Chunk 2 runs, then the merged window's optimistic run;
    // chunk 3 never runs on its own.
    @Test func anEngineBehindSkipsTheChunkMergedIntoTheFinalWindow() throws {
        var take = LiveTake()
        take.append(Self.loud(300) + Self.quiet(40))
        let next = take.nextJob()
        let first = try #require(next)
        #expect(first.kind == .chunk && Self.words(first.samples) == "w1")
        #expect(Self.feed(&take, Self.loud(300, 0.2) + Self.quiet(40) + Self.loud(300, 0.3) + Self.quiet(40)).isEmpty)
        #expect(Self.feed(&take, Self.loud(60, 0.4)).isEmpty)
        #expect(take.stop(nowMs: 1_000) == nil)
        take.finished(first, text: Self.words(first.samples), heard: true)
        var ran = Self.drain(&take)
        ran += Self.feed(&take, Self.quiet(4)) // the tail
        #expect(take.check(nowMs: 1_120) == .hangover)
        ran += Self.drain(&take)
        #expect(ran.map(\.kind) == [.chunk, .optimistic])
        #expect(ran.map { Self.words($0.samples) } == ["w2", "w3 w4"])
        let outcome = try #require(take.outcome)
        #expect(outcome.texts == ["w1", "w2", "w3 w4"])
        #expect(outcome.engineRuns == 3) // chunks 1 and 2 and the final window: chunk 3 never ran on its own
    }

    // A 5-minute take holds at most two chunks of audio.
    @Test func memoryStaysBoundedOnALongTake() {
        var take = LiveTake()
        for _ in 0..<30 { Self.feed(&take, Self.loud(300) + Self.quiet(40)) }
        #expect(take.bufferedSamples <= 2 * ChunkConfig.window15s.maxSamples)
    }
}

// Optimistic stop.
extension LiveTakeTests {
    // Stop mid-speech: the final window up to the stop runs at once, with zeros for the tail; a quiet tail keeps it.
    @Test func aStopMidSpeechRunsAtOnceAndAQuietTailKeepsIt() throws {
        var take = LiveTake()
        Self.feed(&take, Self.loud(60))
        #expect(take.stop(nowMs: 1_000) == nil)
        let next = take.nextJob()
        let optimistic = try #require(next)
        #expect(optimistic.kind == .optimistic)
        #expect(optimistic.start == 0 && optimistic.end == 60 * 480 && optimistic.zeros == StopTailPolicy.fillSamples)
        take.finished(optimistic, text: Self.words(optimistic.samples), heard: true)
        Self.feed(&take, Self.quiet(4))
        #expect(take.check(nowMs: 1_120) == .hangover)
        #expect(take.nextJob() == nil)
        let outcome = try #require(take.outcome)
        #expect(outcome.path == .optimistic)
        #expect(outcome.texts == ["w1"])
        #expect(outcome.runsAfterStop == 1)
    }

    // Sound in the tail: the run from the stop no longer covers the take, so the final window runs a second time.
    @Test func soundInTheTailMeansASecondRun() throws {
        var take = LiveTake()
        Self.feed(&take, Self.loud(60))
        _ = take.stop(nowMs: 1_000)
        #expect(Self.drain(&take).map(\.kind) == [.optimistic])
        Self.feed(&take, Self.loud(3, 0.2) + Self.quiet(4))
        _ = take.check(nowMs: 1_210)
        #expect(Self.drain(&take).map(\.kind) == [.afterTail])
        let outcome = try #require(take.outcome)
        #expect(outcome.texts == ["w1 w2"])
        #expect(outcome.runsAfterStop == 2)
    }

    // An interrupted take nobody stopped: endTail stops it, so the final window runs as at a stop, with the zeros, and
    // that text stands. The path reads optimistic though nobody tapped stop.
    @Test func endTailStopsAnInterruptedTakeAndItsRunWithTheZerosStands() throws {
        var take = LiveTake()
        Self.feed(&take, Self.loud(60))
        take.endTail()
        let ran = Self.drain(&take)
        #expect(ran.map(\.kind) == [.optimistic])
        #expect(ran.first?.zeros == StopTailPolicy.fillSamples)
        let outcome = try #require(take.outcome)
        #expect(outcome.path == .optimistic)
        #expect(outcome.texts == ["w1"])
    }

    // The replay harness's baseline: without the optimistic stop, the final window waits for the tail.
    @Test func withOptimisticOffTheFinalWindowWaitsForTheTail() throws {
        var take = LiveTake(optimistic: false)
        Self.feed(&take, Self.loud(70))
        _ = take.stop(nowMs: 0)
        #expect(take.nextJob() == nil)
        Self.feed(&take, Self.quiet(4))
        _ = take.check(nowMs: 120)
        #expect(Self.drain(&take).map(\.kind) == [.afterTail])
        let outcome = try #require(take.outcome)
        #expect(outcome.path == .afterTail)
    }
}

// Speculative finish.
extension LiveTakeTests {
    // Stop 600 ms into a pause: the speculation from 300 ms in is the answer, with no engine run after the stop.
    @Test func aStopAfterAPauseUsesTheSpeculation() throws {
        var take = LiveTake()
        let ran = Self.feed(&take, Self.loud(60) + Self.quiet(20))
        #expect(ran.map(\.kind) == [.speculation])
        #expect(ran.first?.end == 70 * 480) // 300 ms into the pause
        #expect(ran.first?.zeros == StopTailPolicy.fillSamples)
        #expect(take.stop(nowMs: 0) == .quiet)
        #expect(take.nextJob() == nil)
        let outcome = try #require(take.outcome)
        #expect(outcome.path == .speculation)
        #expect(outcome.texts == ["w1"])
        #expect(outcome.runsAfterStop == 0)
    }

    // Speech after a speculation: its text no longer covers the take, so the stop runs the final window again.
    @Test func speechAfterASpeculationIsNotLost() throws {
        var take = LiveTake()
        #expect(Self.feed(&take, Self.loud(60) + Self.quiet(20)).map(\.kind) == [.speculation])
        Self.feed(&take, Self.loud(30, 0.2))
        #expect(take.stop(nowMs: 0) == nil)
        #expect(Self.drain(&take).map(\.kind) == [.optimistic])
        Self.feed(&take, Self.quiet(4))
        #expect(take.check(nowMs: 120) == .hangover)
        let outcome = try #require(take.outcome)
        #expect(outcome.path == .optimistic)
        #expect(outcome.texts == ["w1 w2"])
    }

    // One speculation at a time, and one that went stale while it ran is followed by a fresh one.
    @Test func aStaleSpeculationIsFollowedByAFreshOne() throws {
        var take = LiveTake()
        take.append(Self.loud(60) + Self.quiet(10))
        let next = take.nextJob()
        let first = try #require(next)
        #expect(first.kind == .speculation)
        take.append(Self.loud(30, 0.2) + Self.quiet(10))
        #expect(take.nextJob() == nil) // the first is still out
        take.finished(first, text: Self.words(first.samples), heard: true)
        let later = take.nextJob()
        let second = try #require(later)
        #expect(second.kind == .speculation)
        #expect(second.end == 110 * 480)
        #expect(Self.words(second.samples) == "w1 w2")
    }

    @Test func withSpeculationOffAPauseRunsNothing() throws {
        var take = LiveTake(speculateAfterMs: nil)
        #expect(Self.feed(&take, Self.loud(60) + Self.quiet(20)).isEmpty)
        #expect(take.stop(nowMs: 0) == .quiet)
        #expect(Self.drain(&take).map(\.kind) == [.optimistic])
        let outcome = try #require(take.outcome)
        #expect(outcome.path == .optimistic)
    }
}
