# The contract between the iOS app's parts

The engine, the text rules, the audio and session logic, and the app with its keyboard each own their files; these signatures and rules are the only things they rely on from each other. A part may add more public API, but must not change these without updating this file in the same change.

Conventions for every part:
- Swift 6 language mode, strict concurrency. Value types are `Sendable`. No force unwraps in production code.
- Tests use Swift Testing (`import Testing`, `@Test`, `#expect`). UI tests use XCTest.
- `TFCore` imports Foundation (and CryptoKit for file hashes) only: no UIKit, AVFoundation or CoreML. `TFEngine` may import CoreML and Accelerate.
- Plain English in every user-facing string: short sentences, no em-dashes, never name other dictation apps.
- Logs never contain transcript or field text.
- Run package tests with `tools/test-kit.sh [--filter X]`, app tests with `TF_SIM="<device>" tools/test-app.sh`.
- Git: one commit per change.

## Engine (`ThumbFreeKit/Sources/TFEngine/`)

```swift
public enum ModelVariant: String, Sendable, CaseIterable {
    case v2 = "parakeet-tdt-0.6b-v2"   // English
    case v3 = "parakeet-tdt-0.6b-v3"   // 25 languages
}

/// Loads Preprocessor (.cpuOnly), Encoder (`encoderUnits`: .cpuAndNeuralEngine or .cpuOnly, the app's choice;
/// anything else throws loadFailed), Decoder and JointDecision (.cpuOnly) from `modelDirectory` (a folder holding the four
/// .mlmodelc bundles and the vocabulary JSON). An Encoder prediction that fails on the Neural Engine reloads the Encoder on the
/// CPU, retries once and stays there (usesNeuralEngine turns false). A Neural Engine Encoder that fails to load falls back to the
/// CPU the same way, and a fallback always releases the failed Encoder before loading the CPU copy.
public actor ParakeetEngine {
    public init(modelDirectory: URL, variant: ModelVariant, encoderUnits: MLComputeUnits = .cpuAndNeuralEngine,
                placementMemory: UserDefaults = .standard) async throws
    public func warmUp() async throws
    /// Runs the Encoder placement check (`MLComputePlan`) once per Encoder file and iOS version, after the engine is ready, and
    /// only remembers the answer: a remembered "cpu" makes the next load use `.cpuOnly`. Never blocks a load or a take; callers
    /// run it in a utility task after warm-up.
    public func settlePlacement() async
    /// 16 kHz mono samples. At most `ParakeetEngine.maxSamples` (240_000). Short input is padded internally
    /// with `EnginePadding.pad`. Returns trimmed text (may be empty).
    public func transcribe(_ samples: [Float]) async throws -> EngineResult
    public static let maxSamples = 240_000
}

public struct EngineResult: Sendable, Equatable {
    public let text: String
    public let preprocessMs: Double
    public let encodeMs: Double
    public let decodeMs: Double
}

public enum EngineError: Error, Equatable {
    case inputTooLong(samples: Int)
    case modelMissing(String)
    case loadFailed(String)
}

/// Silero VAD speech check for one chunk, using silero-vad-unified-256ms-v6.2.1 (one score per 256 ms = 8 windows;
/// matches official Silero v6.2 on JFK with mean error 0.006). Speech when one score is at least 0.2775, or two scores
/// in a row are at least 0.15 (keeps every chunk Android's per-window rule keeps). Fails open (true). `modelName` names the folder.
public actor SpeechCheck {
    public init(modelDirectory: URL) async
    public func hasSpeech(_ samples: [Float]) async -> Bool
}

/// Engine-only padding, called only inside ParakeetEngine.transcribe (callers never pad; the WAV keeps the real audio): input under 16_000 samples gets 8_000 zeros on each side,
/// then trailing zeros up to 20_000 samples in total. Longer input is returned unchanged; empty input is returned empty
/// (transcribe([]) returns empty text without running a model).
public enum EnginePadding { public static func pad(_ samples: [Float]) -> [Float] }

/// Finds models for development and tests. $TF_MODELS_DIR (else ~/Library/Application Support/FluidAudio/Models) is the
/// folder holding the model folders; a model's folder is <rawValue> or <rawValue>-coreml.
public enum DevModels {
    public static func directory(for variant: ModelVariant) -> URL?
    public static func sileroDirectory() -> URL?
}
// Also public: ParakeetEngine.usesNeuralEngine (Bool; picks EnginePhase.readyNeuralEngine vs readyCPU),
// SpeechCheck.modelName, and Bench (the WER helpers used by tests and tfbench).
```

## Text (`ThumbFreeKit/Sources/TFCore/Text/`)

```swift
public enum ChunkJoin { public static func join(_ texts: [String]) -> String }
public enum Fillers { public static func remove(_ text: String, language: String?) -> String }
public enum Normalize { public static func apply(_ text: String) -> String }

public enum CustomWords {
    public static func parse(_ raw: String) -> [String]
    public static func correct(_ text: String, entries: [String], exactOnly: Bool) -> String
    /// false only for English ("en", "en-US"...); true for every other language and for nil (multilingual model).
    public static func exactOnly(language: String?) -> Bool
    public static func riskyEntries(_ entries: [String]) -> [String]
    public enum EntryProblem: Error, Equatable { case blank, duplicate, tooLong, several }
    public static func checkEntry(_ raw: String, existing: [String], editing: String?) -> Result<String, EntryProblem>
}

// Also public for the Dictionary tab: CustomWords.maxEntries (500), CustomWords.maxChars (60),
// EntryProblem.message: String?, CustomWords.riskyNote(_:) -> String?, CustomWords.ListAddition
// (entries, added, duplicates, tooLong, notFitting, summary), CustomWords.addList(_:to:) -> ListAddition.

public enum CursorFormatter {
    public enum Field: Sendable, Equatable { case text, url, email }
    /// before/after nil means unreadable. capsExpected nil means unknown.
    public static func payload(text: String, before: String?, after: String?, capsExpected: Bool?,
                               field: Field, trailingSpace: Bool) -> String
}

/// Order (Android's): join -> custom words -> fillers -> normalize. Every step is a total function: it never
/// throws and never traps; custom words returns its input on a loop or after 64 passes.
public enum TextPipeline {
    public static func run(chunkTexts: [String], dictionary: [String], language: String?) -> (raw: String, text: String)
}

// Clean up (issue #1): what the on-device model is asked, what may replace a take, and where.
public enum CleanupStyle: String, Codable, Sendable, CaseIterable { case clean, shorter, friendly, professional, simple }
public enum CleanupPrompt {
    public static let rules: String                                   // instruction v2, word for word
    public static func instructions(_ style: CleanupStyle) -> String  // rules, plus one line for every style but Clean
    public static func prompt(take: String) -> String                 // "Text: <take>\nCleaned text:"
}
/// The answer trimmed (an echoed "Cleaned text:" label and wrapping quotes dropped), or nil: empty or unchanged; a
/// chatter opener the take lacks ("Sure", "Here is", "Here's", "I can't", "I cannot", "As an AI"); under 40% of the
/// take's words; another main alphabet; a negation kind of the take missing ("not"/"n't", "never", "no longer"); a
/// digit group of the take not in the answer's digits, in order; for Clean, more than max(2, 20% of the answer's words)
/// words not in the take (a word with a digit is a number form, never new).
public enum CleanupCheck { public static func accept(take: String, output: String?, style: CleanupStyle) -> String? }
public enum CleanupReplace {
    /// The text before the cursor ends with the typed take, as much as iOS shows: its last 16 characters or all of it.
    public static func matches(before: String, typed: String) -> Bool
    /// The text before the take when iOS shows all of it; nil (unknown to the cursor formatter) otherwise.
    public static func beforeTake(before: String, typed: String) -> String?
}
```

