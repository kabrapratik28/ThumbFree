/// A take cut into chunks and transcribed while it records, as values in and values out: its caller
/// feeds the audio, the stop and the stop tail's clock, runs each job `nextJob()` hands out on the engine, one at a
/// time, and reports its text. The gate, planner and stop tail judge the audio; this type decides the engine jobs.
///
/// - Each closed chunk with sound (a speech frame or a frame above -55 dBFS) is a job at once, in order. A chunk
///   without sound gets empty text and no job.
/// - The final window is the open chunk, or the last closed chunk and the open chunk together when both have sound and
///   fit in 14.5 s: the Encoder pays for a 15 s window either way, and the seam between them goes away.
/// - Speculative finish: after `speculateAfterMs` without sound, when no chunk waits, the final window so far is a
///   job, as if the take ended there. Like every job, one at a time. A new one starts only once the last one no
///   longer stands: sound came after its end, or the final window starts elsewhere (in a long pause the last closed
///   chunk and the open chunk can outgrow 14.5 s together, and the window drops the closed chunk).
/// - Optimistic stop: at the stop, unless a speculation stands, the final window up to the stop is a job at once,
///   while the tail still records.
/// - When the tail ends, the text picked at the stop stands if its window still starts at the same sample and no sound
///   came after its end. Otherwise the final window runs again with the tail.
/// - Speculative and optimistic runs get `StopTailPolicy.fillSamples` zeros; a run after the tail gets the policy's
///   zero fill. The zeros are for the engine only.
public struct LiveTake: Sendable {
    public enum Kind: String, Sendable { case chunk, speculation, optimistic, afterTail }

    /// One engine run: `samples` (the window's audio, which the speech check hears), then `zeros` zeros.
    public struct Job: Sendable {
        public let id: Int
        public let kind: Kind
        public let start: Int
        public let end: Int
        public let zeros: Int
        public let samples: [Float]
        public var engineInput: [Float] { samples + [Float](repeating: 0, count: zeros) }
    }

    public struct Outcome: Sendable, Equatable {
        /// How the final window got its text: a speculation from before the stop, the run started at the stop, a run
        /// after the tail, or no run because the final window had no sound.
        public enum Path: String, Sendable, CaseIterable { case speculation, optimistic, afterTail, silent }
        /// One text per chunk, in order; two merged chunks count once. Empty for a chunk without sound or without speech.
        public let texts: [String]
        /// Some window passed the speech check.
        public let heard: Bool
        public let path: Path
        /// Engine runs for the whole take, speculation included, and those started after the stop.
        public let engineRuns: Int
        public let runsAfterStop: Int
    }

    private struct Run: Sendable {
        let id: Int
        let kind: Kind
        let start: Int
        let end: Int
        let zeros: Int
    }

    private let maxSamples: Int
    private let speculateFrames: Int?
    private let optimistic: Bool
    private var gate = SpeechGate()
    private var planner: ChunkPlanner
    private var tail = StopTailPolicy()

    private var audio: [Float] = [] // the take from `audioStart` on
    private var audioStart = 0
    private var judged = 0 // samples in the whole frames judged so far
    private var lastSound = 0 // one past the last frame with sound
    private var quietFrames = 0 // frames without sound since then
    private var closed: [(run: Run, sound: Bool)] = []
    private var results: [Int: (text: String, heard: Bool)] = [:]
    private var handedOut: Set<Int> = []
    private var running: Int?
    private var lastID = 0
    private var speculation: Run?
    private var stopped = false
    private var early: Run? // picked at the stop: a standing speculation or the optimistic run
    private var final: Run? // picked when the tail ends
    private var finalStart: Int?
    private var path: Outcome.Path?
    private var engineRuns = 0
    private var runsAfterStop = 0

