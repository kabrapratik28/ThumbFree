import CoreML
import Foundation
import TFCore
import TFEngine

// Stop-to-text replay. Run inside ThumbFreeKit:
//   swift run -c release tfreplay [--variant v2|v3] [--cpu] [--realtime] [--clips a.wav,b.wav] [--out FILE]
// Feeds the public English clips through ChunkedTranscriber in 20 ms blocks, as the microphone does, and stops 150 ms
// before, at, 300 ms after and 1 s after the end of speech (the end of the last 30 ms frame with sound: speech, or
// above -55 dBFS). Digital silence follows each clip. Three modes: plain (the final window runs after the tail),
// optimistic and full (the app's). Reports the time from the stop to the result, which path
// gave it, and the WER against refs.tsv next to a plain one-shot transcription, then two long takes. Simulated time
// (the default): before the stop, blocks go in as fast as the engine keeps up; from the stop on, they arrive every
// 20 ms on the real clock. --realtime paces every block. Writes the report to --out (default
// docs/benchmarks/2026-09-27-stop-to-text.md, or 2026-09-27-stop-to-text-cpu.md with --cpu). Transcripts of public
// clips print to the terminal: a developer tool on public audio, not an app log.
let arguments = CommandLine.arguments
func option(_ name: String) -> String? {
    guard let i = arguments.firstIndex(of: name), i + 1 < arguments.count else { return nil }
    return arguments[i + 1]
}
guard let variant = ModelVariant.allCases.first(where: { "\($0)" == (option("--variant") ?? "v2") }) else {
    print("usage: tfreplay [--variant v2|v3] [--cpu] [--realtime] [--clips a.wav,b.wav] [--out FILE]")
    exit(2)
}
guard let modelDirectory = DevModels.directory(for: variant) else {
    print("No \(variant) models. Set TF_MODELS_DIR or cache them under ~/Library/Application Support/FluidAudio/Models.")
    exit(1)
}
let realtime = arguments.contains("--realtime")
let root = URL(fileURLWithPath: #filePath)
    .deletingLastPathComponent().deletingLastPathComponent()
    .deletingLastPathComponent().deletingLastPathComponent()
let data = root.appendingPathComponent("testdata/public")
let cpu = arguments.contains("--cpu")
let out = option("--out").map { URL(fileURLWithPath: $0) }
    ?? root.appendingPathComponent("docs/benchmarks/2026-09-27-stop-to-text\(cpu ? "-cpu" : "").md")

let engine = try await ParakeetEngine(modelDirectory: modelDirectory, variant: variant,
                                      encoderUnits: cpu ? .cpuOnly : .cpuAndNeuralEngine)
try await engine.warmUp()
let silero: SpeechCheck? = if let folder = DevModels.sileroDirectory() { await SpeechCheck(modelDirectory: folder) } else { nil }
let usesNeuralEngineAtStart = await engine.usesNeuralEngine
let encoderPlace = usesNeuralEngineAtStart ? "the Neural Engine" : "the CPU"
let transcribe: @Sendable ([Float]) async throws -> String = { try await engine.transcribe($0).text }
let hasSpeech: @Sendable ([Float]) async -> Bool = { await silero?.hasSpeech($0) ?? true }

enum Mode: String, CaseIterable { case plain, optimistic, full }
let offsetsMs = [-150, 0, 300, 1_000]
func offsetName(_ ms: Int) -> String {
    ms < 0 ? "\(-ms) ms early" : ms == 0 ? "at the end" : ms % 1_000 == 0 ? "\(ms / 1_000) s after" : "\(ms) ms after"
}

/// The end of the last 30 ms frame with sound, as the stop tail judges sound. Padded with one frame's worth of
/// zeros first, so a trailing partial frame is judged the way `LiveTake.append` judges it: as a zero-padded whole
/// frame, not dropped. All-zero frames are never loud, so only a loud partial frame can move `end`.
func soundEnd(_ samples: [Float]) -> Int {
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

/// The engine on the whole clip, cut only where it must be (clips over 15 s, with the bench splitter).
func oneShot(_ samples: [Float]) async throws -> String {
    var texts: [String] = []
    for piece in Bench.windows(samples) { texts.append(try await engine.transcribe(piece).text) }
    return ChunkJoin.join(texts)
}

struct Replay {
    let path: LiveTake.Outcome.Path
    let ms: Double
    let runs: Int
    let runsAfterStop: Int
    let text: String
    var errors = 0
}

let clock = ContinuousClock()
let started = clock.now
func nowMs() -> Int { Int((clock.now - started) / .milliseconds(1)) }

/// One take through a fresh ChunkedTranscriber: blocks up to `stopAt`, the stop, then the tail on the real clock.
func replay(_ take: [Float], stopAt: Int, mode: Mode) async throws -> Replay {
    // Full mode uses ChunkedTranscriber's own defaults (300 ms, optimistic) rather than repeating them here, so
    // "full is what the app runs" cannot go stale if those defaults ever change.
    let transcriber = mode == .full ? ChunkedTranscriber(transcribe: transcribe, hasSpeech: hasSpeech)
        : ChunkedTranscriber(speculateAfterMs: nil, optimistic: mode == .optimistic, transcribe: transcribe, hasSpeech: hasSpeech)
    let begin = clock.now
    var at = 0
    while at < stopAt {
        let end = min(at + 320, stopAt)
        // --realtime: wait for the block's own slot to end before it arrives, as the tail loop below already does,
        // so a block is never appended before its real-time moment.
        if realtime { try await clock.sleep(until: begin + .milliseconds(end / 16)) }
        await transcriber.append(Array(take[at..<end]))
        at = end
        if !realtime { await transcriber.idle() }
    }
    var tailEnd = await transcriber.stop(nowMs: nowMs())
    let stopped = clock.now
    var blocks = 0
    while tailEnd == nil {
        blocks += 1
        try await clock.sleep(until: stopped + .milliseconds(20 * blocks))
        let end = min(at + 320, take.count)
        if end > at { await transcriber.append(Array(take[at..<end])) }
        at = end
        tailEnd = await transcriber.check(nowMs: nowMs())
    }
    let result = try await transcriber.finish()
    return Replay(path: result.outcome.path, ms: result.stopToResultMs, runs: result.outcome.engineRuns,
                  runsAfterStop: result.outcome.runsAfterStop, text: ChunkJoin.join(result.outcome.texts))
}

/// The clip, then digital silence to 1.5 s past the latest stop, so every tail has audio to read.
func takeFor(_ samples: [Float], end: Int) -> [Float] {
    samples + [Float](repeating: 0, count: max(0, end + 16 * (offsetsMs.max() ?? 0) + 24_000 - samples.count))
}

func percentile(_ values: [Double], _ p: Double) -> Double {
    let sorted = values.sorted()
    guard !sorted.isEmpty else { return 0 }
    return sorted[min(sorted.count - 1, max(0, Int((p * Double(sorted.count)).rounded(.up)) - 1))]
}
/// The Mac's chip, for the report ("Apple M4 Pro").
func chip() -> String {
    var size = 0
    sysctlbyname("machdep.cpu.brand_string", nil, &size, nil, 0)
    var bytes = [CChar](repeating: 0, count: size)
    sysctlbyname("machdep.cpu.brand_string", &bytes, &size, nil, 0)
    return String(decoding: bytes.prefix { $0 != 0 }.map { UInt8(bitPattern: $0) }, as: UTF8.self)
}
func percent(_ errors: Int, _ words: Int) -> Double { 100 * Double(errors) / Double(max(1, words)) }
func pathCounts(_ replays: [Replay]) -> String {
    LiveTake.Outcome.Path.allCases.compactMap { path in
        let n = replays.filter { $0.path == path }.count
        return n == 0 ? nil : "\(path.rawValue) \(n)"
    }.joined(separator: ", ")
}

// The clips: English only (the accuracy rule's clips); --clips narrows them for a quick check.
let references = try Bench.references(in: data)
let wanted = option("--clips").map { Set($0.split(separator: ",").map(String.init)) }
if let unknown = wanted?.subtracting(references.keys), !unknown.isEmpty {
    print("--clips names not in refs.tsv: \(unknown.sorted().joined(separator: ", "))")
    exit(2)
}
let names = references.keys.sorted().filter { $0 != "fleurs-de.wav" && (wanted?.contains($0) ?? true) }
var clips: [(name: String, samples: [Float], end: Int, reference: String, oneShot: String)] = []
var oneShotErrors = 0, words = 0
print("model \(variant), Encoder on \(encoderPlace), speech check \(silero == nil ? "off" : "on"), \(realtime ? "real time" : "simulated time")")
for name in names {
    let samples = try WavFile.readMono16k(url: data.appendingPathComponent(name))
    let reference = references[name] ?? ""
    let text = try await oneShot(samples)
    let counts = Bench.wordErrors(reference: reference, hypothesis: text, english: true)
    oneShotErrors += counts.errors
    words += counts.words
    clips.append((name, samples, soundEnd(samples), reference, text))
}
print(String(format: "one-shot WER %.2f%% (%d of %d words)", percent(oneShotErrors, words), oneShotErrors, words))

var replays: [Mode: [Int: [Replay]]] = [:]
var errors: [Mode: Int] = [:]
for mode in Mode.allCases {
    for offset in offsetsMs {
        for clip in clips {
            let take = takeFor(clip.samples, end: clip.end)
            var r = try await replay(take, stopAt: clip.end + 16 * offset, mode: mode)
            r.errors = Bench.wordErrors(reference: clip.reference, hypothesis: r.text, english: true).errors
            replays[mode, default: [:]][offset, default: []].append(r)
            errors[mode, default: 0] += r.errors
            print(String(format: "%-10@ %-14@ %-22@ %6.1f ms  %-10@ runs %d (%d after the stop)  %@", mode.rawValue,
                         offsetName(offset), clip.name, r.ms, r.path.rawValue, r.runs, r.runsAfterStop, r.text))
        }
    }
}

// Long takes: JFK six times with 0.6 s gaps, and every English clip in a row with 0.6 s gaps.
let gap = [Float](repeating: 0, count: 9_600)
let jfk = try WavFile.readMono16k(url: data.appendingPathComponent("jfk.wav"))
var longTakes: [(name: String, samples: [Float], reference: String)] = []
longTakes.append(("JFK 6 times, 0.6 s gaps", (0..<6).flatMap { $0 < 5 ? jfk + gap : jfk },
                  Array(repeating: references["jfk.wav"] ?? "", count: 6).joined(separator: " ")))
longTakes.append(("\(clips.count) clips in a row, 0.6 s gaps", clips.enumerated().flatMap { $0.offset < clips.count - 1 ? $0.element.samples + gap : $0.element.samples },
                  clips.map(\.reference).joined(separator: " ")))
var longRows: [String] = []
var jfkCopiesOK = true
for long in longTakes {
    let end = soundEnd(long.samples)
    let take = takeFor(long.samples, end: end)
    for offset in offsetsMs {
        let r = try await replay(take, stopAt: end + 16 * offset, mode: .full)
        let counts = Bench.wordErrors(reference: long.reference, hypothesis: r.text, english: true)
        var check = String(format: "one-shot %.2f%%", percent(oneShotErrors, words))
        if long.name.hasPrefix("JFK") {
            let copies = Bench.normalize(r.text, english: true).components(separatedBy: "ask not what your country").count - 1
            check = "\(copies) of 6 copies"
            jfkCopiesOK = jfkCopiesOK && copies == 6
        }
        longRows.append(String(format: "| %@ | %.0f s | %@ | %.0f | %@ | %d (%d after the stop) | %.2f%% | %@ |", long.name,
                               Double(long.samples.count) / 16_000, offsetName(offset), r.ms, r.path.rawValue, r.runs,
                               r.runsAfterStop, percent(counts.errors, counts.words), check))
        print(longRows.last ?? "")
    }
}

// Re-read after every replay: a failed Neural Engine prediction reloads the Encoder on the CPU
// mid-run, and the report and exit code must say so rather than repeat the place read at the start.
let usesNeuralEngineNow = await engine.usesNeuralEngine
let fellBackMidRun = usesNeuralEngineAtStart && !usesNeuralEngineNow

// The report.
let oneShotWER = percent(oneShotErrors, words)
var accuracyOK = true
var report = """
# Stop to text on this Mac

Date: \(Date.now.formatted(.iso8601.year().month().day())). Made by `tfreplay`, \(realtime ? "in real time" : "in simulated time").
Machine: \(chip()), macOS \(ProcessInfo.processInfo.operatingSystemVersionString).
Model: \(variant.rawValue), Encoder on \(encoderPlace)\(fellBackMidRun ? "; it fell back to the CPU mid-run" : ""). Speech check: \(silero == nil ? "off (no Silero model)" : "Silero, beside each run").

**Provisional.** These are this Mac's numbers, Encoder on \(encoderPlace). The targets are for an iPhone with an A17 Pro or newer, from the stop tap in the keyboard to the text in the field (p50: 60 ms for a stop after a pause, 250 ms for a stop mid-speech in a take up to 15 s, 300 ms in a longer take); an iPhone run replaces this table.

## Method

- Clips: the \(clips.count) English public clips in `testdata/public` (LibriSpeech and JFK), each followed by digital silence.
- Each take goes through `ChunkedTranscriber` in 20 ms blocks. That size is an assumption here (Android's read size); the iPhone's real buffer size is only measured on a device. The stop comes 150 ms before, at, 300 ms after or 1 s after the end of speech: the end of the last 30 ms frame with sound (speech, or above -55 dBFS), the stop tail's own rule.
- \(realtime ? "Every block waits for its real-time slot." : "Before the stop, blocks go in as fast as the engine keeps up (the harness waits for the engine after each block). From the stop on, the tail's blocks arrive every 20 ms on the real clock.")
- Stop to result: from the stop call to the texts being ready, on the real clock. It includes the stop tail (up to 350 ms) and every engine run the stop still waits for. It leaves out the text pipeline and the keyboard's insertion.
- Modes: **plain** runs the final window after the tail ends; **optimistic** runs it at the stop with zeros for the tail and runs it again only if the tail holds sound; **full** adds the speculative finish 300 ms into each pause. Full is what the app runs.
- WER: every stop of a mode pooled, against `refs.tsv`, next to the engine's one-shot text of each clip (clips over 15 s cut with `Bench.windows`). The rule: at most 0.3 points worse than one-shot.

## Stop to result, ms

| Mode | Stop | p50 | p90 | Paths |
|---|---|---|---|---|

"""
for mode in Mode.allCases {
    for offset in offsetsMs {
        let rs = replays[mode]?[offset] ?? []
        report += String(format: "| %@ | %@ | %.0f | %.0f | %@ |\n", mode.rawValue, offsetName(offset),
                         percentile(rs.map(\.ms), 0.5), percentile(rs.map(\.ms), 0.9), pathCounts(rs))
    }
    let all = offsetsMs.flatMap { replays[mode]?[$0] ?? [] }
    report += String(format: "| %@ | all | %.0f | %.0f | %@ |\n", mode.rawValue, percentile(all.map(\.ms), 0.5),
                     percentile(all.map(\.ms), 0.9), pathCounts(all))
}
report += """

## Each clip, full mode (ms and path at each stop)

| Clip | Length | \(offsetsMs.map(offsetName).joined(separator: " | ")) | Errors at each stop | One-shot errors |
|---|---|\(String(repeating: "---|", count: offsetsMs.count))---|---|

"""
for (i, clip) in clips.enumerated() {
    let rs = offsetsMs.compactMap { replays[.full]?[$0]?[i] }
    let oneShotCount = Bench.wordErrors(reference: clip.reference, hypothesis: clip.oneShot, english: true).errors
    report += "| \(clip.name) | \(String(format: "%.1f s", Double(clip.samples.count) / 16_000)) | "
        + rs.map { String(format: "%.0f %@", $0.ms, $0.path.rawValue) }.joined(separator: " | ")
        + " | \(rs.map { String($0.errors) }.joined(separator: ", ")) | \(oneShotCount) |\n"
}
report += """

## Accuracy

| Transcription | Errors | Words | WER | Against one-shot |
|---|---|---|---|---|
| One-shot | \(oneShotErrors) | \(words) | \(String(format: "%.2f%%", oneShotWER)) | |

"""
for mode in Mode.allCases {
    let e = errors[mode] ?? 0, w = words * offsetsMs.count
    let delta = percent(e, w) - oneShotWER
    accuracyOK = accuracyOK && delta <= 0.3
    let runs = offsetsMs.flatMap { replays[mode]?[$0] ?? [] }.map(\.runs)
    let wordsAStop = Double(e - oneShotErrors * offsetsMs.count) / Double(offsetsMs.count)
    report += String(format: "| %@ (%d stops, %.1f engine runs a take) | %d | %d | %.2f%% | %+.2f points, %+.1f words a stop%@ |\n",
                     mode.rawValue, runs.count, Double(runs.reduce(0, +)) / Double(max(1, runs.count)),
                     e, w, percent(e, w), delta, wordsAStop, delta <= 0.3 ? "" : " (over the 0.3 limit)")
}
report += """

The rule is checked on the Neural Engine run, the app's path\(usesNeuralEngineAtStart ? "" : "; this CPU run (the app's fallback) only reports it"): \(accuracyOK ? "every mode is within 0.3 points of one-shot" : "a mode is more than 0.3 points worse than one-shot").

## Long takes (full mode)

| Take | Length | Stop | ms | Path | Engine runs | WER | Check |
|---|---|---|---|---|---|---|---|
\(longRows.joined(separator: "\n"))

## Notes

- A room above -55 dBFS counts every frame as sound, so there the tail runs to its 350 ms cap and the final window runs again after it. Three clips here already sit above -55 dBFS on their own (`jfk.wav`, `1272-135031-0010.wav`, `1272-135031-0023.wav`); this harness follows each with digital silence where the clip itself ends, so their tails behave like a quiet room's. A real noisy room needs the device run.
- Nothing here measures the app switch, the keyboard, or an iPhone's Neural Engine.

"""
try FileManager.default.createDirectory(at: out.deletingLastPathComponent(), withIntermediateDirectories: true)
try report.write(to: out, atomically: true, encoding: .utf8)
print("wrote \(out.path)")
// The accuracy gate is the Neural Engine run's; a run with the Encoder on the CPU from the start (--cpu, or the
// placement check put it there) only reports it. A mid-run fallback to the CPU invalidates that gate
// for this run, so it fails regardless.
let passed = jfkCopiesOK && (accuracyOK || !usesNeuralEngineAtStart) && !fellBackMidRun
let failReasons = [accuracyOK ? nil : "WER over the 0.3 point limit", jfkCopiesOK ? nil : "JFK copies lost or doubled",
                   fellBackMidRun ? "the Encoder fell back to the CPU mid-run" : nil].compactMap { $0 }
print(passed ? "PASS" : "FAIL: \(failReasons.joined(separator: ", "))")
exit(passed ? 0 : 1)
