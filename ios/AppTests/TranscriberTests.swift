import Foundation
import Testing
import TFCore
import TFEngine
@testable import ThumbFree

@MainActor @Suite struct TranscriberTests {
    // The Encoder uses the Neural Engine on iOS 26, and on iOS 27 only with the entitlement.
    @Test func iOS27RunsTheEncoderOnTheCPUWithoutTheEntitlement() {
        #expect(Transcriber.encoderUnits(osMajor: 26, entitled: false) == .cpuAndNeuralEngine)
        #expect(Transcriber.encoderUnits(osMajor: 27, entitled: false) == .cpuOnly)
        #expect(Transcriber.encoderUnits(osMajor: 27, entitled: true) == .cpuAndNeuralEngine)
        #expect(Bundle.main.object(forInfoDictionaryKey: "TFBackgroundInference") as? Bool == false)
    }

    // A load that failed is tried again by the next session; only a missing model stays missing.
    @Test func aFailedLoadIsTriedAgain() async throws {
        let folder = try TestFiles.folder()
        defer { try? FileManager.default.removeItem(at: folder) }
        try Data("not a vocabulary".utf8).write(to: folder.appendingPathComponent("parakeet_vocab.json"))
        let transcriber = Transcriber(.parakeet(folder))
        var phases: [EnginePhase] = []
        transcriber.onPhase = { phases.append($0) }
        for _ in 0..<2 { // two sessions, one take each
            transcriber.loadIfNeeded()
            let live = transcriber.newTake()
            await feed(live, level(0.1, seconds: 2))
            do {
                _ = try await live.finish()
                Issue.record("finish should throw")
            } catch {
                #expect(Transcriber.message(for: error) == .loadFailed)
            }
        }
        #expect(phases == [.loading, .failed, .loading, .failed])
    }

    // Live chunking: one ChunkedTranscriber per take, fed 20 ms blocks, as the session host feeds it.
    func feed(_ live: ChunkedTranscriber, _ samples: [Float]) async {
        for start in stride(from: 0, to: samples.count, by: 320) {
            await live.append(Array(samples[start..<min(start + 320, samples.count)]))
        }
    }

    func level(_ value: Float, seconds: Double) -> [Float] { [Float](repeating: value, count: Int(seconds * 16_000)) }

    // A take that starts before the engine has loaded (a cold take) waits for it, then its text goes through the text
    // pipeline.
    @Test func aLiveTakeWaitsForTheLoadThenGoesThroughTheTextPipeline() async throws {
        let transcriber = Transcriber(.fixed("um so hello world"))
        var phases: [EnginePhase] = []
        transcriber.onPhase = { phases.append($0) }
        transcriber.loadIfNeeded() // the session starts the load; the take's first job waits for it
        let live = transcriber.newTake()
        await feed(live, level(0.1, seconds: 2))
        _ = await live.stop(nowMs: 0)
        let result = try await live.finish()
        let expected = TextPipeline.run(chunkTexts: ["um so hello world"], dictionary: [], language: "en")
        #expect(Transcriber.transcription(result, dictionary: [], language: "en") == .text(raw: expected.raw, text: expected.text, speech: true))
        #expect(phases == [.readyCPU])
    }

