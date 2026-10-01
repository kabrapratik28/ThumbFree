import CoreML
import Foundation

extension ParakeetEngine {
    /// Test hook: counts calls to `neuralEngineShare`, so a test can confirm the placement check ran (or was
    /// skipped because the answer was remembered). Reset by the test itself before use; safe because tests that
    /// read it run serialized (`ModelTests` is `.serialized`).
    nonisolated(unsafe) static var neuralEngineShareCallCount = 0

    /// Test hook: when set, the placement check reports this share instead of the plan's. The plan is still made, so
    /// a plan that cannot be made still throws. Reset by the test that sets it (a `ModelTests` test, serialized).
    nonisolated(unsafe) static var neuralEngineShareOverride: Double?

    /// Share of a model's operations (0...1) that Core ML plans to run on the Neural Engine with `units`.
    /// Constants and other operations without a device are not counted.
    /// Costs about 0.2 s once the plan is cached; the first plan for a model compiles it (about 16 s on an M4 Pro).
    static func neuralEngineShare(of url: URL, units: MLComputeUnits) async throws -> Double {
        neuralEngineShareCallCount += 1
        let configuration = MLModelConfiguration()
        configuration.computeUnits = units
        let plan = try await MLComputePlan.load(contentsOf: url, configuration: configuration)
        // A plan that isn't an ML Program with a "main" function, or reports no operations, could not be
        // assessed: that throws too, so the caller's catch treats it the same as a failed load (share 1,
        // keep the Neural Engine) instead of silently reporting 0% and sending the Encoder to the CPU.
        guard case .program(let program) = plan.modelStructure, let main = program.functions["main"] else {
            throw EngineError.loadFailed("Encoder plan")
        }
        var counted = 0, neural = 0
        func visit(_ block: MLModelStructure.Program.Block) {
            for operation in block.operations {
                if let usage = plan.deviceUsage(for: operation) {
                    counted += 1
                    if case .neuralEngine = usage.preferred { neural += 1 }
                }
                operation.blocks.forEach(visit)
            }
        }
        visit(main.block)
        guard counted > 0 else { throw EngineError.loadFailed("Encoder plan") }
        return neuralEngineShareOverride ?? Double(neural) / Double(counted)
    }

    /// The Encoder stays on the Neural Engine unless Core ML plans less than half of it there;
    /// then a split between CPU and Neural Engine would be slower than the CPU alone.
    static func encoderUnits(forNeuralEngineShare share: Double) -> MLComputeUnits {
        share < 0.5 ? .cpuOnly : .cpuAndNeuralEngine
    }

    /// The check runs once per Encoder file and iOS version. The key under which the placement
    /// memory is stored: a `[String: String]` from `placementFingerprint` to `"neuralEngine"` or `"cpu"`.
    static let placementMemoryKey = "TFEncoderPlacement"

    /// Identifies the Encoder's weight file (a model update changes its size or modification date) and this OS
    /// version, so a stale answer from a replaced model or an OS update is never read back. Nil when the weight
    /// file cannot be read (an unexpected model layout): nothing can be remembered then, so no check runs and the
    /// Encoder loads as asked.
    static func placementFingerprint(encoder: URL) -> String? {
        let weights = encoder.appendingPathComponent("weights/weight.bin")
        guard let attributes = try? FileManager.default.attributesOfItem(atPath: weights.path),
              let size = attributes[.size] as? Int,
              let modified = attributes[.modificationDate] as? Date else { return nil }
        return "\(size)-\(modified.timeIntervalSince1970)-\(ProcessInfo.processInfo.operatingSystemVersionString)"
    }

    /// The remembered compute units for `fingerprint`, or nil on a miss (nothing stored yet, or the stored
    /// entry belongs to a different fingerprint).
    static func rememberedPlacement(fingerprint: String, memory: UserDefaults) -> MLComputeUnits? {
        switch (memory.dictionary(forKey: placementMemoryKey) as? [String: String])?[fingerprint] {
        case "neuralEngine": .cpuAndNeuralEngine
        case "cpu": .cpuOnly
        default: nil
        }
    }

    /// Remembers `units` for `fingerprint`, replacing the whole stored dictionary rather than merging into it:
    /// only the current fingerprint is kept, so a model or OS update does not accumulate old entries forever.
    static func rememberPlacement(_ units: MLComputeUnits, fingerprint: String, memory: UserDefaults) {
        memory.set([fingerprint: units == .cpuOnly ? "cpu" : "neuralEngine"], forKey: placementMemoryKey)
    }
}
