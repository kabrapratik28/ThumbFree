import SwiftUI
import AVFoundation
import FoundationModels
import os

let probeLog = Logger(subsystem: "fmprobe", category: "probe")
let instructions = "Clean up dictated text. Remove repeats and fillers. Keep only the final version of a self-correction. Write times as digits. Keep every name and place. Return only the text."
let sample = "yes yes I booked a table for like seven no seven thirty at the Italian place on Main Street"

/// Probe app: a field for the keyboard test, a foreground call, and a background run that keeps the app alive with
/// silent audio (as ThumbFree's session keeps it alive with the microphone) and calls the model every 3 s for 30 s.
@main struct FMProbeApp: App {
    @Environment(\.scenePhase) private var phase
    @State private var text = UserDefaults.standard.string(forKey: "raw") ?? ""
    @AppStorage("bgLog") private var bgLog = "none"
    @AppStorage("armed") private var armed = false
    @State private var fgResult = "none"
    var body: some Scene {
        WindowGroup {
            ScrollView {
                VStack(alignment: .leading, spacing: 14) {
                    Text("FM probe (for ThumbFree)").font(.headline)
                    Text("model: \(String(describing: SystemLanguageModel.default.availability)); power: \(Probe.battery())")
                        .font(.caption).accessibilityIdentifier("app.status")
                    Text(fgResult).font(.caption)
                    Text("Step 1. Tap the box, switch to FMKeyboard with the globe, tap Clean, wait, then tap Burst and wait.").font(.subheadline)
                    TextField("field", text: $text, axis: .vertical).textFieldStyle(.roundedBorder)
                        .onChange(of: text) { _, now in Probe.save("field", now) }
                    Text("Step 2. Unplug the phone. Tap Arm, go to the Home Screen and keep the screen awake for 2 minutes (swipe between Home pages now and then), then open FM probe again.").font(.subheadline)
                    Button("Arm background test") { armed = true; bgLog = "armed: now go Home for 40 s" }.buttonStyle(.borderedProminent)
                    Text(bgLog).font(.caption2)
                    Text("Step 3. Tell Claude you're done.").font(.subheadline)
                }
                .padding()
            }
            .task { fgResult = await Probe.once(label: "fg") ; Probe.save("fg", fgResult) }
            .onChange(of: phase) { _, now in
                if now == .background && armed { armed = false; Probe.backgroundRun() }
            }
        }
    }
}

enum Probe {
    static var player: AVAudioPlayer?
    nonisolated(unsafe) static var parts: [String: String] = [:]

