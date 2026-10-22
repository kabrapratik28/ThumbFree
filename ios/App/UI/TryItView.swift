import AVFoundation
import SwiftUI
import TFCore
import UIKit

/// A first try with the real ThumbFree keyboard, inside ThumbFree: the welcome's step 3, and Home's Try it. A text box
/// brings the iPhone keyboard up, and a line above it follows the person one thing at a time: switch to the ThumbFree
/// keyboard with the real globe key (named in words, never drawn: a drawn globe drew the taps), tap its yellow mic, speak
/// and tap stop while the take records, wait while it turns into text, then it worked. The line follows the live take
/// (`HostStatus.take`). The keyboard leaves its mark (`KeyboardMark`) each time it comes up with Full Access, and while
/// ThumbFree is in front it can only come up in this box, so a mark other than the one there when the screen came up
/// means it is up. A take that gave text (`SessionHost.textTakes` above its value when the screen came up) with new words
/// in the box means it worked: the keyboard types the words into the box, then goes down once the take is over, and Done
/// is the big button. When no on-screen keyboard comes up (a hardware keyboard hides every one, ThumbFree's too), the
/// line says how to show it instead of pointing at a globe that is not there. What stops the try (`SetupBlocker.forTry`:
/// the microphone refused, the keyboard not added, speech still downloading or failed) shows in the box's place as one
/// page with its one fix; the download's pages show how far
/// it is, with Change language. While the engine loads a model that is here (again, when iOS ended ThumbFree in
/// Settings), the box and the keyboard are up with one small line, and a take waits for the engine. iOS asks for the
/// microphone at the first take. A keyboard added but not yet seen with Full Access stops nothing: the box is
/// where it first comes up, and without Full Access its own bar says to turn that on, with a quiet Full Access settings
/// here once it has had time to come up. Not now always leaves; leaving cancels a take that has not given its text yet.
struct TryItView: View {
    /// What the person does next, or what happens now, as the line above the box says it.
    enum Stage: String, CaseIterable { case showKeyboard, switchKeyboard, tapMic, recording, transcribing, worked }

    let host: SessionHost
    let models: SpeechModels
    let onDone: () -> Void
    /// The welcome's step 3: its bar of steps, with no Back (it would only lead to a finished step). Home's sheet has no bar.
    var inWelcome = false

    @State private var text = ""
    @FocusState private var typing: Bool
    /// The keyboard's mark when the screen came up: another one is the ThumbFree keyboard coming up in the box.
    @State private var markAtStart: Date?
    /// Takes that had given text when the screen came up.
    @State private var takesAtStart: Int
    @State private var keyboardSeen = false
    /// The box's text when the latest take began recording: a take worked only once the box changed from it.
    @State private var boxAtRecording: String?
    @State private var keyboard = KeyboardStatus.current()
    /// The switch has had time to happen and the keyboard has not come up with Full Access: a quiet Full Access settings
    /// shows.
    @State private var fullAccessHelp = false
    /// Change language was tapped: iOS's own sheet asks which.
    @State private var changingLanguage = false
    /// VoiceOver starts on the line, while the box has the keyboard.
    @AccessibilityFocusState private var lineFocused: Bool
    /// The keyboard has shown since the box last asked for it: the box stops asking again, so a keyboard put away stays
    /// down.
    @State private var keyboardShown = false
    /// An on-screen keyboard is up: it takes its room from the page's bottom (`keyboardRoom`).
    @State private var keyboardUp = false
    /// The box has wanted the keyboard for `keyboardWait` and none came up, so the switch says how to show one.
    @State private var keyboardHidden = false
    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    @Environment(\.accessibilityVoiceOverEnabled) private var voiceOver

    init(host: SessionHost, models: SpeechModels, onDone: @escaping () -> Void, inWelcome: Bool = false) {
        self.host = host
        self.models = models
        self.onDone = onDone
        self.inWelcome = inWelcome
        _takesAtStart = State(initialValue: host.textTakes)
        _markAtStart = State(initialValue: KeyboardStatus.marksFolder.flatMap(KeyboardMark.lastSeen(in:)))
    }

