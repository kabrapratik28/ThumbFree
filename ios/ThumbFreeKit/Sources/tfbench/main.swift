import CoreML
import Foundation
import TFCore
import TFEngine

// Engine bench over the public clips. Run: swift run -c release tfbench --variant v2 [--runs 3] [--cpu]
// --cpu loads the Encoder on the CPU, the path for iOS 27 without the Background Inference entitlement.
// Per clip: audio seconds and the median preprocess, encode and decode times of the warm runs, then the text.
// Clips longer than one window are cut with Bench.windows and their times summed.
let arguments = CommandLine.arguments
func option(_ name: String) -> String? {
    guard let i = arguments.firstIndex(of: name), i + 1 < arguments.count else { return nil }
    return arguments[i + 1]
}
guard let variant = ModelVariant.allCases.first(where: { "\($0)" == (option("--variant") ?? "v2") }),
      let runs = Int(option("--runs") ?? "3"), runs > 0 else {
    print("usage: tfbench --variant v2|v3 [--runs N] [--cpu]")
    exit(2)
}
guard let modelDirectory = DevModels.directory(for: variant) else {
    print("No \(variant) models. Set TF_MODELS_DIR or cache them under ~/Library/Application Support/FluidAudio/Models.")
    exit(1)
}
let data = URL(fileURLWithPath: #filePath)
    .deletingLastPathComponent().deletingLastPathComponent()
    .deletingLastPathComponent().deletingLastPathComponent()
    .appendingPathComponent("testdata/public")

let clock = ContinuousClock()
let loadStart = clock.now
let engine = try await ParakeetEngine(modelDirectory: modelDirectory, variant: variant,
                                      encoderUnits: arguments.contains("--cpu") ? .cpuOnly : .cpuAndNeuralEngine)
let loaded = clock.now
try await engine.warmUp()
let place = await engine.usesNeuralEngine ? "the Neural Engine" : "the CPU"
print("model \(variant), Encoder on \(place): load \(loaded - loadStart), warm-up \(clock.now - loaded), runs per clip \(runs)")

func median(_ values: [Double]) -> Double { values.sorted()[values.count / 2] }
let refs = try Bench.references(in: data)
var english = (errors: 0, words: 0)
print("clip                      sec   pre ms  enc ms  dec ms  text")
for name in refs.keys.sorted() {
    let samples = try WavFile.readMono16k(url: data.appendingPathComponent(name))
    var pre: [Double] = [], enc: [Double] = [], dec: [Double] = []
    var text = ""
    for _ in 0..<runs {
        var texts: [String] = []
        var p = 0.0, e = 0.0, d = 0.0
        for piece in Bench.windows(samples) {
            let r = try await engine.transcribe(piece)
            texts.append(r.text); p += r.preprocessMs; e += r.encodeMs; d += r.decodeMs
        }
        text = texts.joined(separator: " ")
        pre.append(p); enc.append(e); dec.append(d)
    }
    let isEnglish = name != "fleurs-de.wav"
    let wer = Bench.wordErrors(reference: refs[name] ?? "", hypothesis: text, english: isEnglish)
    if isEnglish { english.errors += wer.errors; english.words += wer.words }
    print(name.padding(toLength: 24, withPad: " ", startingAt: 0) + String(format: " %5.2f  %6.1f  %6.1f  %6.1f  ", Double(samples.count) / 16_000,
                 median(pre), median(enc), median(dec)) + text)
    if !isEnglish { print(String(format: "  WER %@ %.2f%% (%d of %d words)", name as NSString,
                                 100 * Double(wer.errors) / Double(max(1, wer.words)), wer.errors, wer.words)) }
}
print(String(format: "Pooled English WER %.2f%% (%d of %d words)",
             100 * Double(english.errors) / Double(max(1, english.words)), english.errors, english.words))
