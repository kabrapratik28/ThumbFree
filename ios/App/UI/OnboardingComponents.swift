import SwiftUI
import TFCore

// The pieces every onboarding screen is built from: the bar of steps, the title, the buttons, the status chip and the
// speech model's line. Their sizes are the design's; text follows Dynamic Type.

/// The onboarding's bar: three parts, the steps reached in the brand color, the rest muted. It is decorative:
/// `OnboardingHeader` says the step to VoiceOver.
struct ProgressSegments: View {
    /// The step on screen, from 0.
    let current: Int
    var total = 3

    var body: some View {
        HStack(spacing: 6) {
            ForEach(0..<total, id: \.self) { index in
                Capsule().fill(index <= current ? Theme.primary : Theme.muted).frame(height: 6)
            }
        }
    }
}

/// Back (when there is a step to go back to) and the bar, one height on every step. VoiceOver hears the bar as one
/// line: "Step 2 of 3, Add the ThumbFree keyboard".
struct OnboardingHeader: View {
    let step: Int
    let name: String
    var onBack: (() -> Void)?
    /// The Back button grows with the text, following the largest text style, which grows the least at the largest
    /// sizes, so the circle never takes the bar's width.
    @ScaledMetric(relativeTo: .largeTitle) private var chip: CGFloat = 44

    /// The bar as VoiceOver reads it.
    static func label(step: Int, name: String) -> String { "Step \(step + 1) of 3, \(name)" }

    var body: some View {
        HStack(spacing: 8) {
            if let onBack {
                Button(action: onBack) {
                    Image(systemName: "chevron.backward").font(.title3.weight(.semibold)).frame(width: chip, height: chip)
                        .contentShape(.rect)
                }
                .foregroundStyle(Theme.primary)
                .accessibilityLabel("Back")
                .accessibilityIdentifier("welcome.back")
            } else {
                Color.clear.frame(width: chip, height: chip)
            }
            ProgressSegments(current: step)
                .accessibilityElement(children: .ignore)
                .accessibilityLabel(Self.label(step: step, name: name))
                .accessibilityIdentifier("welcome.progress")
            Color.clear.frame(width: chip, height: chip)
        }
        .padding(.horizontal, 12)
        .padding(.top, 8)
    }
}

/// A step's title and the line under it. The title is the screen's heading for VoiceOver, and never leaves one word
/// alone on its last line.
struct OnboardingTitleBlock: View {
    let title: String
    var support: String?
    var id = "welcome.title"

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(title.keepingLastWordsTogether)
                .font(.title2.bold())
                .foregroundStyle(Theme.ink)
                .accessibilityLabel(title)
                .accessibilityAddTraits(.isHeader)
                .accessibilityIdentifier(id)
            if let support {
                Text(support).font(.subheadline).foregroundStyle(Theme.inkSoft).accessibilityIdentifier("welcome.support")
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .fixedSize(horizontal: false, vertical: true) // wraps, never cut
    }
}

/// A button shrinks a little while pressed, as iOS's own do, eased in and out; with Reduce Motion it only dims.
private struct PressStyle: ButtonStyle {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .scaleEffect(configuration.isPressed && !reduceMotion ? 0.98 : 1)
            .opacity(configuration.isPressed ? 0.85 : 1)
            .animation(reduceMotion ? nil : .easeOut(duration: 0.12), value: configuration.isPressed)
    }
}

/// The big button: the one thing the step needs now. Full width, 54 points tall, filled with the brand color.
struct PrimaryActionButton: View {
    let title: String
    var id = "welcome.primary"
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(title)
                .font(.headline)
                .multilineTextAlignment(.center)
                .foregroundStyle(Theme.onPrimary)
                .padding(.horizontal, 20)
                .frame(maxWidth: .infinity, minHeight: 54)
                .background(Theme.primary, in: .capsule)
                .contentShape(.capsule)
        }
        .buttonStyle(PressStyle())
        .accessibilityIdentifier(id)
    }
}

/// The other choice: as big as the main button, outlined in the brand color.
struct SecondaryActionButton: View {
    let title: String
    var id = "welcome.secondary"
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(title)
                .font(.headline)
                .multilineTextAlignment(.center)
                .foregroundStyle(Theme.primary)
                .padding(.horizontal, 20)
                .frame(maxWidth: .infinity, minHeight: 54)
                .overlay(Capsule().strokeBorder(Theme.primary, lineWidth: 1.5))
                .contentShape(.capsule)
        }
        .buttonStyle(PressStyle())
        .accessibilityIdentifier(id)
    }
}

/// A quiet way out or aside (Not now, Change language): text in the brand color, a tap target at least 44 points tall.
struct QuietActionButton: View {
    let title: String
    var id = "welcome.quiet"
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(title)
                .font(.subheadline.weight(.semibold))
                .multilineTextAlignment(.center)
                .foregroundStyle(Theme.primary)
                .padding(.horizontal, 16)
                .frame(minWidth: 44, minHeight: 44)
                .contentShape(.rect)
        }
        .buttonStyle(PressStyle())
        .frame(maxWidth: .infinity)
        .accessibilityIdentifier(id)
    }
}

