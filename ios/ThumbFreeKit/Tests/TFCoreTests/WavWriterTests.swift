import Foundation
import Testing
@testable import TFCore

@Suite final class WavWriterTests {
    let dir: URL

    init() throws {
        dir = FileManager.default.temporaryDirectory.appendingPathComponent("WavWriterTests-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
    }

    // Each test gets its own temporary folder (see `dir` above); remove it so runs never pile up in shared temp space.
    deinit { try? FileManager.default.removeItem(at: dir) }

    func u32(_ data: Data, _ offset: Int) -> UInt32 { data.withUnsafeBytes { $0.loadUnaligned(fromByteOffset: offset, as: UInt32.self) } }
    func u16(_ data: Data, _ offset: Int) -> UInt16 { data.withUnsafeBytes { $0.loadUnaligned(fromByteOffset: offset, as: UInt16.self) } }
    func tag(_ data: Data, _ offset: Int) -> String { String(decoding: data[offset..<offset + 4], as: UTF8.self) }
    /// Samples that 16-bit PCM holds exactly.
    func pcm(_ count: Int) -> [Float] { (0..<count).map { Float(Int16(truncatingIfNeeded: $0 * 7 - 30_000)) / 32_768 } }

    @Test func aNewFileIsAnEmptyWav() throws {
        let url = dir.appendingPathComponent("empty.wav")
        #expect(try WavWriter(url: url).finish() == 0)
        let data = try Data(contentsOf: url)
        #expect(data.count == 44)
        #expect(tag(data, 0) == "RIFF" && u32(data, 4) == 36 && tag(data, 8) == "WAVE")
        #expect(tag(data, 12) == "fmt " && u32(data, 16) == 16 && u16(data, 20) == 1 && u16(data, 22) == 1)
        #expect(u32(data, 24) == 16_000 && u32(data, 28) == 32_000 && u16(data, 32) == 2 && u16(data, 34) == 16)
        #expect(tag(data, 36) == "data" && u32(data, 40) == 0)
    }

    @Test func samplesRoundTripThroughTheReader() throws {
        let url = dir.appendingPathComponent("take.wav")
        let writer = try WavWriter(url: url)
        let samples = pcm(1_000)
        try writer.append(Array(samples[..<300]))
        try writer.append(Array(samples[300...]))
        #expect(try writer.finish() == 1_000)
        let data = try Data(contentsOf: url)
        #expect(u32(data, 4) == 2_036 && u32(data, 40) == 2_000)
        #expect(try WavFile.readMono16k(url: url) == samples)
    }

    @Test func jfkRoundTripsExactly() throws {
        let jfk = try WavFile.readMono16k(url: TestAudio.url("jfk.wav"))
        let url = dir.appendingPathComponent("jfk.wav")
        let writer = try WavWriter(url: url)
        try writer.append(jfk)
        #expect(try writer.finish() == jfk.count)
        #expect(try WavFile.readMono16k(url: url) == jfk)
    }

    // Out-of-range samples clip, and a NaN is silence.
    @Test func samplesAreRoundedAndClipped() throws {
        let url = dir.appendingPathComponent("clip.wav")
        let writer = try WavWriter(url: url)
        try writer.append([1.5, -1.5, .nan, .infinity, 0.25, 1 / 65_536])
        _ = try writer.finish()
        #expect(try WavFile.readMono16k(url: url) == [32_767 / 32_768, -1, 0, 32_767 / 32_768, 0.25, 1 / 32_768])
    }

    // sync() patches the header, so what was synced plays even if the process dies before finish().
    @Test func syncLeavesAPlayablePrefix() throws {
        let url = dir.appendingPathComponent("live.wav")
        let writer = try WavWriter(url: url)
        let samples = pcm(1_500)
        try writer.append(Array(samples[..<1_000]))
        try writer.sync()
        #expect(try WavFile.readMono16k(url: url) == Array(samples[..<1_000]))
        try writer.append(Array(samples[1_000...])) // not synced yet: the header still says 1,000
        #expect(try WavFile.readMono16k(url: url).count == 1_000)
        #expect(try writer.finish() == 1_500)
        #expect(try WavFile.readMono16k(url: url) == samples)
    }

    // A writer that never syncs or finishes (the process was killed) leaves a header that says 0; repair() finds the audio.
    @Test func aKilledWriterIsRepaired() throws {
        let url = dir.appendingPathComponent("killed.wav")
        do {
            let writer = try WavWriter(url: url)
            try writer.append(pcm(16_000))
        }
        #expect(try WavFile.readMono16k(url: url).isEmpty)
        #expect(try WavWriter.repair(url: url) == 16_000)
        #expect(try WavFile.readMono16k(url: url) == pcm(16_000))
    }

    @Test func repairFixesAFileCutAtAnyByte() throws {
        let original = dir.appendingPathComponent("original.wav")
        let writer = try WavWriter(url: original)
        try writer.append(pcm(16_000))
        _ = try writer.finish()
        let full = try Data(contentsOf: original)
        for cut in [44, 45, 1_000, 1_001, 32_043, 32_044] {
            let url = dir.appendingPathComponent("cut-\(cut).wav")
            try full.prefix(cut).write(to: url)
            let expected = (cut - 44) / 2
            #expect(try WavWriter.repair(url: url) == expected)
            let data = try Data(contentsOf: url)
            #expect(data.count == 44 + 2 * expected)
            #expect(u32(data, 40) == UInt32(2 * expected) && u32(data, 4) == UInt32(36 + 2 * expected))
        }
    }

    // Power lost before the header reached the disk: an empty or cut header becomes a valid empty WAV.
    @Test func repairOfAShortFileGivesAnEmptyWav() throws {
        let empty = dir.appendingPathComponent("reference.wav")
        _ = try WavWriter(url: empty).finish()
        let reference = try Data(contentsOf: empty)
        for size in [0, 20] {
            let url = dir.appendingPathComponent("short-\(size).wav")
            try reference.prefix(size).write(to: url)
            #expect(try WavWriter.repair(url: url) == 0)
            #expect(try Data(contentsOf: url) == reference)
        }
    }

    // A failed write (for example ENOSPC) must not corrupt later samples: append() refuses to write again once a
    // write has failed, and everything written before the failure is still kept.
    @Test func appendAfterAFailedAppendThrowsAndWritesNothing() throws {
        let url = dir.appendingPathComponent("write-failure.wav")
        let writer = try WavWriter(url: url)
        try writer.append(pcm(10))
        writer.debugFailNextWrite = true
        #expect(throws: (any Error).self) { try writer.append(pcm(5)) }
        #expect(throws: WavWriter.Error.self) { try writer.append(pcm(5)) } // poisoned: throws at once, writes nothing
        #expect(try writer.finish() == 10)
    }
}
