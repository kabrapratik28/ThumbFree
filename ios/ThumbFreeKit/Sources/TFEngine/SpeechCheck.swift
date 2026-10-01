import CoreML
import Foundation
import os

/// Silero VAD speech check for one chunk: Silero v6.2 (MIT) in FluidInference's Core ML conversion
/// `silero-vad-unified-256ms-v6.2.1`, on the CPU. Each call hears 256 ms (64 samples of context, then 4,096 new
/// samples) and returns the noisy-OR of its eight 32 ms window probabilities, 1 - (1 - p1)...(1 - p8).
/// The Android rule "two 32 ms windows in a row at 0.15 or more" then means: one output at 0.2775 or more
/// (two such windows inside one call give at least 1 - 0.85 x 0.85), or two outputs in a row at 0.15 or more
/// (the pair straddles two calls). Every chunk the Android rule keeps is kept. Fresh state per chunk, last call
/// zero-padded. Fails open: no model, a load error or a run error counts as speech.
public actor SpeechCheck {
    public static let modelName = "silero-vad-unified-256ms-v6.2.1.mlmodelc"
    static let context = 64, block = 4_096, stateSize = 128
    private static let log = Logger(subsystem: "io.github.kabrapratik28.thumbfree", category: "speech-check")

    private let queue = DispatchSerialQueue(label: "io.github.kabrapratik28.thumbfree.speech-check", qos: .userInitiated)
    public nonisolated var unownedExecutor: UnownedSerialExecutor { queue.asUnownedSerialExecutor() }

    /// The model and its arrays, allocated once and reused.
    private final class Runner {
        let model: MLModel
        let audio: MLMultiArray, hidden: MLMultiArray, cell: MLMultiArray
        let input: MLFeatureProvider

        init(url: URL) throws {
            let configuration = MLModelConfiguration()
            configuration.computeUnits = .cpuOnly
            model = try MLModel(contentsOf: url, configuration: configuration)
            audio = try MLMultiArray(shape: [1, NSNumber(value: SpeechCheck.context + SpeechCheck.block)], dataType: .float32)
            hidden = try MLMultiArray(shape: [1, NSNumber(value: SpeechCheck.stateSize)], dataType: .float32)
            cell = try MLMultiArray(shape: [1, NSNumber(value: SpeechCheck.stateSize)], dataType: .float32)
            input = try MLDictionaryFeatureProvider(dictionary: ["audio_input": audio, "hidden_state": hidden, "cell_state": cell])
        }
    }

    private let runner: Runner?

    public init(modelDirectory: URL) async {
        // `queue` already has its value (stored properties with a default initializer, like `queue`, are set
        // before the rest of this init body runs), but a closure cannot capture `self` (even just to read that
        // one property) until every stored property, including `runner`, is set. Copying it to a plain local
        // first lets the closure below capture only that local queue, not `self`, so the blocking
        // `MLModel(contentsOf:)` load runs on the speech check's own serial queue instead of whatever
        // cooperative-pool thread called this initializer.
        let loadQueue = queue
        let url = modelDirectory.appendingPathComponent(Self.modelName)
        runner = await withCheckedContinuation { (continuation: CheckedContinuation<Runner?, Never>) in
            loadQueue.async {
                do {
                    continuation.resume(returning: try Runner(url: url))
                } catch {
                    Self.log.error("Silero speech check unavailable (\(String(describing: type(of: error)), privacy: .public)); every chunk counts as speech")
                    continuation.resume(returning: nil)
                }
            }
        }
    }

    public func hasSpeech(_ samples: [Float]) async -> Bool {
        do {
            return Self.isSpeech(try probabilities(samples))
        } catch {
            Self.log.error("Silero speech check run failed (\(String(describing: type(of: error)), privacy: .public)); this chunk counts as speech")
            return true
        }
    }

    /// One probability per 256 ms of `samples`. Synchronous, so calls never interleave on the shared arrays.
    func probabilities(_ samples: [Float]) throws -> [Float] {
        guard let runner else { throw EngineError.modelMissing(Self.modelName) }
        let audio = runner.audio.dataPointer.assumingMemoryBound(to: Float.self)
        audio.update(repeating: 0, count: Self.context + Self.block)
        runner.hidden.dataPointer.assumingMemoryBound(to: Float.self).update(repeating: 0, count: Self.stateSize)
        runner.cell.dataPointer.assumingMemoryBound(to: Float.self).update(repeating: 0, count: Self.stateSize)
        var probabilities: [Float] = []
        var start = 0
        while start < samples.count {
            // Context: the last 64 samples of the previous block (zeros for the first).
            audio.update(from: audio + Self.block, count: Self.context)
            let count = min(Self.block, samples.count - start)
            samples.withUnsafeBufferPointer { source in
                if let base = source.baseAddress { (audio + Self.context).update(from: base + start, count: count) }
            }
            (audio + Self.context + count).update(repeating: 0, count: Self.block - count)
            let output = try runner.model.prediction(from: runner.input)
            guard let p = output.featureValue(for: "vad_output")?.multiArrayValue,
                  let h = output.featureValue(for: "new_hidden_state")?.multiArrayValue,
                  let c = output.featureValue(for: "new_cell_state")?.multiArrayValue else {
                throw EngineError.loadFailed("Silero outputs")
            }
            probabilities.append(p[0].floatValue)
            runner.hidden.dataPointer.assumingMemoryBound(to: Float.self)
                .update(from: h.dataPointer.assumingMemoryBound(to: Float.self), count: Self.stateSize)
            runner.cell.dataPointer.assumingMemoryBound(to: Float.self)
                .update(from: c.dataPointer.assumingMemoryBound(to: Float.self), count: Self.stateSize)
            start += Self.block
        }
        return probabilities
    }

    static func isSpeech(_ probabilities: [Float]) -> Bool {
        probabilities.indices.contains { i in
            probabilities[i] >= 0.2775 || (i > 0 && probabilities[i] >= 0.15 && probabilities[i - 1] >= 0.15)
        }
    }
}
