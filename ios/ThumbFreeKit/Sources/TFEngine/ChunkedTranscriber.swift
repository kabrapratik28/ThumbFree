import Foundation
import TFCore

/// Transcribes one take while it records: a `LiveTake` whose engine jobs run here one at a time,
/// each with the speech check beside it (Silero on the CPU while the Encoder runs; it fails open, and a job whose
/// check hears no speech keeps empty text). Make one per take. The app's SessionHost feeds it the take's audio, the
/// stop and the tail's clock, and awaits `finish()`.
public actor ChunkedTranscriber {
    public struct Result: Sendable, Equatable {
        public let outcome: LiveTake.Outcome
        /// From the `stop` call to the result being ready.
        public let stopToResultMs: Double
    }

    /// Distinct from the engine's own errors, so a `LiveTake` bug that leaves `outcome` nil is never mistaken for a
    /// user cancel.
    public enum Error: Swift.Error, Equatable {
        case noOutcome
    }

    private var take: LiveTake
    private let transcribe: @Sendable ([Float]) async throws -> String
    private let hasSpeech: @Sendable ([Float]) async -> Bool
    private var worker: Task<Void, Never>?
    private var failure: (any Swift.Error)?
    private var cancelled = false
    private var stoppedAt: ContinuousClock.Instant?

    /// `transcribe`: the engine's text for up to 240,000 samples (`ParakeetEngine.transcribe`). `hasSpeech`: the speech
    /// check (`SpeechCheck.hasSpeech`). `speculateAfterMs` nil turns speculation off and `optimistic` false waits for
    /// the tail: the replay harness compares them; the app keeps the defaults.
    public init(speculateAfterMs: Int? = 300, optimistic: Bool = true,
                transcribe: @escaping @Sendable ([Float]) async throws -> String,
                hasSpeech: @escaping @Sendable ([Float]) async -> Bool = { _ in true }) {
        take = LiveTake(speculateAfterMs: speculateAfterMs, optimistic: optimistic)
        self.transcribe = transcribe
        self.hasSpeech = hasSpeech
    }

    /// 16 kHz mono samples, any block size, in order: the take, then the tail after the stop. Dropped once cancelled
    /// or failed, so a caller that keeps recording after that does not buffer audio nobody will transcribe.
    public func append(_ samples: [Float]) {
        guard !cancelled, failure == nil else { return }
        take.append(samples)
        pump()
    }

    /// The stop tap. Returns `.quiet` when there is no tail; otherwise keep appending and calling `check`.
    public func stop(nowMs: Int) -> StopTailPolicy.End? {
        if stoppedAt == nil { stoppedAt = .now }
        defer { pump() }
        return take.stop(nowMs: nowMs)
    }

    /// After each tail block and on a timer. Returns how the tail ended once it has.
    public func check(nowMs: Int) -> StopTailPolicy.End? {
        defer { pump() }
        return take.check(nowMs: nowMs)
    }

    /// The take's texts, once every job they need is done. Call it when `stop` or `check` has returned how the tail
    /// ended; called earlier (an interruption), it ends the tail where the audio stopped. Throws the first engine
    /// error, CancellationError after `cancel()`, or `Error.noOutcome` if idle leaves no outcome (a `LiveTake` bug).
    public func finish() async throws -> Result {
        if stoppedAt == nil { stoppedAt = .now }
        take.endTail()
        pump()
        await idle()
        if cancelled { throw CancellationError() }
        if let failure { throw failure }
        // Idle with no job left to hand out means every text the take needs is in.
        guard let outcome = take.outcome, let stoppedAt else { throw Error.noOutcome }
        return Result(outcome: outcome, stopToResultMs: ParakeetEngine.ms(.now - stoppedAt))
    }

    /// The closed chunks' texts so far, in order (History's partial text; see `LiveTake.partialTexts`).
    public var partialTexts: [String] { take.partialTexts }

    /// Drops the take: nothing more reaches the engine, a job in flight is ignored, and `finish()` throws. Cancels the
    /// worker too, so a wait inside the engine closure that honors it stops at once instead of at its own pace.
    public func cancel() {
        cancelled = true
        worker?.cancel()
    }

    /// Waits until no job runs or waits. The replay harness uses it to keep pace in simulated time.
    public func idle() async {
        while let worker { await worker.value }
    }

    /// Starts a worker only once a job is ready, so a call between jobs never spins one up with nothing to run.
    private func pump() {
        guard worker == nil, !cancelled, failure == nil, let job = take.nextJob() else { return }
        worker = Task { await work(job) }
    }

    private func work(_ job: LiveTake.Job) async {
        let check = hasSpeech, transcribe = transcribe
        async let heard = check(job.samples)
        do {
            let text = try await transcribe(job.engineInput)
            let speech = await heard
            // A result for a job cancel() already dropped is not this take's to keep.
            if !cancelled { take.finished(job, text: speech ? text : "", heard: speech) }
        } catch {
            _ = await heard
            failure = error
        }
        worker = nil
        pump()
    }
}
