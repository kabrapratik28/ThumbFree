import AVFoundation
import SwiftUI
import TFCore

/// The first-run screens, three with little to read. Get started starts the speech model's download at once (Wi-Fi only
/// unless you allowed mobile data) and asks for the microphone: a plain line says iOS will ask and why, then iOS's own
/// prompt comes. Never a picture of the prompt or a pointer at Allow. The keyboard step shows the taps in Settings (`SetupGuideView`) under Open Settings, the big
/// button, until you come back from a trip away (Settings, by any route) with the keyboard added: then the guide goes on
/// from Allow Full Access, and Continue is the big button. iOS may list the keyboard before any trip (after a reinstall),
/// so the list alone never makes Continue the big button: people tap it. The step goes on by itself once the keyboard
/// comes up with Full Access anywhere (`KeyboardStatus`). No text box there: people tap it before turning on Full
/// Access; the Try tab's box is where the keyboard is tried. The last step shows dictating in
/// another app (`GuideView`) while the model downloads and gets ready for this iPhone; only then does Start appear, and
/// it opens the Try tab. The step reached is saved, so a
/// flow left halfway comes back there (iOS restarts the app when you change a permission in Settings). Start saves that
/// the flow is done, and it never opens by itself again.
struct WelcomeView: View {
    enum Step: Int, CaseIterable { case welcome, keyboard, howTo }
    static let stepKey = "TFWelcomeStep"
    static let doneKey = "TFWelcomeDone"
    /// The app left the front during the keyboard step (a trip to Settings, by any route), saved with the step.
    static let tripKey = "TFWelcomeTrip"

