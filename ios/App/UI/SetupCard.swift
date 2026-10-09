import AVFoundation
import SwiftUI
import TFCore
import UIKit

/// The one thing that stops dictation now, from the microphone's permission, the keyboard's status, the speech model's
/// download and the engine's load. Home, the try and the welcome's wait each show only this one, with its one fix as
/// the big button (`Words`). A keyboard in iOS's list is added, not ready, until it comes up once with Full Access.
enum SetupBlocker: Equatable {
    case micOff
    /// The keyboard is not in iOS's list.
    case keyboardOff
    /// No model here and no download begun.
    case speechMissing
    case downloading(percent: Int)
    case waitingForWifi
    case waitingForConnection
    /// Every byte is in, and the files are being checked.
    case checking
    /// The download broke off (the connection, or no internet): trying again goes on from what came.
    case downloadPaused
    case noSpace(needed: Int64)
    case checkFailed
    /// The model is here and the engine loads it (about half a minute the first time).
    case loading
    case loadFailed
    /// Home only: speech works, but the keyboard is in iOS's list and not yet seen with Full Access, or iOS has not asked
    /// for the microphone yet. The try finishes both: its box is where the keyboard first comes up, and iOS asks at its
    /// first take.
    case untried

    /// Where the blocker shows: its words differ a little.
    enum Place { case home, tryIt, welcome }

    /// The fix behind a blocker's big button.
    enum Action: Equatable, CaseIterable {
        case openSettings, download, downloadAgain, tryAgain, useMobileData, loadAgain, tryKeyboard

        var title: String {
            switch self {
            case .openSettings: "Open Settings"
            case .download: "Download"
            case .downloadAgain: "Download again"
            case .tryAgain, .loadAgain: "Try again"
            case .useMobileData: "Use mobile data"
            case .tryKeyboard: "Try it"
            }
        }
    }

    /// Home's one blocker: a refused microphone, the keyboard, then speech. What only the try finishes comes last, once
    /// speech works: a keyboard waiting for its first appearance, or a microphone iOS has not asked about, which is no card
    /// of its own (iOS asks at the try's first take). Nil: ready.
    static func forHome(mic: AVAudioApplication.recordPermission, keyboard: KeyboardStatus, phase: SpeechModel.Phase,
                        waiting: SpeechModel.Waiting?, engine: EnginePhase) -> SetupBlocker? {
        if mic == .denied { return .micOff }
        if keyboard == .notAdded { return .keyboardOff }
        if let speech = speech(phase: phase, waiting: waiting, engine: engine) { return speech }
        return keyboard == .added || mic == .undetermined ? .untried : nil
    }

    /// The try's one blocker, also the welcome's step 3: a refused microphone, a keyboard not in iOS's list, then
    /// speech. A microphone iOS has not asked about is none (iOS asks at the first take), nor is a keyboard not yet
    /// seen with Full Access (the box is where it first comes up), nor the engine's load of a model that is here (the
    /// box comes with a small line, and a take waits for the engine). Nil: the try goes on.
    static func forTry(mic: AVAudioApplication.recordPermission, keyboard: KeyboardStatus, phase: SpeechModel.Phase,
                       waiting: SpeechModel.Waiting?, engine: EnginePhase) -> SetupBlocker? {
        if mic == .denied { return .micOff }
        if keyboard == .notAdded { return .keyboardOff }
        let speech = speech(phase: phase, waiting: waiting, engine: engine)
        return speech == .loading ? nil : speech
    }

    /// What is left before speech works: the download in its states, then the engine's load. Nil once loaded.
    static func speech(phase: SpeechModel.Phase, waiting: SpeechModel.Waiting?, engine: EnginePhase) -> SetupBlocker? {
        switch phase {
        case .missing: return .speechMissing
        case .downloading(let done, let total) where done >= total: return .checking
        case .downloading(let done, let total):
            switch waiting {
            case .wifi?: return .waitingForWifi
            case .connection?: return .waitingForConnection
            case nil: return .downloading(percent: Int(done * 100 / max(total, 1)))
            }
        case .failed(.noSpace(let needed)): return .noSpace(needed: needed)
        case .failed(.checkFailed): return .checkFailed
        case .failed: return .downloadPaused
        case .ready:
            switch engine {
            case .readyNeuralEngine, .readyCPU: return nil
            case .failed: return .loadFailed
            default: return .loading
            }
        }
    }

    /// A blocker's few words and its fix, in `place`, for the model `name`d with its size.
    struct Words: Equatable {
        let title: String
        let detail: String?
        let action: Action?
    }

