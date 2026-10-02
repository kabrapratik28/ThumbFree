import Foundation

/// How a recording take is held: `hold` ends at the release, `locked` (a tap) ends at the next press.
public enum TakeMode: String, Codable, Sendable { case hold, locked }

/// A keyboard's report on its one write of a take's text (the insertion... command kinds).
public enum Insertion: String, Codable, Sendable { case began, confirmed, unverified, heldBack }

/// The contract's Delivery table in one place. The live take's delivering phase (TakeReducer), a later
/// Insert here on an ended take (the host) and startup recovery all use it.
public enum DeliveryTable {
    public struct Row: Sendable, Equatable {
        public let status: TakeStatus
        public let outbox: OutboxItem.State
        /// False only for began: the take waits for the keyboard's final report.
        public let ends: Bool
    }

    /// How long the app waits for a keyboard's report: from the outbox write, and again from insertionBegan.
    public static let timeoutMs = 3_000

    public static func row(for insertion: Insertion) -> Row {
        switch insertion {
        case .began: Row(status: .inserting, outbox: .pending, ends: false)
        case .confirmed: Row(status: .inserted, outbox: .typed, ends: true)
        case .unverified: Row(status: .unverified, outbox: .unverified, ends: true)
        case .heldBack: Row(status: .notInserted, outbox: .heldBack, ends: true)
        }
    }

    /// No report within 3 s, or the app restarted. Nothing began: not inserted, and the item stays pending. Began: the
    /// text may already be in the field.
    public static func noAnswer(began: Bool) -> Row {
        began ? Row(status: .needsReview, outbox: .unverified, ends: true) : Row(status: .notInserted, outbox: .pending, ends: true)
    }
}

/// Transcript text inside events and effects. Printing, interpolating or dumping it shows only its length, so logging
/// an event or an effect never logs what the user said.
public struct Transcript: Sendable, Equatable, CustomStringConvertible, CustomDebugStringConvertible, CustomReflectable {
    public let text: String
    public init(_ text: String) { self.text = text }
    public var description: String { "<\(text.count) characters>" }
    public var debugDescription: String { description }
    public var customMirror: Mirror { Mirror(self, children: [:]) }
}

/// The live take. The keyboards are shown `phase`.
public enum TakeState: Sendable, Equatable {
    case idle
    /// Capture was asked to start and no audio came yet. A tap (release under 300 ms) sets `lockOnReady`.
    case arming(UUID, lockOnReady: Bool)
    case recording(UUID, TakeMode, warned: Bool)
    /// The stop tail is being recorded. `reason`: why the take stopped, kept for history when the outcome has none.
    case stopping(UUID, reason: TakeMessage?)
    /// `samples`: the take's length, for the short-take rule when Silero heard no speech.
    case transcribing(UUID, reason: TakeMessage?, samples: Int)
    /// The text is in the outbox. `began`: a keyboard wrote insertionBegan and may be typing it.
    case delivering(UUID, reason: TakeMessage?, began: Bool)

    public var takeID: UUID? {
        switch self {
        case .idle: nil
        case .arming(let id, _), .recording(let id, _, _), .stopping(let id, _), .transcribing(let id, _, _),
             .delivering(let id, _, _): id
        }
    }

    /// Arming shows as recording, so the status names the take from the press on. The keyboard says "Listening" only
    /// once audio flows too (`HostStatus.micOn`).
    public var phase: TakePhase {
        switch self {
        case .idle: .idle
        case .arming, .recording: .recording
        case .stopping: .stopping
        case .transcribing: .transcribing
        case .delivering: .delivering
        }
    }
}

