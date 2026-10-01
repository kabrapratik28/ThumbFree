import Foundation

/// One judged 30 ms frame. `loud`: above -55 dBFS, the level no speech is at or under.
public struct FrameVerdict: Sendable, Equatable {
    public let isSpeech: Bool
    public let levelDBFS: Float
    public let loud: Bool

    public init(isSpeech: Bool, levelDBFS: Float) {
        self.isSpeech = isSpeech
        self.levelDBFS = levelDBFS
        loud = levelDBFS > SpeechGate.speechMinDBFS
    }
}

/// The energy speech gate, ported from Android: judges 30 ms frames of a take against a noise floor that follows steady
/// noise. It never drops, trims or changes audio.
///
/// The floor is the 10th percentile of the last 3 s of frame levels, in 1 dB bins, never under -80 dBFS. After the first
/// 3 s a frame is speech at 12 dB over the floor and above -55 dBFS. The first 3 s have no floor to judge against yet:
/// push() reports them as non-speech, and once they end they are judged together against their own floor with 8 dB
/// (`firstSpeechFrames`). A floor guessed at the start either called steady noise speech or missed a word said at once.
public struct SpeechGate: Sendable {
    public static let frameSamples = 480 // 30 ms at 16 kHz
    public static let speechMinDBFS: Float = -55
    static let floorFrames = 100 // 3 s: the floor's window, and the first frames, judged together once it is full
    static let silentCheckFrames = 50 // 1.5 s

    /// True once the take has 5 speech frames (150 ms), so one click is not speech.
    public var hasSpeech: Bool { speechFrames >= 5 }
    /// True when the peak stayed under -60 dBFS for the first 1.5 s: the mic is blocked or muted.
    public var silentMic: Bool { frames >= Self.silentCheckFrames && Self.dbfs(peak) < -60 }
    /// The take's speech frames so far, those of the first 3 s included.
    public var speechFrames: Int { firstSpeechFrames + laterSpeechFrames }
    /// Speech frames of the first 3 s: 8 dB over the floor of those 3 s, or of the take so far while it is shorter.
    public var firstSpeechFrames: Int { firstJudged ?? judgeFirst() }

    private var histogram = [Int](repeating: 0, count: 81) // frames per 1 dB bin, -80 to 0 dBFS
    private var recentBins = [Int](repeating: 0, count: floorFrames) // the bin of each of the last 3 s of frames
    private var firstLevels = [Float](repeating: 0, count: floorFrames) // the level of each frame of the first 3 s
    private var firstJudged: Int? // firstSpeechFrames, fixed once the first 3 s are over
    private var laterSpeechFrames = 0
    private var frames = 0
    private var peak: Float = 0

    public init() {}

    /// Judges one frame: 480 samples (other sizes are judged as they are). Frames of the first 3 s report non-speech.
    public mutating func push(_ frame: ArraySlice<Float>) -> FrameVerdict {
        var sum: Float = 0
        for sample in frame {
            sum += sample * sample
            if frames < Self.silentCheckFrames { peak = max(peak, abs(sample)) }
        }
        let level = Self.dbfs(frame.isEmpty ? 0 : (sum / Float(frame.count)).squareRoot()) // digital silence: -infinity
        return FrameVerdict(isSpeech: judge(level), levelDBFS: level)
    }

    private mutating func judge(_ level: Float) -> Bool {
        let slot = frames % Self.floorFrames
        if frames >= Self.floorFrames { histogram[recentBins[slot]] -= 1 }
        recentBins[slot] = Self.bin(level)
        histogram[recentBins[slot]] += 1
        if frames < Self.floorFrames {
            firstLevels[frames] = level
            frames += 1
            if frames == Self.floorFrames { firstJudged = judgeFirst() }
            return false
        }
        frames += 1
        let speech = Self.isSpeech(level, over: noiseFloor() + 12)
        if speech { laterSpeechFrames += 1 }
        return speech
    }

    private func judgeFirst() -> Int {
        let threshold = noiseFloor() + 8
        return firstLevels[..<min(frames, Self.floorFrames)].filter { Self.isSpeech($0, over: threshold) }.count
    }

    /// The 10th percentile of the last 3 s of frame levels, to 1 dB. Frames under -80 dBFS count as -80.
    private func noiseFloor() -> Int {
        var rank = (min(frames, Self.floorFrames) + 9) / 10 // the rank-th quietest frame
        var bin = 0
        while bin < histogram.count - 1, histogram[bin] < rank {
            rank -= histogram[bin]
            bin += 1
        }
        return bin - 80
    }

    private static func isSpeech(_ level: Float, over threshold: Int) -> Bool {
        level >= Float(threshold) && level > speechMinDBFS
    }

    private static func bin(_ level: Float) -> Int {
        guard level.isFinite else { return level > 0 ? 80 : 0 } // -infinity (and NaN) go to the -80 dBFS bin
        return min(80, max(0, Int(level.rounded(.down)) + 80))
    }

    static func dbfs(_ amplitude: Float) -> Float { 20 * log10(amplitude) }
}
