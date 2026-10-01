import AVFoundation

enum MicError: Error { case permissionDenied, noInput }

/// The microphone through AVAudioEngine, converted to 16 kHz mono Float32.
@MainActor final class MicAudioSource: AudioSource {
    private let engine = AVAudioEngine()
    private var generation = StartGeneration()

    func start(_ onSamples: @escaping @Sendable ([Float]) -> Void) async throws {
        let token = generation.begin()
        guard await AVAudioApplication.requestRecordPermission() else { throw MicError.permissionDenied }
        // The main actor is re-entrant across this await (the permission prompt can stay up for seconds), so a
        // stop() or a newer start() may have run while this call was suspended. Back off instead of installing a
        // second tap on top of one that already exists, or one nobody now owns.
        guard generation.isCurrent(token) else { return }
        do {
            try AudioSessionSetup.configure()
            try AVAudioSession.sharedInstance().setActive(true)
            let input = engine.inputNode
            // Teardown right before install, with nothing left to suspend in between: this can only be reached by
            // the current generation, and every step from here to engine.start() is synchronous.
            input.removeTap(onBus: 0)
            engine.stop()
            let format = input.outputFormat(forBus: 0)
            guard format.sampleRate > 0, let resampler = Resampler(from: format) else { throw MicError.noInput }
            // @Sendable on purpose: the tap runs on AVAudioEngine's own thread (not the real-time render thread), so it
            // must not inherit the main actor, and converting there is allowed.
            input.installTap(onBus: 0, bufferSize: 1_024, format: format) { @Sendable buffer, _ in
                onSamples(resampler.convert(buffer))
            }
            engine.prepare()
            try engine.start()
        } catch {
            stop() // A failed start leaves nothing behind: undo whatever of the above already happened.
            throw error
        }
    }

    func stop() {
        generation.advance()
        engine.inputNode.removeTap(onBus: 0)
        engine.stop()
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
    }
}

/// Which `start()` call, if any, still owns the right to finish setting up after the permission-prompt await:
/// `start()` takes a token with `begin()` before awaiting and checks `isCurrent(_:)` after; `stop()` calls
/// `advance()`. Only the most recent `begin()` is ever current, so an overlapping `start()` or an interleaved
/// `stop()` supersedes whichever call is still waiting. Confined to the main actor, like `MicAudioSource` itself.
struct StartGeneration {
    private var current = 0
    mutating func begin() -> Int { current += 1; return current }
    mutating func advance() { current += 1 }
    func isCurrent(_ token: Int) -> Bool { token == current }
}

/// Converts capture buffers at any rate and channel count to 16 kHz mono Float32. The converter keeps its filter state
/// between buffers, so a stream converts like one long buffer. Used by one thread at a time (the tap's).
final class Resampler: @unchecked Sendable {
    private let converter: AVAudioConverter
    private let target: AVAudioFormat

    init?(from input: AVAudioFormat) {
        guard let target = AVAudioFormat(commonFormat: .pcmFormatFloat32, sampleRate: 16_000, channels: 1, interleaved: false),
              let converter = AVAudioConverter(from: input, to: target) else { return nil }
        self.target = target
        self.converter = converter
    }

    func convert(_ buffer: AVAudioPCMBuffer) -> [Float] {
        let capacity = AVAudioFrameCount((Double(buffer.frameLength) * 16_000 / buffer.format.sampleRate).rounded(.up)) + 64
        guard let out = AVAudioPCMBuffer(pcmFormat: target, frameCapacity: capacity) else { return [] }
        var given = false
        var error: NSError?
        _ = converter.convert(to: out, error: &error) { _, status in
            if given {
                status.pointee = .noDataNow
                return nil
            }
            given = true
            status.pointee = .haveData
            return buffer
        }
        guard error == nil, let channel = out.floatChannelData?[0] else { return [] }
        return Array(UnsafeBufferPointer(start: channel, count: Int(out.frameLength)))
    }
}