public enum TakeEvent: Sendable, Equatable {
    /// Mic key down, in a keyboard or in the app, timed by the keyboard's clock. Acts on the live take; `id` names the
    /// new take (a fresh UUID) and is only used when no take is live. A press naming a take this machine ended changes
    /// nothing: a tap that still names it must not start it again.
    case press(UUID, atMs: Int)
    /// Mic key up, for the take it names. Under 300 ms after the press is a tap (the take locks on); longer ends a hold.
    case release(UUID, atMs: Int)
    /// The take's first audio. A hot session sends it at once, with the 300 ms pre-roll already in the take.
    case firstAudio(UUID)
    /// The microphone could not start, or failed while recording (the take keeps its audio; tailDone follows).
    case captureFailed(UUID, TakeMessage)
    /// About once a second while recording: `recordedMs` since the first audio, `msSinceSpeech` since the last gate
    /// speech frame (or the first audio). Drives the 15 minute limit, its warning at 14, and the 2 minute silence stop.
    case tick(UUID, recordedMs: Int, msSinceSpeech: Int)
    /// The audio session ended (idle timeout, a call, a lost route, End session). Acts on the live take; capture ends
    /// with it, so a recording take keeps its audio and tailDone follows.
    case sessionEnded(TakeMessage?)
    case chunkClosed(UUID, Chunk)
    /// The stop tail is recorded and the WAV finished, the last chunk closed. `mayHoldSpeech`: a chunk has a loud frame.
    case tailDone(UUID, samples: Int, mayHoldSpeech: Bool)
    /// A chunk's text arrived. `partial`: every chunk text so far, joined.
    case chunkTextReady(UUID, partial: Transcript, chunksDone: Int)
    /// The take's text. `saved`: it reached history (status staged). `speech`: Silero heard a chunk, or failed open.
    case transcriptReady(UUID, Transcript, saved: Bool, speech: Bool)
    /// The queue reports a failure when it answers finish, never before.
    case transcriptFailed(UUID, TakeMessage)
    /// A keyboard's report on its one write.
    case insertion(UUID, Insertion)
    /// The host's timer ran out: DeliveryTable.timeoutMs after writeOutbox, or after markInserting, with no final report.
    case deliveryTimedOut(UUID)
    /// A keyboard's X button, for the take it names.
    case cancel(UUID)

    /// The take an event is for. A press and the session's end carry none: they act on the live take.
    var takeID: UUID? {
        switch self {
        case .press, .sessionEnded: nil
        case .release(let id, _), .firstAudio(let id), .captureFailed(let id, _), .tick(let id, _, _),
             .chunkClosed(let id, _), .tailDone(let id, _, _), .chunkTextReady(let id, _, _),
             .transcriptReady(let id, _, _, _), .transcriptFailed(let id, _), .insertion(let id, _),
             .deliveryTimedOut(let id), .cancel(let id): id
        }
    }
}

/// What the app's session host runs, in order. `saveOutcome` and `discard` end a take: the host also stops its capture
/// (keeping the audio for saveOutcome) and drops its engine work.
public enum TakeEffect: Sendable, Equatable {
    /// The take's folder and take.json (status recording). If that fails, the host skips the rest of the batch (no
    /// capture) and sends captureFailed(historyWriteFailed).
    case createTake(UUID)
    /// Write the take's WAV from the pre-roll on and feed the gate, the planner and the stop tail policy.
    case startTakeCapture(UUID)
    /// Record the stop tail (StopTailPolicy), close the last chunk, finish the WAV, then send tailDone.
    case startStopTail(UUID)
    /// A chunk that may not hold speech gets empty text without the engine.
    case transcribeChunk(UUID, Chunk)
    /// Transcribe what is left (with the stop tail's zero fill), join, clean up, stage in history, send transcriptReady.
    case finish(UUID)
    case savePartial(UUID, Transcript, chunksDone: Int)
    /// Add the take's item to outbox.json with its insert target, post the status notification so a keyboard reads it,
    /// and start the DeliveryTable.timeoutMs timer. The host keeps the targets (this reducer carries none): the press's,
    /// or for a cold take (the one whose press opened the app) the stop command's, per the contract.
    case writeOutbox(UUID, Transcript, OutboxItem.State)
    /// History status inserting: a keyboard wrote insertionBegan. Restart the timer.
    case markInserting(UUID)
    /// Set the take's outbox item to this state.
    case setOutboxState(UUID, OutboxItem.State)
    /// The take's end in history. `error`: the take's own message, or the reason it stopped.
    case saveOutcome(UUID, TakeStatus, error: TakeMessage?)
    /// A take that leaves no trace: stop capture without keeping audio, delete only the folder this take created.
    case discard(UUID)
    /// Show this in HostStatus.message.
    case message(TakeMessage)
}