    /// `speculateAfterMs` nil turns speculation off; `optimistic` false waits for the tail (the replay harness compares).
    public init(config: ChunkConfig = .window15s, speculateAfterMs: Int? = 300, optimistic: Bool = true) {
        maxSamples = config.maxSamples
        planner = ChunkPlanner(config: config)
        let frameMs = SpeechGate.frameSamples / 16 // 16 samples a millisecond at 16 kHz
        speculateFrames = speculateAfterMs.map { max(1, ($0 + frameMs - 1) / frameMs) }
        self.optimistic = optimistic
    }

    /// Samples held in memory: the last closed chunk on, and any chunk still waiting for the engine.
    var bufferedSamples: Int { audio.count }
    private var received: Int { audioStart + audio.count }
    private var openStart: Int { closed.last?.run.end ?? 0 }

    /// 16 kHz mono samples, any block size: the take before the stop, then the tail. Ignored once the tail has ended.
    public mutating func append(_ samples: [Float]) {
        guard path == nil else { return }
        audio += samples
        while received - judged >= SpeechGate.frameSamples {
            let at = judged - audioStart
            let verdict = gate.push(audio[at..<at + SpeechGate.frameSamples])
            judged += SpeechGate.frameSamples
            tail.push(verdict)
            if verdict.isSpeech || verdict.loud {
                lastSound = judged
                quietFrames = 0
            } else {
                quietFrames += 1
            }
            if let chunk = planner.push(verdict) { close(chunk) }
        }
    }

    /// The stop tap. Returns `.quiet` when there is no tail (StopTailPolicy).
    public mutating func stop(nowMs: Int) -> StopTailPolicy.End? {
        guard !stopped else { return tail.end }
        stopped = true
        let end = tail.stop(nowMs: nowMs, received: received)
        let start = finalWindowStart(end: received)
        if let speculation, stands(speculation, start: start) {
            early = speculation
        } else if optimistic, lastSound > start {
            early = newRun(.optimistic, start: start, end: received, zeros: StopTailPolicy.fillSamples)
        }
        if end != nil { settle() }
        return end
    }

    /// While the tail records: after each block and on a timer. Returns how the tail ended once it has.
    public mutating func check(nowMs: Int) -> StopTailPolicy.End? {
        guard stopped, path == nil else { return tail.end }
        let end = tail.check(nowMs: nowMs, received: received)
        if end != nil { settle() }
        return end
    }

    /// Ends a tail nobody waited for (the take was interrupted): no more audio comes. A take nobody stopped is stopped
    /// here first, so its outcome reads as for a stop tapped where the audio ends. With the optimistic stop on (the
    /// default), `.optimistic` then means the final window ran from here with `StopTailPolicy.fillSamples` zeros
    /// (they help the last word, and the window has room for them), and `.speculation` a speculation that still
    /// stands. An `.afterTail` run gets no zero fill here, since the tail ends at its cap.
    public mutating func endTail() {
        _ = stop(nowMs: 0)
        _ = check(nowMs: Int.max / 2) // any time past the 350 ms cap
    }

    /// The next engine run, or nil while one runs or nothing is due. Chunks first, in order; then the final window;
    /// before the stop, a speculation.
    public mutating func nextJob() -> Job? {
        guard running == nil else { return nil }
        var next = pendingChunk() ?? pendingFinal()
        if next == nil { next = newSpeculation() }
        guard let run = next else { return nil }
        running = run.id
        handedOut.insert(run.id)
        engineRuns += 1
        if stopped { runsAfterStop += 1 }
        if run.kind == .speculation { speculation = run }
        return Job(id: run.id, kind: run.kind, start: run.start, end: run.end, zeros: run.zeros,
                   samples: Array(audio[(run.start - audioStart)..<(run.end - audioStart)]))
    }

    /// The text of the job `nextJob()` handed out. `heard`: it passed the speech check (else `text` is empty).
    public mutating func finished(_ job: Job, text: String, heard: Bool) {
        guard job.id == running else { return }
        running = nil
        results[job.id] = (text, heard)
    }