## Audio, session, IPC, history and models (`ThumbFreeKit/Sources/TFCore/{Audio,Session,IPC,History,Models}/`)

```swift
// Audio (16 kHz mono Float samples everywhere)
public struct FrameVerdict: Sendable, Equatable { public let isSpeech: Bool; public let levelDBFS: Float; public let loud: Bool } // loud = above -55 dBFS
public struct SpeechGate { public init(); public mutating func push(_ frame: ArraySlice<Float>) -> FrameVerdict } // 480-sample frames
public struct ChunkConfig: Sendable { public static let window15s: ChunkConfig } // 8 s silence cut, 11 s pause cut, 14.5 s cap
public struct Chunk: Sendable, Equatable { public let start: Int; public let end: Int; public let mayHoldSpeech: Bool } // absolute sample indices
public struct ChunkPlanner {
    public init(config: ChunkConfig = .window15s)
    /// Feed one frame's verdict; returns the chunk that just closed, if any.
    public mutating func push(_ verdict: FrameVerdict) -> Chunk?
    /// Closes the open chunk at the stop.
    public mutating func finish(endSample: Int) -> Chunk
    /// Re-plans a whole recording (Transcribe again) exactly as the live take was cut.
    public static func chunks(of samples: [Float], config: ChunkConfig) -> [Chunk]
}
public struct StopTailPolicy: Sendable { /* exact type: ThumbFreeKit/Sources/TFCore/Audio/StopTailPolicy.swift; Android rule: quiet end / hangover / 350 ms cap; zero fill to cap length (fillSamples) */ }
public final class WavWriter { public init(url: URL) throws; public func append(_ samples: [Float]) throws; public func sync() throws; public func finish() throws -> Int }

// Session: pure reducer for one take (the app runs the effects)
public enum TakePhase: String, Codable, Sendable { case idle, recording, stopping, transcribing, delivering }
public struct TakeReducer { /* exact TakeState, TakeEvent, TakeEffect and reduce: ThumbFreeKit/Sources/TFCore/Session/TakeReducer.swift (it ignores presses naming an ended take, and the 2-minute silence stop covers any recording take). The delivering phase follows the Delivery table below (insertion commands and the app's 3 s timeouts). After a restart the app ignores presses sent before its launch; a launch link naming a take from before this launch starts a fresh cold take in its place instead of reusing the old id (see "Keyboard status and the stale link" below). */ }

// IPC between keyboard(s) and app. Keyboards only create command files; the app only writes status.json,
// outbox.json and cleanups.json and deletes command files after handling them. Unique file names, so several keyboard processes
// (one per host app) never collide. Handling is idempotent: a crash before the delete replays the command harmlessly.
public struct InsertTarget: Codable, Sendable, Equatable {
    public let documentID: UUID      // textDocumentProxy.documentIdentifier at press
    public let contextHash: String   // SHA-256 hex of the 32 characters before and after the cursor at press (never the text itself)
}
public struct KeyboardCommand: Codable, Sendable, Equatable {
    public enum Kind: String, Codable, Sendable {
        case press, release, cancel        // key down and key up (the app's reducer applies the 300 ms tap-or-hold rule from sentAt); the X button
        case insertionBegan                // written durably BEFORE insertText
        case insertionConfirmed            // insertText ran and the read-back shows the text
        case insertionUnverified           // insertText ran but the read-back could not confirm it
        case insertionHeldBack             // not inserted: another field (documentID or contextHash differ), or no text proxy
        case ping
        case clean                         // Clean up: tidy `text` (the take as typed) in `style` (nil: the app's default)
        case cleanBegan, cleanConfirmed, cleanUnverified // Clean up's replacement: on disk before the take is deleted; the read-back
    }
    public let id: UUID; public let takeID: UUID; public let kind: Kind
    public let target: InsertTarget?     // set on press; for a cold take also on the stop command (see below)
    public let sentAt: Date
    public let text: String?; public let style: CleanupStyle?   // a clean command's; nil for every other kind
}
public enum CleanupAvailability: String, Codable, Sendable { case ready, off, appleIntelligenceOff, notEligible, notReady, unsupportedLanguage }
public struct CleanupResult: Codable, Sendable, Equatable {   // the app's answer to one clean command (requestID = its id)
    public enum State: String, Codable, Sendable { case done, failed, paused, unavailable }
    public let requestID: UUID; public let takeID: UUID; public let state: State
    public let text: String?             // done: the answer, already through CleanupCheck
    public let resetAt: Date?            // paused: Apple's rate limit ends then (iOS 27), nil when iOS gives no date
    public let createdAt: Date
}
public enum SessionPhase: String, Codable, Sendable { case off, starting, ready, ending }
public enum EnginePhase: String, Codable, Sendable { case unloaded, loading, warming, readyNeuralEngine, readyCPU, failed, noModel } // noModel: no speech model downloaded yet
public struct HostStatus: Codable, Sendable, Equatable {
    public var session: SessionPhase; public var engine: EnginePhase; public var micOn: Bool
    public var takeID: UUID?; public var take: TakePhase; public var level: Float
    public var takeStartedAt: Date?   // when the live take began, on the wall clock as updatedAt; nil with no take, and from an app version without the field
    public var expiresAt: Date?; public var updatedAt: Date
    public var message: String?   // user-facing outcome text, for example "No speech heard."
    public var cleanup: CleanupAvailability?   // Clean up's state; nil from an app without it (no sparkle)
}
public struct OutboxItem: Codable, Sendable, Equatable {
    public enum State: String, Codable, Sendable { case pending, typed, unverified, heldBack }
    public let takeID: UUID; public let text: String; public let target: InsertTarget?
    public let pinnedAt: Date?           // the sentAt of the keyboard command whose target this is (see "Delivery into the right field")
    public var state: State; public let createdAt: Date
}
public struct SharedStore: Sendable {            // files in the App Group container, atomic writes (temp file, then rename)
    public init(directory: URL)
    public func append(_ command: KeyboardCommand) throws        // commands/<sentAt nanoseconds, 20 digits>-<id>.json
    public func pendingCommands() throws -> [KeyboardCommand]    // sorted by sentAt, then id
    public func remove(_ command: KeyboardCommand) throws        // app only, after handling
    public func write(_ status: HostStatus) throws               // status.json
    public func status() throws -> HostStatus?
    public func write(_ outbox: [OutboxItem]) throws             // outbox.json (newest last, at most 20)
    public func outbox() throws -> [OutboxItem]
    public func write(_ cleanups: [CleanupResult]) throws        // cleanups.json (newest last, at most 10), app only
    public func cleanups() throws -> [CleanupResult]
}
public enum DarwinName {
    public static let command = "io.github.kabrapratik28.thumbfree.command"
    public static let status = "io.github.kabrapratik28.thumbfree.status"
}

// Delivery: one table shared by the outbox, the history status and the keyboard commands.
// | What happens                                          | Outbox state | History status |
// | text saved, keyboard told                             | pending      | staged         |
// | keyboard sends insertionBegan                         | pending      | inserting      |
// | keyboard sends insertionConfirmed                     | typed        | inserted       |
// | keyboard sends insertionUnverified                    | unverified   | unverified     |
// | keyboard sends insertionHeldBack                      | heldBack     | notInserted    |
// | no keyboard answer within 3 s (nothing began)         | pending      | notInserted    |
// | began, then no answer within 3 s, or the app restarts | unverified   | needsReview ("May already be in the field") |
// A later "Insert here" tap on a pending or heldBack item runs the same cycle again for that take. Nothing is retried automatically.
// Take ids: a press that starts a take always carries a fresh UUID; a stop press (or release) names the live take.
// The reducer ignores a press that names a take it already ended, and the app never deletes a take folder it did
// not create in the current take (a failed create because the folder exists must not discard the existing take).
// Starting a take: the app leaves a press command on disk while it cannot record (no live session and not in the
// foreground). A keyboard whose press file is still on disk after 150 ms opens the app with the dictate link.
// Cold takes (the take whose press opened the app): switching apps gives the field a new documentIdentifier, so the
// keyboard that sends the stop (the second press, or the release of a hold) also sends `target` = the field where the
// user stopped, and the app uses that target for a cold take. Takes inside a live session keep the target from their press.

// History: one folder per take: <root>/<uuid>/take.json and audio.wav
public enum TakeStatus: String, Codable, Sendable {
    case recording, transcribing, staged, inserting
    case inserted, unverified, notInserted, noSpeech, cancelled, failed, interrupted, needsReview
}
public struct TakeRecord: Codable, Sendable, Identifiable, Equatable {
    public let id: UUID; public let order: Int; public let startedAt: Date
    public var durationMs: Int; public var modelID: String; public var status: TakeStatus; public var error: String?
    public var rawText: String?; public var text: String?; public var partialText: String?; public var chunksDone: Int
    public var insertedText: String?; public var retranscribed: Bool; public var starred: Bool
}
public struct HistoryStore: Sendable {
    public init(root: URL)
    public func create(_ record: TakeRecord) throws
    public func update(_ record: TakeRecord) throws
    public func all() throws -> [TakeRecord]          // newest first by order
    public func delete(_ id: UUID) throws             // audio first, then the folder
    public func audioURL(for id: UUID) -> URL
    public func nextOrder() throws -> Int
}
public enum Retention { public static func idsToDelete(_ takes: [TakeRecord], keepDays: Int?, keepCount: Int?, now: Date) -> [UUID] } // nil = no limit

// Models
public struct ModelFile: Sendable, Equatable { public let path: String; public let bytes: Int64; public let sha256: String }
public struct ModelEntry: Sendable, Equatable, Identifiable {
    public let id: String; public let displayName: String; public let summary: String
    public let repo: String; public let revision: String; public let files: [ModelFile]
    public let languageHint: String?   // "en" for v2, nil for v3
}
public enum ModelCatalog { public static let all: [ModelEntry]; public static let defaultID: String }
// ModelVariant(rawValue: entry.id) maps a catalog entry to the engine; nil (unknown id) means "No speech model yet."
```

