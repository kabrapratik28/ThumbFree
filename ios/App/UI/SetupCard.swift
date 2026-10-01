import AVFoundation
import SwiftUI
import TFCore
import UIKit

/// What ThumbFree needs, one row each with its fix (the Android app's Finish setup rows, with the keyboard in place of
/// the bubble): the microphone, the ThumbFree keyboard with Full Access (`KeyboardStatus`), and the speech model in use.
/// Settings' Setup section shows all three with their state; the Try tab's card, `compact`, only what is missing.
struct SetupRows: View {
    /// The three facts one redraw needs, read once and shared by the header count and every row so the two can
    /// never disagree (the keyboard's status is the one fact that can change while this card stays on screen).
    struct Facts: Equatable {
        let mic: AVAudioApplication.recordPermission
        let keyboard: KeyboardStatus
        let model: Bool
    }

    let host: SessionHost
    let model: SpeechModel
    let facts: Facts
    /// The Try tab's card: only what is missing, one line each with its fix, over the tab's text box.
    var compact = false
    /// Neither scenePhase nor a notification fires the moment the system's mic prompt is answered: the caller
    /// bumps its own redraw from here instead.
    var onMicAnswered: () -> Void = {}
    /// A row's icon circle grows with its title, the text style it sits beside, so the symbol stays inside.
    @ScaledMetric(relativeTo: .headline) private var rowIcon: CGFloat = 44
    @ScaledMetric(relativeTo: .subheadline) private var lineIcon: CGFloat = 26
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    var body: some View {
        let modelLine = Self.modelLine(model.phase, engine: host.status.engine, total: model.totalBytes, waiting: model.waiting)
        VStack(alignment: .leading, spacing: compact ? 12 : 18) {
            if !compact || facts.mic != .granted {
                row("mic.fill", "Microphone", facts.mic == .granted ? "Allowed" : "Needed to hear you",
                    line: facts.mic == .denied ? "Turn on the microphone in Settings" : "Allow the microphone", done: facts.mic == .granted) {
                    if facts.mic != .granted { action("Allow", "setup.mic", allowMic) }
                }
            }
            if !compact || facts.keyboard != .ready {
                row("keyboard", "ThumbFree keyboard", Self.keyboardLine(facts.keyboard, compact: false),
                    line: Self.keyboardLine(facts.keyboard, compact: true), done: facts.keyboard == .ready) {
                    // Added: switching to it finishes the row (its first appearance with Full Access); a small way to
                    // Settings stays for someone who turned Full Access down.
                    switch facts.keyboard {
                    case .notAdded: action("Turn on", "setup.keyboard", Self.openSettings)
                    case .added: link("Open Settings", "setup.fullAccess", Self.openSettings).accessibilityHint("To turn on Allow Full Access.")
                    case .ready: EmptyView()
                    }
                }
            }
            if !compact || !facts.model {
                row("waveform", "\(model.name) speech model", modelLine, line: "Speech model: \(modelLine)", done: facts.model) {
                    switch model.phase {
                    case .missing: action("Get it", "try.getModel", model.download)
                    case .failed: action("Try again", "try.getModel", model.download)
                    case .downloading where compact: // the card's one fix: a download waiting for Wi-Fi
                        if model.waiting == .wifi { action("Use mobile data", "setup.mobileData", model.useMobileData) }
                    case .downloading: action("Cancel", "try.cancelModel", model.cancel)
                    case .ready where host.status.engine == .failed: action("Try again", "try.loadModel", host.prepareEngine)
                    case .ready: EmptyView()
                    }
                }
            }
            if case .downloading(let done, let total) = model.phase, done < total {
                ProgressView(value: Double(done), total: Double(total)).tint(Theme.primary)
            }
            if !compact, model.waiting == .wifi {
                Button("Use mobile data", action: model.useMobileData)
                    .buttonStyle(.bordered)
                    .buttonBorderShape(.capsule)
                    .accessibilityIdentifier("setup.mobileData")
            }
        }
    }

    /// How many of the three are done, from one snapshot: the header above this card reads the very same value, so
    /// it can never disagree with what the rows show.
    static func doneCount(_ facts: Facts) -> Int {
        [facts.mic == .granted, facts.keyboard == .ready, facts.model].filter { $0 }.count
    }