    var body: some View {
        let _ = scenePhase // back from Settings: read the microphone again
        let blocker = self.blocker
        let stage = self.stage
        let speechLoads = self.speechLoads
        VStack(spacing: 0) {
            if inWelcome { OnboardingHeader(step: 2, name: "Try ThumbFree") }
            // Nothing scrolls with the keyboard up; only the accessibility text sizes scroll.
            let content = VStack(alignment: .leading, spacing: 0) {
                if let blocker {
                    blockerPage(blocker)
                } else {
                    line(stage)
                    ForEach(Self.notes(stage, speechLoads: speechLoads, mic: AVAudioApplication.shared.recordPermission), id: \.self) { note in
                        Text(note)
                            .font(.subheadline)
                            .foregroundStyle(Theme.inkSoft)
                            .fixedSize(horizontal: false, vertical: true)
                            .padding(.top, 6)
                            .accessibilityIdentifier("try.note")
                    }
                    // The box stays one view through every stage, so it keeps the keyboard.
                    box.padding(.top, 16)
                }
            }
            .padding(.horizontal, 24)
            .padding(.top, inWelcome ? 20 : 24)
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
            if dynamicTypeSize.isAccessibilitySize {
                ScrollView { content }
            } else {
                content
            }
            buttons(blocker, stage: stage)
                .padding(.horizontal, 24)
                .padding(.top, 12)
                .padding(.bottom, 8)
        }
        .background { Theme.paper.ignoresSafeArea() } // under the keyboard too, which shows the page's color through it
        .foregroundStyle(Theme.ink)
        .tint(Theme.primary)
        .followsKeyboard($keyboard)
        .onAppear { host.prepareEngine() } // a first take need not wait for the model to load
        // VoiceOver begins on the line, while the box takes the keyboard (input focus and VoiceOver's are apart).
        .task {
            guard voiceOver else { return }
            try? await Task.sleep(for: .milliseconds(500))
            lineFocused = true
        }
        // Speech loaded again while the try was up: said once, since the line that said it was loading just went.
        .onChange(of: speechLoads) { _, loads in
            if !loads, self.blocker == nil { AccessibilityNotification.Announcement(String(localized: "Speech is ready")).post() }
        }
        // Gone (Not now, Done, Home's sheet swiped down): a take still on its way would type into no box and count.
        .onDisappear {
            if let take = Self.takeToCancel(host.status, coldTake: host.coldTake) { host.send(.cancel(take)) }
        }
        // About twice a second while the app is in front, until the ThumbFree keyboard has come up here.
        .task(id: scenePhase == .active && !keyboardSeen) {
            while scenePhase == .active, !keyboardSeen, !Task.isCancelled {
                let mark = KeyboardStatus.marksFolder.flatMap(KeyboardMark.lastSeen(in:))
                keyboardSeen = Self.keyboardAppeared(mark: mark, markAtStart: markAtStart)
                try? await Task.sleep(for: .milliseconds(500))
            }
        }
        // The switch has had its time: without the keyboard seen with Full Access, a quiet way to Settings shows.
        .task(id: stage == .switchKeyboard && blocker == nil) {
            guard stage == .switchKeyboard, blocker == nil else { return }
            try? await Task.sleep(for: Self.fullAccessWait)
            if !Task.isCancelled { fullAccessHelp = true }
        }
        // The box takes the keyboard by itself: on arrival (step 3, Home's sheet), when it takes a waiting page's place, and
        // back in front (from Settings, where it lost the keyboard), with no tap. A focus set while a sheet still slides up
        // or the box is not on screen yet can be dropped, so it is set again twice, as long as the box still lacks it and
        // the keyboard has not shown: once it has, a box without it means the person put the keyboard away.
        .task(id: [wantsKeyboard, scenePhase == .active]) {
            keyboardShown = false
            guard wantsKeyboard else {
                typing = false
                return
            }
            guard scenePhase == .active else { return }
            typing = true
            for _ in 0..<2 {
                try? await Task.sleep(for: .milliseconds(350))
                guard !Task.isCancelled, !keyboardShown else { return }
                if !typing { typing = true }
            }
        }
        .onReceive(NotificationCenter.default.publisher(for: UIResponder.keyboardDidShowNotification)) { _ in
            keyboardShown = true
        }
        // A hardware keyboard leaves no room, or a thin bar's: iOS then shows no on-screen keyboard, ThumbFree's included.
        .onGeometryChange(for: Bool.self) { $0.safeAreaInsets.bottom > Self.keyboardRoom } action: { up in
            keyboardUp = up
            if up { keyboardHidden = false }
        }
        .task(id: wantsKeyboard && !keyboardUp && scenePhase == .active) {
            guard wantsKeyboard, !keyboardUp, scenePhase == .active else { return }
            try? await Task.sleep(for: Self.keyboardWait)
            if !Task.isCancelled { keyboardHidden = true }
        }
        .onChange(of: host.status.take) { _, take in if take == .recording { boxAtRecording = text } }
        // Not while the take records: VoiceOver's voice would go into it.
        .onChange(of: stage) { _, now in if now != .recording { AccessibilityNotification.Announcement(Self.spoken(now)).post() } }
    }