## Speed: the live take (`ThumbFreeKit/Sources/TFCore/Live/LiveTake*`, `ThumbFreeKit/Sources/TFEngine/ChunkedTranscriber.swift`, `tfreplay`)

The app uses these through `ChunkedTranscriber`:

```swift
// Speed (ThumbFreeKit/Sources/TFCore/Live/LiveTake.swift, ThumbFreeKit/Sources/TFEngine/ChunkedTranscriber.swift)

/// One take's chunk, speculation and stop rules as values. The app uses it through ChunkedTranscriber.
public struct LiveTake: Sendable {
    public enum Kind: String, Sendable { case chunk, speculation, optimistic, afterTail }
    public struct Job: Sendable {   // one engine run: `samples` (what the speech check hears), then `zeros` zeros
        public let id: Int; public let kind: Kind; public let start: Int; public let end: Int
        public let zeros: Int; public let samples: [Float]; public var engineInput: [Float]
    }
    public struct Outcome: Sendable, Equatable {
        public enum Path: String, Sendable, CaseIterable { case speculation, optimistic, afterTail, silent }
        public let texts: [String]; public let heard: Bool; public let path: Path
        public let engineRuns: Int; public let runsAfterStop: Int
    }
    public init(config: ChunkConfig = .window15s, speculateAfterMs: Int? = 300, optimistic: Bool = true)
    public mutating func append(_ samples: [Float])
    public mutating func stop(nowMs: Int) -> StopTailPolicy.End?
    public mutating func check(nowMs: Int) -> StopTailPolicy.End?
    public mutating func endTail()
    public mutating func nextJob() -> Job?
    public mutating func finished(_ job: Job, text: String, heard: Bool)
    public var outcome: Outcome? { get }
    public var partialTexts: [String] { get }
}

/// One per take: runs a LiveTake's jobs on the engine one at a time, the speech check beside each.
public actor ChunkedTranscriber {
    public struct Result: Sendable, Equatable { public let outcome: LiveTake.Outcome; public let stopToResultMs: Double }
    public init(speculateAfterMs: Int? = 300, optimistic: Bool = true,
                transcribe: @escaping @Sendable ([Float]) async throws -> String,
                hasSpeech: @escaping @Sendable ([Float]) async -> Bool = { _ in true })
    public func append(_ samples: [Float])
    public func stop(nowMs: Int) -> StopTailPolicy.End?
    public func check(nowMs: Int) -> StopTailPolicy.End?
    public func finish() async throws -> Result   // the engine's error, CancellationError after cancel(), or ChunkedTranscriber.Error.noOutcome (a bug, not a user cancel)
    public func cancel()
    public func idle() async
    public var partialTexts: [String] { get }
}
```

## How SessionHost uses ChunkedTranscriber

