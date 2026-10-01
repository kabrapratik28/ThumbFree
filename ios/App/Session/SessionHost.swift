import AVFoundation
import Foundation
import Observation
import os
import TFCore

/// Runs dictation sessions and takes. It owns the audio source, feeds session audio to the
/// take capture, runs the take reducer's effects (history, engine, text pipeline, outbox) and tells keyboards through
/// status.json plus a Darwin notification after every event. It all runs on the main actor; only the engine has its own.
/// Each take runs through its own live transcriber (`LiveFeed`): chunks are transcribed while the user talks,
/// and the transcriber owns the stop tail.
@MainActor @Observable final class SessionHost {
    static let armingTimeoutMs = 2_000
    static let messageSeconds: TimeInterval = 3
    /// About 1 s of 50 ms timer ticks with no audio block: the mic stopped sending audio.
    static let stalledTicks = 20

    private(set) var status = HostStatus()
    /// The app is on screen (scenePhase `.active`; a system "Open in ...?" prompt and the background both read as
    /// false here). Without a live session, a keyboard's press waits for the app (it cannot record from the background).
    var appActive = false {
        didSet {
            guard appActive != oldValue else { return }
            if appActive {
                // Back in front, so a hit reported meanwhile took ThumbFree nowhere (a Cancel on iOS's prompt): no return
                // to mark, even if the user then swipes back by hand. After a real departure the resolve already ran.
                returnOpened = nil
                // Coming back from the prompt or the background: falls back only if the delay task or the watchdog
                // already gave up on this trip because the app was not active when they checked (awaitingActive). A
                // trip still in flight (no attempt has run or been decided yet) is left alone.
                guard awaitingActive else { return }
                awaitingActive = false
                fallbackToSwipeBack()
            }
        }
    }
    /// The scene is `.background` (scenePhase `.background`; unlike `appActive`, a system "Open in ...?" prompt
    /// (`.inactive`) never sets this). Gates `resolveIfOpened()`, so a hit reported while merely inactive is not
    /// cleared until the scene actually leaves: a prompt the user might cancel is not a departure.
    var inBackground = false {
        didSet {
            guard inBackground != oldValue, inBackground else { return }
            resolveIfOpened()
        }
    }
    /// Types an in-app take's text (the Try tab). Without it, in-app takes are held back.
    var deliverInApp: ((String) -> Void)?
    let history: HistoryStore
    /// Grows with every history write, so the History tab can follow along.
    private(set) var historyChanges = 0
    /// The last take's time from the stop to its text, for the Try tab's small print: a phone run compares it with the
    /// Mac's numbers. It counts from the stop effect here, so the hop to the transcriber's actor is in it.
    private(set) var lastStopToTextMs: Int?
    /// Milliseconds since this host started, from a monotonic clock: feeds the stop tail (`stop(nowMs:)` and
    /// `check(nowMs:)`, StopTailPolicy's 350 ms cap) so a wall-clock change (a call answered, DST, an NTP sync) can
    /// never shorten or stretch it. Tests set their own to drive the tail without a real wait.
    @ObservationIgnored var tailClockMs: () -> Int = SessionHost.monotonicClock()
    let launchedAt = Date()
    /// The take whose press opened the app (the dictate link). Its text goes to the field where the user stops it.
    private var coldTake: UUID?
    /// The Dictionary's entries (the app keeps them in step with `DictionaryStore`): every take's text goes through them.
    var dictionary: [String] = []

    /// The dictate link's own (stale) take id, when it named a take from before this launch and a fresh one started in
    /// its place: a repeated link with that same stale id must resolve to the fresh take already running for it, not
    /// start another or return nil (which would close the session screen while the take keeps recording).
    private var coldLink: UUID?

    /// Automatic return: the round-trip screen's state, the target waiting for the first audio buffer, and a Debug
    /// delay before the open (`-TFReturnDelayMs`). The app injects how to open a URL, how to remember which apps we
    /// have returned to, and whether we have returned to one before; tests inject fakes.
    private(set) var returnTrip: ReturnTrip?
    private var pendingReturn: ReturnTarget?
    private(set) var returnTask: Task<Void, Never>?
    /// When the current open attempt started, for the fallback's own log line.
    private var returnAttemptAt: ContinuousClock.Instant?
    /// Set when the Debug delay task or the fallback watchdog gives up on the current trip because the app was not
    /// active when it checked: consumed (and cleared) by `appActive`'s didSet, which falls back only then, never
    /// merely because an attempt already ran (`pendingReturn` goes nil long before the trip is actually decided).
    private var awaitingActive = false
    /// The app an open attempt reported success for (a hit) while the trip was still leaving, until the trip resolves: once
    /// the scene then reaches the background, the trip is over and that app is marked returned (not before: a Cancel on
    /// iOS's prompt is no return), and a later return to active shows the plain listening screen instead of a stale
    /// "leaving" one. Coming back to active or a fallback forgets it.
    private var returnOpened: String?
    private let returnDelayMs: Int
    private let fallbackDelayMs: Int
    /// Every press `handleCommands()` has read from the App Group this launch, by take id, including one it dropped before
    /// it reached the reducer (from before this launch, or expired): proof our keyboard sent it even after the command
    /// file itself is gone. It vouches for one round trip only, within `seenPressSeconds` of its sending
    /// (`useKeyboardPress`); older ones are dropped as new presses come in.
    private var seenPresses: [UUID: SeenPress] = [:]
    private struct SeenPress {
        let sentAt: Date
        /// A round trip already used it: it never vouches for another, even if `handleCommands()` reads it again.
        var used = false
    }
    @ObservationIgnored var openHost: (@MainActor (URL, @escaping @MainActor (Bool) -> Void) -> Void)?
    @ObservationIgnored var onReturned: (@MainActor (String) -> Void)?
    @ObservationIgnored var hasReturned: (@MainActor (String) -> Bool)?

