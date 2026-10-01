import CryptoKit
import Foundation
import Testing
@testable import TFCore

// Serialized: `hashingTheEncoderKeepsMemoryFlat` reads `ru_maxrss`, a process-wide high-water mark, so it cannot
// share a run with another test (`theCachedEnglishModelVerifies`) that hashes the same 445 MB file concurrently.
@Suite(.serialized) final class ModelVerifierTests {
    static let cachedV2 = URL.applicationSupportDirectory.appendingPathComponent("FluidAudio/Models/parakeet-tdt-0.6b-v2")
    let folder = FileManager.default.temporaryDirectory.appendingPathComponent("ModelVerifierTests-\(UUID().uuidString)")

    // Each test gets its own temporary folder (see `folder` above); remove it so runs never pile up in shared temp space.
    deinit { try? FileManager.default.removeItem(at: folder) }

    func write(_ path: String, _ data: Data) throws {
        let url = folder.appendingPathComponent(path)
        try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        try data.write(to: url)
    }

    @Test func aKnownVector() throws {
        try write("abc.txt", Data("abc".utf8))
        #expect(try ModelVerifier.sha256(of: folder.appendingPathComponent("abc.txt"))
                == "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
    }

    // Read in 1 MiB blocks: a file that ends past a block edge hashes like the whole file at once.
    @Test func blocksHashLikeTheWholeFile() throws {
        var rng = SplitMix64(seed: 1)
        let data = Data((0..<(5 << 19) + 3).map { _ in UInt8.random(in: 0...255, using: &rng) }) // 2.5 MiB and 3 bytes
        try write("big.bin", data)
        let whole = SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
        #expect(try ModelVerifier.sha256(of: folder.appendingPathComponent("big.bin")) == whole)
    }

    @Test func eachFileIsOkMissingWrongSizeOrWrongHash() throws {
        let abc = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        let entry = ModelEntry(id: "test", displayName: "Test", summary: "", repo: "", revision: "", files: [
            ModelFile(path: "ok/a.bin", bytes: 3, sha256: abc),
            ModelFile(path: "missing.bin", bytes: 3, sha256: abc),
            ModelFile(path: "short.bin", bytes: 3, sha256: abc),
            ModelFile(path: "damaged.bin", bytes: 3, sha256: abc),
        ], languageHint: nil)
        try write("ok/a.bin", Data("abc".utf8))
        try write("short.bin", Data("ab".utf8))
        try write("damaged.bin", Data("abd".utf8))
        try write("extra.json", Data("{}".utf8)) // files the entry does not list are ignored

        #expect(ModelVerifier.check(folder: folder, entry: entry)
                == ["ok/a.bin": .ok, "missing.bin": .missing, "short.bin": .wrongSize, "damaged.bin": .wrongHash])
    }

    @Test func urlInFolderRejectsPathsThatWouldEscape() {
        #expect(ModelFile(path: "a/b.bin", bytes: 0, sha256: "").url(in: folder) == folder.appendingPathComponent("a/b.bin"))
        for path in ["", "/etc/passwd", "../x", "a/../x", "a/./b", "a//b", "a/"] {
            #expect(ModelFile(path: path, bytes: 0, sha256: "").url(in: folder) == nil)
        }
    }

    // A hostile path (from a corrupt manifest, or a bad download response) must never be
    // opened: `appendingPathComponent` does not resolve "..", but `stat`/`open` do, so an unchecked join can
    // resolve outside `folder`. Proof: a real file sits where "../<name>" would land; if `check` ever opened it,
    // the result would be .ok (sizes and hash match), not .missing.
    @Test func hostilePathsAreMissingNotRead() throws {
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        let outsideName = "ModelVerifierTests-outside-\(UUID().uuidString).bin"
        let outside = folder.deletingLastPathComponent().appendingPathComponent(outsideName)
        try Data("abc".utf8).write(to: outside)
        defer { try? FileManager.default.removeItem(at: outside) }

        let abc = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        let entry = ModelEntry(id: "hostile", displayName: "", summary: "", repo: "", revision: "", files: [
            ModelFile(path: "../\(outsideName)", bytes: 3, sha256: abc),
            ModelFile(path: "a//b.bin", bytes: 3, sha256: abc), // empty path component
        ], languageHint: nil)

        #expect(ModelVerifier.check(folder: folder, entry: entry)
                == ["../\(outsideName)": .missing, "a//b.bin": .missing])
    }

    // The Core ML files FluidAudio cached on this Mac are the pinned ones (skipped where they are absent).
    @Test(.enabled(if: FileManager.default.fileExists(atPath: cachedV2.path)))
    func theCachedEnglishModelVerifies() {
        let results = ModelVerifier.check(folder: Self.cachedV2, entry: ModelCatalog.v2)
        #expect(results.count == 21)
        #expect(results.values.allSatisfy { $0 == .ok })
    }

    // A block is freed before the next is read: holding a 445 MB file in memory would get the app killed on an iPhone.
    // Opt-in only: this reads process-wide `ru_maxrss`, so sharing a process with the engine tests (which load
    // models) makes it flaky. Run it alone when `sha256(of:)` changes:
    // TF_MEMORY_TESTS=1 tools/test-kit.sh --filter ModelVerifierTests
    @Test(.enabled(if: ProcessInfo.processInfo.environment["TF_MEMORY_TESTS"] != nil))
    func hashingTheEncoderKeepsMemoryFlat() throws {
        func peakBytes() -> Int {
            var usage = rusage()
            getrusage(RUSAGE_SELF, &usage)
            return usage.ru_maxrss // bytes on Apple platforms
        }
        let before = peakBytes()
        _ = try ModelVerifier.sha256(of: Self.cachedV2.appendingPathComponent("Encoder.mlmodelc/weights/weight.bin"))
        #expect(peakBytes() - before < 100 << 20)
    }
}