    // MARK: The rules

    /// The stage from what the screen has seen: the switch until the ThumbFree keyboard is up in the box, or how to show
    /// a keyboard while none comes up; then it worked
    /// once a take gave text since the screen came up and the box changed, to words, from what it held when the latest
    /// take began recording here (`boxAtRecording`: a take held back, one whose recording began elsewhere, or words typed
    /// by hand never count); else the live take's own stage, recording once the mic is on, then turning into text, or the
    /// mic still to tap, or how to show the keyboard again when it went (a hardware keyboard came) before a take. A take
    /// records before the mic is on while iOS asks for the microphone at the first take: the line stays on the mic behind
    /// iOS's prompt.
    static func stage(keyboardSeen: Bool, keyboardHidden: Bool = false, take: TakePhase, micOn: Bool, takesAtStart: Int,
                      takesNow: Int, box: String, boxAtRecording: String?) -> Stage {
        guard keyboardSeen else { return keyboardHidden ? .showKeyboard : .switchKeyboard }
        if takesNow > takesAtStart, let before = boxAtRecording, box != before, !box.allSatisfy(\.isWhitespace) { return .worked }
        switch take {
        case .idle: return keyboardHidden ? .showKeyboard : .tapMic
        case .recording: return micOn ? .recording : .tapMic
        case .stopping, .transcribing, .delivering: return .transcribing
        }
    }

    /// The ThumbFree keyboard came up since the screen did: its mark is there, and not the one there was then. Dates of
    /// the same file, so no clock is compared with another.
    static func keyboardAppeared(mark: Date?, markAtStart: Date?) -> Bool {
        mark != nil && mark != markAtStart
    }

    /// The take to cancel when the screen goes away: one that has not given its text yet (recording, or stopping and
    /// being transcribed). Its words would come with no box to type into, and count as a try that worked. The cancel
    /// keeps it in History with the audio it has (the keyboard's X button does the same); a take the keyboard is already
    /// typing is left to end. So is the dictate link's take (`coldTake`): the keyboard in another app started it while
    /// the try was still up, and its words go to that app.
    static func takeToCancel(_ status: HostStatus, coldTake: UUID?) -> UUID? {
        guard let take = status.takeID, take != coldTake, [.recording, .stopping, .transcribing].contains(status.take) else { return nil }
        return take
    }

    /// How long the switch may take before the quiet Full Access settings shows.
    static let fullAccessWait = Duration.seconds(8)

    /// How long the box may want the keyboard before the line says how to show one: more than iOS takes to bring it up.
    static let keyboardWait = Duration.seconds(2)

    /// The room at the page's bottom above which an on-screen keyboard is up: about 300 points for the iPhone's, 34 for
    /// the home indicator alone, and less than 100 with the thin bar some hardware keyboards leave.
    static let keyboardRoom: CGFloat = 150

    /// The line above the box.
    static func line(_ stage: Stage) -> String {
        switch stage {
        case .showKeyboard: String(localized: "Show your on-screen keyboard.")
        case .switchKeyboard: String(localized: "Hold the globe key at the bottom left of your keyboard, then choose ThumbFree.")
        case .tapMic: String(localized: "Tap the yellow mic.")
        case .recording: String(localized: "Speak, then tap the red stop button.")
        case .transcribing: String(localized: "Turning speech into text…")
        case .worked: String(localized: "Your words appeared.")
        }
    }

    /// The small lines under the line: how to show a keyboard that is not up; with the ThumbFree keyboard up, that iOS
    /// will ask for the microphone at this first take; once it worked, where to use it next; then speech loading again
    /// (iOS ended ThumbFree in Settings), which a take waits for.
    static func notes(_ stage: Stage, speechLoads: Bool, mic: AVAudioApplication.recordPermission) -> [String] {
        var notes: [String] = []
        if stage == .showKeyboard {
            notes.append(String(localized: "Tap the box. If you’re using a hardware keyboard, disconnect it and tap again."))
        }
        if stage == .tapMic, mic == .undetermined { notes.append(String(localized: "iOS will ask for microphone access.")) }
        if stage == .worked {
            notes.append(String(localized: "Next, use it in other apps: hold the globe key, then choose ThumbFree."))
        }
        if speechLoads { notes.append(String(localized: "Getting speech ready…")) }
        return notes
    }