    private static let log = Logger(subsystem: Brand.bundleID, category: "session")
    private static let returnLog = Logger(subsystem: Brand.bundleID, category: "hostReturn")
    private let shared: SharedStore
    private let transcriber: Transcriber
    /// Session length: the session ends this long after the last take (Settings; 5 minutes by default).
    var idleTimeout: TimeInterval
    /// History's retention rule (Settings), applied after every take: days kept and the most takes kept, nil for no limit.
    var keepDays = Retention.defaultDays
    var keepCount = Retention.defaultCount
    private let deliveryTimeoutMs: Int
    /// The object interruption notifications must carry to end this host's session. Real iOS posts them with
    /// `AVAudioSession.sharedInstance()`; tests can inject their own object so a process-wide post in one test never
    /// ends another suite's live session (they run in parallel).
    private let interruptionSource: AnyObject
    private let makeSource: @MainActor () -> AudioSource

    private var reducer = TakeReducer()
    private let capture = TakeCapture()
    private var source: AudioSource?
    /// The source's start() returned, so the mic runs. It can wait seconds on the first run's permission prompt.
    private var micRunning = false
    private var blocks: Task<Void, Never>?
    private var timer: Task<Void, Never>?
    private var interruption: NSObjectProtocol?
    private var queue: [TakeEvent] = []
    private var running = false
    private var lastTouchMs = 0
    private var armedAtMs: Int?
    private var lastTickMs = 0
    /// Timer ticks since the last audio block. Ticks, not wall time: after the main actor was busy for a second, the
    /// blocks queued meanwhile still count, so only a mic that really went quiet reaches stalledTicks.
    private var ticksSinceAudio = 0
    private var deliveryDeadlineMs: Int?
    private var messageAt: Date?
    /// Each take's pin: the keyboard command whose field its text goes to (the press, or a cold take's stop).
    private var pins: [UUID: KeyboardCommand] = [:]
    private var inApp: Set<UUID> = []
    /// The take folder this host created for the live take: the only one a discard may delete.
    private var createdTake: UUID?
    /// The live take's transcriber, fed every block its WAV gets.
    private var live: LiveFeed?
    /// The live take's chunk texts already in history's partial text.
    private var partialCount = 0
    /// When the live take's stop effect ran: where lastStopToTextMs starts.
    private var stoppedAt: ContinuousClock.Instant?
    /// The live take's transcription, dropped when the take ends first (a cancel).
    private var work: Task<Void, Never>?
    /// The next take's order. nextOrder() decodes every take, so it runs at startup, not on each press.
    private var cachedOrder: Int?
    /// A model change that came during a take or a Transcribe again: it applies once neither runs (a new choice applies
    /// from your next take).
    private var pendingModel: URL??

    init(history: HistoryStore, shared: SharedStore, engine: EngineSource, idleTimeout: TimeInterval = 300,
         deliveryTimeoutMs: Int = DeliveryTable.timeoutMs, interruptionSource: AnyObject = AVAudioSession.sharedInstance(),
         returnDelayMs: Int = 0, fallbackDelayMs: Int = 1_500, makeSource: @escaping @MainActor () -> AudioSource) {
        self.history = history
        self.shared = shared
        self.transcriber = Transcriber(engine)
        self.idleTimeout = idleTimeout
        self.deliveryTimeoutMs = deliveryTimeoutMs
        self.interruptionSource = interruptionSource
        self.returnDelayMs = returnDelayMs
        self.fallbackDelayMs = fallbackDelayMs
        self.makeSource = makeSource
        if !transcriber.hasModel { status.engine = .noModel }
        cachedOrder = try? history.nextOrder()
        transcriber.onPhase = { [weak self] phase in
            self?.status.engine = phase
            self?.publish()
        }
    }

    /// The live take's length so far, in milliseconds; it keeps the last take's length after a take ends.
    var recordedMs: Int { capture.recordedMs }

    // MARK: The speech model

    /// The model to use from now on: a download that just finished, the model you chose, or none (nil) after a delete.
    /// The engine loads and warms up now, in the foreground, so the first Neural Engine compile never waits for a take,
    /// and keyboards learn whether there is a model. During a take, or while History transcribes a take
    /// again, it waits for them to end: each keeps the model it began with.
    func useModel(_ folder: URL?) {
        pendingModel = .some(folder)
        applyPendingModel()
    }

    private func applyPendingModel() {
        guard let folder = pendingModel, reducer.state == .idle, retranscribing.isEmpty else { return }
        pendingModel = nil
        transcriber.useModel(folder)
        prepareEngine()
    }

    /// A take is live, or History is transcribing a take again: Settings deletes no model now. A new choice waits for
    /// them, so the model in use may be the one no longer chosen.
    var modelInUse: Bool { status.take != .idle || !retranscribing.isEmpty }

    /// Loads and warms up the engine now: the welcome flow's "Getting ready for this iPhone". Does nothing once loaded.
    func prepareEngine() { transcriber.loadIfNeeded() }

    /// The engine has a model, or needs none. Without one, keyboard takes never start (they could only fail); the app
    /// shows where to get the model instead.
    var hasModel: Bool { transcriber.hasModel }

    /// The language the text rules and the Dictionary use: the loaded model's.
    var language: String? { transcriber.language }

    // MARK: History

    /// Delete: first the take's outbox item, so no keyboard can offer its text again, then its audio and folder. A
    /// failure to rewrite the outbox stops the delete and throws. A live take, or one being transcribed again, stays.
    func deleteTake(_ id: UUID) throws {
        guard let record = try history.record(id), !record.status.isLive, id != reducer.state.takeID,
              !retranscribing.contains(id) else { return }
        try dropOutbox([id])
        try history.delete(id)
        historyChanges += 1
    }

    /// Clear all: every ended take and its outbox item (`deleteTakes`). Returns how many went.
    @discardableResult
    func clearHistory() throws -> Int {
        try deleteTakes(history.all().filter { !$0.status.isLive }.map(\.id))
    }

