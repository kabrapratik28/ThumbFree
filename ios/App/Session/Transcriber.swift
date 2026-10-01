import CoreML
import Foundation
import os
import TFCore
import TFEngine

/// The engine a session uses.
enum EngineSource: Sendable {
    /// Parakeet from this folder (v3 when its name says so, else v2). Nil: the model is missing ("No speech model yet.").
    case parakeet(URL?)
    /// The same text for every take that holds sound (`-TFFakeEngine` and fast tests).
    case fixed(String)
    /// Any engine call (tests: one that counts its calls, waits on cue or throws).
    case custom(@Sendable ([Float]) async throws -> String)
}

/// A take's text, or why there is none.
enum Transcription: Equatable {
    case text(raw: String, text: String, speech: Bool)
    case failed(TakeMessage)
}

/// Loads the engine when the first session starts and keeps it (a failed load, unless the model is missing,
/// is tried again when the next take starts). Each take gets its own live transcriber (`newTake()`), whose
/// engine jobs run here, and its chunk texts go through the text pipeline.
/// ponytail: the engine stays loaded until the app exits; unload at session end or on a memory warning when a device
/// shows the memory matters.
@MainActor final class Transcriber {
    /// Every phase change, for status.json.
    var onPhase: (EnginePhase) -> Void = { _ in }

    private static let log = Logger(subsystem: Brand.bundleID, category: "take")
    private var source: EngineSource
    private let placementSuite: String?
    private var load: Task<Void, Never>?
    /// The loaded engine's placement check, which holds that engine until it ends.
    private var placement: Task<Void, Never>?
    /// The model you left, until it lets go: its load and its placement check. The next load waits for it, so only one
    /// model is ever in memory.
    private var leaving: Task<Void, Never>?
    private var engine: ParakeetEngine?
    private var speechCheck: SpeechCheck?
    private var loadError: Error?
    /// Grows with every model change, so a load of the model you left never lands.
    private var generation = 0
    /// Stands in for the speech check (Silero) in tests.
    var hearsSpeech: (@Sendable ([Float]) async -> Bool)?

    /// `placementSuite`: the `UserDefaults` suite holding the engine's placement memory; nil is the app's
    /// own defaults. Tests pass a fresh one.
    init(_ source: EngineSource, placementSuite: String? = nil) {
        self.source = source
        self.placementSuite = placementSuite
    }

    var modelID: String {
        if case .parakeet(let folder) = source { return Self.variant(of: folder).rawValue }
        return "fixed-text"
    }

    /// The text rules' language: nil for the multilingual model, so the Dictionary changes exact matches only;
    /// English for everything else.
    var language: String? {
        if case .parakeet(let folder) = source, Self.variant(of: folder) == .v3 { return nil }
        return "en"
    }

    /// The engine a model folder holds, by its name ("parakeet-tdt-0.6b-v3", or the Mac's "...-coreml" copy); English for
    /// any other name (the UI tests' one-file fixture).
    nonisolated static func variant(of folder: URL?) -> ModelVariant {
        ModelVariant.allCases.first { folder?.lastPathComponent.hasPrefix($0.rawValue) == true } ?? .v2
    }

    /// False for Parakeet with no model folder: status.json says noModel, and keyboards offer to get the model.
    var hasModel: Bool {
        if case .parakeet(let folder) = source { return folder != nil }
        return true
    }

    /// The model the engine loads from now on: a download that finished, the model you chose, or none (nil) after a
    /// delete. What was loaded is dropped, so the next loadIfNeeded() loads this folder. The host calls it between takes.
    func useModel(_ folder: URL?) {
        guard case .parakeet(let current) = source, current != folder else { return }
        source = .parakeet(folder)
        generation += 1
        engine = nil
        speechCheck = nil
        leaving = Task { [load, placement] in
            await load?.value
            await placement?.value
        }
        load = nil
        placement = nil
        loadError = nil
    }

    /// Called at session start, so loading is never on the stop-to-text path.
    func loadIfNeeded() {
        guard load == nil else { return }
        loadError = nil
        load = Task { [leaving] in await loadNow(after: leaving) }
    }

    /// One take's live transcriber. Its engine jobs wait for the load first, one at a time and in
    /// order, so a cold take (the engine still loading) queues its chunks, and the transcriber keeps their audio.
    func newTake() -> ChunkedTranscriber {
        ChunkedTranscriber(transcribe: { [self] samples in try await engineText(samples) },
                           hasSpeech: { [self] samples in await hasSpeech(samples) })
    }

    /// The take's text from its live transcriber's result: the chunk texts through the text pipeline, with the
    /// Dictionary. Logs how the text was made, never the text.
    static func transcription(_ result: ChunkedTranscriber.Result, dictionary: [String], language: String?) -> Transcription {
        let outcome = result.outcome
        log.info("Take text: path \(outcome.path.rawValue, privacy: .public), \(outcome.engineRuns, privacy: .public) engine runs, \(outcome.runsAfterStop, privacy: .public) after the stop, \(Int(result.stopToResultMs.rounded()), privacy: .public) ms from the stop")
        let (raw, text) = TextPipeline.run(chunkTexts: outcome.texts, dictionary: dictionary, language: language)
        return .text(raw: raw, text: text, speech: outcome.heard)
    }