/// The take state machine: a pure reducer, ported from Android's Session with its invariants and the contract's
/// Delivery table. The app runs the effects and feeds their results back as events. Handling is idempotent: an event
/// for another take (a late timer, chunk or report) is ignored, and so is a press or release sent no later than the
/// last one handled (a replayed or late command file), and a press naming a take it already ended.
public struct TakeReducer: Sendable, Equatable {
    public static let shortTakeSamples = 16_000 // a silent take under 1 s leaves no trace
    public static let warningMs = 840_000
    public static let limitMs = 900_000
    public static let lockedSilenceMs = 120_000 // any recording take, a hold too: its release can be lost

    public private(set) var state: TakeState
    public let holdThresholdMs: Int
    private var pressedAtMs: Int? // the press the next release is timed from
    private var lastTouchMs: Int? // the latest press or release handled
    private var endedIDs: [UUID] = [] // the takes this machine ended, newest last: a press naming one is ignored
    private static let endedMemory = 64 // a stale tap comes seconds after its take ends, so this is plenty

    public init(state: TakeState = .idle, pressedAtMs: Int? = nil, holdThresholdMs: Int = 300) {
        self.state = state
        self.pressedAtMs = pressedAtMs
        self.lastTouchMs = pressedAtMs
        self.holdThresholdMs = holdThresholdMs
    }

    /// The next machine and the effects to run, in order.
    public func reduce(_ event: TakeEvent) -> (TakeReducer, [TakeEffect]) {
        var next = self
        let effects = next.apply(event)
        return (next, effects)
    }

    private mutating func apply(_ event: TakeEvent) -> [TakeEffect] {
        if let id = event.takeID, id != state.takeID { return [] }
        // A tap that still names an ended take would start it again: its create fails in history, and the discard that
        // follows deletes the ended take's folder.
        if case .press(let id, _) = event, endedIDs.contains(id) { return [] }
        var heldMs: Int?
        switch event {
        case .press(_, let at), .release(_, let at):
            // Sent no later than the last touch handled: a replayed command file, or one from another keyboard that
            // arrived late. It belongs to an earlier gesture.
            if let last = lastTouchMs, at <= last { return [] }
            lastTouchMs = at
            if case .release = event {
                heldMs = pressedAtMs.map { at - $0 }
                pressedAtMs = nil
            } else {
                pressedAtMs = at
            }
        default:
            break
        }
        switch state {
        case .idle:
            guard case .press(let id, _) = event else { return [] }
            state = .arming(id, lockOnReady: false)
            return [.createTake(id), .startTakeCapture(id)]

        case .arming(let id, let lockOnReady):
            switch event {
            case .firstAudio:
                state = .recording(id, lockOnReady ? .locked : .hold, warned: false)
                return []
            case .release:
                guard !lockOnReady, let heldMs else { return [] }
                if heldMs < holdThresholdMs {
                    state = .arming(id, lockOnReady: true)
                    return []
                }
                return end([.discard(id), .message(.micNotReady)]) // a hold released before any audio
            case .press:
                return lockOnReady ? end([.discard(id), .message(.micNotReady)]) : [] // a stop tap before any audio
            case .cancel:
                return end([.discard(id)])
            case .sessionEnded(let message):
                return end([.discard(id)] + say(message))
            case .captureFailed(_, let message):
                return end([.discard(id), .message(message)])
            default:
                return []
            }

        case .recording(let id, let mode, let warned):
            switch event {
            case .release:
                guard mode == .hold, let heldMs else { return [] }
                if heldMs < holdThresholdMs {
                    state = .recording(id, .locked, warned: warned)
                    return []
                }
                return stop(id, nil)
            case .press:
                // Locked: the stop tap. Hold: its release was lost (the keyboard went away, or the app opened from a
                // cold start), so this tap stops the take too.
                return stop(id, nil)
            case .tick(_, let recordedMs, let msSinceSpeech):
                if recordedMs >= Self.limitMs { return stop(id, .takeLimit) }
                if msSinceSpeech >= Self.lockedSilenceMs { return stop(id, .lockedSilence) }
                guard recordedMs >= Self.warningMs, !warned else { return [] }
                state = .recording(id, mode, warned: true)
                return [.message(.takeEndsSoon)]
            case .sessionEnded(let message):
                state = .stopping(id, reason: message)
                return say(message)
            case .captureFailed(_, let message):
                state = .stopping(id, reason: message)
                return [.message(message)]
            case .chunkClosed(_, let chunk):
                return [.transcribeChunk(id, chunk)]
            case .chunkTextReady(_, let partial, let done):
                return [.savePartial(id, partial, chunksDone: done)]
            case .cancel:
                return end([.saveOutcome(id, .cancelled, error: nil)])
            default:
                return []
            }

        case .stopping(let id, let reason):
            switch event {
            case .chunkClosed(_, let chunk):
                return [.transcribeChunk(id, chunk)]
            case .chunkTextReady(_, let partial, let done):
                return [.savePartial(id, partial, chunksDone: done)]
            case .tailDone(_, let samples, let mayHoldSpeech):
                if mayHoldSpeech {
                    state = .transcribing(id, reason: reason, samples: samples)
                    return [.finish(id)]
                }
                // A silent take under 1 s leaves no trace; a longer one stays in history as No speech, with its audio.
                if samples < Self.shortTakeSamples { return end([.discard(id), .message(.noSpeech)]) }
                return end([.saveOutcome(id, .noSpeech, error: reason), .message(.noSpeech)])
            case .cancel:
                return end([.saveOutcome(id, .cancelled, error: reason)])
            default:
                return []
            }

        case .transcribing(let id, let reason, let samples):
            switch event {
            case .chunkTextReady(_, let partial, let done):
                return [.savePartial(id, partial, chunksDone: done)]
            case .transcriptReady(_, let text, let saved, let speech):
                if !speech, samples < Self.shortTakeSamples { return end([.discard(id), .message(.noSpeech)]) }
                if text.text.allSatisfy(\.isWhitespace) { return end([.saveOutcome(id, .noSpeech, error: reason), .message(.noSpeech)]) }
                guard saved else {
                    // Text that did not reach history is only offered (Insert here, Copy): the held-back row at once.
                    let row = DeliveryTable.row(for: .heldBack)
                    return end([.writeOutbox(id, text, row.outbox), .saveOutcome(id, row.status, error: .historyWriteFailed),
                                .message(.historyWriteFailed)])
                }
                state = .delivering(id, reason: reason, began: false)
                return [.writeOutbox(id, text, .pending)]
            case .transcriptFailed(_, let message):
                return end([.saveOutcome(id, .failed, error: message), .message(message)])
            case .cancel:
                return end([.saveOutcome(id, .cancelled, error: reason)])
            default:
                return []
            }

        case .delivering(let id, let reason, let began):
            let row: DeliveryTable.Row
            switch event {
            case .insertion(_, let insertion): row = DeliveryTable.row(for: insertion)
            case .deliveryTimedOut: row = DeliveryTable.noAnswer(began: began)
            default: return [] // cancel and presses too: the keyboard's one write may already be under way
            }
            guard row.ends else {
                if began { return [] } // a second insertionBegan
                state = .delivering(id, reason: reason, began: true)
                return [.markInserting(id)]
            }
            return end([.setOutboxState(id, row.outbox), .saveOutcome(id, row.status, error: reason)])
        }
    }