    /// Deletes these takes (Clear all, and the retention rule's): first their outbox items, so no keyboard offers their
    /// text again, then their audio and folders. A failure to rewrite the outbox deletes none and throws. The live take,
    /// one being transcribed again, and one whose audio could not be deleted stay. Returns how many went.
    @discardableResult
    func deleteTakes(_ ids: [UUID]) throws -> Int {
        let ids = ids.filter { $0 != reducer.state.takeID && !retranscribing.contains($0) }
        guard !ids.isEmpty else { return 0 }
        try dropOutbox(ids)
        let gone = ids.filter { (try? history.delete($0)) != nil }
        historyChanges += 1
        return gone.count
    }

    /// Takes being transcribed again. A second request for one does nothing, and Delete leaves it until the run ends.
    private(set) var retranscribing: Set<UUID> = []

    /// Transcribe again from History, with Android's rules: the saved audio through the engine and the text pipeline with
    /// today's Dictionary, and nothing is typed. A take that was typed, or may be in its field, keeps its status and what
    /// was typed (`insertedText`), and shows the new text as transcribed again; any other take ends Not inserted with
    /// the new text. No speech leaves a typed take as it was and ends any other No speech. The outbox drops before
    /// History changes, as Delete's order does, so a crash or a failed write never leaves the old text offerable.
    /// Returns what to tell the user, or nil when the new text is in.
    func transcribeAgain(_ id: UUID) async -> TakeMessage? {
        guard !retranscribing.contains(id), let record = try? history.record(id), !record.status.isLive,
              id != reducer.state.takeID else { return nil }
        guard transcriber.hasModel else { return .noModel }
        retranscribing.insert(id)
        defer {
            retranscribing.remove(id)
            applyPendingModel()
        }
        let url = history.audioURL(for: id)
        guard let samples = try? await Task.detached(operation: { try WavFile.readMono16k(url: url) }).value else {
            return .audioMissing
        }
        let result: (texts: [String], heard: Bool)
        do {
            result = try await transcriber.transcribeAgain(samples)
        } catch {
            return Transcriber.message(for: error)
        }
        let (raw, text) = TextPipeline.run(chunkTexts: result.texts, dictionary: dictionary, language: language)
        guard var take = try? history.record(id) else { return nil } // deleted meanwhile
        let typed = [TakeStatus.inserted, .unverified, .needsReview].contains(take.status)
        let heard = result.heard && !text.allSatisfy(\.isWhitespace)
        if !heard {
            guard !typed else { return .noSpeech }
            take.status = .noSpeech
            (take.rawText, take.text, take.partialText, take.insertedText, take.retranscribed) = (nil, nil, nil, nil, false)
        } else {
            if typed, !take.retranscribed, take.insertedText == nil { take.insertedText = take.text } // what was typed stays
            if !typed { take.status = .notInserted }
            (take.rawText, take.text, take.retranscribed, take.error) = (raw, text, true, nil)
            take.modelID = transcriber.modelID
        }
        guard (try? dropOutbox([id])) != nil else { return .historyWriteFailed } // first, as Delete does: no keyboard offers the old text again
        guard (try? history.update(take)) != nil else { return .historyWriteFailed }
        historyChanges += 1
        return heard ? nil : .noSpeech
    }

    private func dropOutbox(_ ids: [UUID]) throws {
        let items = try shared.outbox()
        guard items.contains(where: { ids.contains($0.takeID) }) else { return }
        try shared.write(items.filter { !ids.contains($0.takeID) })
    }

    // MARK: The Try tab's mic key

    /// Key down. `take` names a new take; while a take is live the press acts on it (a stop tap).
    func pressInApp(_ take: UUID) {
        if reducer.state == .idle { inApp.insert(take) }
        send(.press(take, atMs: touchMs()))
    }

    /// Key up, for the take the press named.
    func releaseInApp(_ take: UUID) {
        send(.release(take, atMs: touchMs()))
    }

    /// Deletes what the retention rule no longer keeps, as `deleteTakes` does: after each take, and with a day limit also
    /// at launch and on return to the foreground (`applyDayRetention`). A stricter rule from Settings deletes exactly the
    /// takes its warning counted instead.
    func applyRetention() {
        _ = try? deleteTakes(takesToDelete(keepDays: keepDays, keepCount: keepCount))
    }

    /// Retention with no new take (at launch, on return to the foreground): only a day limit can find takes to delete
    /// then, so without one History is not read at all.
    func applyDayRetention() {
        guard keepDays != nil else { return }
        applyRetention()
    }

    /// The takes a rule would delete now, for the warning before it applies: never the live take or one being
    /// transcribed again.
    func takesToDelete(keepDays: Int?, keepCount: Int?) -> [UUID] {
        Retention.idsToDelete((try? history.all()) ?? [], keepDays: keepDays, keepCount: keepCount, now: Date())
            .filter { $0 != reducer.state.takeID && !retranscribing.contains($0) }
    }

    /// Start ThumbFree (Control Center, the Action Button, Shortcuts): the mic comes on for a session with no take yet,
    /// so the keyboard's next tap starts at once. True once audio flows; false with no model, at once when the start
    /// fails (iOS did not let it start from the background, or the permission is off), or when no audio came within 2 s.
    /// A mic that started for no take but never sent audio has nothing to time it out, so it is stopped then (and a
    /// call that finds one left starts it over); a live take, and a permission prompt still up (the mic has not
    /// started), are left alone. A cancelled intent ends the wait at once, with the same stop.
    /// ponytail: a start still waiting on the permission prompt after 2 s is left to run; if its mic then sends no audio,
    /// it stays until the next Start ThumbFree or the next take's arming timeout. Give takeless sessions their own
    /// first-audio timeout if a phone shows that.
    func startIdleSession() async -> Bool {
        guard transcriber.hasModel else { return false }
        if reducer.state == .idle, micRunning, !status.micOn { stopSource() }
        if status.session == .off { startSession() }
        for _ in 0..<40 where !status.micOn && status.session != .off { try? await Task.sleep(for: .milliseconds(50)) }
        if reducer.state == .idle, micRunning, !status.micOn { stopSource() }
        return status.micOn
    }

