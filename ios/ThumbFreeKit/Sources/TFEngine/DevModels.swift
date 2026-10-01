import Foundation

public enum ModelVariant: String, Sendable, CaseIterable {
    case v2 = "parakeet-tdt-0.6b-v2"   // English
    case v3 = "parakeet-tdt-0.6b-v3"   // 25 languages

    /// The blank token id, one past the last real token (FluidAudio's `AsrModelVersion.blankId`;
    /// the JointDecision's token logits are 1,025 wide for v2 and 8,193 for v3). Not the JSON's
    /// entry count: v2's `parakeet_vocab.json` carries 7 stray entries after id 1023.
    var blankID: Int { self == .v2 ? 1024 : 8192 }
}

/// Finds models for development and tests: $TF_MODELS_DIR, else ~/Library/Application Support/FluidAudio/Models/<folder>.
public enum DevModels {
    /// `<root>/<rawValue>` or `<root>/<rawValue>-coreml`, whichever holds an Encoder; nil when neither does.
    public static func directory(for variant: ModelVariant) -> URL? {
        directory(for: variant, root: root)
    }

    /// `<root>/silero-vad-coreml` when it holds the model `SpeechCheck` uses; nil otherwise.
    public static func sileroDirectory() -> URL? {
        let directory = root.appendingPathComponent("silero-vad-coreml", isDirectory: true)
        return FileManager.default.fileExists(atPath: directory.appendingPathComponent(SpeechCheck.modelName).path)
            ? directory : nil
    }

    static var root: URL {
        if let dir = ProcessInfo.processInfo.environment["TF_MODELS_DIR"], !dir.isEmpty {
            return URL(fileURLWithPath: dir, isDirectory: true)
        }
        return URL.applicationSupportDirectory.appendingPathComponent("FluidAudio/Models", isDirectory: true)
    }

    static func directory(for variant: ModelVariant, root: URL) -> URL? {
        [variant.rawValue, variant.rawValue + "-coreml"]
            .map { root.appendingPathComponent($0, isDirectory: true) }
            .first { FileManager.default.fileExists(atPath: $0.appendingPathComponent("Encoder.mlmodelc").path) }
    }
}