1. Make one `ChunkedTranscriber` per take when the take starts: `ChunkedTranscriber(transcribe: { try await engine.transcribe($0).text }, hasSpeech: { await speechCheck?.hasSpeech($0) ?? true })`. While the engine still loads (a cold take), pass a closure that awaits the load first: jobs wait in order, and the transcriber keeps the audio of every chunk still waiting.
2. Feed it every block the take records, in order, from the task that writes the WAV: the pre-roll first, then the take, then the stop tail. The live planner then sees exactly the WAV's samples, so Transcribe again (`ChunkPlanner.chunks(of:)`) cuts where the live take was cut. Use `await transcriber.append(block)` in that loop, never a new `Task` per block: order matters.
3. The transcriber owns the stop tail: `stop(nowMs:)` at the stop, then `check(nowMs:)` after each tail block and on the 50 ms timer. When either returns non-nil, the tail is done (send `tailDone`), and `finish()` gives the result. `TakeCapture` drops its `StopTailPolicy` and its in-memory samples (58 MB for 15 minutes): `LiveTake` keeps only the last closed chunk, the open one and any chunk still waiting (about 30 s). `TakeCapture` can keep its own `SpeechGate` for the level ring, the silence clock and `mayHoldSpeech`: two gates over the same samples give the same verdicts.
4. `finish()` returns a `Result`. `outcome.texts` go to `TextPipeline.run(chunkTexts:dictionary:language:)`; `outcome.heard` is the reducer's `speech` (false when Silero heard no window); `path`, `engineRuns`, `runsAfterStop` and `stopToResultMs` are for a log line with no text. In the final window the last two chunks merge only when both have sound, since merging a silent chunk would run a finished chunk again for nothing.
5. Call `cancel()` at `saveOutcome` and `discard`: nothing more reaches the engine and a pending `finish()` throws `CancellationError`. An engine error (after the engine's own CPU retry) makes `finish()` throw it; map it with `Transcriber.message(for:)`.
6. The transcriber closes and transcribes chunks itself, so the host needs no `chunkClosed` or `transcribeChunk`. For `savePartial`, read `await transcriber.partialTexts` (the closed chunks' texts in order, up to the first still out) on the 1 s tick and send `chunkTextReady(partial:chunksDone:)` when the count grows.
7. `finish()` called before the tail ended (a call, a lost route, End session) ends the tail where the audio stopped. A take nobody stopped is stopped there first, so with the optimistic stop on (the default) its final window still gets the 5,760 zeros of `StopTailPolicy.fillSamples`, and `outcome.path` reads `.optimistic` or `.speculation`. For an interrupted take the path only says which run made the text.
8. What bounds the stop now is the stop tail (the 100 ms hangover, the 350 ms cap), not the engine. In a room above -55 dBFS every frame is sound, so the tail runs to the cap and the final window runs again after it (about 400 ms). Speculation and the optimistic stop then never help: no frame counts as quiet, so no speculation starts and the optimistic run is always thrown away. The replay cannot show this, because its clips end in digital silence. This is the main open risk to stop time on a phone. The likely fix is to judge quiet against the session's own noise floor (measured from the pre-roll) instead of a fixed -55 dBFS; measure real rooms on a device first. Speculation adds about one engine run per pause of 300 ms or more; count them if battery shows it.
9. The tail's hangover and cap are judged per audio block, so the input block size adds to the stop time directly. The replay assumes 20 ms blocks (Android's read size). Ask the audio session for a short IO buffer (`setPreferredIOBufferDuration(0.02)` or less) and measure the tap's real block size on a device.

## App and keyboard (`App/`, `Keyboard/`, `AppTests/`, `UITests/`)

Consumes all of the above. Maps iOS keyboard types to `CursorFormatter.Field` (URL and email keyboards -> .url/.email, else .text). Owns: `AudioSource` (mic and a file-backed source for tests), `SessionHost` (runs the reducer's effects: capture, engine, pipeline, history, outbox), the Darwin notification transport, the keyboard UI and inserter, opening the app from the keyboard, the Home tab and the session screen.

### Welcome and downloads

With no speech model the host publishes `engine: .noModel` from launch and starts no take for a keyboard press or a dictate link; the keyboard then writes no command for a press and opens `thumbfree://model` instead (the app shows Home's one card, or the welcome where it is: its step 1 gets speech ready). Composition: `AppEnvironment.make()`, `RootView(host:models:dictionary:settings:tab:onWelcome:)`, `HomeView(host:models:)`, `WelcomeView(host:models:onDone:)` (it reads `models.active`, so every step follows the language choice) and `TryItView(host:models:onDone:inWelcome:)`, the first try with the ThumbFree keyboard in a box of the app's own: the welcome's step 3 (`inWelcome`, under the bar of steps) and Home's Try it, in a sheet. One mapper says what stops dictation now (`SetupBlocker`, `UI/SetupCard.swift`), and each screen shows only that one, with its few words and its one fix (`words(_:model:size:)`, `Action`): `forHome` (a refused microphone, then the keyboard not in iOS's list, then speech, then what only the try finishes, "Try ThumbFree" with Try it: a keyboard in the list but not yet seen with Full Access, or a microphone iOS has not asked about, which has no card of its own), `forTry` (a refused microphone, a keyboard not in iOS's list, then speech; iOS asks for the microphone at the first take, the box is where a keyboard not yet seen first comes up, and the engine's load of a model that is here stops nothing: the box comes with one small line, and a take waits for the engine) and `speech(phase:waiting:engine:)` (no model, the download with its percentage, waiting for Wi-Fi or a connection, checking the files, paused, no space, the check failed, then the engine's load or its failure). Ready means usable now (`SetupRows.ready`): the microphone allowed, the keyboard seen with Full Access (`KeyboardStatus.ready`) and the engine loaded (`.readyNeuralEngine` or `.readyCPU`, `SetupRows.modelDone`). Home's top (`HomeView.top`) is "Ready · works offline" only then; else one blocker's card and nothing else, or, when only the engine's load of a model it loaded since its download is left (`SpeechModel.loadedBefore`, `TFModelLoaded` in the app's defaults: `SessionHost.onEngineReady` reports the folder that loaded and `SpeechModels.engineLoaded` marks the model with that folder, not the model chosen meanwhile, since a choice made during a take waits for its end; a new download or a Delete clears it), a quiet "Getting ready" chip in the card's place; the first load after a download keeps the card. Home gets the engine ready whenever the model is here. Ready, it offers Try it while `SessionHost.textTakes` (takes that gave text, ever: `TFTextTakes` in the app's defaults; when it is missing, as after an update from a version without it, it starts at 1 if History holds a take, else 0, and is saved) is 0, and Put ThumbFree first after that. The welcome's steps (`WelcomeView.Step`, saved in `TFWelcomeStep` with the numbers of earlier versions: welcome 0, keyboard 1, tryIt 2, and wait 3, the microphone's page's number, so a flow saved there waits for speech or goes on once it is ready; the bar counts the wait in step 1): step 1 asks "Which language do you speak?", the phone's guess first and filled (`SpeechModels.startsMultilingual(_:)` over `Locale.preferredLanguages`); the tap chooses that model (`SpeechModels.choose(_:)` for a model that is here, else `chooseToDownload(_:)` and `SpeechModel.download()`) and fades the answers into the wait, on the same screen, under the example, which holds still on its typed reply: `SetupBlocker.speech` in the welcome's words (`Place.welcome`, where the engine's load is "Almost ready"), the download's percentage and bar (`WelcomeView.progress`), each stop with its one fix and Change language at the bottom, and the engine loaded once the model is here (`SessionHost.prepareEngine`, again each time the app comes to the front). Change language is iOS's own sheet (`changesLanguage(_:models:)`: "Change language?", Use the other model, which `chooseToDownload(_:)`s it, or Keep this one), here and on the try's download pages. Speech ready with the app in front, "Speech ready" shows for `WelcomeView.readyBeat` (0.4 s; none with VoiceOver), then step 2, or the try when step 2 has nothing to do. There is no microphone page: iOS asks at the first take. Step 2's page (`WelcomeView.keyboardPage`) is its guide until a trip away (`TFWelcomeTrip`, set when the app leaves the front during the step), then the keyboard still off (the guide again, Open Settings again); back in front with the keyboard added after a trip, or ready (seen with Full Access) at any time, the try comes at once, and the list alone never moves the step on, since iOS may list the keyboard before any trip (after a reinstall). Page 2 decides what it shows only with the app in front (`WelcomeView.keyboardPage(from:status:wentAway:active:)`): while the app is away it keeps exactly what it showed, guide included, since a page that changed then would take the guide away and close the window floating over Settings (after a reinstall iOS lists the keyboard before any trip, and the trip flag is set as the app leaves). The trip flag is saved at `.background` and applied at the next `.active`, a relaunch included. At launch, a flow saved past step 1 whose model is not downloaded, or not loaded once since its download, comes back at the wait (`WelcomeView.restoreStep`). The ready step waits while Change language's sheet is up. Step 3 is `TryItView(inWelcome: true)`: its bar, and no Back. Under its line, one small note at most (`TryItView.note`): "Getting speech ready…" while the engine loads a model that is here, or "iOS will ask for microphone access." with the ThumbFree keyboard up and the microphone not asked yet. Not now on steps 2 and 3, and Done, finish the welcome (`TFWelcomeDone`). The try's line follows the live take (`HostStatus.take`), and it worked (`TryItView.stage`) only once the keyboard came up in its box, a take gave text since the screen came up, and the box has words; leaving the try cancels a take that has not given its text yet (`TryItView.takeToCancel`, the reducer's `.cancel`, which keeps the take in History), but never the dictate link's take (`SessionHost.coldTake`), which the keyboard in another app may start while the try is still up. Someone who turned Full Access down learns it from the keyboard's own bar ("Full Access is off. Turn it on to dictate."), and after 8 s on the switch without the keyboard seen with Full Access the try shows a quiet Open Settings (`TryItView.fullAccessWait`); Settings' full rows keep the keyboard's row and its small Open Settings. A plain UI-test launch shows the welcome unless it passes `-TFWelcomeDone YES`, and `-TFOpenTry YES` (Debug) opens the try over Home, its box the one the keyboard UI tests and the dump and store tools type in (`ThumbFreeUI.launchTry`), with `-TFTryStage switchKeyboard|tapMic|recording|transcribing|worked` (Debug) holding it at that stage (the tests hold `tapMic`, so a take that gives text never takes the keyboard down). For screenshots (Debug): `-TFHoldDownload <percent>`, `-TFHoldSpeech wifi|connection|checking|paused|noSpace|checkFailed|missing` (`SpeechModel.hold`), `-TFHoldEngine loading|failed` (`SessionHost.holdEngine`, after which the engine never loads), `-TFSetupKeyboard notAdded|added|ready` (`KeyboardStatus.current`), `-TFGuideBeat <n>` (a guide held on its beat n, with the look it has while it plays; the keyboard step's still picture at that beat) and `-TFGuidePaused YES` (every guide still, Back and Next shown, as with Reduce Motion). The model lives at Application Support/Models/<id> on a phone (downloaded and verified); the Simulator keeps using the Mac's cached models. Every drawing sits in an `IllustrationFrame` (EXAMPLE, IN SETTINGS, HOW TO SWITCH): continuous corners, a screen panel inside it on the 16-point inset with corners parallel to the frame's (`DiagramScreen`, `innerRadius`), lines at the widths drawn (0.75 and 1 point), no taps, and one image to VoiceOver, exactly the frame's size. A drawing keeps its shape at its own size or less (`Shrinks`), and the frames keep to their heights: the IN SETTINGS frame at most 340 points, Home's walkthrough 320, the try's HOW TO SWITCH 152. A `TapCue` (a sunflower ring with a soft glow and a Tap, Hold or Swipe pill, `TapTimeline`'s 1.3 s) shows where to tap. The keyboard step's floating guide (`FloatingGuide`) is a video of five beats of 1.3 s (Keyboards; ThumbFree; Allow Full Access; iOS's question, named in words beside a shield and never drawn; the way back at the top left), 322 x 274 points, its frames drawn by `GuideFrame` from the step's own pieces (`SettingsRowDiagram`, `SettingsSwitchDiagram`, `TapCue`), each tap's ripple and change at 30 frames a second (`FloatingGuide.shots`), version 4. It is made on the iPhone during step 1 and kept by scheme, version and build; the step shows that same video in its IN SETTINGS frame, so what floats over Settings is what the person just saw. With Reduce Motion or VoiceOver, or when the page leaves the frame under 250 points, the step shows a list of the five rows instead (`SetupGuideView.names`), and with less room still, nothing.

