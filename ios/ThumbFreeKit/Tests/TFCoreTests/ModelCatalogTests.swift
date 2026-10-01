import Foundation
import Testing
@testable import TFCore

@Suite struct ModelCatalogTests {
    /// docs/models/model-manifest.json, the pinned files the catalog is generated from.
    struct Manifest: Decodable {
        struct File: Decodable { let path: String; let bytes: Int64; let sha256: String }
        struct Model: Decodable { let revision: String; let files: [File]; let totalBytes: Int64 }
        let v2: Model
        let v3: Model
        let silero: Model
        enum CodingKeys: String, CodingKey {
            case v2 = "parakeet-tdt-0.6b-v2-coreml", v3 = "parakeet-tdt-0.6b-v3-coreml", silero = "silero-vad-coreml"
        }

        static func load() throws -> Manifest {
            let url = TestAudio.dir.deletingLastPathComponent().deletingLastPathComponent()
                .appendingPathComponent("docs/models/model-manifest.json")
            return try JSONDecoder().decode(Manifest.self, from: Data(contentsOf: url))
        }
    }

    @Test func englishIsTheDefault() {
        #expect(ModelCatalog.defaultID == "parakeet-tdt-0.6b-v2")
        #expect(ModelCatalog.all.map(\.id) == ["parakeet-tdt-0.6b-v2", "parakeet-tdt-0.6b-v3"])
    }

    @Test func namesSummariesAndLanguageHints() {
        let v2 = ModelCatalog.v2
        #expect([v2.displayName, v2.summary] == ["English", "Most accurate for English"])
        #expect(v2.languageHint == "en")
        let v3 = ModelCatalog.v3
        #expect([v3.displayName, v3.summary] == ["Multilingual", "Supports 25 languages"])
        #expect(v3.languageHint == nil) // the model hears which language is spoken
    }

    @Test func reposArePinnedToARevision() {
        #expect(ModelCatalog.v2.repo == "FluidInference/parakeet-tdt-0.6b-v2-coreml")
        #expect(ModelCatalog.v2.revision == "ee09c569f73759e6d44c9bd16766f477b2b36d39")
        #expect(ModelCatalog.v3.repo == "FluidInference/parakeet-tdt-0.6b-v3-coreml")
        #expect(ModelCatalog.v3.revision == "7dd20fe6b1797d35f5e3307e8b1732d9a178edfe")
    }

    // The generated literals match the manifest file for file, so regenerating after a manifest change is caught.
    @Test func everyFileMatchesTheManifest() throws {
        let manifest = try Manifest.load()
        for (entry, pinned) in [(ModelCatalog.v2, manifest.v2), (ModelCatalog.v3, manifest.v3)] {
            #expect(entry.revision == pinned.revision)
            #expect(entry.files == pinned.files.map { ModelFile(path: $0.path, bytes: $0.bytes, sha256: $0.sha256) })
            #expect(entry.totalBytes == pinned.totalBytes)
            #expect(entry.files.count == 21)
            #expect(entry.files.allSatisfy { $0.sha256.count == 64 && !$0.path.hasPrefix("/") && !$0.path.contains("..") })
        }
    }

    // The speech check (Silero VAD, MIT) is downloaded with the speech models but is never a choice: only its 256 ms
    // v6.2.1 folder, and not in `all`.
    @Test func sileroIsASupportModelNotAChoice() throws {
        let silero = ModelCatalog.silero
        #expect(silero.id == "silero-vad")
        #expect(silero.repo == "FluidInference/silero-vad-coreml")
        #expect(silero.revision == "b419383c55c110e2c9271fa6ee0ea83d03c70d96")
        #expect(silero.languageHint == nil)
        #expect(silero.files.count == 5 && silero.totalBytes == 1_063_425)
        #expect(silero.files.allSatisfy { $0.path.hasPrefix("silero-vad-unified-256ms-v6.2.1.mlmodelc/") })
        let pinned = try Manifest.load().silero
        #expect(silero.files == pinned.files.filter { $0.path.hasPrefix("silero-vad-unified-256ms-v6.2.1.mlmodelc/") }
            .map { ModelFile(path: $0.path, bytes: $0.bytes, sha256: $0.sha256) })
        #expect(!ModelCatalog.all.contains(silero))
        #expect(ModelCatalog.entry(for: silero.id) == ModelCatalog.v2) // it cannot be picked
    }

    // A saved id the catalog no longer has reads as the default.
    @Test func unknownIDsReadAsTheDefault() {
        #expect(ModelCatalog.entry(for: "parakeet-tdt-0.6b-v3") == ModelCatalog.v3)
        #expect(ModelCatalog.entry(for: "old-model") == ModelCatalog.v2)
        #expect(ModelCatalog.entry(for: nil) == ModelCatalog.v2)
    }
}