    /// End session: the mic closes; a take that is recording stops and is still transcribed. `reason`: why, for the
    /// take's history (a call).
    func endSession(_ reason: TakeMessage? = nil) {
        guard status.session != .off else { return }
        status.session = .ending
        send(.sessionEnded(reason))
        captureEnded()
        stopSource()
    }

    // MARK: Events and effects

    func send(_ event: TakeEvent) {
        queue.append(event)
        guard !running else { return }
        running = true
        while !queue.isEmpty {
            let (next, effects) = reducer.reduce(queue.removeFirst())
            reducer = next
            status.takeID = next.state.takeID
            status.take = next.state.phase
            run(effects)
            publish()
        }
        running = false
    }

    private func run(_ effects: [TakeEffect]) {
        for effect in effects {
            switch effect {
            case .createTake(let id):
                guard createTake(id) else { return } // it sent captureFailed: skip the rest of the batch
            case .startTakeCapture(let id):
                startTakeCapture(id)
            case .startStopTail:
                stoppedAt = .now
                live?.stop(nowMs: tailClockMs()) // the tail's end comes back through endTail
            case .transcribeChunk:
                break // the live transcriber closes and runs chunks itself
            case .savePartial(let id, let partial, let done):
                updateRecord(id) {
                    $0.partialText = partial.text
                    $0.chunksDone = done
                }
            case .finish(let id):
                finish(id)
            case .writeOutbox(let id, let text, let state):
                writeOutbox(id, text.text, state)
            case .markInserting(let id):
                updateRecord(id) { $0.status = .inserting }
                deliveryDeadlineMs = Self.wallMs() + deliveryTimeoutMs
            case .setOutboxState(let id, let state):
                setOutboxState(id, state)
            case .saveOutcome(let id, let outcome, let error):
                updateRecord(id) { $0.status = outcome; $0.error = error?.rawValue }
                endTake(id)
                applyRetention()
            case .discard(let id):
                capture.discard()
                if createdTake == id { try? history.delete(id) } // never a take folder this take did not create
                historyChanges += 1
                endTake(id)
            case .message(let message):
                status.message = message.text
                messageAt = Date()
            }
        }
    }

    private func createTake(_ id: UUID) -> Bool {
        status.message = nil
        messageAt = nil
        guard Self.freeBytes() >= 64 << 20 else { // Android's rule: 64 MiB free before a take starts
            send(.captureFailed(id, .storageFull))
            return false
        }
        do {
            let order = try cachedOrder ?? history.nextOrder()
            try history.create(TakeRecord(id: id, order: order, startedAt: Date(), modelID: transcriber.modelID))
            cachedOrder = order + 1
        } catch HistoryError.exists {
            send(.cancel(id)) // the press named a take that already exists (an old status): drop it, leave that take alone
            return false
        } catch {
            send(.captureFailed(id, .historyWriteFailed))
            return false
        }
        createdTake = id
        historyChanges += 1
        return true
    }

    private func startTakeCapture(_ id: UUID) {
        transcriber.loadIfNeeded() // a load that failed earlier in this same session gets a fresh try
        let live = LiveFeed(transcriber.newTake()) { [weak self] in self?.endTail(id) }
        do {
            try capture.begin(id, wavURL: history.audioURL(for: id), onAudio: live.append)
        } catch {
            live.cancel()
            return send(.captureFailed(id, .storageFull))
        }
        self.live = live
        partialCount = 0
        armedAtMs = micRunning ? Self.wallMs() : nil // otherwise the clock starts when the mic does (sourceStarted)
        if status.micOn { send(.firstAudio(id)) } else { startSession() } // a live session: the pre-roll is in the take
    }

    /// The stop tail ended (the live transcriber said so), or the capture ended without one: close the take's audio and
    /// tell the reducer. A late call for a take whose capture already ended does nothing.
    private func endTail(_ id: UUID) {
        guard capture.takeID == id else { return }
        let done = capture.finish()
        updateRecord(id) { $0.durationMs = done.samples / 16 }
        send(.tailDone(id, samples: done.samples, mayHoldSpeech: done.mayHoldSpeech))
    }

    /// The capture ended without a stop tail (a failure or the session's end): a recording take closes now, and its
    /// transcriber's finish() ends the tail where the audio stopped.
    private func captureEnded() {
        if case .stopping(let id, _) = reducer.state { endTail(id) }
    }

    /// History's partial text, once a second: the closed chunks' texts so far, when more are in. A
    /// cancelled or failed take keeps it.
    private func savePartial() {
        guard let live, let id = reducer.state.takeID else { return }
        Task {
            let texts = await live.transcriber.partialTexts
            guard self.live === live, texts.count > partialCount else { return } // a newer take, or nothing new
            partialCount = texts.count
            let partial = TextPipeline.run(chunkTexts: texts, dictionary: dictionary, language: language).text
            send(.chunkTextReady(id, partial: Transcript(partial), chunksDone: texts.count))
        }
    }

