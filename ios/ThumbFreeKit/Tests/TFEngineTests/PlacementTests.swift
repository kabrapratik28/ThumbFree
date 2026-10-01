import CoreML
import Foundation
import Testing
@testable import TFEngine

struct PlacementTests {
    @Test func encoderMovesToTheCPUWhenLessThanHalfIsOnTheNeuralEngine() {
        #expect(ParakeetEngine.encoderUnits(forNeuralEngineShare: 0.3) == .cpuOnly)
        #expect(ParakeetEngine.encoderUnits(forNeuralEngineShare: 0.5) == .cpuAndNeuralEngine)
        #expect(ParakeetEngine.encoderUnits(forNeuralEngineShare: 0.99) == .cpuAndNeuralEngine)
    }

    /// A plan that cannot be assessed must throw, not silently report a 0% share (which would wrongly send
    /// the Encoder to the CPU instead of keeping the Neural Engine).
    @Test func placementCheckThrowsInsteadOfReportingZero() async {
        let missing = FileManager.default.temporaryDirectory.appendingPathComponent("no-such-encoder.mlmodelc")
        await #expect(throws: (any Error).self) {
            try await ParakeetEngine.neuralEngineShare(of: missing, units: .cpuAndNeuralEngine)
        }
    }

    @Test func onlyTheNeuralEngineOrTheCPUIsAccepted() async {
        let nowhere = FileManager.default.temporaryDirectory.appendingPathComponent("no-such-models")
        for units in [MLComputeUnits.all, .cpuAndGPU] {
            await #expect(throws: EngineError.loadFailed("Encoder compute units must be .cpuAndNeuralEngine or .cpuOnly")) {
                try await ParakeetEngine(modelDirectory: nowhere, variant: .v2, encoderUnits: units)
            }
        }
    }
}

