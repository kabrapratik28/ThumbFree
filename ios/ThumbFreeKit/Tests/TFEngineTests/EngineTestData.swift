import Foundation
import TFCore
import Testing
@testable import TFEngine

/// Model folders and public clips for the engine tests.
enum EngineTestData {
    static let v2 = DevModels.directory(for: .v2)
    static let v3 = DevModels.directory(for: .v3)
    static let noModels: Comment = "No Parakeet Core ML models: set TF_MODELS_DIR or cache them under ~/Library/Application Support/FluidAudio/Models"
    static func directory(_ variant: ModelVariant) -> URL? { variant == .v2 ? v2 : v3 }

    /// `testdata/public` at the repository root (this file is ThumbFreeKit/Tests/TFEngineTests/EngineTestData.swift).
    static let audio = URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent().deletingLastPathComponent()
        .deletingLastPathComponent().deletingLastPathComponent()
        .appendingPathComponent("testdata/public")

    static func samples(_ name: String) throws -> [Float] {
        try WavFile.readMono16k(url: audio.appendingPathComponent(name))
    }

    /// FluidAudio's text for every public clip (commit 20d4f0b, the same pinned models, a fresh decoder per clip).
    static func oracle(_ variant: ModelVariant) throws -> [String: String] {
        let url = audio.appendingPathComponent(variant == .v2 ? "oracle-fluidaudio-v2.json" : "oracle-fluidaudio-v3.json")
        return try JSONDecoder().decode([String: String].self, from: Data(contentsOf: url))
    }
}