    /// The take's text from its live transcriber, saved to history before any keyboard hears of it, so the text is
    /// never lost.
    private func finish(_ id: UUID) {
        guard let live else { return send(.transcriptFailed(id, .audioMissing)) }
        let stoppedAt = stoppedAt
        work = Task {
            let result: Transcription
            var stopToTextMs: Int?
            do {
                let done = try await live.finish()
                // From the stop effect; a take a call or End session ended has none and counts from finish().
                stopToTextMs = stoppedAt.map { Int((ContinuousClock.now - $0) / .milliseconds(1)) }
                    ?? Int(done.stopToResultMs.rounded())
                result = Transcriber.transcription(done, dictionary: dictionary, language: language)
            } catch {
                result = .failed(Transcriber.message(for: error)) // CancellationError is dropped just below
            }
            guard !Task.isCancelled, reducer.state.takeID == id else { return } // the take ended meanwhile: drop its text
            if let stopToTextMs { lastStopToTextMs = stopToTextMs } // only now: a cancelled or superseded take must not overwrite it
            switch result {
            case .text(let raw, let text, let speech):
                send(.transcriptReady(id, Transcript(text), saved: stage(id, raw: raw, text: text), speech: speech))
            case .failed(let message):
                send(.transcriptFailed(id, message))
            }
        }
    }

    private func stage(_ id: UUID, raw: String, text: String) -> Bool {
        guard !text.allSatisfy(\.isWhitespace) else { return false }
        return updateRecord(id) {
            $0.rawText = raw
            $0.text = text
            $0.status = .staged
        }
    }

    private func writeOutbox(_ id: UUID, _ text: String, _ state: OutboxItem.State) {
        var items = (try? shared.outbox()) ?? []
        items.removeAll { $0.takeID == id }
        items.append(OutboxItem(takeID: id, text: text, target: pins[id]?.target, pinnedAt: pins[id]?.sentAt, state: state))
        try? shared.write(items)
        guard state == .pending else { return }
        guard inApp.contains(id) else {
            deliveryDeadlineMs = Self.wallMs() + deliveryTimeoutMs
            return
        }
        guard let deliverInApp else { return send(.insertion(id, .heldBack)) }
        send(.insertion(id, .began))
        deliverInApp(text)
        send(.insertion(id, .confirmed))
    }

    private func setOutboxState(_ id: UUID, _ state: OutboxItem.State) {
        guard var items = try? shared.outbox(), let index = items.firstIndex(where: { $0.takeID == id }) else { return }
        items[index].state = state
        try? shared.write(items)
    }

    @discardableResult
    private func updateRecord(_ id: UUID, _ change: (inout TakeRecord) -> Void) -> Bool {
        guard var record = try? history.record(id) else { return false }
        change(&record)
        guard (try? history.update(record)) != nil else { return false }
        historyChanges += 1
        return true
    }

    private func endTake(_ id: UUID) {
        if capture.takeID == id { _ = capture.finish() } // a cancel while recording keeps the audio
        work?.cancel() // saveOutcome and discard drop the take's engine work
        work = nil
        live?.cancel() // nothing more of this take reaches the engine
        live = nil
        stoppedAt = nil
        createdTake = nil
        pins[id] = nil
        inApp.remove(id)
        armedAtMs = nil
        deliveryDeadlineMs = nil
        if id == coldTake { // the round trip is over: drop its screen state and its timers, so a later take is never touched
            coldTake = nil
            coldLink = nil
            returnTrip = nil
            pendingReturn = nil
            returnAttemptAt = nil
            returnTask?.cancel()
            returnTask = nil
            awaitingActive = false
            returnOpened = nil
        }
        if status.session == .ready { status.expiresAt = Date().addingTimeInterval(idleTimeout) } // reset after each take
        applyPendingModel()
    }

    func publish() {
        // The take's start for the keyboards' time: its arming clock (the wall clock, as updatedAt), nil once it ends.
        status.takeStartedAt = armedAtMs.map { Date(timeIntervalSince1970: Double($0) / 1_000) }
        status.updatedAt = Date()
        try? shared.write(status)
        DarwinObserver.post(DarwinName.status)
    }

    // MARK: Session, audio and timers

    private func startSession() {
        guard source == nil else { return }
        status.session = .starting
        let source = makeSource()
        self.source = source
        let (stream, sink) = AsyncStream.makeStream(of: [Float].self)
        blocks = Task { [weak self] in
            for await block in stream {
                // stopSource cancels this task in the same main-actor job that drops the source, but a block the source
                // handed over first (a tap callback in flight) still arrives here: it must not reopen the ended session.
                guard !Task.isCancelled else { break }
                self?.consume(block)
            }
        }
        Task { [weak self] in
            do {
                try await source.start { sink.yield($0) }
                self?.sourceStarted(source)
            } catch {
                self?.sourceFailed(error)
            }
        }
        // A call (or another app that takes the mic) ends the session; the take keeps its audio.
        // A backgrounded app cannot start recording again, so the next take reopens ThumbFree.
        interruption = NotificationCenter.default.addObserver(forName: AVAudioSession.interruptionNotification,
                                                              object: interruptionSource, queue: .main) { [weak self] note in
            guard (note.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt) == AVAudioSession.InterruptionType.began.rawValue else { return }
            MainActor.assumeIsolated { self?.endSession(.call) }
        }
        transcriber.loadIfNeeded()
        startTimer()
    }

    private func consume(_ block: [Float]) {
        ticksSinceAudio = 0
        let first = !status.micOn
        if first {
            status.micOn = true
            status.session = .ready
            status.expiresAt = Date().addingTimeInterval(idleTimeout)
            // The stop tail is judged per block, so the block size adds to the stop time.
            Self.log.info("Mic on: first block \(block.count / 16, privacy: .public) ms, IO buffer \(Int(AVAudioSession.sharedInstance().ioBufferDuration * 1_000), privacy: .public) ms")
        }
        do {
            try capture.consume(block)
        } catch {
            if let id = capture.takeID {
                send(.captureFailed(id, .storageFull))
                captureEnded()
            }
        }
        guard let id = capture.takeID else {
            if first { publish() }
            return
        }
        if first { send(.firstAudio(id)) }
        fireReturnIfReady() // audio is flowing for this take: open the host now (automatic return; self-guarded below)
        if case .stopping = reducer.state { live?.check(nowMs: tailClockMs()) }
    }