    /// The line as VoiceOver reads it: its own gesture for the globe.
    static func spoken(_ stage: Stage) -> String {
        stage == .switchKeyboard
            ? String(localized: "On your keyboard, double-tap and hold the globe key at the bottom left, then choose ThumbFree.") : line(stage)
    }

    /// The model the try uses: the one in use.
    private var model: SpeechModel { models.active }

    private var blocker: SetupBlocker? {
        SetupBlocker.forTry(mic: AVAudioApplication.shared.recordPermission, keyboard: keyboard, phase: model.phase,
                            waiting: model.waiting, engine: host.status.engine)
    }

    /// The model is here and the engine loads it: again after iOS ended ThumbFree in Settings (seconds, its compiled
    /// model kept), or the first time. The box is up meanwhile, and a take waits for the engine.
    private var speechLoads: Bool {
        SetupBlocker.speech(phase: model.phase, waiting: model.waiting, engine: host.status.engine) == .loading
    }

    private var stage: Stage {
        Self.heldStage ?? Self.stage(keyboardSeen: keyboardSeen, keyboardHidden: keyboardHidden, take: host.status.take,
                                     micOn: host.status.micOn, takesAtStart: takesAtStart, takesNow: host.textTakes,
                                     box: text, boxAtRecording: boxAtRecording)
    }

    /// The box has the keyboard while the try goes on: not while something stops it, and not once a take worked and is
    /// over, so the keyboard has typed its words before it goes down. A held `showKeyboard` (Debug) keeps it down, as a
    /// hardware keyboard would.
    private var wantsKeyboard: Bool {
        blocker == nil && !(stage == .worked && host.status.take == .idle) && Self.heldStage != .showKeyboard
    }

    // MARK: Parts

