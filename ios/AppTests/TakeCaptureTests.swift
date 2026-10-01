import Foundation
import Testing
import TFCore
@testable import ThumbFree

// Levels: 0.1 is -20 dBFS (loud), 0.001 is -60 dBFS (quiet noise), 0 is digital silence.
@MainActor @Suite final class TakeCaptureTests {
    let root: URL
    init() throws { root = try TestFiles.folder() }
    deinit { try? FileManager.default.removeItem(at: root) }

    func level(_ value: Float, seconds: Double) -> [Float] { [Float](repeating: value, count: Int(seconds * 16_000)) }

    // The live transcriber hears exactly what the WAV gets, in the same order: the pre-roll first.
    @Test func aTakeStartsWithThePreRollAndItsTranscriberHearsTheWav() throws {
        let capture = TakeCapture()
        try capture.consume(level(0.1, seconds: 1)) // before the tap: only the last 300 ms is kept
        let url = root.appendingPathComponent("audio.wav")
        var heard: [Float] = []
        try capture.begin(UUID(), wavURL: url) { heard += $0 }
        try capture.consume(level(0.2, seconds: 0.5))
        let done = capture.finish()
        #expect(done.samples == 4_800 + 8_000)
        #expect(heard.count == 12_800)
        #expect(heard.prefix(4_800).allSatisfy { $0 == 0.1 })
        #expect(heard.suffix(8_000).allSatisfy { $0 == 0.2 })
        #expect(try WavFile.readMono16k(url: url).count == 12_800)
        #expect(capture.takeID == nil)
        try capture.consume(level(0.3, seconds: 0.1)) // after the take: the pre-roll again, never the old transcriber
        #expect(heard.count == 12_800)
    }

    @Test func aTakeWithSoundMayHoldSpeech() throws {
        let capture = TakeCapture()
        try capture.begin(UUID(), wavURL: root.appendingPathComponent("audio.wav")) { _ in }
        try capture.consume(level(0.1, seconds: 1) + level(0, seconds: 0.6))
        #expect(capture.finish().mayHoldSpeech)
    }

    @Test func aSilentTakeMayNotHoldSpeech() throws {
        let capture = TakeCapture()
        try capture.begin(UUID(), wavURL: root.appendingPathComponent("audio.wav")) { _ in }
        try capture.consume(level(0, seconds: 0.5))
        #expect(!capture.finish().mayHoldSpeech)
    }

    // After the gate's first 3 s, a loud second is speech; the silence clock counts from its end.
    @Test func theSilenceClockCountsFromTheLastSpeech() throws {
        let capture = TakeCapture()
        try capture.begin(UUID(), wavURL: root.appendingPathComponent("audio.wav")) { _ in }
        try capture.consume(level(0.001, seconds: 3) + level(0.1, seconds: 1) + level(0.001, seconds: 1))
        #expect(capture.recordedMs == 5_000)
        #expect(abs(capture.msSinceSpeech - 1_000) <= 40)
    }
}