    /// The mic runs: a take that waits for audio starts its arming clock now, not while a permission prompt is up.
    private func sourceStarted(_ started: AudioSource) {
        guard started === source else { return } // its session ended while start() waited
        micRunning = true
        if case .arming = reducer.state { armedAtMs = Self.wallMs() }
    }

    private func sourceFailed(_ error: Error) {
        let message: TakeMessage = (error as? MicError) == .permissionDenied ? .micPermission : .micUnavailable
        // With no take, a message only on screen: off screen, iOS refused Start ThumbFree's background start, which no
        // message describes ("Another app is using the microphone." would be wrong), and the intent opens ThumbFree to
        // try again there.
        if let id = capture.takeID {
            send(.captureFailed(id, message))
            captureEnded()
        } else if appActive {
            status.message = message.text
            messageAt = Date()
        }
        stopSource()
    }

    private func stopSource() {
        if let interruption { NotificationCenter.default.removeObserver(interruption) }
        interruption = nil
        source?.stop()
        source = nil
        micRunning = false
        blocks?.cancel()
        blocks = nil
        status.session = .off
        status.micOn = false
        status.expiresAt = nil
        returnTrip = nil // the round-trip screen is gone once the session ends
        pendingReturn = nil
        returnAttemptAt = nil
        returnTask?.cancel()
        returnTask = nil
        awaitingActive = false
        returnOpened = nil
        publish()
    }

    private func startTimer() {
        guard timer == nil else { return }
        timer = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(for: .milliseconds(50))
                guard let self else { return }
                self.onTimer()
            }
        }
    }

    /// Every 50 ms while a session or a take is live: the stop tail's own monotonic clock, the stalled-mic watchdog,
    /// one tick and status write a second, the arming timeout, the delivery timeout, the message's 3 s, and the
    /// session's idle end. `nowMs` is the wall clock for everything here except the tail (tests can pin it).
    func onTimer(nowMs now: Int = SessionHost.wallMs()) {
        if case .stopping = reducer.state { live?.check(nowMs: tailClockMs()) }
        // The mic went quiet with no error (AVAudioEngine stops on a route or configuration change or a media services
        // reset, or another app took the input): end the session; a recording take keeps what it captured. Only once
        // the mic has sent audio, never while start() waits on the permission prompt.
        if status.micOn {
            ticksSinceAudio += 1
            if ticksSinceAudio >= Self.stalledTicks { endSession(.captureStalled) }
        }
        // About once a second while a take or the session is live: a recording take gets its tick, and status.json is
        // rewritten either way, since a keyboard reads a status older than 5 s as an app that went away (during a long
        // transcription too, and for "Ready, mic on").
        if reducer.state != .idle || status.session != .off, now - lastTickMs >= 1_000 {
            lastTickMs = now
            savePartial()
            if case .recording(let id, _, _) = reducer.state {
                send(.tick(id, recordedMs: capture.recordedMs, msSinceSpeech: capture.msSinceSpeech)) // send publishes
            } else {
                publish()
            }
        }
        switch reducer.state {
        case .arming(let id, _):
            if let armed = armedAtMs, now - armed >= Self.armingTimeoutMs {
                send(.captureFailed(id, .micNotReady))
                if !status.micOn { stopSource() } // the mic never started: the next tap tries it again
            }
        case .delivering(let id, _, _):
            if let deadline = deliveryDeadlineMs, now >= deadline {
                deliveryDeadlineMs = nil
                send(.deliveryTimedOut(id))
            }
        default:
            break
        }
        if let messageAt, Date().timeIntervalSince(messageAt) >= Self.messageSeconds {
            status.message = nil
            self.messageAt = nil
            publish()
        }
        if reducer.state == .idle, status.session == .ready, let expires = status.expiresAt, Date() >= expires { endSession() }
        if reducer.state == .idle, status.session == .off, messageAt == nil {
            timer?.cancel()
            timer = nil
        }
    }

    static func wallMs() -> Int { Int(Date().timeIntervalSince1970 * 1_000) }

    /// A fresh `() -> Int` clock, milliseconds elapsed since this call, from `ContinuousClock`: never the wall clock,
    /// so it cannot be shortened or stretched by a clock change.
    private static func monotonicClock() -> () -> Int {
        let started = ContinuousClock.now
        return { Int((ContinuousClock.now - started) / .milliseconds(1)) }
    }

    /// Touch times strictly increase: the reducer drops a touch that is not later than the last one.
    private func touchMs() -> Int {
        lastTouchMs = max(Self.wallMs(), lastTouchMs + 1)
        return lastTouchMs
    }

    nonisolated static func freeBytes() -> Int64 {
        let values = try? URL(fileURLWithPath: NSHomeDirectory()).resourceValues(forKeys: [.volumeAvailableCapacityForImportantUsageKey])
        return values?.volumeAvailableCapacityForImportantUsage ?? .max
    }
}

// MARK: Keyboards

extension SessionHost {
    static let pendingPressSeconds: TimeInterval = 30
    /// How long a keyboard press vouches for the host in its dictate link (`useKeyboardPress`). The link opens the app
    /// about 150 ms after the press, or a little longer when iOS has to launch it.
    static let seenPressSeconds: TimeInterval = 60