    /// One snapshot of the three facts, for the header count and every row alike, with the keyboard's status as the
    /// screen follows it (`followsKeyboard(_:)`).
    static func facts(model: SpeechModel.Phase, engine: EnginePhase, keyboard: KeyboardStatus) -> Facts {
        Facts(mic: AVAudioApplication.shared.recordPermission, keyboard: keyboard, model: modelDone(model, engine: engine))
    }

    /// The keyboard row's line, under its title, or on its own in the Try tab's card, over its text box (`compact`).
    /// Added but not seen with Full Access: switching to it is what finishes the setup (its first appearance leaves the
    /// mark; without Full Access its own status line says to turn that on).
    static func keyboardLine(_ status: KeyboardStatus, compact: Bool) -> String {
        switch status {
        case .notAdded: compact ? "Add the keyboard in Settings" : "Add it in Settings"
        case .added: compact ? "Tap the box below and switch to ThumbFree" : "Tap a text box and switch to ThumbFree"
        case .ready: "Added, with Full Access"
        }
    }

    /// One row: the full one in Settings, the compact one (only `line`) in the Try tab's card.
    @ViewBuilder private func row(_ symbol: String, _ title: String, _ status: String, line: String, done: Bool,
                                  @ViewBuilder fix: () -> some View) -> some View {
        if compact {
            compactRow(symbol, line, fix: fix)
        } else {
            fullRow(symbol, title, status, done: done, fix: fix)
        }
    }

    /// The Try tab's row: the symbol and what to do, its fix beside it (under it at the accessibility sizes, where the
    /// words would get too narrow a column).
    private func compactRow(_ symbol: String, _ line: String, @ViewBuilder fix: () -> some View) -> some View {
        let label = Label {
            Text(line).font(.subheadline)
        } icon: {
            Image(systemName: symbol).font(.subheadline.weight(.semibold)).frame(width: lineIcon) // the lines start in one column
        }
        .accessibilityElement(children: .combine)
        let layout = dynamicTypeSize.isAccessibilitySize ? AnyLayout(VStackLayout(alignment: .leading, spacing: 8))
                                                          : AnyLayout(HStackLayout(spacing: 8))
        return layout {
            label.frame(maxWidth: .infinity, alignment: .leading)
            fix()
        }
    }

    /// Settings' row: the fix sits at the end, or under the words when large text leaves no room beside them. Text can
    /// always shrink by wrapping, so a single shared label would make the row below read as "fitting" even when
    /// it's really squeezed onto many cramped lines: only the title is pinned to its true, unwrapped width for the
    /// row candidate's fit check (the no-mid-word-break rule), so the status line stays free to wrap to a second
    /// line instead of stacking the whole row early; the stacked candidate keeps both free to wrap normally. Done
    /// swaps the icon for a check and turns the icon and status green, as Android does.
    private func fullRow(_ symbol: String, _ title: String, _ status: String, done: Bool, @ViewBuilder fix: () -> some View) -> some View {
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
            // needs this button's true width (see fullRow(_:_:_:done:fix:) above).
            Text(title).font(compact ? .subheadline.weight(.semibold) : .headline).foregroundStyle(Theme.onPrimary)
                .fixedSize(horizontal: true, vertical: false)
        }
        .buttonStyle(.borderedProminent)
        .buttonBorderShape(.capsule)
        .controlSize(compact ? .small : .regular)
        .tint(Theme.primary) // navy on the sunflower card in light, sunflower on the navy card in dark
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

    /// Allow: the system's question the first time; once refused, only Settings can turn it on.
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

    static func modelDone(_ phase: SpeechModel.Phase, engine: EnginePhase) -> Bool { phase == .ready && engine != .failed }

    /// The model row's line: "Ready", or the model's state in the Android app's words.
    static func modelLine(_ phase: SpeechModel.Phase, engine: EnginePhase, total: Int64, waiting: SpeechModel.Waiting? = nil) -> String {
        if phase == .ready, engine != .failed { return "Ready" }
        return ModelStatusView.title(phase, engine: engine, total: total, waiting: waiting)
    }
}
