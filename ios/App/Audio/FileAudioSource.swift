import Foundation
import TFCore

/// Replays a 16 kHz mono WAV in 20 ms blocks. Real time: then silence, like a quiet room, until stopped (the UI tests
/// and `-TFAudioFile`). Fast: the file once, as fast as the reader takes it, then nothing (unit tests).
@MainActor final class FileAudioSource: AudioSource {
    static let blockSamples = 320

    private let samples: [Float]
    private let realTime: Bool
    private var task: Task<Void, Never>?

    init(url: URL, realTime: Bool) throws {
        samples = try WavFile.readMono16k(url: url)
        self.realTime = realTime
    }

    func start(_ onSamples: @escaping @Sendable ([Float]) -> Void) async throws {
        let samples = samples, realTime = realTime, size = Self.blockSamples
        task = Task.detached {
            let clock = ContinuousClock()
            let begin = clock.now
            var block = 0
            while !Task.isCancelled {
                let start = block * size
                if start < samples.count {
                    onSamples(Array(samples[start..<min(start + size, samples.count)]))
                } else if realTime {
                    onSamples([Float](repeating: 0, count: size))
                } else {
                    return
                }
                block += 1
                if realTime { try? await clock.sleep(until: begin + .milliseconds(20 * block)) } else { await Task.yield() }
            }
        }
    }

    func stop() {
        task?.cancel()
        task = nil
    }
}