extension ModelTests {
    @Test(.enabled(if: EngineTestData.v2 != nil, EngineTestData.noModels))
    func encoderIsPlannedOnTheNeuralEngine() async throws {
        let directory = try #require(EngineTestData.v2)
        let share = try await ParakeetEngine.neuralEngineShare(
            of: directory.appendingPathComponent("Encoder.mlmodelc"), units: .cpuAndNeuralEngine)
        print("Encoder operations planned on the Neural Engine: \(share)")
        #expect(share >= 0.9)
        let suiteName = freshSuite()
        defer { UserDefaults(suiteName: suiteName)?.removePersistentDomain(forName: suiteName) }
        func memory() throws -> UserDefaults { try #require(UserDefaults(suiteName: suiteName)) }
        let engine = try await ParakeetEngine(modelDirectory: directory, variant: .v2, placementMemory: memory())
        #expect(await engine.encoderUnits == .cpuAndNeuralEngine)
        #expect(await engine.usesNeuralEngine)
    }

    /// The app's choice for iOS 27 without the Background Inference entitlement.
    @Test(.enabled(if: EngineTestData.v2 != nil, EngineTestData.noModels))
    func cpuEncoderGivesTheSameText() async throws {
        let directory = try #require(EngineTestData.v2)
        let jfk = try EngineTestData.samples("jfk.wav")
        let suiteName = freshSuite()
        defer { UserDefaults(suiteName: suiteName)?.removePersistentDomain(forName: suiteName) }
        func memory() throws -> UserDefaults { try #require(UserDefaults(suiteName: suiteName)) }
        let expected = try await ParakeetEngine(modelDirectory: directory, variant: .v2, placementMemory: memory()).transcribe(jfk).text
        let cpu = try await ParakeetEngine(modelDirectory: directory, variant: .v2, encoderUnits: .cpuOnly, placementMemory: memory())
        #expect(await !cpu.usesNeuralEngine)
        try await cpu.warmUp()
        for _ in 0..<3 {
            let result = try await cpu.transcribe(jfk)
            print(String(format: "JFK v2, Encoder on the CPU: preprocess %.1f, encode %.1f, decode %.1f ms",
                         result.preprocessMs, result.encodeMs, result.decodeMs))
            #expect(result.text == expected)
        }
    }

    /// A failed Encoder prediction on the Neural Engine reloads the Encoder on the CPU, retries
    /// once, and the engine stays on the CPU. The seam makes the next Encoder prediction throw.
    @Test(.enabled(if: EngineTestData.v2 != nil, EngineTestData.noModels))
    func failedNeuralEngineCallMovesTheEncoderToTheCPU() async throws {
        let suiteName = freshSuite()
        defer { UserDefaults(suiteName: suiteName)?.removePersistentDomain(forName: suiteName) }
        func memory() throws -> UserDefaults { try #require(UserDefaults(suiteName: suiteName)) }
        let engine = try await ParakeetEngine(modelDirectory: #require(EngineTestData.v2), variant: .v2, placementMemory: memory())
        let jfk = try EngineTestData.samples("jfk.wav")
        let expected = try await engine.transcribe(jfk).text
        #expect(await engine.usesNeuralEngine)
        await engine.failNextEncoderPrediction()
        #expect(try await engine.transcribe(jfk).text == expected)   // reloaded on the CPU, retried once
        #expect(await !engine.usesNeuralEngine)
        #expect(await engine.encoderUnits == .cpuOnly)
        // On the CPU there is no second fallback: the failure reaches the caller, and the next call works.
        await engine.failNextEncoderPrediction()
        await #expect(throws: EngineError.loadFailed("injected Encoder failure")) { try await engine.transcribe(jfk) }
        #expect(try await engine.transcribe(jfk).text == expected)
    }

    /// At load time, the Neural Engine compiler service can fail while the Encoder loads (it did once on an
    /// iPhone: "Couldn't communicate with a helper application"). The Encoder then loads on the CPU and the engine
    /// works. The static hook makes the next Encoder load throw; the `defer` keeps it from leaking into another test.
    @Test(.enabled(if: EngineTestData.v2 != nil, EngineTestData.noModels))
    func failedNeuralEngineLoadFallsBackToTheCPU() async throws {
        let suiteName = freshSuite()
        defer { UserDefaults(suiteName: suiteName)?.removePersistentDomain(forName: suiteName) }
        func memory() throws -> UserDefaults { try #require(UserDefaults(suiteName: suiteName)) }
        defer { ParakeetEngine.failNextEncoderLoad = false }
        ParakeetEngine.failNextEncoderLoad = true
        let engine = try await ParakeetEngine(modelDirectory: #require(EngineTestData.v2), variant: .v2, placementMemory: memory())
        #expect(await !engine.usesNeuralEngine)
        let result = try await engine.transcribe(EngineTestData.samples("jfk.wav"))
        #expect(result.text == "And so, my fellow Americans, ask not what your country can do for you, ask what you can do for your country.")
    }

    /// A prediction fallback drops the failed Neural Engine Encoder before it loads the CPU copy, so two 445 MB Encoders
    /// never coexist. Seen from outside: when that CPU load fails too, the engine no longer holds the Neural Engine
    /// Encoder (it reports the CPU), and the next call loads the CPU copy and works.
    @Test(.enabled(if: EngineTestData.v2 != nil, EngineTestData.noModels))
    func fallbackDropsTheNeuralEngineEncoderBeforeLoadingTheCPUCopy() async throws {
        let suiteName = freshSuite()
        defer { UserDefaults(suiteName: suiteName)?.removePersistentDomain(forName: suiteName) }
        func memory() throws -> UserDefaults { try #require(UserDefaults(suiteName: suiteName)) }
        defer { ParakeetEngine.failNextEncoderLoad = false }
        let engine = try await ParakeetEngine(modelDirectory: #require(EngineTestData.v2), variant: .v2, placementMemory: memory())
        let jfk = try EngineTestData.samples("jfk.wav")
        let expected = try await engine.transcribe(jfk).text
        #expect(await engine.usesNeuralEngine)
        await engine.failNextEncoderPrediction()
        ParakeetEngine.failNextEncoderLoad = true
        await #expect(throws: EngineError.loadFailed("injected Encoder load failure")) { try await engine.transcribe(jfk) }
        #expect(await !engine.usesNeuralEngine)
        #expect(try await engine.transcribe(jfk).text == expected)
        #expect(await !engine.usesNeuralEngine)
    }

    /// The placement check never delays a load, runs once per Encoder file and iOS version, and its answer
    /// is remembered. With nothing remembered the engine is ready on the Neural Engine without the check;
    /// `settlePlacement()` then runs it once and remembers the answer, and after that it does nothing. Each test in
    /// this file gets its own on-disk `UserDefaults` suite (`freshSuite()`), cleared before and after it
    /// (never `.standard`: other tests and other checkouts must not see these keys). `UserDefaults` is a handle
    /// onto that on-disk suite, not the storage itself, so each use below opens a fresh handle by suite name
    /// rather than reusing one handle across the engine's actor-isolated init (Swift 6 will not let a
    /// non-`Sendable` argument be reused after it crosses into an actor-isolated call).
    @Test(.enabled(if: EngineTestData.v2 != nil, EngineTestData.noModels))
    func placementCheckRunsOnceThenIsRemembered() async throws {
        let directory = try #require(EngineTestData.v2)
        let suiteName = freshSuite()
        defer { UserDefaults(suiteName: suiteName)?.removePersistentDomain(forName: suiteName) }
        func memory() throws -> UserDefaults { try #require(UserDefaults(suiteName: suiteName)) }
        func stored() throws -> [String: String]? { try memory().dictionary(forKey: "TFEncoderPlacement") as? [String: String] }
        let fingerprint = try #require(ParakeetEngine.placementFingerprint(encoder: directory.appendingPathComponent("Encoder.mlmodelc")))

        ParakeetEngine.neuralEngineShareCallCount = 0
        let engine = try await ParakeetEngine(modelDirectory: directory, variant: .v2, placementMemory: memory())
        #expect(ParakeetEngine.neuralEngineShareCallCount == 0)
        #expect(await engine.usesNeuralEngine)
        #expect(try stored() == nil)
        await engine.settlePlacement()
        #expect(ParakeetEngine.neuralEngineShareCallCount == 1)
        #expect(try stored() == [fingerprint: "neuralEngine"])
        #expect(await engine.usesNeuralEngine)
        await engine.settlePlacement()
        #expect(ParakeetEngine.neuralEngineShareCallCount == 1)
    }

    @Test(.enabled(if: EngineTestData.v2 != nil, EngineTestData.noModels))
    func secondLoadWithTheSameSuiteSkipsTheCheck() async throws {
        let directory = try #require(EngineTestData.v2)
        let suiteName = freshSuite()
        defer { UserDefaults(suiteName: suiteName)?.removePersistentDomain(forName: suiteName) }
        func memory() throws -> UserDefaults { try #require(UserDefaults(suiteName: suiteName)) }

        try await ParakeetEngine(modelDirectory: directory, variant: .v2, placementMemory: memory()).settlePlacement()
        ParakeetEngine.neuralEngineShareCallCount = 0
        let engine = try await ParakeetEngine(modelDirectory: directory, variant: .v2, placementMemory: memory())
        await engine.settlePlacement()
        #expect(ParakeetEngine.neuralEngineShareCallCount == 0)
        #expect(await engine.usesNeuralEngine)
    }

    /// A check that finds less than half of the Encoder on the Neural Engine is remembered, and the next load runs the
    /// Encoder on the CPU. The engine that ran the check keeps its Neural Engine Encoder (a CPU load would hold the
    /// actor for seconds while in use). On this Mac nearly all of it is on the Neural Engine, so the hook stands in
    /// for such a plan.
    @Test(.enabled(if: EngineTestData.v2 != nil, EngineTestData.noModels))
    func aCPUAnswerIsRememberedForTheNextLoad() async throws {
        let directory = try #require(EngineTestData.v2)
        let suiteName = freshSuite()
        defer { UserDefaults(suiteName: suiteName)?.removePersistentDomain(forName: suiteName) }
        func memory() throws -> UserDefaults { try #require(UserDefaults(suiteName: suiteName)) }
        let fingerprint = try #require(ParakeetEngine.placementFingerprint(encoder: directory.appendingPathComponent("Encoder.mlmodelc")))
        defer { ParakeetEngine.neuralEngineShareOverride = nil }
        let engine = try await ParakeetEngine(modelDirectory: directory, variant: .v2, placementMemory: memory())

        ParakeetEngine.neuralEngineShareOverride = 0.3
        await engine.settlePlacement()
        #expect(try memory().dictionary(forKey: "TFEncoderPlacement") as? [String: String] == [fingerprint: "cpu"])
        #expect(await engine.usesNeuralEngine)
        let next = try await ParakeetEngine(modelDirectory: directory, variant: .v2, placementMemory: memory())
        #expect(await !next.usesNeuralEngine)
    }

    @Test(.enabled(if: EngineTestData.v2 != nil, EngineTestData.noModels))
    func storedCPUAnswerSkipsTheCheckAndUsesTheCPU() async throws {
        let directory = try #require(EngineTestData.v2)
        let suiteName = freshSuite()
        defer { UserDefaults(suiteName: suiteName)?.removePersistentDomain(forName: suiteName) }
        func memory() throws -> UserDefaults { try #require(UserDefaults(suiteName: suiteName)) }
        let fingerprint = try #require(ParakeetEngine.placementFingerprint(encoder: directory.appendingPathComponent("Encoder.mlmodelc")))
        try memory().set([fingerprint: "cpu"], forKey: "TFEncoderPlacement")

        ParakeetEngine.neuralEngineShareCallCount = 0
        let engine = try await ParakeetEngine(modelDirectory: directory, variant: .v2, placementMemory: memory())
        #expect(ParakeetEngine.neuralEngineShareCallCount == 0)
        #expect(await !engine.usesNeuralEngine)
        await engine.settlePlacement()
        #expect(ParakeetEngine.neuralEngineShareCallCount == 0)
        #expect(await !engine.usesNeuralEngine)
    }

    @Test(.enabled(if: EngineTestData.v2 != nil, EngineTestData.noModels))
    func storedAnswerUnderADifferentFingerprintIsIgnoredAndReplaced() async throws {
        let directory = try #require(EngineTestData.v2)
        let suiteName = freshSuite()
        defer { UserDefaults(suiteName: suiteName)?.removePersistentDomain(forName: suiteName) }
        func memory() throws -> UserDefaults { try #require(UserDefaults(suiteName: suiteName)) }
        let fingerprint = try #require(ParakeetEngine.placementFingerprint(encoder: directory.appendingPathComponent("Encoder.mlmodelc")))
        try memory().set(["stale-fingerprint": "cpu"], forKey: "TFEncoderPlacement")

        ParakeetEngine.neuralEngineShareCallCount = 0
        let engine = try await ParakeetEngine(modelDirectory: directory, variant: .v2, placementMemory: memory())
        #expect(await engine.usesNeuralEngine)
        await engine.settlePlacement()
        #expect(ParakeetEngine.neuralEngineShareCallCount == 1)
        #expect(await engine.usesNeuralEngine)
        let stored = try memory().dictionary(forKey: "TFEncoderPlacement") as? [String: String]
        #expect(stored == [fingerprint: "neuralEngine"])   // the stale entry is gone, not just supplemented
    }
}

/// A `UserDefaults` suite for one test, cleared now and again by the test's `defer`, so a crashed run cannot leave an
/// answer that breaks the next. Named for this checkout's folder (this file is
/// ThumbFreeKit/Tests/TFEngineTests/PlacementTests.swift) and the test rather than a fresh UUID: clearing a suite
/// leaves its empty plist behind, so a rerun reuses the one file, and two checkouts on this Mac never share one.
private func freshSuite(_ test: String = #function) -> String {
    let checkout = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
        .deletingLastPathComponent().deletingLastPathComponent().lastPathComponent
    let name = "TFPlacementTests-\(checkout)-\(test.prefix { $0 != "(" })"
    UserDefaults(suiteName: name)?.removePersistentDomain(forName: name)
    return name
}

struct PlacementFingerprintTests {
    /// A temporary copy of a tiny file stands in for the Encoder's `weights/weight.bin`, so this test does not
    /// need the real model.
    @Test func fingerprintChangesWithModificationDateAndIsNilForAMissingFile() throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent("TFPlacementFingerprint-\(UUID().uuidString)", isDirectory: true)
        defer { try? FileManager.default.removeItem(at: root) }
        let weights = root.appendingPathComponent("weights", isDirectory: true)
        try FileManager.default.createDirectory(at: weights, withIntermediateDirectories: true)
        let weightFile = weights.appendingPathComponent("weight.bin")
        try Data([0, 1, 2, 3]).write(to: weightFile)

        let missing = root.appendingPathComponent("no-such-encoder.mlmodelc")
        #expect(ParakeetEngine.placementFingerprint(encoder: missing) == nil)

        let before = try #require(ParakeetEngine.placementFingerprint(encoder: root))
        try FileManager.default.setAttributes([.modificationDate: Date().addingTimeInterval(60)], ofItemAtPath: weightFile.path)
        let after = try #require(ParakeetEngine.placementFingerprint(encoder: root))
        #expect(before != after)
    }
}