    /// Keeps every result in Documents/results.txt, which devicectl copies off the phone.
    static func save(_ key: String, _ value: String) {
        parts[key] = value
        let text = parts.keys.sorted().map { "== \($0)\n\(parts[$0]!)" }.joined(separator: "\n")
        let url = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0].appendingPathComponent("results.txt")
        try? text.write(to: url, atomically: true, encoding: .utf8)
    }

    static func battery() -> String {
        UIDevice.current.isBatteryMonitoringEnabled = true
        switch UIDevice.current.batteryState {
        case .charging: return "charging"
        case .full: return "full (on power)"
        case .unplugged: return "unplugged"
        default: return "unknown"
        }
    }

    static func once(label: String) async -> String {
        let t0 = Date()
        do {
            let model = SystemLanguageModel(useCase: .general, guardrails: .permissiveContentTransformations)
            let session = LanguageModelSession(model: model, instructions: instructions)
            let out = try await session.respond(to: sample).content
            let r = "\(label) ok \(String(format: "%.1f", Date().timeIntervalSince(t0)))s: \(out)"
            probeLog.notice("PROBE \(r, privacy: .public)")
            return r
        } catch {
            let r = "\(label) \(describe(error))"
            probeLog.notice("PROBE \(r, privacy: .public)")
            return r
        }
    }

    /// The error in a short line; a rate limit with how long until its reset date (iOS 27).
    static func describe(_ error: Error) -> String {
        if #available(iOS 27.0, *), let e = error as? LanguageModelError {
            if case .rateLimited(let info) = e {
                let wait = info.resetDate.map { String(format: "%.0fs", $0.timeIntervalSinceNow) } ?? "no date"
                return "RATE LIMITED resetDate=\(info.resetDate.map { "\($0)" } ?? "nil") (in \(wait)) \(info.debugDescription.prefix(120))"
            }
            return "error LanguageModelError \(String(describing: e).prefix(200))"
        }
        if let e = error as? LanguageModelSession.GenerationError, case .rateLimited = e { return "RATE LIMITED (GenerationError, no date)" }
        return "error \(String(describing: error).prefix(200))"
    }

    /// Silent audio keeps the app running in the background; then ten calls, 3 s apart, each logged.
    static func backgroundRun() {
        try? AVAudioSession.sharedInstance().setCategory(.playback, options: [.mixWithOthers])
        try? AVAudioSession.sharedInstance().setActive(true)
        let rate = 16_000, seconds = 2
        var wav = Data()
        func le32(_ v: UInt32) { var x = v.littleEndian; wav.append(Data(bytes: &x, count: 4)) }
        func le16(_ v: UInt16) { var x = v.littleEndian; wav.append(Data(bytes: &x, count: 2)) }
        wav.append("RIFF".data(using: .ascii)!); le32(UInt32(36 + rate * 2 * seconds)); wav.append("WAVEfmt ".data(using: .ascii)!)
        le32(16); le16(1); le16(1); le32(UInt32(rate)); le32(UInt32(rate * 2)); le16(2); le16(16)
        wav.append("data".data(using: .ascii)!); le32(UInt32(rate * 2 * seconds)); wav.append(Data(count: rate * 2 * seconds))
        player = try? AVAudioPlayer(data: wav)
        player?.numberOfLoops = -1
        player?.play()
        Task.detached {
            var lines = ["battery \(await MainActor.run { battery() }), audio playing \(await MainActor.run { player?.isPlaying ?? false })"]
            func note(_ line: String) async {
                lines.append(line.count > 170 ? String(line.prefix(170)) : line)
                let text = lines.joined(separator: "\n")
                UserDefaults.standard.set(text, forKey: "bgLog")
                await MainActor.run { save("bg", text) }
            }
            // Back to back for up to 60 s, or until three limits in a row.
            let start = Date()
            var limitsInARow = 0, ok = 0, reset: Date?
            var i = 0
            while Date().timeIntervalSince(start) < 60 && limitsInARow < 3 {
                let state = await MainActor.run { UIApplication.shared.applicationState == .background ? "bg" : "fg" }
                let r = await once(label: String(format: "t+%.0fs call%d [%@]", Date().timeIntervalSince(start), i, state))
                if r.contains("RATE LIMITED") {
                    limitsInARow += 1
                    if #available(iOS 27.0, *), reset == nil, let range = r.range(of: "in "), let end = r[range.upperBound...].firstIndex(of: "s"),
                       let secs = Double(r[range.upperBound..<end]) { reset = Date().addingTimeInterval(secs) }
                } else { limitsInARow = 0; if r.contains(" ok ") { ok += 1 } }
                await note(r)
                i += 1
            }
            await note(String(format: "summary: %d calls, %d ok, stopped at t+%.0fs", i, ok, Date().timeIntervalSince(start)))
            // After the reset date (if one came and is under 2 minutes away), one more call.
            if let reset, reset.timeIntervalSinceNow < 120 {
                try? await Task.sleep(for: .seconds(max(1, reset.timeIntervalSinceNow + 1)))
                await note(await once(label: String(format: "after reset t+%.0fs", Date().timeIntervalSince(start))))
            } else {
                try? await Task.sleep(for: .seconds(30))
                await note(await once(label: String(format: "30s later t+%.0fs", Date().timeIntervalSince(start))))
            }
            await MainActor.run { player?.stop() }
        }
    }
}