    // The Dictionary fixes the take's text; the raw text stays the model's own.
    @Test func theDictionaryFixesTheText() async throws {
        let transcriber = Transcriber(.fixed("i asked chat gpt about kubernetis"))
        transcriber.loadIfNeeded()
        let live = transcriber.newTake()
        await feed(live, level(0.1, seconds: 2))
        _ = await live.stop(nowMs: 0)
        let result = try await live.finish()
        #expect(Transcriber.transcription(result, dictionary: ["ChatGPT", "Kubernetes"], language: "en")
                == .text(raw: "i asked chat gpt about kubernetis", text: "i asked ChatGPT about Kubernetes", speech: true))
        // Exact matches only without English (the multilingual model): the near miss stays.
        #expect(Transcriber.transcription(result, dictionary: ["ChatGPT", "Kubernetes"], language: nil)
                == .text(raw: "i asked chat gpt about kubernetis", text: "i asked ChatGPT about kubernetis", speech: true))
    }

    // Transcribe again: when no chunk is loud enough, the speech check hears every chunk, so quiet speech is kept.
    @Test func transcribeAgainKeepsQuietSpeechTheSpeechCheckHears() async throws {
        let quiet = level(0.001, seconds: 2) // under -55 dBFS: the loudness gate hears nothing
        let transcriber = Transcriber(.fixed("quiet words"))
        let withoutCheck = try await transcriber.transcribeAgain(quiet)
        #expect(withoutCheck.heard == false) // no speech check: nothing passes on loudness alone
        transcriber.hearsSpeech = { _ in true }
        let heard = try await transcriber.transcribeAgain(quiet)
        #expect(heard.texts == ["quiet words"])
        #expect(heard.heard)
        transcriber.hearsSpeech = { _ in false }
        #expect(try await transcriber.transcribeAgain(quiet).heard == false)
    }

    // Nothing above -55 dBFS: no engine run, no text, no speech.
    @Test func aSilentLiveTakeNeverReachesTheEngine() async throws {
        let calls = Calls()
        let transcriber = Transcriber(.custom { _ in await calls.add(); return "hello" })
        let live = transcriber.newTake()
        await feed(live, level(0, seconds: 2))
        _ = await live.stop(nowMs: 0)
        let result = try await live.finish()
        #expect(Transcriber.transcription(result, dictionary: [], language: "en") == .text(raw: "", text: "", speech: false))
        #expect(await calls.count == 0)
    }

    @Test func aLiveTakeWithoutAModelFailsWithNoModel() async {
        let transcriber = Transcriber(.parakeet(nil))
        transcriber.loadIfNeeded()
        let live = transcriber.newTake()
        await feed(live, level(0.1, seconds: 2))
        _ = await live.stop(nowMs: 0)
        do {
            _ = try await live.finish()
            Issue.record("finish should throw")
        } catch {
            #expect(Transcriber.message(for: error) == .noModel)
        }
    }

    // No model yet: the phase says so, and the downloaded folder is loaded by the next loadIfNeeded() (this one holds a
    // broken vocabulary, so its load fails, which shows it was tried).
    @Test func aDownloadedModelReplacesNoModel() async throws {
        let folder = try TestFiles.folder()
        defer { try? FileManager.default.removeItem(at: folder) }
        try Data("not a vocabulary".utf8).write(to: folder.appendingPathComponent("parakeet_vocab.json"))
        let transcriber = Transcriber(.parakeet(nil))
        var phases: [EnginePhase] = []
        transcriber.onPhase = { phases.append($0) }
        #expect(!transcriber.hasModel)
        transcriber.loadIfNeeded()
        try await waitUntil { phases == [.noModel] }
        transcriber.useModel(folder)
        #expect(transcriber.hasModel)
        transcriber.loadIfNeeded()
        try await waitUntil { phases == [.noModel, .loading, .failed] }
    }

    // The folder's name says which engine it holds; the multilingual model's text rules have no language, so the
    // Dictionary changes exact matches only.
    @Test func theFolderNameSaysWhichModel() {
        let v3 = URL(fileURLWithPath: "/Models/parakeet-tdt-0.6b-v3")
        #expect(Transcriber.variant(of: v3) == .v3)
        #expect(Transcriber.variant(of: URL(fileURLWithPath: "/Models/parakeet-tdt-0.6b-v3-coreml")) == .v3)
        #expect(Transcriber.variant(of: URL(fileURLWithPath: "/Models/parakeet-tdt-0.6b-v2")) == .v2)
        #expect(Transcriber.variant(of: URL(fileURLWithPath: "/Models/test-fixture")) == .v2)
        #expect(Transcriber(.parakeet(v3)).language == nil)
        #expect(Transcriber(.parakeet(v3)).modelID == ModelCatalog.v3.id)
        #expect(Transcriber(.parakeet(nil)).language == "en")
        #expect(Transcriber(.fixed("hello")).language == "en")
    }

    // Choosing another model drops what was loaded, so the next load reads the new folder; none means no model.
    @Test func anotherModelIsLoadedNext() async throws {
        let root = try TestFiles.folder()
        defer { try? FileManager.default.removeItem(at: root) }
        var folders: [URL] = []
        for name in ["parakeet-tdt-0.6b-v2", "parakeet-tdt-0.6b-v3"] {
            let folder = root.appendingPathComponent(name)
            try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
            try Data("not a vocabulary".utf8).write(to: folder.appendingPathComponent("parakeet_vocab.json"))
            folders.append(folder)
        }
        let transcriber = Transcriber(.parakeet(folders[0]))
        var phases: [EnginePhase] = []
        transcriber.onPhase = { phases.append($0) }
        transcriber.loadIfNeeded()
        try await waitUntil { phases == [.loading, .failed] }
        transcriber.useModel(folders[1])
        #expect(transcriber.modelID == ModelCatalog.v3.id)
        transcriber.loadIfNeeded()
        try await waitUntil { phases == [.loading, .failed, .loading, .failed] }
        transcriber.useModel(nil)
        #expect(!transcriber.hasModel)
        transcriber.loadIfNeeded()
        try await waitUntil { phases.last == .noModel }
    }

    // A fixed engine needs no model, and a model folder does not change it.
    @Test func aFixedEngineNeedsNoModel() {
        let transcriber = Transcriber(.fixed("hello"))
        #expect(transcriber.hasModel)
        transcriber.useModel(FileManager.default.temporaryDirectory)
        #expect(transcriber.modelID == "fixed-text")
    }

    // finish() throws the engine's own error, or noOutcome (a LiveTake bug): both read as the engine failing.
    @Test func engineErrorsReadAsTheEngineFailing() async {
        let transcriber = Transcriber(.custom { _ in throw EngineError.inputTooLong(samples: 1) })
        let live = transcriber.newTake()
        await feed(live, level(0.1, seconds: 2))
        do {
            _ = try await live.finish()
            Issue.record("finish should throw")
        } catch {
            #expect(Transcriber.message(for: error) == .engineFailed)
        }
        #expect(Transcriber.message(for: ChunkedTranscriber.Error.noOutcome) == .engineFailed)
    }
}