    func words(_ place: Place, model: String, size: Int64) -> Words {
        let amount = ModelStatusView.size(size).replacingOccurrences(of: " ", with: "\u{00A0}")
        let sized = String(localized: "\(model) · about \(amount)")
        switch self {
        case .micOff:
            return Words(title: String(localized: "Microphone is off"),
                         detail: place == .home ? String(localized: "Turn it on in Settings.")
                             : String(localized: "Turn it on in Settings to try ThumbFree."),
                         action: .openSettings)
        case .keyboardOff:
            return Words(title: String(localized: "The keyboard is still off"),
                         detail: String(localized: "Add ThumbFree in Settings."), action: .openSettings)
        case .untried:
            return Words(title: String(localized: "Try ThumbFree"), detail: nil, action: .tryKeyboard)
        case .speechMissing:
            return Words(title: String(localized: "Speech isn’t ready"),
                         detail: place == .tryIt ? String(localized: "Download speech before trying it.") : sized, action: .download)
        case .downloading(let percent):
            return Words(title: String(localized: "Getting speech ready"),
                         detail: place == .home ? String(localized: "\(model) · \(percent)%")
                             : String(localized: "It downloads once. You can leave ThumbFree."),
                         action: nil)
        case .waitingForWifi:
            return Words(title: String(localized: "Waiting for Wi-Fi"), detail: sized, action: .useMobileData)
        case .waitingForConnection:
            return Words(title: String(localized: "Waiting for a connection"),
                         detail: String(localized: "Connect to Wi-Fi or mobile data. The download continues by itself."), action: nil)
        case .checking:
            return Words(title: String(localized: "Checking the download"), detail: String(localized: "This takes a moment."), action: nil)
        case .downloadPaused:
            return Words(title: String(localized: "Speech download paused"), detail: String(localized: "Your progress is saved."),
                         action: .tryAgain)
        case .noSpace(let needed):
            let room = ModelStatusView.size(needed).replacingOccurrences(of: " ", with: "\u{00A0}")
            return Words(title: String(localized: "Not enough space"),
                         detail: String(localized: "Make about \(room) of room, then try again."), action: .tryAgain)
        case .checkFailed:
            return Words(title: String(localized: "Couldn’t check the download"), detail: String(localized: "Download it again."),
                         action: .downloadAgain)
        case .loading:
            // On the welcome's wait, the last stretch before speech works; Home's card says no more than its title.
            return Words(title: place == .welcome ? String(localized: "Almost ready") : String(localized: "Getting ThumbFree ready"),
                         detail: place == .home ? nil : String(localized: "The first time takes about half a minute."), action: nil)
        case .loadFailed:
            return Words(title: String(localized: "Couldn’t get speech ready"), detail: String(localized: "Your download is saved."),
                         action: .loadAgain)
        }
    }

    /// The download can switch to the other language from here: while it runs, waits or broke off.
    var offersLanguageChange: Bool {
        switch self {
        case .downloading, .waitingForWifi, .waitingForConnection, .downloadPaused: true
        default: false
        }
    }

    /// The blocker's symbol, for Home's card and the try's page.
    var symbol: String {
        switch self {
        case .micOff: "mic.slash"
        case .keyboardOff, .untried: "keyboard"
        case .speechMissing, .downloading: "arrow.down.circle"
        case .waitingForWifi: "wifi"
        case .waitingForConnection: "wifi.slash"
        case .checking, .loading: "hourglass"
        case .downloadPaused: "pause.circle"
        case .noSpace, .checkFailed, .loadFailed: "exclamationmark.triangle"
        }
    }

    /// A problem to fix, in red, rather than work under way.
    var isProblem: Bool {
        switch self {
        case .micOff, .noSpace, .checkFailed, .loadFailed, .downloadPaused: true
        default: false
        }
    }
}

/// Home's one card when something is missing: the blocker's title, its state, and its one fix as the big button. Work
/// under way (the download, the first load) has no button: it goes on by itself.
struct SetupBlockerCard: View {
    let blocker: SetupBlocker
    let model: SpeechModel
    let engine: EnginePhase
    let run: (SetupBlocker.Action) -> Void

    var body: some View {
        let words = blocker.words(.home, model: model.languageName, size: model.totalBytes)
        VStack(alignment: .leading, spacing: 12) {
            Label {
                Text(words.title.keepingLastWordsTogether).font(.headline).foregroundStyle(blocker.isProblem ? Theme.error : Theme.ink)
            } icon: {
                Image(systemName: blocker.symbol).foregroundStyle(blocker.isProblem ? Theme.error : Theme.inkSoft)
            }
            .accessibilityLabel(words.title)
            .accessibilityAddTraits(.isHeader)
            .accessibilityIdentifier("home.blocker")
            if case .downloading = blocker {
                ModelProgressLine(model: model, engine: engine)
            } else if let detail = words.detail {
                Text(detail).font(.subheadline).foregroundStyle(Theme.inkSoft)
            }
            if let action = words.action {
                PrimaryActionButton(title: action.title, id: "home.fix") { run(action) }
            }
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Theme.card, in: .rect(cornerRadius: 20, style: .continuous))
        .accessibilityElement(children: .contain)
    }
}