    /// Handles the keyboards' command files oldest first, removing each before its work runs: a
    /// keyboard that still sees its press 150 ms later opens the link, so a slow start must not look like no session took
    /// it. Replays are harmless: the reducer drops touches it already saw and events for other takes.
    func handleCommands(now: Date = Date()) {
        for command in (try? shared.pendingCommands()) ?? [] {
            if command.kind == .press {
                // Proof our keyboard sent it, kept even once the command file is gone (a link naming this take can
                // still trust it, once, for 60 s): every press this reads counts, including the ones just below that
                // never start a take. The first press for a take id is the one kept, so a later stop press for the same
                // take never renews it or makes a used one new again.
                seenPresses = seenPresses.filter { Self.isRecent($0.value.sentAt, now: now) }
                if seenPresses[command.takeID] == nil { seenPresses[command.takeID] = SeenPress(sentAt: command.sentAt) }
                // From before this launch, or older than 30 s (its link never opened the app): never starts a take.
                guard command.sentAt >= launchedAt, now.timeIntervalSince(command.sentAt) <= Self.pendingPressSeconds else {
                    try? shared.remove(command)
                    continue
                }
                // No mic from the background: the press waits on disk, and the keyboard opens the app.
                guard status.session != .off || appActive else { continue }
                // No model: the take could only fail. The press is dropped, and the keyboard shows the missing model.
                guard transcriber.hasModel else {
                    try? shared.remove(command)
                    continue
                }
                if reducer.state == .idle, command.target != nil { pins[command.takeID] = command }
            }
            try? shared.remove(command)
            var recording: UUID?
            if case .recording(let id, _, _) = reducer.state { recording = id }
            switch command.event {
            case .insertion(let id, let insertion)? where id != reducer.state.takeID:
                applyLate(id, insertion)
            case let event?:
                send(event)
            case nil:
                publish() // ping
            }
            // The command that stopped the cold take (the second press, or a hold's release) carries the field where the
            // user stopped, and the text goes there: the app switch gave the field of its press a new identity. A quiet
            // end has no tail, so the take can be transcribing already.
            if let id = recording, id == coldTake, reducer.state.takeID == id, reducer.state.phase != .recording,
               command.target != nil {
                pins[id] = command
            }
        }
    }

    /// `thumbfree://dictate?take=<id>` opened the app: with no live take, that take starts as a tap (it locks on) and
    /// is the cold take; the keyboard's own press for it is older, so the reducer drops it. A take from before this
    /// launch (ThumbFree died during it, and its last status still named it for a few seconds) stays as startup
    /// recovery marked it, and a fresh take starts in its place; a repeat of that same (now stale) link resolves back
    /// to the fresh take, never nil (nil would close the session screen while the take keeps recording). A link naming
    /// the live take (a keyboard command already started it) makes it the cold take. A link for another take leaves the
    /// live take alone. Returns the cold take, or nil when the link started none (a take that already ended in this launch).
    @discardableResult
    func openLink(_ link: UUID, host: String? = nil, autoReturn: Bool = false) -> UUID? {
        guard transcriber.hasModel else {
            handleCommands() // no model: no take, and its press is dropped there
            return nil
        }
        var take = link
        if link == coldLink, let coldTake {
            take = coldTake // the same stale link again: resolve to the fresh take already substituted for it
        } else if reducer.state == .idle {
            if let old = try? history.record(link), old.startedAt < launchedAt { take = UUID() }
            // Both times first: the press's work (take folder, WAV file, making the mic source) must not turn the tap
            // into a hold.
            let pressAt = touchMs(), releaseAt = touchMs()
            send(.press(take, atMs: pressAt))
            send(.release(take, atMs: releaseAt))
        }
        // Only on the take's first link: this gate stops openLink's round-trip setup from running twice for the same
        // URL (onOpenURL firing again for a link it already opened), without resetting an already-armed round trip.
        if reducer.state.takeID == take, coldTake != take {
            coldTake = take
            coldLink = take == link ? nil : link // remember a stale link's substitution, so a repeat can find it above
            setupReturnTrip(link: link, host: host, autoReturn: autoReturn)
        }
        handleCommands()
        return reducer.state.takeID == take ? take : nil
    }

    /// Sets the round-trip screen's state for a cold take. When automatic return is on and the host is in the table, it aims
    /// to leave; otherwise it shows the swipe-back screen (naming the app when we know it). Never opens the wrong app: an
    /// unknown or untrusted host has no target, so it falls back, and so does an entry still waiting for its device
    /// check outside a Debug build (`ReturnTargets.mayOpen`).
    ///
    /// The host is trusted only when our own keyboard's press for this link sits (or sat) in the App Group, which no
    /// outside app can write, sent within the last 60 s and not used by an earlier trip (`useKeyboardPress`). A crafted
    /// `thumbfree://dictate?...&host=` link from another app or a web page has no such press, so its host is ignored and
    /// the app shows the swipe-back screen (never opens the named app).
    private func setupReturnTrip(link: UUID, host: String?, autoReturn: Bool) {
        let fromOurKeyboard = useKeyboardPress(link)
        guard autoReturn, fromOurKeyboard, let host, let target = ReturnTargets.target(forBundleID: host),
              ReturnTargets.mayOpen(target, debugBuild: ReturnTargets.isDebugBuild) else {
            let name = fromOurKeyboard ? ReturnTargets.displayName(forBundleID: host) : nil
            returnTrip = ReturnTrip(appName: name, phase: .swipeBack, firstReturn: false)
            pendingReturn = nil
            if !fromOurKeyboard { Self.returnLog.notice("trusted no") }
            return
        }
        returnTrip = ReturnTrip(appName: target.displayName, phase: .leaving, firstReturn: !(hasReturned?(host) ?? false))
        pendingReturn = target
    }

    /// Uses our keyboard's press for this take id as the proof for one round trip: true when it was sent within the last
    /// 60 s and no trip has used it yet. It is either already read (and by now likely removed) by `handleCommands()` this
    /// launch, or on disk right now: `handleCommands()` removes a press before or as it starts the take, often before this
    /// runs, and `openLink` calls it again right after this. The press is marked used, not forgotten, so that later read
    /// cannot make it new again.
    private func useKeyboardPress(_ take: UUID) -> Bool {
        let onDisk = { (try? self.shared.pendingCommands())?.first { $0.takeID == take && $0.kind == .press } }
        guard var press = seenPresses[take] ?? onDisk().map({ SeenPress(sentAt: $0.sentAt) }) else { return false }
        let trusted = !press.used && Self.isRecent(press.sentAt, now: Date())
        press.used = true
        seenPresses[take] = press
        return trusted
    }

