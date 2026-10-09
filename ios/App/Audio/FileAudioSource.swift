import Foundation
import TFCore

/// Replays a 16 kHz mono WAV in 20 ms blocks. Real time: then silence, like a quiet room, until stopped (the UI tests
/// and `-TFAudioFile`). Fast: the file once, as fast as the reader takes it, then nothing (unit tests). A start delay
/// (`-TFMicDelayMs`) makes it a mic that is slow to start.
@MainActor final class FileAudioSource: AudioSource {
    static let blockSamples = 320

    private let samples: [Float]
    private let realTime: Bool
    private let startDelay: Duration
    private var task: Task<Void, Never>?
    private var stopped = false

    init(url: URL, realTime: Bool, startDelay: Duration = .zero) throws {
        samples = try WavFile.readMono16k(url: url)
        self.realTime = realTime
        self.startDelay = startDelay
    }

    func start(_ onSamples: @escaping @Sendable ([Float]) -> Void) async throws {
        // Before start() returns, as a real mic's start takes its time: the take's arming clock waits for it too.
        if startDelay > .zero { try await Task.sleep(for: startDelay) }
        guard !stopped else { return } // stopped while it waited: nothing would ever stop the replay
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
        stopped = true
        task?.cancel()
        task = nil
    }
}
