import Foundation

/// Where the planner cuts, in samples into the open chunk. One place for the constants, so a sweep can change them.
public struct ChunkConfig: Sendable, Equatable {
    /// From here, cut in the middle of the latest `silenceFrames` of true silence (non-speech at or under -55 dBFS).
    public var silenceFrom: Int
    public var silenceFrames: Int
    /// From here, cut in the middle of the first pause of `pauseFrames` non-speech frames of any level.
    public var pauseFrom: Int
    public var pauseFrames: Int
    /// No chunk is longer than this. At the last frame that fits with no cut, cut in the middle of the quietest
    /// `quietWindowFrames` window that starts at or after `quietFrom`.
    public var maxSamples: Int
    public var quietFrom: Int
    public var quietWindowFrames: Int

    /// For the Encoder's 15 s window (240,000 samples): silence cuts from 8 s, 300 ms pauses from 11 s, a forced cut
    /// before 14.5 s. Chunks of 8 s or more keep the model's accuracy (Android lost about 2 WER points with phrase-sized
    /// chunks), and 14.5 s leaves room for the stop tail's zero fill (StopTailPolicy.fillSamples).
    public static let window15s = ChunkConfig(
        silenceFrom: 128_000, silenceFrames: 33, pauseFrom: 176_000, pauseFrames: 10,
        maxSamples: 232_000, quietFrom: 160_000, quietWindowFrames: 4)
}

/// Samples [start, end) of a take. `mayHoldSpeech`: a frame was speech or above -55 dBFS. A chunk without one never
/// reaches the engine (it gets empty text at once); any other chunk goes to the engine and the Silero check.
public struct Chunk: Sendable, Equatable {
    public let start: Int
    public let end: Int
    public let mayHoldSpeech: Bool

    public init(start: Int, end: Int, mayHoldSpeech: Bool) {
        self.start = start
        self.end = end
        self.mayHoldSpeech = mayHoldSpeech
    }
}

/// Cuts a take into chunks while it records, so each can be transcribed before the stop (ported from Android, adapted
/// to the 15 s window). Fed each frame's verdict from SpeechGate. Chunks tile the take with no overlap and no gap, and
/// every cut falls on a frame edge. Rules, checked on every frame, first match wins: see ChunkConfig.
public struct ChunkPlanner: Sendable {
    private let config: ChunkConfig
    private var power: [Double] // mean square of each frame of the open chunk
    private var loud: [Bool] // each frame of the open chunk was speech or above -55 dBFS
    private var frames = 0 // in the open chunk
    private var start = 0 // the open chunk's first sample
    private var nonSpeechRun = 0
    private var quietRun = 0 // non-speech frames at or under -55 dBFS, in a row

    public init(config: ChunkConfig = .window15s) {
        self.config = config
        let capacity = config.maxSamples / SpeechGate.frameSamples + 1
        power = [Double](repeating: 0, count: capacity)
        loud = [Bool](repeating: false, count: capacity)
    }

    /// Adds the next frame. Returns the chunk it closes, which can go to the engine at once, or nil.
    public mutating func push(_ verdict: FrameVerdict) -> Chunk? {
        power[frames] = pow(10, Double(verdict.levelDBFS) / 10)
        loud[frames] = verdict.isSpeech || verdict.loud
        frames += 1
        nonSpeechRun = verdict.isSpeech ? 0 : nonSpeechRun + 1
        quietRun = verdict.isSpeech || verdict.loud ? 0 : quietRun + 1
        if paused(quietRun, config.silenceFrames, from: config.silenceFrom) {
            return cut(at: frames - config.silenceFrames / 2)
        }
        if paused(nonSpeechRun, config.pauseFrames, from: config.pauseFrom) {
            return cut(at: frames - config.pauseFrames / 2)
        }
        if (frames + 1) * SpeechGate.frameSamples > config.maxSamples { return cut(at: quietestMiddle()) }
        return nil
    }

    /// Closes the last chunk at the stop. `endSample` is the take's length, so it includes a final partial frame.
    public func finish(endSample: Int) -> Chunk {
        Chunk(start: start, end: max(start, endSample), mayHoldSpeech: loud[..<frames].contains(true))
    }

    /// A whole take cut as it was while it recorded: every full frame through a fresh gate and planner, the last chunk
    /// to the last sample. Transcribe again uses it, so a take is cut where the live take was cut, up to 16-bit
    /// rounding (the WAV holds 16-bit PCM). The WAV has no stop tail zeros (they are for the engine only), so give the
    /// last chunk StopTailPolicy.fillSamples zeros.
    public static func chunks(of samples: [Float], config: ChunkConfig = .window15s) -> [Chunk] {
        var gate = SpeechGate()
        var planner = ChunkPlanner(config: config)
        var chunks: [Chunk] = []
        var at = 0
        while at + SpeechGate.frameSamples <= samples.count {
            if let chunk = planner.push(gate.push(samples[at..<at + SpeechGate.frameSamples])) { chunks.append(chunk) }
            at += SpeechGate.frameSamples
        }
        let last = planner.finish(endSample: samples.count)
        if last.end > last.start { chunks.append(last) }
        return chunks
    }

    private mutating func cut(at frame: Int) -> Chunk {
        let frame = min(max(frame, 1), frames)
        let chunk = Chunk(start: start, end: start + frame * SpeechGate.frameSamples, mayHoldSpeech: loud[..<frame].contains(true))
        for i in frame..<frames {
            power[i - frame] = power[i]
            loud[i - frame] = loud[i]
        }
        frames -= frame
        start = chunk.end
        return chunk
    }

    /// True once `run` covers the last `pause` frames and their middle is `from` samples or more into the chunk.
    private func paused(_ run: Int, _ pause: Int, from: Int) -> Bool {
        run >= pause && (frames - pause / 2) * SpeechGate.frameSamples >= from
    }

    /// The middle of the quietest window that starts at or after `quietFrom` (the first one on a tie).
    private func quietestMiddle() -> Int {
        let window = config.quietWindowFrames
        let first = (config.quietFrom + SpeechGate.frameSamples - 1) / SpeechGate.frameSamples
        guard first <= frames - window else { return frames - window / 2 }
        var best = first
        var bestPower = Double.infinity
        for w in first...(frames - window) {
            let sum = power[w..<w + window].reduce(0, +)
            if sum < bestPower {
                best = w
                bestPower = sum
            }
        }
        return best + window / 2
    }
}