/// What ThumbFree needs, in Settings' Setup section, one row each with its state and fix (the Android app's Finish setup
/// rows, with the keyboard in place of the bubble): the microphone, the ThumbFree keyboard with Full Access
/// (`KeyboardStatus`), and the speech model in use, loaded. Ready means usable now (`ready(_:)`): all three done.
struct SetupRows: View {
    /// The three facts one redraw needs, read once and shared by every row.
    struct Facts: Equatable {
        let mic: AVAudioApplication.recordPermission
        let keyboard: KeyboardStatus
        let model: Bool
    }

    let host: SessionHost
    let model: SpeechModel
    let facts: Facts
    /// Neither scenePhase nor a notification fires the moment the system's mic prompt is answered: the caller
    /// bumps its own redraw from here instead.
    var onMicAnswered: () -> Void = {}
    /// A row's icon circle grows with its title, the text style it sits beside, so the symbol stays inside.
    @ScaledMetric(relativeTo: .headline) private var rowIcon: CGFloat = 44

    var body: some View {
        let modelLine = Self.modelLine(model.phase, engine: host.status.engine, total: model.totalBytes, waiting: model.waiting)
        VStack(alignment: .leading, spacing: 18) {
            row("mic.fill", "Microphone", facts.mic == .granted ? "Allowed" : "Needed to hear you", done: facts.mic == .granted) {
                if let fix = Self.micFix(facts.mic) { action(fix, "setup.mic", allowMic) }
            }
            row("keyboard", "ThumbFree keyboard", Self.keyboardLine(facts.keyboard), done: facts.keyboard == .ready) {
                // Added: switching to it finishes the row (its first appearance with Full Access); a small way to Settings
                // stays for someone who turned Full Access down.
                switch facts.keyboard {
                case .notAdded: action("Turn on", "setup.keyboard", Self.openSettings)
                case .added: link("Open Settings", "setup.fullAccess", Self.openSettings).accessibilityHint("To turn on Allow Full Access.")
                case .ready: EmptyView()
                }
            }
            row("waveform", "\(model.name) speech model", modelLine, done: facts.model) {
                switch model.phase {
                case .missing: action("Get it", "try.getModel", model.download)
                case .failed: action("Try again", "try.getModel", model.download)
                case .downloading: action("Cancel", "try.cancelModel", model.cancel)
                case .ready where host.status.engine == .failed: action("Try again", "try.loadModel", host.prepareEngine)
                case .ready: EmptyView()
                }
            }
            if case .downloading(let done, let total) = model.phase, done < total {
                // VoiceOver skips the bar: the model's row already says how far it is.
                ProgressView(value: Double(done), total: Double(total)).tint(Theme.primary).accessibilityHidden(true)
            }
            if model.waiting == .wifi {
                Button("Use mobile data", action: model.useMobileData)
                    .buttonStyle(.bordered)
                    .buttonBorderShape(.capsule)
                    .accessibilityIdentifier("setup.mobileData")
            }
        }
    }

    /// How many of the three are done.
    static func doneCount(_ facts: Facts) -> Int {
        [facts.mic == .granted, facts.keyboard == .ready, facts.model].filter { $0 }.count
    }

    /// Usable now: the microphone allowed, the keyboard seen with Full Access, and the engine loaded. Home's "Ready ·
    /// works offline" means this.
    static func ready(_ facts: Facts) -> Bool { doneCount(facts) == 3 }

    /// One snapshot of the three facts, with the keyboard's status as the screen follows it (`followsKeyboard(_:)`).
    static func facts(model: SpeechModel.Phase, engine: EnginePhase, keyboard: KeyboardStatus) -> Facts {
        Facts(mic: AVAudioApplication.shared.recordPermission, keyboard: keyboard, model: modelDone(model, engine: engine))
    }

    /// The keyboard row's line under its title. Added but not seen with Full Access: switching to it with Full Access on
    /// is what finishes the setup (its first appearance leaves the mark; without Full Access its own status line says
    /// to turn that on).
    static func keyboardLine(_ status: KeyboardStatus) -> String {
        switch status {
        case .notAdded: "Add it in Settings"
        case .added: "Tap a text box and switch to ThumbFree"
        case .ready: "Added, with Full Access"
        }
    }