### Keyboard status and the stale link

The app rewrites status.json about once a second while a take or the session is live. A keyboard treats a status over 5 s old (`HostStatus.isFresh(now:)`, `HostStatus.liveTake(now:)`) as from an app that went away: no live take, no "Ready, mic on", and no "Listening"/"Transcribing" for its own take (`KeyState.from`). `KeyboardClient.refresh` adopts a fresh live take that replaced a stale link's dead one, so "Opening ThumbFree" and delivery do not stick on it. `SessionHost.openLink(_:)` starts a fresh cold take in place of a link naming a take that started before this launch, and returns the take actually running (nil if another take already owns the session); the session screen shows the take `openLink` returns, not the link's id. The keyboard sets `hasDictationKey = true` in `KeyboardViewController.viewWillAppear`.

### The keyboard mark, and withdrawing a take's text

The keyboard writes `IPC/keyboard-seen` (`KeyboardMark.record`) in `KeyboardViewController.viewWillAppear` when it has Full Access; the app reads it (`KeyboardMark.lastSeen`, `KeyboardStatus`) for Settings' keyboard row and the setup blockers (`SetupBlocker`), but only while iOS's list of keyboards (`AppleKeyboards`) has the keyboard: one removed from the list is not added, whatever its mark says (a list that cannot be read leaves it to the mark), and each screen that follows the status (`followsKeyboard`) reads it again every time the app comes to the front, also once ready. The try screen (`TryItView`) takes a mark newer than its own appearance as the ThumbFree keyboard coming up in its box, so the keyboard writes it on every appearance, not only the first. The app withdraws a take's text before History changes: `SessionHost.deleteTake`, `clearHistory` and `transcribeAgain` each drop the take's outbox item first, so Delete, Clear all and Transcribe again never leave an item a keyboard could still offer. `KeyboardClient.insertHere` and `copy` reread the outbox item by takeID before acting, so either types or copies only what the outbox still has, if anything; `refresh` drops the chip outright once the item is gone (Delete or Transcribe again arriving while the chip is up), not only on the next Insert here. Copy, in the app (`HistoryView.copy`) and in the keyboard (`KeyboardClient.copy`) alike, writes the pasteboard with `options: [.localOnly: true]`: never the Universal Clipboard.

### Keyboard and automatic return

The private-API code sits behind `TF_AUTO_RETURN`, a Swift condition with a matching preprocessor macro, on in Debug and Release (`project.yml`), and `AutoReturn.enabled` is false without it. The dictate link is `thumbfree://dictate?take=<uuid>[&host=<bundle id>]` (`DictateLink.url(take:host:)`, `DictateLink.host(from:)`); without `host` it is the old link byte for byte. The keyboard adds `host` only with Full Access and the setting on, and only when the arbiter's pid read at the mic tap is the pid of the app it serves. The app trusts `host` only when our keyboard's press command for that take id sits (or sat) in the App Group, which no outside app can write: sent within the last 60 s and used for one round trip only (`SessionHost.seenPressSeconds`); otherwise it shows the swipe-back screen. App Group keys (UserDefaults suite `Brand.appGroupID`): `TFAutoReturn` (Bool, on when absent; `AutoReturn.enabled`, `AutoReturn.setEnabled(_:)`, which the Settings switch calls; a Debug launch argument `-TFAutoReturn NO` overrides it) and `TFReturnedApps` (the bundle ids ThumbFree has taken you back to, `AutoReturn.hasReturned(to:)` and `markReturned(to:)`, marked only once the scene reaches the background after a successful open, so the one-time "If iOS asks, tap Open" line shows until then). The app side: `SessionHost.openLink(_ link: UUID, host: String? = nil, autoReturn: Bool = false) -> UUID?` and `SessionHost.returnTrip: ReturnTrip?` (`appName`, `phase` `.leaving` or `.swipeBack`, `firstReturn`), shown by `SessionScreen(status:returnTrip:onEnd:)`; `AppEnvironment` injects `openHost`, `onReturned` and `hasReturned`. The app opens only a scheme from `ReturnTargets`, and a build other than Debug never opens an entry still marked `needsDeviceCheck` (`ReturnTargets.mayOpen(_:debugBuild:)`), which a test iPhone clears once the scheme lands back on the same screen. A custom URL scheme is not bound to one app (another app can register the same one); the URL is only the fixed scheme and never carries text. Universal links, bound to the app's own domain, are the stronger option. The keyboard side: `KeyboardClient.hostBundleID: String?` (set at the mic tap, put in the link), and `KeyboardClient.showsGlobe` is gone. The keyboard is `KeyboardBar` (SwiftUI: the status at the left, the mic at the right, the delivery chip in the bar's place) above `KeyplaneView` (UIKit: every key drawn and hit-tested in one view) inside `KeyboardInputView` (the click sound); the pure key model is `Shared/Keyplane.swift`. Shorter bar words: `KeyState` reads "Full Access is off. Turn it on to dictate.", "Speech isn't ready. Tap the mic to open ThumbFree." and "Tap the mic. ThumbFree opens and listens." (the other states as before, except that a recording take reads "Recording", not "Listening"), and the chip reads "Not typed in." or "The text may not have arrived." (VoiceOver hears "Not typed in. Tap Insert here or Copy."). The `hostReturn` log lines (subsystem `Brand.bundleID`, no user text): the keyboard's `host trusted|untrusted pairs=<n> hostPid=<pid> seen=<pid|none> bundle=<id|none>` at the mic tap, the bundle id logged as private; the app's `open hit|miss ms=<n>`, `open fallback ms=<n>`, and `trusted no` for a host it ignored (no press of ours, too old, or already used).