/// One answer to "Which language do you speak?": the language, and under it what it covers and its size on one line,
/// "Best for English · about 465 MB", or on two lines without the dot when one is too narrow. Each answer keeps the room
/// of the tallest (`all`), its words in the middle, so the answers are one height. The phone's guess is filled with the
/// brand color, the other outlined.
struct LanguageChoiceButton: View {
    /// What an answer says under its name.
    struct Detail: Hashable {
        let covers: String
        let size: String
    }

    let title: String
    let detail: Detail
    /// Every answer's detail.
    let all: [Detail]
    let recommended: Bool
    let id: String
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 12) {
                ZStack(alignment: .leading) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(title).font(.headline)
                        ZStack { ForEach(all, id: \.self) { LanguageDetail(detail: $0) } }
                    }
                    .hidden()
                    .accessibilityHidden(true)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(title).font(.headline)
                        LanguageDetail(detail: detail).foregroundStyle(recommended ? Theme.onPrimary : Theme.inkSoft)
                    }
                }
                .multilineTextAlignment(.leading)
                .fixedSize(horizontal: false, vertical: true) // wraps, never cut: the page's picture gives way first
                Spacer(minLength: 0)
                Image(systemName: "arrow.forward").font(.subheadline.weight(.semibold)).accessibilityHidden(true)
            }
            .foregroundStyle(recommended ? Theme.onPrimary : Theme.primary)
            .padding(.horizontal, 16)
            .padding(.vertical, 11)
            .frame(maxWidth: .infinity, minHeight: 68, alignment: .leading)
            .background(recommended ? Theme.primary : Color.clear, in: .rect(cornerRadius: 18, style: .continuous))
            .overlay {
                if !recommended { RoundedRectangle(cornerRadius: 18, style: .continuous).strokeBorder(Theme.primary, lineWidth: 1.5) }
            }
            .contentShape(.rect(cornerRadius: 18, style: .continuous))
        }
        .buttonStyle(PressStyle())
        .accessibilityLabel("\(title). \(detail.covers), \(detail.size)")
        .accessibilityIdentifier(id)
    }
}

/// A language answer's detail in the footnote style: on one line with a dot, or on two without it.
private struct LanguageDetail: View {
    let detail: LanguageChoiceButton.Detail

    var body: some View {
        ViewThatFits(in: .horizontal) {
            Text("\(detail.covers) · \(detail.size)").lineLimit(1)
            VStack(alignment: .leading, spacing: 0) {
                Text(detail.covers)
                Text(detail.size)
            }
        }
        .font(.footnote)
    }
}

/// A short state in a capsule: ready in green, busy on the chip color, a problem in red.
struct StatusChip: View {
    enum Tone { case ready, busy, problem }

    let text: String
    let symbol: String
    var tone = Tone.ready
    var id = "status.chip"
    @ScaledMetric(relativeTo: .subheadline) private var size: CGFloat = 14

    var body: some View {
        HStack(spacing: 8) {
            Image(systemName: symbol).accessibilityHidden(true)
            Text(text)
        }
        .font(.system(size: size, weight: .semibold))
        .foregroundStyle(tone == .ready ? Theme.success : tone == .problem ? Theme.error : Theme.ink)
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
        .frame(minHeight: 32)
        .background(tone == .ready ? Theme.success.opacity(0.12) : tone == .problem ? Theme.error.opacity(0.1) : Theme.chip,
                    in: .capsule)
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier(id)
    }
}

/// The speech model's state in one line, "English · 42%", with a thin bar while it downloads. VoiceOver hears the
/// line once, not the bar, and while the download runs on its own it hears each tenth.
struct ModelProgressLine: View {
    let model: SpeechModel
    let engine: EnginePhase