    private mutating func stop(_ id: UUID, _ reason: TakeMessage?) -> [TakeEffect] {
        state = .stopping(id, reason: reason)
        return [.startStopTail(id)] + say(reason)
    }

    private mutating func end(_ effects: [TakeEffect]) -> [TakeEffect] {
        if let id = state.takeID { endedIDs = Array((endedIDs + [id]).suffix(Self.endedMemory)) }
        state = .idle
        return effects
    }

    private func say(_ message: TakeMessage?) -> [TakeEffect] { message.map { [.message($0)] } ?? [] }
}

extension KeyboardCommand {
    /// The reducer event for this command; nil for ping. Times come from the keyboard's clock, so IPC delay never turns
    /// a tap into a hold.
    public var event: TakeEvent? {
        let ms = Int(exactly: (sentAt.timeIntervalSince1970 * 1000).rounded()) ?? 0
        switch kind {
        case .press: return .press(takeID, atMs: ms)
        case .release: return .release(takeID, atMs: ms)
        case .cancel: return .cancel(takeID)
        case .insertionBegan: return .insertion(takeID, .began)
        case .insertionConfirmed: return .insertion(takeID, .confirmed)
        case .insertionUnverified: return .insertion(takeID, .unverified)
        case .insertionHeldBack: return .insertion(takeID, .heldBack)
        case .ping: return nil
        }
    }
}
