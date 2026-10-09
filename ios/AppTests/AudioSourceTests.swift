import Foundation
import Testing
import TFCore
@testable import ThumbFree

@MainActor @Suite final class AudioSourceTests {
    let root: URL
    init() throws { root = try TestFiles.folder() }
    deinit { try? FileManager.default.removeItem(at: root) }

    @Test(.timeLimit(.minutes(1))) func fastReplayDeliversTheWholeFileIn20msBlocks() async throws {
        let url = try TestFiles.url("jfk.wav")
        let expected = try WavFile.readMono16k(url: url)
        let source = try FileAudioSource(url: url, realTime: false)
        let (blocks, sink) = AsyncStream.makeStream(of: [Float].self)
        try await source.start { sink.yield($0) }
        var got: [Float] = []
        for await block in blocks {
            #expect(block.count <= FileAudioSource.blockSamples)
            got += block
            if got.count >= expected.count { break }
        }
        source.stop()
        #expect(got == expected)
    }

    // 100 ms of sound, then the source keeps a real-time pace with silence: 300 ms of audio takes about 300 ms.
    @Test(.timeLimit(.minutes(1))) func realTimeReplayKeepsPaceThenSendsSilence() async throws {
        let source = try FileAudioSource(url: TestFiles.wav([Float](repeating: 0.5, count: 1_600), in: root), realTime: true)
        let (blocks, sink) = AsyncStream.makeStream(of: [Float].self)
        let clock = ContinuousClock()
        let start = clock.now
        try await source.start { sink.yield($0) }
        var got: [Float] = []
        for await block in blocks {
            got += block
            if got.count >= 4_800 { break }
        }
        source.stop()
        #expect(clock.now - start >= .milliseconds(270)) // 15 blocks: the last one is due at 280 ms
        #expect(got.prefix(1_600).allSatisfy { $0 > 0.4 })
        #expect(got.dropFirst(1_600).allSatisfy { $0 == 0 })
    }

    // `-TFMicDelayMs`: the source starts that much later, like a mic that is slow to start, so its first block and the
    // take's arming clock (which starts when start() returns) both wait. Stopped meanwhile, it never sends a block.
    @Test(.timeLimit(.minutes(1))) func aStartDelayHoldsTheFirstBlockBack() async throws {
        let url = try TestFiles.url("jfk.wav")
        let source = try FileAudioSource(url: url, realTime: true, startDelay: .milliseconds(300))
        let (blocks, sink) = AsyncStream.makeStream(of: [Float].self)
        let clock = ContinuousClock()
        let start = clock.now
        try await source.start { sink.yield($0) }
        #expect(clock.now - start >= .milliseconds(300))
        var next = blocks.makeAsyncIterator()
        #expect(await next.next() != nil)
        source.stop()

        let stopped = try FileAudioSource(url: url, realTime: true, startDelay: .milliseconds(300))
        let (none, noneSink) = AsyncStream.makeStream(of: [Float].self)
        async let started: Void = stopped.start { noneSink.yield($0) }
        try await Task.sleep(for: .milliseconds(100))
        stopped.stop()
        try await started
        try await Task.sleep(for: .milliseconds(200)) // ten blocks' time
        noneSink.finish()
        var count = 0
        for await _ in none { count += 1 }
        #expect(count == 0)
    }

    @Test func thePreRollKeepsTheLast300ms() {
        var roll = PreRoll()
        roll.push((0..<3_000).map(Float.init))
        roll.push((3_000..<10_000).map(Float.init))
        #expect(roll.drain() == (5_200..<10_000).map(Float.init))
        #expect(roll.drain().isEmpty)
        roll.push([1, 2, 3])
        #expect(roll.drain() == [1, 2, 3])
    }
}
