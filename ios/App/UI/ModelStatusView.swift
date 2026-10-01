import SwiftUI
import TFCore

/// The speech model's state in words, with a bar while it downloads: the welcome flow's last step and Settings.
/// The Android app's words and colors (red for a problem, green once ready), and no model names.
struct ModelStatusView: View {
    enum Tone { case plain, busy, problem, done }

    let phase: SpeechModel.Phase
    let engine: EnginePhase
    let total: Int64
    /// A download that cannot go on now says what it waits for.
    var waiting: SpeechModel.Waiting? = nil

    var body: some View {
        let tone = Self.tone(phase, engine: engine)
        VStack(alignment: .leading, spacing: 8) {
            if case .downloading(let done, let total) = phase, done < total {
                ProgressView(value: Double(done), total: Double(total)).tint(Theme.primary)
            }
            HStack(alignment: .top, spacing: 10) {
                switch tone {
                case .busy: ProgressView()
                case .problem: Image(systemName: "exclamationmark.circle.fill").foregroundStyle(Theme.error).accessibilityHidden(true)
                case .done: Image(systemName: "checkmark.circle.fill").foregroundStyle(Theme.success).accessibilityHidden(true)
                case .plain: EmptyView()
                }
                VStack(alignment: .leading, spacing: 4) {
                    Text(Self.title(phase, engine: engine, total: total, waiting: waiting))
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(tone == .problem ? Theme.error : tone == .done ? Theme.success : Theme.ink)
                    if let detail = Self.detail(phase, engine: engine) {
                        Text(detail).font(.footnote).foregroundStyle(Theme.inkSoft)
                    }
                }
            }
        }
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier("model.status")
    }

    static func tone(_ phase: SpeechModel.Phase, engine: EnginePhase) -> Tone {
        switch phase {
        case .missing: .plain
        case .downloading(let done, let total): done < total ? .plain : .busy
        case .failed: .problem
        case .ready:
            switch engine {
            case .readyNeuralEngine, .readyCPU: .done
            case .failed: .problem
            default: .busy
            }
        }
    }

    static func title(_ phase: SpeechModel.Phase, engine: EnginePhase, total: Int64, waiting: SpeechModel.Waiting? = nil) -> String {
        switch (phase, waiting) {
        case (.downloading, .wifi?): return "Waiting for Wi-Fi"
        case (.downloading, .connection?): return "Waiting for a connection"
        default: break
        }
        return switch phase {
        case .missing: "Not downloaded · \(size(total))"
        case .downloading(let done, let total): done < total ? "Downloading · \(done * 100 / max(total, 1))%" : "Checking the file"
        case .failed(let failure): reason(failure)
        case .ready:
            switch tone(phase, engine: engine) {
            case .done: "The model is ready"
            case .problem: TakeMessage.loadFailed.text
            default: "Getting ready for this iPhone"
            }
        }
    }

    static func detail(_ phase: SpeechModel.Phase, engine: EnginePhase) -> String? {
        switch phase {
        case .missing: nil
        case .downloading(let done, let total): done < total ? "\(size(done)) of \(size(total))" : nil
        case .failed(let failure): hint(failure)
        case .ready: tone(phase, engine: engine) == .busy ? "The first time takes about half a minute. After that, ThumbFree starts in a moment." : nil
        }
    }

    static func reason(_ failure: ModelDownloader.Failure) -> String {
        switch failure {
        case .noInternet: "No internet connection"
        case .noSpace: "Not enough space"
        case .interrupted: "Download interrupted"
        case .checkFailed: "File check failed"
        }
    }

    static func hint(_ failure: ModelDownloader.Failure) -> String {
        switch failure {
        case .noInternet: "Check your connection, then try again."
        case .noSpace(let needed): "It needs about \(size(needed)) free. Make some room, then try again."
        case .interrupted: "Try again. Files already downloaded are kept."
        case .checkFailed: "The file didn't match. Try again to download it fresh."
        }
    }

    /// Decimal sizes, as the Android app shows them: "465 MB" under 1,000,000,000 bytes, else "1.5 GB".
    static func size(_ bytes: Int64) -> String {
        bytes < 1_000_000_000 ? "\((bytes + 500_000) / 1_000_000) MB" : String(format: "%.1f GB", Double(bytes) / 1e9)
    }
}