### Settings, background downloads, retention and Start ThumbFree

Downloads: each model downloads into `Application Support/Models/<id>.download` (excluded from backups) and is renamed to `Models/<id>` only after every file passes `ModelVerifier` (size and SHA-256); bytes land in `<file>.part`, and each try asks for the rest with a Range request. `ModelTransfers` keeps two background sessions, `io.github.kabrapratik28.thumbfree.models.wifi` (no mobile data) and `.any`; each download task's `taskDescription` is "<file bytes> <first byte> <part path from Models>", a task already on a session for a part is joined, never started twice, no resume data is kept, and only your Cancel (a task the app cancelled) deletes a download: any other cancel is tried again like a broken connection. The receipt `verified.json` keys each file by "<catalog path> <pinned SHA-256>" with its size and modification date; a model is installed only while every file still matches, and a receipt from before the pins (by path alone) is hashed against the pins once at launch, with nothing fetched. The stored network: `SpeechModel.networkKey` ("TFDownloadCellular.<model id>" in the app's defaults, true when the download may use mobile data) is set when a download starts and when Use mobile data switches it, and removed on ready, Cancel and Delete; `resumeIfStarted()` at launch goes on with it, since its task is in that network's session (with none, it follows Download on Wi-Fi only, `TFWifiOnly`, on when absent). A failed download keeps it; Download and Try again follow the switch again. Cancel wins over a Use mobile data still on its way. Retention and the outbox: History's single Delete is `SessionHost.deleteTake(_:)`, and several deletions go through `SessionHost.deleteTakes(_:)` (Clear all, `applyRetention()` and Settings' stricter rule); both drop the takes' outbox items first (a failed rewrite deletes nothing and throws), then their audio and folders, and never touch the live take or one being transcribed again (`retranscribing`). Retention runs after every take, and with a day limit also at launch and whenever the app becomes active (`applyDayRetention()`). A stricter rule from Settings warns with the ids from `takesToDelete(keepDays:keepCount:)` (the live take and those being transcribed again left out), and its Delete deletes exactly those ids, then saves the rule (`TFKeepDays`, `TFKeepCount`; 0 means forever or no limit). `TFSessionMinutes` (2, 5, 15 or 60) is read at each reset of the session's end and never moves a running deadline; in a Debug build `-TFEndSessions YES` makes the length 0 (`AppSettings.apply(to:)`), so each session ends with its take and the app stops rewriting status.json, for UI tests that watch the keyboard's bar. The model handoff: English and Multilingual (`SpeechModels`, the choice in `TFSpeechModel`; a saved choice whose model is missing falls back to the other one when it is here, and the switch is saved). A first run (no saved choice, neither model here, and no download begun, so no staging folder) starts with Multilingual when the language code of any of `Locale.preferredLanguages` is one of its languages other than English (`SpeechModels.startsMultilingual(_:)`, over `SpeechModels.languages`, the one list of its 25 languages, which Settings shows), else with English, and saves it at once, so a later change of the iPhone's languages moves nothing; an installed model, a begun download or a saved choice (an update from 1.0.x) keeps its model. `SpeechModels.choose(_:)` (Settings) takes only a model that is here; `chooseToDownload(_:)` (the welcome's language choice and Change language) makes the other model the choice, also over a model that is here (the welcome shown again, or a download that ended while the sheet was up), so the welcome waits for the model chosen: it cancels the model in use's download, which deletes what came and its stored network also when nothing runs (a failed download), so no launch resumes it (a model that is here stays here, and the engine keeps it until the new one is in), saves it as the model left (`TFSpeechModelLeft`) and the other model as the choice, and hands the other model to the engine only if it is here. Nothing stops a download once its files are checked (the receipt and the move into place come after the last cancellation check), so the model left can still end ready; it then never takes over from the choice, neither when its download ends nor in the launch fallback, until you choose it (`choose(_:)`, `chooseToDownload(_:)`, or Delete handing over to it) or ask for its download again (`SpeechModel.onDownload`, from Download and Try again), and a first run clears it. Any other download that ends while the model in use is not here still takes over. `SessionHost.useModel(_:)` applies a change only while no take is live and no Transcribe again runs, else when the last of them ends, so each keeps one model. `Transcriber` holds one model in memory (the next load waits for the model it left: its load and its placement check) and commits the engine, its phase and its placement check with no suspension after its last generation check. Settings refuses any Delete while `SessionHost.modelInUse` (a take live or a Transcribe again running), checked at the tap and again at the confirm. Start ThumbFree: `StartSessionIntent` (an `AudioRecordingIntent`, `supportedModes` `[.background, .foreground(.dynamic)]`, `allowedExecutionTargets` `.main` from iOS 27) acts only through `StartSessionIntent.start` and `.modelOffer`, which the app sets in `AppEnvironment.routeStart(to:)`: with no `start` (not the app's process) it opens ThumbFree, with no model it opens `thumbfree://model`, and when the mic does not come on it opens ThumbFree and starts there. `SessionHost.startIdleSession()` is true once audio flows; false with no model, at once when the start is refused (with no message off screen), or when no audio came within 2 s, and then the mic it started for no take is stopped. The keyboard's command observer is made at launch (`ThumbFreeApp.init`), so a background launch with no scene hears keyboard taps. The Control Center control (kind `io.github.kabrapratik28.thumbfree.start`) lives in the widget extension `io.github.kabrapratik28.thumbfree.widgets`, which compiles the intent but never sets `start`; every target is iPhone only (`TARGETED_DEVICE_FAMILY` "1" in each target, since xcodegen's preset writes "1,2" there). The auto-return flag: Settings' "Go back to the app automatically" switch and its footer exist only in a build with `TF_AUTO_RETURN` (Debug and Release), since `AutoReturn.enabled` is false without it; a build without it shows neither.

### Delivery into the right field