    let host: SessionHost
    let model: SpeechModel
    let onDone: () -> Void
    @AppStorage(WelcomeView.stepKey) private var step = Step.welcome
    @State private var keyboard = KeyboardStatus.current()
    /// Back from a trip away during the keyboard step: an added keyboard then gets Continue; one still not added, a line
    /// on where else to look. Saved, since iOS may restart the app meanwhile.
    @AppStorage(WelcomeView.tripKey) private var wentAway = false
    /// Get started was tapped and iOS is about to ask for the microphone: the page shows which button to tap.
    @State private var askingMic = false
    @Environment(\.scenePhase) private var scenePhase
    /// The Back button and the dots' row grow with the text. They follow the largest text style, which grows the least
    /// at the largest sizes, so a circle never takes the words' width.
    @ScaledMetric(relativeTo: .largeTitle) private var chip: CGFloat = 44
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    var body: some View {
        VStack(spacing: 0) {
            if !askingMic { header } // while iOS asks, the line takes the top, clear of iOS's prompt in the middle
            // No scrolling: the drawings shrink to the height left (`Shrinks`), captions keep
            // to two lines, and the buttons stay at the bottom. Only at the accessibility text sizes does a page
            // scroll, where clipped words would be worse.
            let content = VStack(alignment: .leading, spacing: 16) { page }
                .padding(.horizontal, 24)
                .padding(.vertical, 12)
                // The first page sits mid-screen; the microphone line at the top, clear of iOS's prompt in the middle.
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: step == .welcome && !askingMic ? .leading : .topLeading)
            if dynamicTypeSize.isAccessibilitySize {
                ScrollView { content }
            } else {
                content
            }
            actions
                .padding(.horizontal, 24)
                .padding(.top, 12)
                .padding(.bottom, 8)
        }
        .background(Theme.paper)
        .foregroundStyle(Theme.ink)
        .tint(Theme.primary)
        .followsKeyboard($keyboard)
        .onChange(of: step) { UIAccessibility.post(notification: .screenChanged, argument: nil) }
        .onChange(of: scenePhase) { _, phase in
            if phase == .background, step == .keyboard { wentAway = true }
        }
        // The keyboard came up with Full Access, anywhere: nothing is left to do here. Also at launch, when iOS ended the
        // app meanwhile and it starts on this step with the keyboard ready.
        .onChange(of: keyboard, initial: true) { _, now in
            if now == .ready, step == .keyboard { step = .howTo }
        }
        .onChange(of: step == .howTo && model.phase == .ready, initial: true) { _, due in
            if due { host.prepareEngine() } // "Getting ready for this iPhone", about 40 s the first time
        }
        // The last step can wait about 40 s with no button: VoiceOver hears when Start comes.
        .onChange(of: step == .howTo && Self.next(model.phase, waiting: model.waiting, engine: host.status.engine) == .start) { _, ready in
            if ready { AccessibilityNotification.Announcement("The model is ready. Start.").post() }
        }
    }

    // MARK: Pages

    @ViewBuilder private var page: some View {
        switch step {
        case .welcome where askingMic:
            Text("iOS will ask to use the microphone. ThumbFree needs it to hear you.")
                .font(.body)
                .multilineTextAlignment(.center)
                .frame(maxWidth: .infinity)
                .padding(.top, 24)
                .accessibilityIdentifier("welcome.micCaption")
        case .welcome:
            scene
            title("Talk. It types.")
            lead("Private voice typing in any app. Your voice stays on your iPhone.")
        case .keyboard:
            title("Add the ThumbFree keyboard")
            if keyboard == .ready {
                done("Keyboard added")
            } else {
                // Back from Settings with the keyboard added, Full Access may still be off: the guide goes on from there.
                let fromFullAccess = wentAway && keyboard == .added
                SetupGuideView(fromFullAccess: fromFullAccess).id(fromFullAccess)
                if wentAway, keyboard == .notAdded {
                    note("Don't see Keyboards? In Settings, go to General, Keyboard, Keyboards, Add New Keyboard.")
                }
            }
        case .howTo:
            title("How it works")
            GuideView()
            ModelStatusView(phase: model.phase, engine: host.status.engine, total: model.totalBytes, waiting: model.waiting)
        }
    }

    /// The line under Get started: what downloads now, and on which network.
    static func downloadLine(size: String, wifiOnly: Bool) -> String {
        "The speech model (about \(size)) downloads now" + (wifiOnly ? ", over Wi-Fi." : ".")
    }

    // MARK: Buttons

    @ViewBuilder private var actions: some View {
        switch step {
        case .welcome where askingMic:
            EmptyView() // iOS's prompt is coming
        case .welcome:
            VStack(spacing: 10) {
                primary("Get started", getStarted)
                if model.phase != .ready {
                    Text(Self.downloadLine(size: ModelStatusView.size(model.totalBytes), wifiOnly: model.wifiOnly))
                        .font(.subheadline)
                        .foregroundStyle(Theme.inkSoft)
                        .multilineTextAlignment(.center)
                }
            }
        case .keyboard:
            VStack(spacing: 4) {
                switch keyboard {
                case .added where wentAway:
                    primary("Continue") { step = .howTo }
                    small("Open Settings", SetupRows.openSettings) // for Allow Full Access
                case .notAdded, .added:
                    primary("Open Settings", SetupRows.openSettings)
                    small("Skip", id: "welcome.skip") { step = .howTo }
                case .ready:
                    primary("Continue") { step = .howTo }
                }
            }
        case .howTo:
            switch Self.next(model.phase, waiting: model.waiting, engine: host.status.engine) {
            case .download?: primary("Download", model.download)
            case .tryAgain?: primary("Try again", model.download)
            case .useMobileData?: primary("Use mobile data", model.useMobileData)
            case .loadAgain?: primary("Try again", host.prepareEngine)
            case .start?: primary("Start", finish)
            case nil: EmptyView()
            }
        }
    }

    /// The last step's one button.
    enum Next: Equatable { case download, tryAgain, useMobileData, loadAgain, start }

    /// No way on while the model downloads or gets ready for this iPhone, since nothing works before it;
    /// Start once it is ready; else the fix. A missing model means the keyboard's model link
    /// came before Get started.
    static func next(_ phase: SpeechModel.Phase, waiting: SpeechModel.Waiting?, engine: EnginePhase) -> Next? {
        switch phase {
        case .missing: .download
        case .failed: .tryAgain
        case .downloading: waiting == .wifi ? .useMobileData : nil
        case .ready: engine == .failed ? .loadAgain : ModelStatusView.tone(phase, engine: engine) == .done ? .start : nil
        }
    }

    private func primary(_ title: String, _ action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(title)
                .font(.headline)
                .foregroundStyle(Theme.onPrimary)
                .frame(maxWidth: .infinity)
                .padding(.vertical, 8)
        }
        .buttonStyle(.borderedProminent)
        .buttonBorderShape(.capsule)
        .accessibilityIdentifier("welcome.primary")
    }

    /// The quieter button under the big one: small type, a tap target at least 44 points tall.
    private func small(_ title: String, id: String = "welcome.secondary", _ action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(title).font(.subheadline.weight(.semibold)).foregroundStyle(Theme.primary)
                .frame(maxWidth: .infinity, minHeight: 44)
                .contentShape(.rect)
        }
        .accessibilityIdentifier(id)
    }

    /// Get started: the download starts at once, as the line under the button says. Unless the microphone was decided
    /// before, the page says for a moment that iOS will ask, then iOS asks. Whatever the answer, the keyboard step is
    /// next, unless the keyboard is already set up.
    private func getStarted() {
        model.download()
        Task {
            if AVAudioApplication.shared.recordPermission == .undetermined {
                askingMic = true
                try? await Task.sleep(for: .seconds(0.6))
                _ = await AVAudioApplication.requestRecordPermission()
                askingMic = false
            }
            step = keyboard == .ready ? .howTo : .keyboard
        }
    }

    private func finish() {
        UserDefaults.standard.set(true, forKey: Self.doneKey)
        onDone()
    }

    // MARK: Parts

    private var header: some View {
        ZStack {
            dots
            HStack {
                if step != .welcome {
                    Button { step = Step(rawValue: step.rawValue - 1) ?? .welcome } label: {
                        Image(systemName: "chevron.backward").font(.title3.weight(.semibold)).frame(width: chip, height: chip)
                    }
                    .accessibilityLabel("Back")
                    .accessibilityIdentifier("welcome.back")
                }
                Spacer()
            }
        }
        .frame(minHeight: chip)
        .padding(.horizontal, 12)
    }

    private var dots: some View {
        HStack(spacing: 6) {
            ForEach(Step.allCases, id: \.self) { one in
                Capsule()
                    .fill(one == step ? Theme.primary : one.rawValue < step.rawValue ? Theme.inkSoft.opacity(0.45) : Theme.chip)
                    .frame(width: one == step ? 24 : 8, height: 8)
            }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("Step \(step.rawValue + 1) of \(Step.allCases.count)")
    }

    /// A chat with the keyboard's mic key: what ThumbFree does, in one picture, drawn at one size and shrunk to fit.
    private var scene: some View {
        Shrinks { picture }.frame(maxWidth: .infinity)
    }

    private var picture: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("Are you on your way?")
                .padding(.horizontal, 14)
                .padding(.vertical, 8)
                .background(Theme.paper, in: .rect(cornerRadius: 16))
            HStack(spacing: 10) {
                Text("Running ten minutes late, save me a seat")
                    .padding(.horizontal, 14)
                    .padding(.vertical, 8)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .overlay(RoundedRectangle(cornerRadius: 20).stroke(Theme.ink, lineWidth: 2))
                Image(systemName: "mic.fill")
                    .font(.title3)
                    .foregroundStyle(Theme.onSunflower)
                    .frame(width: 44, height: 44)
                    .background(Theme.sunflower, in: .circle)
            }
        }
        .font(.callout)
        .padding(14)
        .frame(width: 327) // the page's width on the smallest iPhone
        .background(Theme.chip, in: .rect(cornerRadius: 24))
        .dynamicTypeSize(.large)
        .accessibilityHidden(true)
    }

    private func title(_ text: String) -> some View {
        Text(text)
            .font(.title2.bold())
            .accessibilityAddTraits(.isHeader)
            .accessibilityIdentifier("welcome.title")
    }

    private func lead(_ text: String) -> some View {
        Text(text).font(.body).foregroundStyle(Theme.inkSoft)
    }

    private func note(_ text: LocalizedStringKey) -> some View {
        Text(text).font(.subheadline).foregroundStyle(Theme.inkSoft)
    }

    private func done(_ text: String) -> some View {
        Label(text, systemImage: "checkmark.circle.fill")
            .font(.headline)
            .foregroundStyle(Theme.success)
            .padding(.horizontal, 16)
            .padding(.vertical, 10)
            .background(Theme.success.opacity(0.12), in: .capsule)
    }
}