    /// The take's texts, once the tail has ended and every text it needs is in.
    public var outcome: Outcome? {
        guard let path, let finalStart else { return nil }
        var texts: [String] = []
        var heard = false
        for entry in closed where entry.run.start != finalStart { // a merged chunk counts once, in the final window
            guard let result = results[entry.run.id] else { return nil }
            texts.append(result.text)
            heard = heard || result.heard
        }
        if let final {
            guard let result = results[final.id] else { return nil }
            texts.append(result.text)
            heard = heard || result.heard
        } else {
            texts.append("")
        }
        return Outcome(texts: texts, heard: heard, path: path, engineRuns: engineRuns, runsAfterStop: runsAfterStop)
    }

    /// The closed chunks' texts that are in, in order, up to the first still out: History's partial text while the
    /// take runs, which a cancelled or failed take keeps.
    public var partialTexts: [String] {
        var texts: [String] = []
        for entry in closed {
            guard let result = results[entry.run.id] else { break }
            texts.append(result.text)
        }
        return texts
    }

    private mutating func close(_ chunk: Chunk) {
        let run = newRun(.chunk, start: chunk.start, end: chunk.end, zeros: 0)
        closed.append((run, chunk.mayHoldSpeech))
        if !chunk.mayHoldSpeech { results[run.id] = ("", false) }
        trim()
    }

    private mutating func newRun(_ kind: Kind, start: Int, end: Int, zeros: Int) -> Run {
        lastID += 1
        return Run(id: lastID, kind: kind, start: start, end: end, zeros: zeros)
    }

    /// Where the final window starts for a take that ends at `end` (see the type's comment).
    private func finalWindowStart(end: Int) -> Int {
        guard let previous = closed.last, previous.sound, lastSound > openStart,
              end - previous.run.start <= maxSamples else { return openStart }
        return previous.run.start
    }

    /// A run's text stands for a final window starting at `start` when it started there and no sound came after it.
    private func stands(_ run: Run, start: Int) -> Bool {
        run.start == start && lastSound <= run.end
    }

    /// The tail has ended: the text picked at the stop stands, or the final window runs again with the tail.
    private mutating func settle() {
        let start = finalWindowStart(end: received)
        finalStart = start
        if let early, stands(early, start: start) {
            final = early
            path = early.kind == .speculation ? .speculation : .optimistic
        } else if lastSound > start {
            final = newRun(.afterTail, start: start, end: received, zeros: tail.zeroFill(endSample: received))
            path = .afterTail
        } else {
            path = .silent
        }
    }

    /// The first chunk with sound not yet run. After the stop, a chunk merged into the final window is skipped.
    /// ponytail: a closed chunk always runs on its own, even when a speculation or the optimistic run already heard the
    /// same audio (one extra run when the planner cuts in the pause that started a speculation, or during the tail);
    /// reuse that text if the runs per take or the CPU path's stops show the extra run matters.
    private func pendingChunk() -> Run? {
        let merged = stopped ? finalStart ?? finalWindowStart(end: received) : nil
        return closed.first { $0.sound && !handedOut.contains($0.run.id) && $0.run.start != merged }?.run
    }

    private func pendingFinal() -> Run? {
        if let final { return handedOut.contains(final.id) ? nil : final }
        if let early, !handedOut.contains(early.id) { return early }
        return nil
    }

    private mutating func newSpeculation() -> Run? {
        guard !stopped, let speculateFrames, quietFrames >= speculateFrames else { return nil }
        let start = finalWindowStart(end: judged)
        guard lastSound > start else { return nil }
        if let speculation, stands(speculation, start: start) { return nil }
        return newRun(.speculation, start: start, end: judged, zeros: StopTailPolicy.fillSamples)
    }

    /// Drops audio no job can still need: before the last closed chunk and before the first chunk still waiting.
    private mutating func trim() {
        guard !stopped, let previous = closed.last?.run else { return }
        let waiting = closed.first { $0.sound && !handedOut.contains($0.run.id) }?.run.start ?? previous.start
        let keep = min(previous.start, waiting)
        guard keep > audioStart else { return }
        audio.removeFirst(keep - audioStart)
        audioStart = keep
    }
}