    /// Within `seenPressSeconds` of `sentAt`, either way (a clock set back never stretches it past that).
    private static func isRecent(_ sentAt: Date, now: Date) -> Bool { abs(now.timeIntervalSince(sentAt)) <= seenPressSeconds }

    /// The cold take's first audio buffer arrived: open the host's URL scheme now, with no minimum display time. Any
    /// failure (no opener, `open()` returns false) falls back to the swipe-back screen. A Debug delay
    /// (`-TFReturnDelayMs`) is for trying other open timings on a phone; skipped while not active, same as the watchdog
    /// below.
    private func fireReturnIfReady() {
        guard let target = pendingReturn, let cold = coldTake, capture.takeID == cold else { return }
        pendingReturn = nil
        guard let url = URL(string: target.scheme), let openHost else {
            returnTrip?.phase = .swipeBack
            return
        }
        let attempt: @MainActor () -> Void = { [weak self] in
            guard let self else { return }
            let started = ContinuousClock.now
            self.returnAttemptAt = started
            openHost(url) { ok in
                let ms = Int((ContinuousClock.now - started) / .milliseconds(1))
                Self.returnLog.notice("open \(ok ? "hit" : "miss", privacy: .public) ms=\(ms, privacy: .public)")
                guard self.coldTake == cold else { return } // outlived its take: a later trip is not this one's to touch
                guard ok else {
                    self.returnTrip?.phase = .swipeBack
                    return
                }
                // Only while still leaving: a late hit after the fallback took ThumbFree nowhere, so it marks nothing and
                // the swipe-back screen stays.
                guard self.returnTrip?.phase == .leaving else { return }
                self.returnOpened = target.bundleID // marked returned only once the scene really reaches the background
                self.resolveIfOpened() // already gone by the time this ran: resolve now, not on some later return
            }
            self.startReturnFallback() // 1.5 s after the attempt, whatever the completion does
        }
        guard returnDelayMs > 0 else { return attempt() }
        returnTask = Task { [weak self] in
            try? await Task.sleep(for: .milliseconds(self?.returnDelayMs ?? 0))
            guard !Task.isCancelled, let self else { return } // the take or the session ended: nothing left to open
            guard self.appActive else { // never attempted: appActive's didSet falls back if the app returns to active
                self.awaitingActive = true
                return
            }
            attempt()
        }
    }

    /// 1.5 s (`fallbackDelayMs`; tests inject a short one) after the open attempt: acts only while the scene is still
    /// (or again) active. Still in front then, the switch did not happen (a scheme that did not resume, a completion
    /// that never came, or a Cancel on iOS's prompt), so fall back to the swipe-back screen and keep listening. Not
    /// active (a system "Open in ...?" prompt is up, or the switch already happened and we are backgrounded), this
    /// gives up instead and sets `awaitingActive`, so `appActive`'s didSet falls back once the app returns to active
    /// with the trip still `.leaving`. Cancelled when the take or the session ends. A hit cancels it too, but only once
    /// the scene has actually reached the background (`resolveIfOpened()`, from the open completion or from
    /// `inBackground`); a miss does not, but the watchdog is harmless by then: `returnTrip` is already `.swipeBack`, so
    /// `fallbackToSwipeBack()`'s own guard makes it a no-op.
    private func startReturnFallback() {
        returnTask?.cancel()
        returnTask = Task { [weak self] in
            try? await Task.sleep(for: .milliseconds(self?.fallbackDelayMs ?? 1_500))
            guard !Task.isCancelled, let self else { return }
            guard self.appActive else {
                self.awaitingActive = true
                return
            }
            self.fallbackToSwipeBack()
        }
    }

    /// Falls back to the swipe-back screen from an open attempt in flight, logging why (allowed fields only: this is the
    /// 1.5 s watchdog above, or `appActive`'s didSet catching a trip the delay task or the watchdog already gave up on).
    /// Cancels the watchdog too, so a late fallback here is never followed by a delayed attempt still opening the host.
    /// Forgets a hit too (the user cancelled iOS's prompt): swiping back by hand later is not a return to mark. A no-op
    /// once the phase is already `.swipeBack`, so it never logs the same open twice.
    private func fallbackToSwipeBack() {
        guard returnTrip?.phase == .leaving else { return }
        returnTrip?.phase = .swipeBack
        returnOpened = nil
        returnTask?.cancel()
        returnTask = nil
        let ms = returnAttemptAt.map { Int((ContinuousClock.now - $0) / .milliseconds(1)) } ?? 0
        Self.returnLog.notice("open fallback ms=\(ms, privacy: .public)")
    }

    /// A hit already happened (`returnOpened`) and the scene is in the background: ThumbFree really left, so the app is
    /// marked returned (the one-time "tap Open" line drops for it) and the trip is cleared now, rather than left for a
    /// later return to active to (wrongly) treat as one that never left. A hit while merely inactive (a system "Open in
    /// ...?" prompt, which the user may cancel) is not enough: `inBackground`'s didSet calls this again once the scene
    /// actually backgrounds. Cancels the watchdog too.
    private func resolveIfOpened() {
        guard let opened = returnOpened, inBackground else { return }
        onReturned?(opened)
        returnTrip = nil
        pendingReturn = nil
        returnAttemptAt = nil
        returnTask?.cancel()
        returnTask = nil
        returnOpened = nil
        awaitingActive = false
    }

    /// A report for a take that already ended (a later Insert here): the Delivery table, straight to history and outbox.
    /// Only for a pending or held-back item, as the contract says: a replayed report never undoes a typed take.
    private func applyLate(_ id: UUID, _ insertion: Insertion) {
        guard let item = (try? shared.outbox())?.last(where: { $0.takeID == id }),
              [.pending, .heldBack].contains(item.state) else { return }
        let row = DeliveryTable.row(for: insertion)
        updateRecord(id) { $0.status = row.status }
        setOutboxState(id, row.outbox)
    }
}
