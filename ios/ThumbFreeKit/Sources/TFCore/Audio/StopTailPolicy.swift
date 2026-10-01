/// The stop tail, ported from Android: how long capture keeps recording after the stop tap, and how many zeros the
/// engine's copy of the last chunk gets. Parakeet's last word needs the silence after it: ending early without a fill
/// broke final words; zeros fixed them with WER unchanged.
///
/// - No tail when the last 500 ms judged had no sound (a speech frame or a frame above -55 dBFS) and at most 800 samples
///   are still unjudged: the user had already paused (`quiet`).
/// - Otherwise the tail ends once 100 ms after the stop and 100 ms after the last sound are judged (`hangover`), or at
///   350 ms on the clock (`cap`). A hangover end gets zeros up to 5,760 samples past the stop, for the engine only.
///
/// The capture actor pushes every frame, calls stop() at the tap, then check() after each frame and on a timer.
public struct StopTailPolicy: Sendable, Equatable {
    public enum End: String, Sendable { case quiet, hangover, cap }

    public static let quietSamples = 8_000 // 500 ms
    public static let floorSamples = 1_600 // 100 ms after the stop
    public static let hangoverSamples = 1_600 // 100 ms after the last sound
    public static let unjudgedSamples = 800
    public static let capMs = 350
    /// The 350 ms cap rounded up to whole 20 ms reads on Android: the length its sweep measured.
    public static let fillSamples = 5_760

    public private(set) var stopSample: Int?
    public private(set) var end: End?
    /// A frame after the stop had sound, so a transcription started at the stop must run again with the tail.
    public private(set) var soundAfterStop = false
    private var stopMs = 0
    private var judged = 0 // samples judged, in whole frames
    private var lastSound = 0 // the sample after the last frame with sound

    public init() {}

    public mutating func push(_ verdict: FrameVerdict) {
        judged += SpeechGate.frameSamples
        guard verdict.isSpeech || verdict.loud else { return }
        lastSound = judged
        if let stopSample, judged > stopSample { soundAfterStop = true }
    }

    /// The stop tap. `received`: every sample captured so far, judged or not. Returns `.quiet` when there is no tail.
    public mutating func stop(nowMs: Int, received: Int) -> End? {
        guard stopSample == nil else { return end }
        stopSample = received
        stopMs = nowMs
        if judged - lastSound >= Self.quietSamples, received - judged <= Self.unjudgedSamples { end = .quiet }
        return end
    }

    /// While the tail runs: returns how it ended once that is known, and the same answer on every later call.
    public mutating func check(nowMs: Int, received: Int) -> End? {
        guard let stopSample, end == nil else { return end }
        if nowMs - stopMs >= Self.capMs {
            end = .cap
        } else if judged - stopSample >= Self.floorSamples, judged - lastSound >= Self.hangoverSamples,
                  received - judged <= Self.unjudgedSamples {
            end = .hangover
        }
        return end
    }

    /// Zeros to append after `endSample` (the take's last sample) in the engine's copy of the last chunk.
    public func zeroFill(endSample: Int) -> Int {
        guard end == .hangover, let stopSample else { return 0 }
        return max(0, stopSample + Self.fillSamples - endSample)
    }
}
