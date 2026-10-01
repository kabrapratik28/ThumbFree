import AVFoundation
import Testing
@testable import ThumbFree

// The microphone itself needs a permission prompt, so these tests cover what it is built from.
@Suite struct MicAudioTests {
    @Test func theAudioSessionFollowsD19() throws {
        let session = AVAudioSession.sharedInstance()
        try AudioSessionSetup.configure(session)
        #expect(session.category == .playAndRecord)
        #expect(session.mode == .default)
        #expect(session.categoryOptions.contains(.mixWithOthers))
        #expect(session.categoryOptions.contains(.allowBluetoothA2DP))
        #expect(session.categoryOptions.contains(.defaultToSpeaker))
        #expect(!session.categoryOptions.contains(.allowBluetoothHFP)) // never the call-quality Bluetooth mic
    }

    // The stop tail is judged per block, so the session asks for 20 ms blocks.
    @Test func theAudioSessionAsksFor20msBlocks() throws {
        let session = AVAudioSession.sharedInstance()
        try AudioSessionSetup.configure(session)
        #expect(abs(session.preferredIOBufferDuration - 0.02) < 0.001)
    }

    // One second of a 1 kHz tone at 48 kHz stereo, in 20 ms buffers, comes out as one second at 16 kHz mono:
    // 16,000 samples (less the converter's few samples of latency) and 2,000 zero crossings.
    @Test func theResamplerTurns48kStereoInto16kMono() throws {
        let input = try #require(AVAudioFormat(commonFormat: .pcmFormatFloat32, sampleRate: 48_000, channels: 2, interleaved: false))
        let resampler = try #require(Resampler(from: input))
        var out: [Float] = []
        for block in 0..<50 {
            let buffer = try #require(AVAudioPCMBuffer(pcmFormat: input, frameCapacity: 960))
            buffer.frameLength = 960
            let channels = try #require(buffer.floatChannelData)
            for channel in 0..<2 {
                for i in 0..<960 { channels[channel][i] = 0.5 * sin(2 * .pi * 1_000 * Float(block * 960 + i) / 48_000) }
            }
            out += resampler.convert(buffer)
        }
        let crossings = zip(out, out.dropFirst()).filter { ($0 < 0) != ($1 < 0) }.count
        #expect(abs(out.count - 16_000) <= 64)
        #expect(abs(crossings - 2_000) <= 20)
        #expect(abs((out.map(abs).max() ?? 0) - 0.5) < 0.02)
    }

    // start()'s only suspension point is the permission prompt. These exercise the token that decides, once it
    // resumes, whether it still owns the right to install a tap, with no real mic, permission prompt, or engine.

    @Test func aStopWhileStartIsAwaitingSupersedesIt() {
        var generation = StartGeneration()
        let token = generation.begin() // start() begins, then suspends awaiting the permission prompt
        generation.advance()           // stop() arrives while start() is still waiting
        #expect(!generation.isCurrent(token)) // start() resumes to find itself superseded: it returns without installing a tap
    }

    @Test func anOverlappingStartSupersedesTheOlderOne() {
        var generation = StartGeneration()
        let older = generation.begin() // the first start() call, now awaiting the permission prompt
        let newer = generation.begin() // a second start() call fires before the first one resumes
        #expect(!generation.isCurrent(older)) // the older call backs off instead of installing a second tap
        #expect(generation.isCurrent(newer))  // only the newest call proceeds
    }
}
