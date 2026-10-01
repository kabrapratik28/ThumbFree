import CoreML
import Foundation
import os

public struct EngineResult: Sendable, Equatable {
    public let text: String
    public let preprocessMs: Double
    public let encodeMs: Double
    public let decodeMs: Double
}

/// Loads Preprocessor (.cpuOnly), Encoder (`encoderUnits`: .cpuAndNeuralEngine by default, or .cpuOnly), Decoder and
/// JointDecision (.cpuOnly) from `modelDirectory` (a folder holding the four .mlmodelc bundles and the vocabulary JSON).
/// Runs one Core ML job at a time on its own serial queue at user-initiated priority. Never the GPU.
/// An Encoder that fails to load on the Neural Engine loads on the CPU instead, and an Encoder prediction
/// that fails on the Neural Engine reloads the Encoder on the CPU and retries once; either way the engine stays on the
/// CPU (iOS 27 needs an entitlement for the Neural Engine in the background). The placement check never delays
/// the load: see `settlePlacement()`.
public actor ParakeetEngine {
    public static let maxSamples = 240_000
    static let samplesPerFrame = 1_280      // one Encoder frame is 80 ms
    static let log = Logger(subsystem: "io.github.kabrapratik28.thumbfree", category: "engine")

    private let queue = DispatchSerialQueue(label: "io.github.kabrapratik28.thumbfree.engine", qos: .userInitiated)
    public nonisolated var unownedExecutor: UnownedSerialExecutor { queue.asUnownedSerialExecutor() }

    /// The compute units the current Encoder was loaded with, read from the loaded model itself rather than a
    /// flag this type sets: `.cpuAndNeuralEngine`, or `.cpuOnly` when asked for, when the placement check found
    /// less than half of the Encoder on the Neural Engine, or after a failed Neural Engine load or prediction
    /// moved the Encoder to the CPU (also while a fallback holds no Encoder, see `encoder`). The placement check
    /// (`settlePlacement()`), not this property, is what looks at where operations actually run.
    var encoderUnits: MLComputeUnits { encoder?.configuration.computeUnits ?? .cpuOnly }
    /// False when the Encoder runs on the CPU (the app then shows `EnginePhase.readyCPU`).
    public var usesNeuralEngine: Bool { encoderUnits != .cpuOnly }
    private let modelDirectory: URL
    private let placementMemory: UserDefaults
    private let preprocessor: MLModel
    /// Optional so a fallback can release the failed Neural Engine Encoder before it loads the CPU copy: assigning
    /// the new load straight over the old one would load first and release after, so two 445 MB Encoders would be
    /// alive at once. Nil only between those two steps, or when that CPU load failed (the next call tries again).
    private var encoder: MLModel?
    private let tdt: TdtDecoder
    private let vocabulary: Vocabulary
    private let audio: MLMultiArray          // [1, 240000] Float32, zero after the input
    private let audioLength: MLMultiArray    // [1] Int32
    private let preprocessorInput: MLFeatureProvider
    private var failNextEncoderCall = false

    /// `encoderUnits`: the app's choice, `.cpuAndNeuralEngine` or `.cpuOnly`; anything else throws.
    /// `placementMemory`: where the placement check's remembered answer lives (see `EncoderPlacement.swift`); the load
    /// only reads it, `settlePlacement()` writes it.
    public init(modelDirectory: URL, variant: ModelVariant, encoderUnits requested: MLComputeUnits = .cpuAndNeuralEngine,
                placementMemory: UserDefaults = .standard) async throws {
        guard requested == .cpuAndNeuralEngine || requested == .cpuOnly else {
            throw EngineError.loadFailed("Encoder compute units must be .cpuAndNeuralEngine or .cpuOnly")
        }
        guard FileManager.default.fileExists(atPath: modelDirectory.path) else {
            throw EngineError.modelMissing(modelDirectory.lastPathComponent)
        }
        self.modelDirectory = modelDirectory
        self.placementMemory = placementMemory
        // A remembered placement answer for this Encoder file and OS version decides at once. Without one the Encoder
        // loads as asked, and `settlePlacement()` runs the check later.
        let units: MLComputeUnits
        if requested == .cpuAndNeuralEngine,
           let fingerprint = Self.placementFingerprint(encoder: modelDirectory.appendingPathComponent("Encoder.mlmodelc")),
           let remembered = Self.rememberedPlacement(fingerprint: fingerprint, memory: placementMemory) {
            units = remembered
            Self.log.notice("Encoder: remembered placement, \(units == .cpuOnly ? "CPU" : "Neural Engine", privacy: .public)")
        } else {
            units = requested
        }
        // `queue` already has its value (stored properties with a default initializer, like `queue`,
        // are set before the rest of this init body runs), but a closure cannot capture `self` (even
        // just to read that one property) until every stored property is set. Copying it to a plain
        // local first lets the closure below capture only that local queue, not `self`, so it can
        // dispatch the blocking Core ML loads onto the engine's own serial queue instead of running
        // them on whatever cooperative-pool thread called this initializer (the Encoder's first Neural
        // Engine compile alone can take about 15 s).
        let loadQueue = queue
        let loaded = try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<(Vocabulary, MLModel, MLModel, TdtDecoder, MLMultiArray, MLMultiArray, MLFeatureProvider), Error>) in
            loadQueue.async {
                do {
                    let vocabulary = try Vocabulary(url: modelDirectory.appendingPathComponent("parakeet_vocab.json"), blankID: variant.blankID)
                    let preprocessor = try Self.load("Preprocessor", from: modelDirectory, units: .cpuOnly)
                    let encoder = try Self.loadEncoder(from: modelDirectory, units: units)
                    let decoder = try Self.load("Decoder", from: modelDirectory, units: .cpuOnly)
                    let joint = try Self.load("JointDecision", from: modelDirectory, units: .cpuOnly)
                    do {
                        let tdt = try TdtDecoder(decoder: decoder, joint: joint, blankID: variant.blankID)
                        let audio = try MLMultiArray(shape: [1, NSNumber(value: Self.maxSamples)], dataType: .float32)
                        let audioLength = try MLMultiArray(shape: [1], dataType: .int32)
                        let preprocessorInput = try MLDictionaryFeatureProvider(dictionary: ["audio_signal": audio, "audio_length": audioLength])
                        continuation.resume(returning: (vocabulary, preprocessor, encoder, tdt, audio, audioLength, preprocessorInput))
                    } catch {
                        continuation.resume(throwing: EngineError.loadFailed("buffers"))
                    }
                } catch {
                    continuation.resume(throwing: error)
                }
            }
        }
        (vocabulary, preprocessor, encoder, tdt, audio, audioLength, preprocessorInput) = loaded
    }

    static func load(_ name: String, from directory: URL, units: MLComputeUnits) throws -> MLModel {
        let url = directory.appendingPathComponent(name + ".mlmodelc")
        guard FileManager.default.fileExists(atPath: url.path) else { throw EngineError.modelMissing(url.lastPathComponent) }
        let configuration = MLModelConfiguration()
        configuration.computeUnits = units
        do { return try MLModel(contentsOf: url, configuration: configuration) } catch {
            let nsError = error as NSError
            log.error("\(name, privacy: .public) failed to load (\(String(describing: type(of: error)), privacy: .public), domain: \(nsError.domain, privacy: .public), code: \(nsError.code, privacy: .public))")
            throw EngineError.loadFailed(name)
        }
    }

    /// At load time, an Encoder that fails to load on the Neural Engine (its compiler service once failed
    /// on an iPhone: "Couldn't communicate with a helper application") loads on the CPU instead; `load` has logged
    /// the Core ML error. A failed load holds no model, so the two attempts never overlap. Only a CPU failure throws.
    static func loadEncoder(from directory: URL, units: MLComputeUnits) throws -> MLModel {
        do {
            if failNextEncoderLoad {
                failNextEncoderLoad = false
                throw EngineError.loadFailed("injected Encoder load failure")
            }
            return try load("Encoder", from: directory, units: units)
        } catch where units != .cpuOnly {
            log.notice("Encoder: the Neural Engine load failed, loading it on the CPU")
            return try load("Encoder", from: directory, units: .cpuOnly)
        }
    }

    /// The placement check, kept off the load path (it took 21.9 s on an iPhone 16's first load, and again
    /// after every iOS update); the app calls this once the engine is ready and warmed. When the Encoder is on the
    /// Neural Engine and nothing is remembered for this Encoder file and OS version, it asks Core ML where the
    /// Encoder's operations would run and remembers the answer, which the next load uses. This engine keeps its
    /// Encoder even after a "cpu" answer: loading the CPU copy would hold the actor for 7 to 10 s while in use, and no
    /// iPhone has needed it. The check awaits Core ML without holding the actor, so transcriptions go on meanwhile. A
    /// check that fails is not remembered, so the next engine tries again; without a fingerprint (an unreadable weight
    /// file) nothing could be remembered, so no check runs.
    public func settlePlacement() async {
        let encoderURL = modelDirectory.appendingPathComponent("Encoder.mlmodelc")
        guard usesNeuralEngine, let fingerprint = Self.placementFingerprint(encoder: encoderURL),
              Self.rememberedPlacement(fingerprint: fingerprint, memory: placementMemory) == nil else { return }
        do {
            let share = try await Self.neuralEngineShare(of: encoderURL, units: .cpuAndNeuralEngine)
            let units = Self.encoderUnits(forNeuralEngineShare: share)
            Self.rememberPlacement(units, fingerprint: fingerprint, memory: placementMemory)
            Self.log.notice("Encoder: \(Int(share * 100))% of operations planned on the Neural Engine; the next load uses \(units == .cpuOnly ? "the CPU" : "the Neural Engine", privacy: .public)")
        } catch {
            let nsError = error as NSError
            Self.log.error("Encoder placement check failed (\(String(describing: type(of: error)), privacy: .public), domain: \(nsError.domain, privacy: .public), code: \(nsError.code, privacy: .public)); keeping the Neural Engine")
        }
    }

    /// One transcription of 1 s of silence, so the first real take does not pay for first-run setup.
    public func warmUp() async throws {
        _ = try await transcribe([Float](repeating: 0, count: 16_000))
    }

    /// 16 kHz mono samples. At most `ParakeetEngine.maxSamples` (240_000). Short input is padded internally
    /// with `EnginePadding.pad`. Returns trimmed text (may be empty).
    public func transcribe(_ samples: [Float]) async throws -> EngineResult {
        guard samples.count <= Self.maxSamples else { throw EngineError.inputTooLong(samples: samples.count) }
        guard !samples.isEmpty else { return EngineResult(text: "", preprocessMs: 0, encodeMs: 0, decodeMs: 0) }
        return try run(EnginePadding.pad(samples))
    }

    /// Synchronous on purpose: no suspension point, so no other call can interleave and touch the shared
    /// arrays. (Inside an async function, `prediction(from:)` would pick Core ML's async overload.)
    private func run(_ input: [Float]) throws -> EngineResult {
        let clock = ContinuousClock()
        let start = clock.now
        // Declare the length rounded up to whole Encoder frames when that still fits, as FluidAudio does.
        let aligned = (input.count + Self.samplesPerFrame - 1) / Self.samplesPerFrame * Self.samplesPerFrame
        let declared = aligned <= Self.maxSamples ? aligned : input.count
        let target = audio.dataPointer.assumingMemoryBound(to: Float.self)
        input.withUnsafeBufferPointer { source in
            if let base = source.baseAddress { target.update(from: base, count: source.count) }
        }
        (target + input.count).update(repeating: 0, count: Self.maxSamples - input.count)
        audioLength[0] = NSNumber(value: declared)
        let mel = try preprocessor.prediction(from: preprocessorInput)
        let preprocessed = clock.now
        let encoded = try encode(mel)
        guard let frames = encoded.featureValue(for: "encoder")?.multiArrayValue,
              let length = encoded.featureValue(for: "encoder_length")?.multiArrayValue else {
            throw EngineError.loadFailed("Encoder outputs")
        }
        let encodedAt = clock.now
        let available = min(length[0].intValue, frames.shape[2].intValue)
        let valid = min(available, (declared + Self.samplesPerFrame - 1) / Self.samplesPerFrame)
        let tokens = try tdt.decode(frames: frames, valid: valid)
        let done = clock.now
        return EngineResult(text: vocabulary.text(tokens), preprocessMs: Self.ms(preprocessed - start),
                            encodeMs: Self.ms(encodedAt - preprocessed), decodeMs: Self.ms(done - encodedAt))
    }

    /// The Encoder call with its CPU fallback: a failed prediction on the Neural Engine reloads the Encoder on
    /// the CPU and retries once; the engine then stays on the CPU. A failure on the CPU reaches the caller.
    private func encode(_ mel: MLFeatureProvider) throws -> MLFeatureProvider {
        do {
            if failNextEncoderCall {
                failNextEncoderCall = false
                throw EngineError.loadFailed("injected Encoder failure")
            }
            return try currentEncoder().prediction(from: mel)
        } catch where encoderUnits != .cpuOnly {
            let nsError = error as NSError
            Self.log.error("Encoder failed on the Neural Engine (\(String(describing: type(of: error)), privacy: .public), domain: \(nsError.domain, privacy: .public), code: \(nsError.code, privacy: .public)); reloading it on the CPU")
            encoder = nil   // released before the CPU copy loads (see `encoder`)
            return try currentEncoder().prediction(from: mel)
        }
    }

    /// The Encoder, loaded on the CPU first when a fallback left none (see `encoder`).
    private func currentEncoder() throws -> MLModel {
        if let encoder { return encoder }
        let cpu = try Self.loadEncoder(from: modelDirectory, units: .cpuOnly)
        encoder = cpu
        return cpu
    }

    /// Test seam: the next Encoder prediction throws, as a failing Neural Engine call would.
    func failNextEncoderPrediction() {
        failNextEncoderCall = true
    }

    /// Test seam: the next Encoder load throws, as a failing Core ML load would. Static because the first Encoder
    /// load happens inside `init` (on the Neural Engine there, it exercises the CPU fallback); safe because the tests
    /// that set it run serialized (`ModelTests` is `.serialized`).
    nonisolated(unsafe) static var failNextEncoderLoad = false

    static func ms(_ duration: Duration) -> Double {
        Double(duration.components.seconds) * 1_000 + Double(duration.components.attoseconds) / 1e15
    }
}