    /// Transcribe again: a saved take's audio, cut exactly as its live take was
    /// (`ChunkPlanner.chunks(of:)`, planned off the main actor), each chunk that may hold speech through the
    /// speech check and the engine; the last one gets the stop's zero fill, as a live take's final window does. When no
    /// chunk is loud enough, the speech check hears every chunk (Android's override for a take read again), so quiet
    /// speech is not lost; only with a speech check, since without one every chunk would pass. A speech check whose
    /// Silero model failed to load also hears every chunk (`SpeechCheck.init` never fails; it keeps its runner nil
    /// and fails open), so having one here is no proof anything was actually checked. `heard` is false when no chunk
    /// held speech.
    func transcribeAgain(_ samples: [Float]) async throws -> (texts: [String], heard: Bool) {
        loadIfNeeded()
        let chunks = await Task.detached { ChunkPlanner.chunks(of: samples) }.value
        await load?.value
        let quiet = !chunks.contains(where: \.mayHoldSpeech) && (speechCheck != nil || hearsSpeech != nil)
        var texts: [String] = []
        for (index, chunk) in chunks.enumerated() where chunk.mayHoldSpeech || quiet {
            let audio = Array(samples[chunk.start..<chunk.end])
            guard await hasSpeech(audio) else { continue }
            let zeros = index == chunks.count - 1 ? StopTailPolicy.fillSamples : 0
            texts.append(try await engineText(audio + [Float](repeating: 0, count: zeros)))
        }
        return (texts, !texts.isEmpty)
    }

    /// One engine run, once the load the session started is done. A job of a take cancelled while it waited never
    /// reaches the engine.
    private func engineText(_ samples: [Float]) async throws -> String {
        await load?.value
        if let loadError { throw loadError }
        try Task.checkCancellation()
        switch source {
        case .fixed(let text): return text
        case .custom(let transcribe): return try await transcribe(samples)
        case .parakeet:
            guard let engine else { throw EngineError.modelMissing(ModelVariant.v2.rawValue) }
            return try await engine.transcribe(samples).text
        }
    }

    /// The speech check (Silero), once loaded; it fails open.
    private func hasSpeech(_ samples: [Float]) async -> Bool {
        await load?.value
        if let hearsSpeech { return await hearsSpeech(samples) }
        return await speechCheck?.hasSpeech(samples) ?? true
    }

    /// `leaving`: the model you left when this load was made (read then, so a load never waits for itself).
    private func loadNow(after leaving: Task<Void, Never>?) async {
        switch source {
        case .fixed, .custom:
            onPhase(.readyCPU)
        case .parakeet(let folder):
            guard let folder else {
                loadError = EngineError.modelMissing(ModelVariant.v2.rawValue)
                return onPhase(.noModel)
            }
            let generation = generation
            onPhase(.loading)
            await leaving?.value // one model in memory at a time: the one you left lets go first
            guard generation == self.generation else { return } // you chose another model meanwhile
            do {
                let memory = if let placementSuite, let suite = UserDefaults(suiteName: placementSuite) { suite } else { UserDefaults.standard }
                let engine = try await ParakeetEngine(modelDirectory: folder, variant: Self.variant(of: folder), encoderUnits: Self.encoderUnits(),
                                                      placementMemory: memory)
                guard generation == self.generation else { return } // you chose another model meanwhile
                onPhase(.warming)
                try await engine.warmUp()
                // The downloaded folder holds the speech check too; the Mac's cached models keep it in a folder of its own.
                let hasSilero = FileManager.default.fileExists(atPath: folder.appendingPathComponent(SpeechCheck.modelName).path)
                var check: SpeechCheck?
                if let silero = hasSilero ? folder : DevModels.sileroDirectory() { check = await SpeechCheck(modelDirectory: silero) }
                let neural = await engine.usesNeuralEngine
                // The last check, then no suspension until the engine, its phase and its placement check are all in
                // place: a model change can never land between them (two engines, or a stale ready).
                guard generation == self.generation else { return }
                speechCheck = check
                self.engine = engine
                onPhase(neural ? .readyNeuralEngine : .readyCPU)
                // The placement check comes after readiness, in the background (it took 21.9 s on an iPhone
                // 16's first load); a take meanwhile runs on the loaded Encoder, and the answer applies from the next
                // load, so the status stays as loaded. It returns at once on the CPU or with an answer remembered.
                placement = Task(priority: .utility) { await engine.settlePlacement() }
            } catch {
                guard generation == self.generation else { return }
                loadError = error
                if Self.message(for: error) != .noModel { load = nil } // the next session tries again; a missing model stays missing
                onPhase(.failed)
            }
        }
    }

    /// iOS 27 allows the Neural Engine in the background only with the Background Inference entitlement, and
    /// keyboard takes are transcribed in the background. Until a build has it (Info.plist `TFBackgroundInference` YES),
    /// iOS 27 loads the Encoder on the CPU, and the status says readyCPU.
    nonisolated static func encoderUnits(osMajor: Int = ProcessInfo.processInfo.operatingSystemVersion.majorVersion,
                                         entitled: Bool = Bundle.main.object(forInfoDictionaryKey: "TFBackgroundInference") as? Bool ?? false)
        -> MLComputeUnits {
        osMajor >= 27 && !entitled ? .cpuOnly : .cpuAndNeuralEngine
    }

    static func message(for error: Error) -> TakeMessage {
        switch error as? EngineError {
        case .modelMissing?: .noModel
        case .loadFailed?: .loadFailed
        default: .engineFailed
        }
    }
}