    @ViewBuilder private func line(_ stage: Stage) -> some View {
        Group {
            if stage == .worked {
                Label {
                    Text(Self.line(stage).keepingLastWordsTogether)
                } icon: {
                    Image(systemName: "checkmark.circle.fill").foregroundStyle(Theme.success)
                }
            } else {
                Text(Self.line(stage).keepingLastWordsTogether)
            }
        }
        .font(.title3.weight(.semibold))
        .foregroundStyle(Theme.ink)
        .fixedSize(horizontal: false, vertical: true)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Self.spoken(stage))
        .accessibilityAddTraits(.isHeader)
        .accessibilityIdentifier("try.line")
        .accessibilityFocused($lineFocused)
    }

    /// The box the keyboard types into, the only real text box in the onboarding: a tap anywhere in it starts typing.
    /// The example is its placeholder; its edge thickens in the brand color while it has the keyboard.
    private var box: some View {
        TextField("Say: I’ll be there in ten minutes.", text: $text, axis: .vertical)
            .lineLimit(2...4)
            .keyboardType(Self.fieldType)
            .autocorrectionDisabled(Self.autocorrectionOff)
            .focused($typing)
            .accessibilityIdentifier("try.field")
            .padding(14)
            .frame(minHeight: 76, alignment: .topLeading)
            .background(Theme.surface, in: .rect(cornerRadius: 14, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: 14, style: .continuous)
                .strokeBorder(typing ? Theme.primary : Theme.line, lineWidth: typing ? 2 : 1))
            .contentShape(.rect)
            .onTapGesture { typing = true }
    }

    /// The page in the box's place while something stops the try: its title and line, how far the download is (the big
    /// number, then the model's line), and its one fix as the big button.
    @ViewBuilder private func blockerPage(_ blocker: SetupBlocker) -> some View {
        let words = blocker.words(.tryIt, model: model.languageName, size: model.totalBytes)
        OnboardingTitleBlock(title: words.title, support: words.detail, id: "try.line")
        if Self.showsModelLine(blocker) {
            ModelProgressLine(model: model, engine: host.status.engine).padding(.top, 16)
        }
        SpeechPanel(blocker: blocker).padding(.top, 20)
    }

    /// The model's line, with its bar, shows while the download's bytes come; in the other states the title says it.
    static func showsModelLine(_ blocker: SetupBlocker) -> Bool {
        if case .downloading = blocker { return true }
        return false
    }

    /// Before it worked, Not now (and, once the switch has had its time without Full Access seen, Open Settings beside
    /// it); then Done, the big button. With something in the way, its fix first, and Change language while the download
    /// runs or waits. All of them but the fixes leave.
    @ViewBuilder private func buttons(_ blocker: SetupBlocker?, stage: Stage) -> some View {
        if let blocker {
            let words = blocker.words(.tryIt, model: model.languageName, size: model.totalBytes)
            VStack(spacing: 10) {
                if let action = words.action { PrimaryActionButton(title: action.title, id: "try.fix") { run(action) } }
                HStack(spacing: 8) {
                    if blocker.offersLanguageChange {
                        QuietActionButton(title: "Change language", id: "try.changeLanguage") { changingLanguage = true }
                            .changesLanguage($changingLanguage, models: models) // iOS's sheet comes from the button
                    }
                    QuietActionButton(title: "Not now", id: "try.notNow", action: onDone)
                }
            }
        } else if stage == .worked {
            PrimaryActionButton(title: "Done", id: "try.done", action: onDone)
        } else {
            HStack(spacing: 8) {
                if fullAccessHelp, stage == .switchKeyboard { // no new mark in 8 s: an old one proves nothing about now
                    QuietActionButton(title: "Full Access settings", id: "try.fullAccess", action: SetupRows.openSettings)
                        .accessibilityHint("To turn on Allow Full Access.")
                }
                QuietActionButton(title: "Not now", id: "try.notNow", action: onDone)
            }
        }
    }

    private func run(_ action: SetupBlocker.Action) {
        switch action {
        case .openSettings: SetupRows.openSettings()
        case .download, .downloadAgain, .tryAgain: model.download()
        case .useMobileData: model.useMobileData()
        case .loadAgain: host.prepareEngine()
        case .tryKeyboard: break // the box is here
        }
    }

    // MARK: Test hooks

    /// Screenshots and UI tests (Debug builds): `-TFTryStage showKeyboard|switchKeyboard|tapMic|recording|transcribing|worked`
    /// holds the screen at that stage. Never so in a Release build.
    static var heldStage: Stage? {
        #if DEBUG
        UserDefaults.standard.string(forKey: "TFTryStage").flatMap(Stage.init(rawValue:))
        #else
        nil
        #endif
    }

    /// UI tests and the store and dump tools (Debug builds): `-TFOpenTry YES` opens this screen over Home at launch, for
    /// its text box. Never so in a Release build.
    static var opensAtLaunch: Bool {
        #if DEBUG
        UserDefaults.standard.bool(forKey: "TFOpenTry")
        #else
        false
        #endif
    }

    /// UI tests (Debug builds): `-TFFieldType <UIKeyboardType raw value>` gives the box that keyboard type, so the
    /// keyboard's layouts for email, web address and number fields can be checked in the app's own field. Otherwise text.
    static var fieldType: UIKeyboardType {
        #if DEBUG
        UIKeyboardType(rawValue: UserDefaults.standard.integer(forKey: "TFFieldType")) ?? .default
        #else
        .default
        #endif
    }

    /// UI tests (Debug builds): `-TFAutocorrect NO` turns the box's autocorrection off (`autocorrectionType` .no), as
    /// some apps' fields are, so the keyboard's suggestions and corrections can be checked there. Otherwise on.
    static var autocorrectionOff: Bool {
        #if DEBUG
        UserDefaults.standard.object(forKey: "TFAutocorrect") != nil && !UserDefaults.standard.bool(forKey: "TFAutocorrect")
        #else
        false
        #endif
    }
}

/// The big picture of a page where speech is not ready yet: the download's percentage, large, or the state's symbol (an
/// hourglass while the files are checked or the engine loads, a spinner there unless Reduce Motion is on). For the eye
/// only: the model's line and the title say it to VoiceOver.
private struct SpeechPanel: View {
    let blocker: SetupBlocker
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        VStack(spacing: 12) {
            switch blocker {
            case .downloading(let percent):
                Image(systemName: "waveform").font(.system(size: 30, weight: .medium)).foregroundStyle(Theme.primary)
                Text("\(percent)%").font(.system(size: 56, weight: .bold).monospacedDigit()).foregroundStyle(Theme.ink)
            case .checking, .loading:
                if reduceMotion {
                    Image(systemName: "hourglass").font(.system(size: 44)).foregroundStyle(Theme.primary)
                } else {
                    ProgressView().controlSize(.large).tint(Theme.primary)
                }
            default:
                Image(systemName: blocker.symbol).font(.system(size: 44))
                    .foregroundStyle(blocker.isProblem ? Theme.error : Theme.primary)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .accessibilityHidden(true)
    }
}
