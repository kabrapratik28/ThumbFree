import CoreML
import Foundation
import Testing
import TFCore
import TFEngine
import UIKit

/// Parakeet on a real iPhone: model load, JFK (11 s, one 15 s window) on the Neural Engine and on the CPU, the speech
/// check, and stop tap to text through the live transcriber (tfreplay's method, JFK only). Needs the v2 model in the
/// app's container (`Library/Application Support/FluidAudio/Models`); skipped on the Simulator, which has no Neural
/// Engine. Run on a device with `-only-testing:"ThumbFreeTests/DeviceBenchmarkTests/parakeetOnThisIPhone()"`; the
/// results are the `TFBENCH` lines in the test output.
@Suite(.serialized) struct DeviceBenchmarkTests {
    static let onDeviceWithModel: Bool = {
        #if targetEnvironment(simulator)
        false
        #else
        DevModels.directory(for: .v2) != nil
        #endif
    }()

    @Test(.enabled(if: onDeviceWithModel)) func parakeetOnThisIPhone() async throws {
        let folder = try #require(DevModels.directory(for: .v2))
        let jfk = try WavFile.readMono16k(url: TestFiles.url("jfk.wav"))
        let clock = ContinuousClock()
        func ms(_ d: Duration) -> Double { Double(d / .microseconds(1)) / 1000 }
        func median(_ v: [Double]) -> Double { v.sorted()[v.count / 2] }
        func f(_ v: Double) -> String { String(format: "%.1f", v) }
        await MainActor.run { UIApplication.shared.isIdleTimerDisabled = true } // an office phone locks itself mid-run

        // Where the Neural Engine load goes: the placement check's compute plan, then the model itself, twice.
        let encoderURL = folder.appendingPathComponent("Encoder.mlmodelc")
        let config = MLModelConfiguration()
        config.computeUnits = .cpuAndNeuralEngine
        for round in 1...2 {
            var t = clock.now
            _ = try await MLComputePlan.load(contentsOf: encoderURL, configuration: config)
            let plan = ms(clock.now - t)
            t = clock.now
            _ = try MLModel(contentsOf: encoderURL, configuration: config)
            print("TFBENCH Encoder load, round \(round): compute plan \(f(plan)) ms, model \(f(ms(clock.now - t))) ms")
        }

        var silero: SpeechCheck?
        if let dir = DevModels.sileroDirectory() {
            let t = clock.now
            let check = await SpeechCheck(modelDirectory: dir)
            let load = ms(clock.now - t)
            var runs: [Double] = []
            for _ in 0..<10 {
                let t = clock.now
                _ = await check.hasSpeech(jfk)
                runs.append(ms(clock.now - t))
            }
            print("TFBENCH speech check: load \(f(load)) ms, JFK median \(f(median(runs))) ms")
            silero = check
        }

        // A placement memory of its own, so the placement check below always runs (a remembered answer skips it).
        let suite = TestFiles.defaultsSuite("TFBenchmark")
        defer { UserDefaults(suiteName: suite)?.removePersistentDomain(forName: suite) }
        var neuralEngine: ParakeetEngine?
        for units in [MLComputeUnits.cpuAndNeuralEngine, .cpuOnly] {
            var t = clock.now
            let engine = try await ParakeetEngine(modelDirectory: folder, variant: .v2, encoderUnits: units,
                                                  placementMemory: #require(UserDefaults(suiteName: suite)))
            let load = ms(clock.now - t)
            let place = await engine.usesNeuralEngine ? "Neural Engine" : "CPU"
            t = clock.now
            try await engine.warmUp()
            let warm = ms(clock.now - t)
            if units == .cpuAndNeuralEngine {
                // Where the app runs the placement check: the engine is ready and warmed. The compute-plan
                // rounds above have already made this plan once, so Core ML may reuse it here.
                t = clock.now
                await engine.settlePlacement()
                let answer = (UserDefaults(suiteName: suite)?.dictionary(forKey: "TFEncoderPlacement") as? [String: String])?.values.first
                print("TFBENCH placement check after readiness: settlePlacement \(f(ms(clock.now - t))) ms, answer \(answer ?? "none")")
            }
            var total: [Double] = [], pre: [Double] = [], enc: [Double] = [], dec: [Double] = []
            var heard = false
            for _ in 0..<10 {
                let t = clock.now
                let r = try await engine.transcribe(jfk)
                total.append(ms(clock.now - t))
                pre.append(r.preprocessMs)
                enc.append(r.encodeMs)
                dec.append(r.decodeMs)
                heard = r.text.lowercased().contains("ask not what your country")
            }
            print("TFBENCH \(units == .cpuOnly ? "CPU asked" : "Neural Engine asked"), Encoder on \(place): load \(f(load)) ms, "
                  + "warm-up \(f(warm)) ms; JFK median total \(f(median(total))) ms (preprocess \(f(median(pre))), "
                  + "encode \(f(median(enc))), decode \(f(median(dec)))), min \(f(total.min() ?? 0)), "
                  + "max \(f(total.max() ?? 0)); text right: \(heard)")
            if units == .cpuAndNeuralEngine { neuralEngine = engine }
        }

        // Stop tap to text: JFK then digital silence, blocks until the stop without waiting (the engine keeps up),
        // then the stop tail on the real clock in 20 ms blocks, as tfreplay does.
        let engine = try #require(neuralEngine)
        let speechCheck = silero
        let end = Self.soundEnd(jfk)
        let take = jfk + [Float](repeating: 0, count: max(0, end + 16 * 1_000 + 24_000 - jfk.count))
        let started = clock.now
        func nowMs() -> Int { Int((clock.now - started) / .milliseconds(1)) }
        for offset in [-150, 0, 300, 1_000] {
            var runs: [Double] = []
            var paths: [String] = []
            for _ in 0..<3 {
                let transcriber = ChunkedTranscriber(transcribe: { try await engine.transcribe($0).text },
                                                     hasSpeech: { await speechCheck?.hasSpeech($0) ?? true })
                let stopAt = end + 16 * offset
                var at = 0
                while at < stopAt {
                    let next = min(at + 320, stopAt)
                    await transcriber.append(Array(take[at..<next]))
                    at = next
                    await transcriber.idle()
                }
                var tailEnd = await transcriber.stop(nowMs: nowMs())
                let stopped = clock.now
                var blocks = 0
                while tailEnd == nil {
                    blocks += 1
                    try await clock.sleep(until: stopped + .milliseconds(20 * blocks))
                    let next = min(at + 320, take.count)
                    if next > at { await transcriber.append(Array(take[at..<next])) }
                    at = next
                    tailEnd = await transcriber.check(nowMs: nowMs())
                }
                let result = try await transcriber.finish()
                runs.append(result.stopToResultMs)
                paths.append("\(result.outcome.path)")
            }
            let when = offset < 0 ? "\(-offset) ms early" : offset == 0 ? "at the end" : "\(offset) ms after"
            print("TFBENCH stop \(when): median \(f(median(runs))) ms, runs \(runs.map(f)), paths \(paths)")
        }
    }

    /// The end of the last 30 ms frame with sound, judged over the zero-padded last partial frame like the take's.
    static func soundEnd(_ samples: [Float]) -> Int {
        let samples = samples + [Float](repeating: 0, count: SpeechGate.frameSamples - 1)
        var gate = SpeechGate()
        var end = 0
        var at = 0
        while at + SpeechGate.frameSamples <= samples.count {
            let verdict = gate.push(samples[at..<at + SpeechGate.frameSamples])
            at += SpeechGate.frameSamples
            if verdict.isSpeech || verdict.loud { end = at }
        }
        return end
    }
}