    var body: some View {
        let line = Self.line(model.languageName, phase: model.phase, waiting: model.waiting, engine: engine,
                             total: model.totalBytes)
        let percent = Self.percent(model.phase)
        VStack(alignment: .leading, spacing: 8) {
            Text(line)
                .font(.footnote.weight(.semibold).monospacedDigit())
                .foregroundStyle(Self.failed(model.phase, engine: engine) ? Theme.error : Theme.inkSoft)
            if let percent, model.waiting == nil {
                ProgressTrack(fraction: Double(percent) / 100).accessibilityHidden(true)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier("welcome.model")
        .announcesProgress(of: model)
    }

    /// How far the download is, while bytes still come; nil otherwise.
    static func percent(_ phase: SpeechModel.Phase) -> Int? {
        guard case .downloading(let done, let total) = phase, done < total else { return nil }
        return Int(done * 100 / max(total, 1))
    }

    static func failed(_ phase: SpeechModel.Phase, engine: EnginePhase) -> Bool {
        if case .failed = phase { return true }
        return phase == .ready && engine == .failed
    }

    /// "English · 42%", or the state in the same few words the screens use.
    static func line(_ name: String, phase: SpeechModel.Phase, waiting: SpeechModel.Waiting?, engine: EnginePhase,
                     total: Int64) -> String {
        let state: String
        switch (phase, waiting) {
        case (.downloading, .wifi?): state = String(localized: "Waiting for Wi-Fi")
        case (.downloading, .connection?): state = String(localized: "Waiting for a connection")
        case (.downloading(let done, let all), _):
            state = done < all ? String(localized: "\(done * 100 / max(all, 1))%") : String(localized: "Checking the download")
        case (.missing, _):
            let amount = ModelStatusView.size(total).replacingOccurrences(of: " ", with: "\u{00A0}")
            state = String(localized: "about \(amount)")
        case (.failed(.noSpace), _): state = String(localized: "Not enough space")
        case (.failed(.checkFailed), _): state = String(localized: "Couldn’t check the download")
        case (.failed, _): state = String(localized: "Download paused")
        case (.ready, _): state = engine == .failed ? String(localized: "Couldn’t get speech ready") : String(localized: "Downloaded")
        }
        return String(localized: "\(name) · \(state)")
    }
}

extension View {
    /// VoiceOver hears the model's download at each tenth while it runs on its own, "English, 40 percent", once each.
    func announcesProgress(of model: SpeechModel) -> some View { modifier(ProgressAnnouncer(model: model)) }

    /// Change language: iOS's own sheet offers the other model, which then becomes the one in use in place of the
    /// download running, which stops (`SpeechModels.chooseLanguage`): its own download starts, or the engine takes it when
    /// it is here; or keeps this one.
    func changesLanguage(_ isPresented: Binding<Bool>, models: SpeechModels) -> some View {
        let active = models.active
        let other = active === models.multilingual ? models.english : models.multilingual
        return confirmationDialog("Change language?", isPresented: isPresented, titleVisibility: .visible) {
            Button("Use \(other.languageName)") { models.chooseLanguage(other) }
            // A plain button: iOS 26 leaves a cancel-role button out of a dialog shown from its button, and both choices
            // must show.
            Button("Keep \(active.languageName)") {}
        } message: {
            Text("This stops the \(active.languageName) download and starts \(other.languageName).")
        }
    }
}

/// `announcesProgress(of:)`'s announcements, only while VoiceOver runs.
struct ProgressAnnouncer: ViewModifier {
    /// The highest tenth VoiceOver has heard of each model's download, by the model's id, while the app runs.
    private static var heard: [String: Int] = [:]

    let model: SpeechModel
    @Environment(\.accessibilityVoiceOverEnabled) private var voiceOver

    /// Whether VoiceOver hears `tenth` (4 for 40%) of `model`'s download: only a tenth above every one heard before, so
    /// a download that starts over (Use mobile data, a resume) never says one twice. It counts as heard from then on.
    static func hears(_ tenth: Int, of model: String) -> Bool {
        guard tenth > heard[model, default: 0] else { return false }
        heard[model] = tenth
        return true
    }

    func body(content: Content) -> some View {
        content.onChange(of: (ModelProgressLine.percent(model.phase) ?? -10) / 10) { _, tenth in
            if voiceOver, Self.hears(tenth, of: model.id) {
                AccessibilityNotification.Announcement("\(model.languageName), \(tenth * 10) percent").post()
            }
        }
    }
}

/// A thin bar of progress: the brand color on the muted track.
struct ProgressTrack: View {
    let fraction: Double

    var body: some View {
        Capsule().fill(Theme.muted)
            .overlay(alignment: .leading) {
                GeometryReader { space in
                    Capsule().fill(Theme.primary).frame(width: space.size.width * min(max(fraction, 0), 1))
                }
            }
            .frame(height: 6)
    }
}

extension SpeechModel {
    /// The model as the welcome names it: "English", or "Other languages" for the multilingual one.
    var languageName: String {
        entries[0].languageHint == nil ? String(localized: "Other languages") : String(localized: "English")
    }

    /// What the model covers, as the language choice says it: "Best for English".
    var languageDetail: String {
        entries[0].languageHint == nil ? String(localized: "Spanish, French, German and 21 more") : String(localized: "Best for English")
    }

    /// The model's size, as the language choice says it: "about 465 MB", kept on one line.
    var languageSize: String {
        String(localized: "about \(ModelStatusView.size(totalBytes))").replacingOccurrences(of: " ", with: "\u{00A0}")
    }

    /// What the language choice says under the model's name.
    var languageChoice: LanguageChoiceButton.Detail { LanguageChoiceButton.Detail(covers: languageDetail, size: languageSize) }
}

extension String {
    /// The text with its last two words bound by a no-break space, so a line never ends with one word alone under it.
    var keepingLastWordsTogether: String {
        guard let space = lastIndex(of: " ") else { return self }
        return replacingCharacters(in: space...space, with: "\u{00A0}")
    }
}