    /// Settings' row: the fix sits at the end, or under the words when large text leaves no room beside them. Text can
    /// always shrink by wrapping, so a single shared label would make the row below read as "fitting" even when
    /// it's really squeezed onto many cramped lines: only the title is pinned to its true, unwrapped width for the
    /// row candidate's fit check (the no-mid-word-break rule), so the status line stays free to wrap to a second
    /// line instead of stacking the whole row early; the stacked candidate keeps both free to wrap normally. Done
    /// swaps the icon for a check and turns the icon and status green, as Android does.
    private func row(_ symbol: String, _ title: String, _ status: String, done: Bool, @ViewBuilder fix: () -> some View) -> some View {
        func label(preventWrap: Bool) -> some View {
            HStack(spacing: 14) {
                Image(systemName: done ? "checkmark.circle.fill" : symbol)
                    .font(.body.weight(.semibold))
                    .frame(width: rowIcon, height: rowIcon)
                    .background(Theme.paper.opacity(0.6), in: .circle)
                    .foregroundStyle(done ? Theme.success : Theme.ink)
                    .accessibilityHidden(true)
                VStack(alignment: .leading, spacing: 2) {
                    Text(title).font(.headline).fixedSize(horizontal: preventWrap, vertical: false)
                    Text(status).font(.subheadline).foregroundStyle(done ? Theme.success : Theme.inkSoft)
                }
                .accessibilityElement(children: .combine)
            }
        }
        return ViewThatFits(in: .horizontal) {
            HStack(spacing: 8) {
                label(preventWrap: true)
                Spacer(minLength: 8)
                fix()
            }
            VStack(alignment: .leading, spacing: 10) {
                label(preventWrap: false)
                fix()
            }
        }
    }

    private func action(_ title: String, _ id: String, _ run: @escaping () -> Void) -> some View {
        Button(action: run) {
            // Short caption, but still refuses to wrap: it's the row's fit check, not just its final look, that
            // needs this button's true width (see row(_:_:_:done:fix:) above).
            Text(title).font(.headline).foregroundStyle(Theme.onPrimary)
                .fixedSize(horizontal: true, vertical: false)
        }
        .buttonStyle(.borderedProminent)
        .buttonBorderShape(.capsule)
        .tint(Theme.primary) // navy on the light card, sunflower on the navy card in dark
        .accessibilityIdentifier(id)
    }

    /// A quieter fix: text in the brand color, its tap target at least 44 points tall.
    private func link(_ title: String, _ id: String, _ run: @escaping () -> Void) -> some View {
        Button(action: run) {
            Text(title)
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(Theme.primary)
                .fixedSize() // the row's fit check needs its true width, as `action`'s
                .frame(minHeight: 44)
                .contentShape(.rect)
        }
        .buttonStyle(.borderless) // in Settings' list, only the button itself takes the tap
        .accessibilityIdentifier(id)
    }

    /// The microphone row's button. Before iOS's own question it says Continue, never Allow (App Review guideline
    /// 5.1.1(iv)); once refused, only Settings can turn the microphone on. Nil once allowed.
    static func micFix(_ mic: AVAudioApplication.recordPermission) -> String? {
        switch mic {
        case .undetermined: "Continue"
        case .denied: "Open Settings"
        default: nil
        }
    }

    /// Continue: the system's question the first time; once refused, only Settings can turn it on.
    private func allowMic() {
        guard facts.mic == .undetermined else { return Self.openSettings() }
        Task {
            _ = await AVAudioApplication.requestRecordPermission()
            onMicAnswered()
        }
    }

    /// ThumbFree's page in Settings: its Microphone switch, and Keyboards with ThumbFree and Allow Full Access.
    static func openSettings() {
        guard let url = URL(string: UIApplication.openSettingsURLString) else { return }
        UIApplication.shared.open(url)
    }

    /// The model is usable now: here, and loaded by the engine (`.readyNeuralEngine` or `.readyCPU`), not still loading
    /// or warming up.
    static func modelDone(_ phase: SpeechModel.Phase, engine: EnginePhase) -> Bool { ModelStatusView.tone(phase, engine: engine) == .done }

    /// The model row's line: "Ready", or the model's state in the Android app's words.
    static func modelLine(_ phase: SpeechModel.Phase, engine: EnginePhase, total: Int64, waiting: SpeechModel.Waiting? = nil) -> String {
        if modelDone(phase, engine: engine) { return "Ready" }
        return ModelStatusView.title(phase, engine: engine, total: total, waiting: waiting)
    }
}