/// Counts engine calls.
private actor Calls {
    private(set) var count = 0
    func add() { count += 1 }
}

/// The real-model tier: the Mac's cached Parakeet v2 (on the Simulator), which Core ML compiles for each build (1 to 2 GB,
/// about 30 s). The whole suite runs it; `tools/test-app.sh` leaves it out of focused runs unless the filter names it, and
/// drops Core ML's copy once it has run.
@MainActor @Suite struct RealModelTests {
    // The placement check never delays readiness. With a fresh placement memory the Transcriber reports
    // readyNeuralEngine right after warm-up, before any check; the check runs afterwards, in the background, and only
    // its answer is remembered: the status stays as loaded, even for a "cpu" answer (the Simulator, with no Neural
    // Engine, gives one), which takes effect at the next load. Each phase is noted with the memory's answer at that
    // moment. Needs the v2 model (on the Simulator, the Mac's cached one) and a Neural Engine load: on iOS 27 without
    // the entitlement the Encoder loads on the CPU and no check runs.
    @Test(.enabled(if: DevModels.directory(for: .v2) != nil && Transcriber.encoderUnits() == .cpuAndNeuralEngine))
    func readinessNeverWaitsForThePlacementCheck() async throws {
        let folder = try #require(DevModels.directory(for: .v2))
        let suite = TestFiles.defaultsSuite("TFTranscriberTests")
        defer { UserDefaults(suiteName: suite)?.removePersistentDomain(forName: suite) }
        func answer() -> String {
            (UserDefaults(suiteName: suite)?.dictionary(forKey: "TFEncoderPlacement") as? [String: String])?.values.first ?? "none"
        }
        let transcriber = Transcriber(.parakeet(folder), placementSuite: suite)
        var seen: [String] = []
        transcriber.onPhase = { seen.append("\($0), \(answer())") }
        transcriber.loadIfNeeded()
        try await waitUntil(.seconds(300)) { answer() != "none" }
        // Room for a status change after the check, which must not come (a CPU reload and a readyCPU took about 2 s).
        try await Task.sleep(for: .seconds(5))
        #expect(seen == ["loading, none", "warming, none", "readyNeuralEngine, none"])
    }

    // One model in memory at a time: the placement check holds the engine it checks, so a model change
    // right after readiness loads the next model only once that check has ended. Each phase is noted with the memory's
    // answer at that moment: the next load (a broken one, which fails at once) ends after the answer. Needs what the
    // test above needs.
    @Test(.enabled(if: DevModels.directory(for: .v2) != nil && Transcriber.encoderUnits() == .cpuAndNeuralEngine))
    func theNextModelLoadsOnceTheLastOneLetsGo() async throws {
        let v2 = try #require(DevModels.directory(for: .v2))
        let root = try TestFiles.folder()
        defer { try? FileManager.default.removeItem(at: root) }
        let v3 = root.appendingPathComponent("parakeet-tdt-0.6b-v3")
        try FileManager.default.createDirectory(at: v3, withIntermediateDirectories: true)
        try Data("not a vocabulary".utf8).write(to: v3.appendingPathComponent("parakeet_vocab.json"))
        let suite = TestFiles.defaultsSuite("TFTranscriberTests")
        defer { UserDefaults(suiteName: suite)?.removePersistentDomain(forName: suite) }
        func answer() -> String {
            (UserDefaults(suiteName: suite)?.dictionary(forKey: "TFEncoderPlacement") as? [String: String])?.values.first ?? "none"
        }
        let transcriber = Transcriber(.parakeet(v2), placementSuite: suite)
        var seen: [String] = []
        transcriber.onPhase = { seen.append("\($0), \(answer())") }
        transcriber.loadIfNeeded()
        try await waitUntil(.seconds(300)) { seen.last == "readyNeuralEngine, none" }
        transcriber.useModel(v3) // the placement check is on its way
        transcriber.loadIfNeeded()
        try await waitUntil(.seconds(300)) { seen.count == 5 }
        #expect(seen.prefix(4) == ["loading, none", "warming, none", "readyNeuralEngine, none", "loading, none"])
        #expect(seen.last?.hasPrefix("failed, ") == true && seen.last != "failed, none")
    }
}