A take's text never goes by itself into a field ThumbFree cannot tell apart from another (checked on the iPhone 17e Simulator, iOS 26.5). UIKit fields (text fields, text views, search fields; ThumbFree's own, Settings, Messages, Contacts) report a real `documentIdentifier`, a new one each time a field gains focus (the same Messages box got a new one when reopened, Settings' search field after Home and back). It is nil only between two fields and while iOS resets the keyboard's document (leaving a chat, a page load), and a keyboard crash once seen on a phone came from such a reset, with the keyboard in the background. A web page (Safari) gives all its fields one identifier, kept across the keyboard hiding and coming back, until the page loads again, and moving between its fields tells the keyboard nothing. So `KeyboardClient.target(of:)` is nil for a field with no identifier (no zero UUID any more): the press or release then carries no target, and a take with no target is never typed by itself. The app keeps the command that pinned a take (the press, or a cold take's stop; `SessionHost` `pins`) and writes its `sentAt` into the outbox item as `pinnedAt`; the keyboard types by itself only when `pinnedAt` is no earlier than its last appearance (`KeyboardClient.appeared()`, called in `KeyboardViewController.viewWillAppear` after the defensive release) and the field still has the same target. Everything else is held back with Insert here. Still automatic: a take that stays in its field, and the round trip (its stop is sent after the keyboard comes back). Not detectable by a keyboard, so not covered: moving straight from one field of a web page to another whose text around the cursor is the same (two empty boxes) while the keyboard stays up, and an app that keeps one text box focused while it swaps the conversation around it.

### Emoji picker

The keyboard's bottom row is Apple's: the layer key, the globe when shown, the emoji key (`Key.emoji`, identifier `keyboard.emoji`, VoiceOver "Emoji"), space, return. The emoji key opens `EmojiPickerView` (`Keyboard/`) in the keys' place, and the keyboard grows to Apple's Emoji keyboard height while it shows (53 pt, 63 in landscape; ABC brings back the letters' height); the bar keeps the mic, with the Search Emoji field in the status's place (the delivery chip takes the bar first, except while a search is being typed). While a take records, the bar's status is the recording line ("Recording 0:07  Speak now", VoiceOver "Recording, 7 seconds. Speak now.", identifier `keyboard.status`), in the Search Emoji field's place too until a search begins, and the mic is `BubbleArt.Mode.stop`, a red stop key, as the app's own mic is on the session screen and the walkthrough (the red ring, `BubbleArt.Mode.listening`, is gone). The time counts from `HostStatus.takeStartedAt`, which `SessionHost` publishes from the take's arming clock (the press in a live session, else the moment the mic started), so every keyboard, in any app, shows the same time for the same take. Search goes back to the letters' height plus a results row above the keys (41 pt, 38 in landscape) and ends on Done, back on the letters, or on any change iOS reports (a tap on the text box); an emoji typed from the picker or the search spends a one capital, as a letter does. While the keyboard runs, the picker reopens where it was left. The list is `Shared/EmojiData.swift`, generated by `tools/gen-emoji.py` from `tools/emoji/apple-order.txt` (Apple's layout, read from a Simulator by `tools/dump-apple-emoji.sh`), Unicode's `emoji-test.txt` (names, versions, skin tones) and CLDR's English annotations (search keywords), each input pinned to a release and recorded with its SHA-256 in the file's header; never edit it by hand. `EmojiCatalog.drawnVersion` hides emoji newer than the running iOS draws (17.0 from iOS 26.4, else 16.0): add a line when iOS adds emoji. The keyboard keeps two stores in its own `UserDefaults.standard` (its own container, so no Full Access): `TFEmojiUsage` (Frequently Used, each emoji counted without its tone: emoji text to [score, last pick]) and `TFEmojiTones` (emoji text to the chosen variant, one tone or a two-person pair); neither is logged or in the App Group, and the app never reads them. New emoji draw at the screen's scale while `os_proc_available_memory()` reports 30 MB or more left, then from bitmaps of at most 64 px and then 40 px whatever their point size, and at most 64 px when it reports nothing (`EmojiMemory.drawScale(screen:available:pointSize:)`). UI-test identifiers: `keyboard.emoji.picker`, `keyboard.emoji.<code points>`, `keyboard.emoji.recent.<n>`, `keyboard.emoji.category.<case>`, `keyboard.emoji.search`, `keyboard.emoji.search.clear`, `keyboard.emoji.results`, `keyboard.emoji.result.<n>`, `keyboard.emoji.tone.<n>`, `keyboard.emoji.pair.left.<n>`, `keyboard.emoji.pair.right.<n>`, `keyboard.emoji.pair.plain`, `keyboard.emoji.pair.preview`; the picker's ABC and delete reuse `keyboard.toLetters` and `keyboard.delete` (the keys under it are hidden).

### Keyboard parity with Apple's iOS 26 keyboard

The keyboard follows the field's `keyboardType` (`KeyboardKind`): email, web address, Twitter and web search fields get Apple's bottom rows, ASCII-capable and numbers-and-punctuation fields have no emoji key (the second opens on 123), number and decimal fields get Apple's digit pad (phone pads never reach a custom keyboard: iOS shows its own), and a new field opens where Apple's keyboard opens. Apple's layouts with their key widths, and its long-press alternatives, are read from Apple's keyboard on a Simulator by `tools/dump-apple-keyboard.sh` into `tools/keyboard/apple-layouts.txt` and `tools/keyboard/apple-alternates.txt`; `KeyAlternates.lines` repeats the second file, and unit tests hold the layouts and the table to both files, so re-read both after each iOS release. The keys look like iOS 26's (one key color, symbols for shift, delete and return, a blank space bar, Apple's balloon popup). Gestures, as on Apple's: a long press opens a key's alternatives (`KeyAlternates`, `KeyPopup.Callout`); holding space turns it into a trackpad that moves the cursor with `adjustTextPosition(byCharacterOffset:)`, which counts UTF-16 units, and moves up or down only through line breaks the keyboard can see (`Trackpad`); 123, ABC and #+= (on 123) switch on touch and shift shows capitals while held, for the quick slide (`KeyLayer.slidesFrom`); delete held goes on to whole words (`DeleteRepeat`); a space or return on 123 or #+= goes back to the letters only once a key was typed there. UI tests give the try screen's box (`-TFOpenTry YES`) a keyboard type with `-TFFieldType <UIKeyboardType raw value>` (Debug builds only). UI-test identifiers: `keyboard.key.periodcom` for .com; the digit pad reuses `keyboard.key.<digit>`, `keyboard.key.period` and `keyboard.delete`. Details: a picked alternate marks its commit point like a tapped key (`markCommitPoint`, `KeyplaneView.pick`), so an apostrophe alternative chosen on 123 or #+= sends the layer back to letters and VoiceOver's refocus lands on the apostrophe, not on whatever was last tapped; starting the trackpad (`startTrackpad`) stops another finger's delete repeat and closes its open alternatives callout without typing anything, and the globe does nothing while the trackpad is on (`hitTest`, `globeTouched`); delete's pace and word length live in one `DeleteRepeat` (`Shared/Keyplane.swift`), shared by the keys and the emoji picker's delete; the 123 rule holds in Search Emoji too (`KeyboardViewController.searchKey` goes through the same `KeyLayer.after(_:typedHere:)` gate as the keys). More details: the 123 rule starts again whenever the letters come back, a search's start and end included (`layer`'s `didSet`; one `advanceLayer(after:)` serves the keys and the search); a caret move without a text change (`selectionDidChange`) is taken as one (`fieldChanged`), so it ends Search Emoji and a double space in progress too (on iOS 26.5 a tap that only moves the caret comes as `textDidChange`, in a text box and in a Safari text area alike); a slide from shift that another key rolls over types its capital once, before that key (`commitPending`); the search's results row sits under the keys, so the top row's balloons and alternatives draw over it; the emoji picker's delete keeps its own pace through the same helper, 400 ms and then an emoji every 80 ms, never a word (`DeleteRepeat.step(_:picker:)`); while the trackpad is on the bar takes no touches either, the mic included (`KeyplaneView.onTrackpad`); letting go of a long press away from the alternatives does as Apple's: from the row's top down to the key's bottom edge the alternative under the finger, above the row or just below the key the key itself, more than a key's height below the key nothing (`KeyPopup.Callout.index(at:)`, `cancels(at:)`); the heights were measured against Apple's on the iPhone 16 and 17 Pro Simulators (iOS 26.5) and already match: the letters (260 pt, 188 in landscape) are Apple's letters keyboard with its suggestions strip, whose place the bar takes, and the emoji picker (313 pt, 251 in landscape) is Apple's Emoji keyboard, now written as its own height rather than the letters' plus an amount; delete takes whole words once twenty characters are gone, the touch's own one included; a finger rolling from a letter onto delete leaves no balloon behind.

### Clean up

After a take the keyboard typed and confirmed (`KeyboardClient.typedTake`: the payload as it went in and the field's
`documentIdentifier`), a round sparkle (`SparkleArt`, the mic's own geometry) sits just left of the mic while: Full
Access, a fresh status with `session == .ready`, `take == .idle` and `cleanup == .ready`, and the typed take right
before the cursor in its field (`CleanupReplace.matches`). A key typed, a new take or another field forgets the take.
Tap: a `clean` command (the take trimmed, `style` nil); hold (0.5 s): a row of the five styles above the bar, the mic
kept, Cancel. The app (`CleanUp`) runs one request at a time: `SystemLanguageModel(useCase: .general, guardrails:
.permissiveContentTransformations)` (never Private Cloud Compute), `CleanupPrompt.instructions` as the session's
instructions, `CleanupPrompt.prompt` as the prompt, greedy, a 20 s limit; `CleanupCheck.accept`; then cleanups.json and
the status notification. Apple's rate limit (`LanguageModelError.rateLimited` on iOS 27 with `resetDate`,
`GenerationError.rateLimited` on iOS 26) is `paused`. The keyboard reads its answer on each refresh (25 s timeout), checks
the pin again, writes `cleanBegan`, deletes the take one `deleteBackward()` per character, inserts the answer through
`CursorFormatter` against the text before the take (that text's spacing kept when iOS cuts it), reads it back and writes
`cleanConfirmed` or `cleanUnverified`; the sparkle becomes Undo, which writes the take back the same way. The bar's status
place says "Cleaning up…", "Apple paused Clean up. Ready in 0:40" (or "for a moment"), "Couldn't tidy this one. Your
words are unchanged." or "The text changed, so it was left as is." Settings: availability, "Show ✨ after you speak"
(`TFCleanupShown`, on) and "Tap ✨ uses" (`TFCleanupStyle`, Clean). Debug: `-TFFakeCleanup <text>` answers every
request with that text and reads as ready (`CleanupUITests`; a Simulator has no model).

### Suggestions, autocorrect, text replacements and smart punctuation

While typing, the bar's status place shows Apple's three suggestions (`SuggestionBarState.slots`; `Suggestion`, kind `.typed` in curly quotes, `.correction` lit in a capsule and read as selected, `.word`, `.undo`); the status shows until the first key, and again once the mic is used, the keyboard shows or a new field is focused (one that reports a `documentIdentifier`: fields without one cannot be told apart); the delivery chip, the recording line (while a take records) and the emoji search field still come first: the status place is shared, and suggestions show only once none of the three claims it. `Speller` (`Shared/Suggestions.swift`) asks `UITextChecker` (main actor, English (US), the word at the end of the last 100 characters before the caret, which rank its answers) and is warmed with one guess when the keyboard appears (a first completions call would block about 150 ms); `Speller.suggestions(before:after:corrects:)` returns the places and the word they are for (`token` when it is a text replacement's shortcut), and a tap or a correction replaces only that word, checked against the text first (a shortcut as a shortcut, `Typing.stillAt`, so one with digits or marks, "ty2" or "@@", can be tapped). The letters of a token that reads as a web address, an email address or a code (`Typing.isCode`: a digit, @ # _ / \, or a full stop between two letters or digits anywhere from the space before the caret to the space after it) are no word: no places and no correction there ("end.teh" stays too), though a text replacement's exact shortcut still becomes its phrase. A clear misspelling typed in this keyboard is corrected at its end (a space, a return, or . , ? ! ; :) by the lit place (`Speller.correction`), never where `FieldTraits.corrects` is false (autocorrection .no, web address and email fields), never while the delivery chip, the Search Emoji field or the recording line has the bar's place (nothing lit and no undo could show there, so a text replacement's shortcut stays too), and never before iOS has answered the keyboard's latest lexicon request (`LexiconRequests`: each request is numbered and an older one's answer is dropped; the older lexicon is cleared as a request starts, so the Dictionary's words go at once without Full Access); a lone "i" becomes "I", before a full stop too ("so do I.", as Apple writes it), and a letter typed right after that full stop puts the "i" back ("i.e."); a tapped suggestion replaces the word and adds a space, a space typed next is dropped and a punctuation mark takes the space's place; a delete right after a correction offers the typed word back in the first place (`.undo`, "Undo correction, <word>"), which a tap restores without a space. The keyboard forgets the word being typed and the undo (`endTyping`) when it shows, the mic is used, a take's text or an emoji goes in, or the text changes from outside; a caret move clears it too, through `fieldChanged()` (the same hook that ends Search Emoji and a double space on a caret move with no text change), which also clears the last correction and its undo on every notice, even one that leaves the same end of text; Insert here, Copy and Dismiss bring the places up to date (the chip's buttons go through the keyboard controller). Words kept (an undo, or the typed word tapped in its quotes) are never corrected or expanded again: `TFLearnedWords` in the keyboard's own `UserDefaults.standard` (as keys, `Typing.key`: lowercased, with the straight apostrophe, so a word kept with ’ is the word typed with '; newest last, at most 1,000; not `UITextChecker.learnWord`, which writes to the system's shared keyboard folder); never logged, never in the App Group. iOS's supplementary lexicon (text replacements, contacts' words) is read each time the keyboard shows; its completion runs on a background queue although `UILexicon` is marked main-actor, so it hops to the main actor. A replacement's shortcut is matched whole at the caret, in any case, digits and marks included; the lexicon's words are never corrected, are offered, and are what a near miss becomes. The lexicon keeps every text replacement, and `Lexicon.limit` (10,000) caps only the contacts' words; its keys are `Typing.key` too, and its words are indexed by first letter when it is made (`Lexicon.words(startingWith:)`). The user's own words (kept, contacts', the Dictionary's) are never corrected, also with 's, ’s or s after them (`Speller.isUsers`: "Zakroff's" stays), except that such a word with a bare s the dictionary does not know becomes its own possessive (`Speller.possessive(of:)`: "zakroffs" is "Zakroff’s", with the field's apostrophe), unless the lexicon writes it in capitals (an acronym, whose s makes a plural: "gpus" stays beside the Dictionary's "GPU"); a near miss (`Edits.nearest`) is never a word the typed one starts with (that word with an ending), keeps the first letter unless the first two were swapped ("ukbernetes": Kubernetes), and is looked for only among the user's words with the typed word's first or second letter; the user's words are offered from the third typed letter (before that, Apple's words fill the places). Typing stays instant: Apple's completions of the typed word are asked only when the rule or an empty place needs them (a typo's correction and its completions usually fill the places), and a one-edit near miss is ruled out without the edit table where it can be (`SuggestionsTests.typingStaysInstant` holds the time per key at 10,000 lexicon entries, 1,000 kept words and 300 Dictionary entries). ThumbFree's Dictionary is shared with the keyboard as `TFDictionary` (an array of entries) in the App Group, written by `DictionaryStore` at launch and on each change and read by the keyboard with Full Access; its words join the lexicon's. Smart punctuation is the keyboard's (iOS's text views leave it to a custom keyboard): curly quotes and a dash for two hyphens where `FieldTraits.smartPunctuation` allows (not in web address, email and ASCII fields unless they ask), and `FieldTraits.shiftExpected` keeps a sentence's capital after an opening quote or bracket. Debug only: `-TFAutocorrect NO` turns the try screen's box's autocorrection off, and a `-TFResetState YES` launch writes `TFKeyboardReset` (a token) to the App Group so the keyboard forgets its kept words. No log line is added. UI-test identifiers: `keyboard.suggestion.0`, `.1`, `.2`.
