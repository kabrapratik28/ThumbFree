import AVKit
import SwiftUI
import TFCore
import UIKit

/// The first-run screens: three steps under a bar of three parts, with little to read on each.
///
/// Step 1, get ready, is one screen: what ThumbFree is, with a framed example of the keyboard typing what is said, and
/// "Which language do you speak?", the phone's guess first and filled (`SpeechModels.startsMultilingual`). The tap chooses
/// that model and starts its download (Wi-Fi only unless you allowed mobile data), and the answers fade into the wait
/// under the same example, which holds still on its typed reply: "Getting speech ready" with the big percentage while it
/// downloads, then "Almost ready" while the engine loads the model into memory, so the first try works at once. Nothing to
/// tap but Change language while the download runs. Speech ready, a green check for 400 ms, then step 2 comes by itself.
/// What stops the download or the load shows there in its few words with its one fix (`SetupBlocker`). There is no
/// microphone page: iOS asks at the first take in the try.
/// Step 2 adds the keyboard: the taps in Settings as one large picture in five beats, a looping video that floats over
/// Settings once you go there (`FloatingGuide`; with Reduce Motion or VoiceOver a short list), under Open Settings, the
/// big button. Back from a trip away (Settings, by any route) with the keyboard in iOS's list, the try comes at once; with
/// it still off, the step says where else to look. iOS may list the keyboard before any trip (after a reinstall), so the
/// list alone never moves the step on, but a keyboard already seen with Full Access needs no step 2. Step 3 is the real
/// try (`TryItView`), its box selected and the real keyboard up as it opens, with no Back.
///
/// The step reached is saved, so a flow left halfway comes back there (iOS restarts the app when you change a permission
/// in Settings). Done or Not now saves that the flow is finished, and it never opens by itself again.
struct WelcomeView: View {
    /// The steps, in the order they come. Their saved numbers stay those of earlier versions (0, 1, 2, and 3, the
    /// microphone's page, which the wait took over), so an update in the middle of the flow comes back at its step, and
    /// one saved on the microphone's page waits for speech, or goes on to step 2 once speech is ready.
    enum Step: Int, CaseIterable {
        case welcome = 0, wait = 3, keyboard = 1, tryIt = 2

        /// The step's part of the bar: the wait is part of getting ready.
        var part: Int {
            switch self {
            case .welcome, .wait: 0
            case .keyboard: 1
            case .tryIt: 2
            }
        }

        /// The step's name, as the bar's VoiceOver label says it.
        var name: String { ["Get ready", "Add the ThumbFree keyboard", "Try ThumbFree"][part] }
    }

    /// What the keyboard step shows: its guide, or the guide again with where else to look once a trip left the keyboard
    /// off; done, on to the try, once a trip added it, or whenever the keyboard has come up with Full Access.
    enum KeyboardPage: Equatable { case guide, stillOff, done }

    /// What page 2's page follows: whether it is on screen, the keyboard, the trip, and whether the app is in front.
    private struct PageFacts: Equatable {
        let onPage: Bool
        let status: KeyboardStatus
        let wentAway: Bool
        let active: Bool
    }

    static let stepKey = "TFWelcomeStep"
    static let doneKey = "TFWelcomeDone"
    /// The app left the front during the keyboard step (a trip to Settings, by any route), saved with the step.
    static let tripKey = "TFWelcomeTrip"
    /// How long the wait shows "Speech ready" before step 2 comes. VoiceOver skips it: step 2's screen change says enough.
    static let readyBeat = 0.4

    let host: SessionHost
    let models: SpeechModels
    let onDone: () -> Void
    @AppStorage(WelcomeView.stepKey) private var step = Step.welcome
    @State private var keyboard = KeyboardStatus.current()
    /// Back from a trip away during the keyboard step. Saved, since iOS may restart the app meanwhile.
    @AppStorage(WelcomeView.tripKey) private var wentAway = false
    /// The keyboard step's video, which Open Settings floats over Settings.
    @State private var guide = FloatingGuide()
    /// What page 2 shows now. It changes only with the app in front (`keyboardPage(from:status:wentAway:active:)`).
    @State private var page = KeyboardPage.guide
    /// Change language was tapped on the wait: iOS's own sheet asks which.
    @State private var changingLanguage = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.accessibilityVoiceOverEnabled) private var voiceOver
    @Environment(\.colorScheme) private var scheme
    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    /// The text size while the app was last in front, which the pages keep while it is away (`typeSize`).
    @State private var heldTypeSize: DynamicTypeSize?

    var body: some View {
        let speech = self.speech
        Group {
            if step == .tryIt {
                TryItView(host: host, models: models, onDone: finish, inWelcome: true)
            } else {
                page(speech).dynamicTypeSize(typeSize)
            }
        }
        .background { Theme.paper.ignoresSafeArea() } // under the try's keyboard too
        .foregroundStyle(Theme.ink)
        .tint(Theme.primary)
        .followsKeyboard($keyboard)
        .onChange(of: step) { UIAccessibility.post(notification: .screenChanged, argument: nil) }
        // The keyboard step's video is made (or found from before) during step 1, so an Open Settings right after the
        // step comes up can float it at once.
        .onChange(of: step, initial: true) { _, now in
            let look = FloatingGuide.look(reduceMotion: reduceMotion, voiceOver: voiceOver, paused: GuideView.pausedForTests)
            if Self.preparesGuide(now, look: look) { Task { [scheme] in _ = try? await FloatingGuide.video(scheme) } }
        }
        .onChange(of: scenePhase) { _, phase in
            if phase == .background, step == .keyboard { wentAway = true }
            guide.scene(phase) // back in the app by any route, the floating guide closes
        }
        // Off the keyboard step (Back, the try) or out of the welcome (Not now), an Open Settings still on its way is
        // dropped, so Settings never opens over another page.
        .onChange(of: step) { old, _ in if old == .keyboard { guide.land() } }
        .onDisappear { guide.land() }
        .onChange(of: scenePhase == .active ? dynamicTypeSize : nil, initial: true) { _, now in if let now { heldTypeSize = now } }
        // The wait gets the engine ready once the model is here, and again back in front: a download that ended while
        // ThumbFree was away, or iOS ended ThumbFree meanwhile.
        .onChange(of: step == .wait && model.phase == .ready && scenePhase == .active, initial: true) { _, due in
            if due { host.prepareEngine() }
        }
        // Speech ready on the wait: "Speech ready" for a moment, then step 2 comes by itself, or the try when the keyboard
        // needs no step 2. Only in front, so someone who left during the download sees it too, and not while Change
        // language's sheet is up, whose choice would then come too late.
        .task(id: step == .wait && speech == nil && scenePhase == .active && !changingLanguage) {
            guard step == .wait, speech == nil, scenePhase == .active, !changingLanguage else { return }
            try? await Task.sleep(for: .seconds(voiceOver ? 0 : Self.readyBeat))
            guard !Task.isCancelled else { return }
            step = Self.afterWait(keyboard, wentAway: wentAway)
        }
        // Page 2 follows the keyboard and the trip only with the app in front, a relaunch included. While the person is in
        // Settings it keeps exactly what it showed, guide included: a page that changed then would take the guide away and
        // close the window floating over Settings, as when iOS lists the keyboard before any trip (after a reinstall) and
        // the trip flag turned the page the moment the app left. Back in front: the try at once, or the keyboard still off.
        .onChange(of: PageFacts(onPage: step == .keyboard, status: keyboard, wentAway: wentAway, active: scenePhase == .active),
                  initial: true) { _, facts in
            guard facts.onPage else { return }
            page = Self.keyboardPage(from: page, status: facts.status, wentAway: facts.wentAway, active: facts.active)
            if page == .done { step = .tryIt }
        }
    }

    /// The model the welcome downloads and gets ready: the one in use, which the language choice sets.
    private var model: SpeechModel { models.active }

    /// The pages' text size: the live one in front, and while the app is away the one it left with, so a size changed in
    /// Settings cannot make a `ViewThatFits` or the page's scrolling choose another branch and take the floating guide's
    /// view away (`FloatingGuide.shown`).
    private var typeSize: DynamicTypeSize {
        FloatingGuide.shown(live: dynamicTypeSize, held: heldTypeSize, active: scenePhase == .active)
    }

    /// What is left before speech works with the model in use: the download in its states, then the engine's load. Nil
    /// once loaded.
    private var speech: SetupBlocker? {
        SetupBlocker.speech(phase: model.phase, waiting: model.waiting, engine: host.status.engine)
    }

    /// The keyboard step's video is made on step 1, before Open Settings can need it, unless the step will show its list
    /// or a still picture instead.
    static func preparesGuide(_ step: Step, look: FloatingGuide.Look) -> Bool {
        (step == .welcome || step == .wait) && look == .video
    }

    /// Page 2's page, decided only with the app in front: while it is away the page stays as it was. In front: done (on
    /// to the try) once the keyboard has come up with Full Access, or once a trip left it in iOS's list; else the guide
    /// until a trip, then the keyboard still off.
    static func keyboardPage(from current: KeyboardPage, status: KeyboardStatus, wentAway: Bool, active: Bool) -> KeyboardPage {
        guard active else { return current }
        if status == .ready || wentAway && status == .added { return .done }
        return wentAway ? .stillOff : .guide
    }

    /// The step to come back at, from the one saved: a flow saved past step 1 (an earlier version allowed it during the
    /// download) whose speech is not downloaded and loaded once goes back to the wait, where speech gets ready first.
    /// The old microphone page's number is the wait already.
    static func restoredStep(_ saved: Step, downloaded: Bool, loadedBefore: Bool) -> Step {
        (saved == .keyboard || saved == .tryIt) && !(downloaded && loadedBefore) ? .wait : saved
    }

    /// At launch, before the welcome shows: the saved step, through `restoredStep`, for the model in use.
    static func restoreStep(model: SpeechModel, defaults: UserDefaults = .standard) {
        guard let saved = Step(rawValue: defaults.integer(forKey: stepKey)) else { return }
        let restored = restoredStep(saved, downloaded: model.phase == .ready, loadedBefore: model.loadedBefore)
        if restored != saved { defaults.set(restored.rawValue, forKey: stepKey) }
    }

    /// Where the wait goes once speech is ready (the app is in front then): the try when page 2 has nothing left to do,
    /// else page 2.
    static func afterWait(_ status: KeyboardStatus, wentAway: Bool) -> Step {
        keyboardPage(from: .guide, status: status, wentAway: wentAway, active: true) == .done ? .tryIt : .keyboard
    }

    /// The language choices in order: the phone's guess first (the multilingual model when one of the iPhone's languages
    /// is one of its own other than English), so it is the filled one.
    static func languageOrder(multilingualFirst: Bool) -> [Bool] { multilingualFirst ? [true, false] : [false, true] }

    // MARK: Pages

    private func page(_ speech: SetupBlocker?) -> some View {
        VStack(spacing: 0) {
            OnboardingHeader(step: step.part, name: step.name, onBack: step == .keyboard ? {
                guide.land() // at once: an Open Settings still on its way could open Settings before the change is seen
                step = .welcome
            } : nil)
            let content = VStack(alignment: .leading, spacing: 0) { pageContent(speech) }
                .padding(.horizontal, 24)
                .padding(.top, 20)
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
            // Nothing scrolls at the standard text sizes: the pictures shrink to the height left. Only the accessibility
            // sizes scroll, where clipped words would be worse.
            if typeSize.isAccessibilitySize {
                ScrollView { content }
            } else {
                content
            }
            // Step 1's answers are its content, right under the example; the wait's fix and Change language, and the
            // keyboard step's buttons, sit at the bottom.
            if step == .keyboard || step == .wait && Self.hasButtons(speech) {
                Group {
                    if step == .keyboard { keyboardButtons } else { waitButtons(speech) }
                }
                .padding(.horizontal, 24)
                .padding(.top, 12)
                .padding(.bottom, 8)
            }
        }
    }

    @ViewBuilder private func pageContent(_ speech: SetupBlocker?) -> some View {
        switch step {
        case .welcome, .wait, .tryIt:
            OnboardingTitleBlock(title: String(localized: "Your voice becomes text in any app."),
                                 support: String(localized: "Private. Works offline after setup."))
            WelcomeExample(frozen: step == .wait)
            Group {
                if step == .wait {
                    waitStatus(speech).padding(.top, 20).transition(.opacity)
                } else {
                    languages.padding(.top, 20).padding(.bottom, 8).transition(.opacity)
                }
            }
            // The answers fade into the wait, in place: the page neither slides nor moves on.
            .animation(reduceMotion ? nil : .easeInOut(duration: 0.18), value: step == .wait)
        case .keyboard:
            // One guide under either title, so the video plays on, not made again, when a trip leaves the keyboard off.
            if page == .stillOff {
                OnboardingTitleBlock(title: String(localized: "The keyboard is still off"),
                                     support: String(localized: "In Settings, open General, Keyboard, Keyboards, then add ThumbFree."))
            } else {
                OnboardingTitleBlock(
                    title: String(localized: "Add the ThumbFree keyboard"),
                    support: String(localized: "Full Access lets ThumbFree send your words to its keyboard. Your voice stays on this iPhone."))
            }
            SetupGuideView(guide: guide).padding(.top, 16)
        }
    }

    /// "Which language do you speak?" and its two answers, the phone's guess first and filled, one height whatever their
    /// details' lengths.
    private var languages: some View {
        let multilingualFirst = SpeechModels.startsMultilingual(Locale.preferredLanguages)
        let details = [models.english, models.multilingual].map(\.languageChoice)
        return VStack(alignment: .leading, spacing: 10) {
            let question = String(localized: "Which language do you speak?")
            Text(question.keepingLastWordsTogether).font(.headline).accessibilityLabel(question).accessibilityAddTraits(.isHeader)
            ForEach(Self.languageOrder(multilingualFirst: multilingualFirst), id: \.self) { multilingual in
                let choice = multilingual ? models.multilingual : models.english
                LanguageChoiceButton(title: choice.languageName, detail: choice.languageChoice, all: details,
                                     recommended: multilingual == multilingualFirst,
                                     id: multilingual ? "welcome.otherLanguages" : "welcome.english") { choose(choice) }
            }
            if models.wifiOnly {
                Text("Downloads on Wi-Fi.").font(.footnote).foregroundStyle(Theme.inkSoft).frame(maxWidth: .infinity)
            }
        }
        .fixedSize(horizontal: false, vertical: true) // the answers keep their height: the example above gives way first
    }

    /// The wait, in the answers' place: what speech waits for, the big line (a glyph and the percentage, or the state's
    /// symbol), its one line, and while the bytes come the model's line and bar. Speech ready, "Speech ready" with a green
    /// check and 100% for a moment.
    private func waitStatus(_ speech: SetupBlocker?) -> some View {
        let words = speech?.words(.welcome, model: model.languageName, size: model.totalBytes)
        let title = words?.title ?? String(localized: "Speech ready")
        return VStack(alignment: .leading, spacing: 0) {
            Text(title.keepingLastWordsTogether)
                .font(.headline)
                .accessibilityLabel(title)
                .accessibilityAddTraits(.isHeader)
                .accessibilityIdentifier("welcome.wait")
            WaitFigure(speech: speech).padding(.top, 4)
            if let detail = words?.detail {
                Text(detail).font(.subheadline).foregroundStyle(Theme.inkSoft).padding(.top, 4)
            }
            if case .downloading(let percent)? = speech {
                Text("\(model.languageName) · \(percent)%")
                    .font(.footnote.weight(.semibold).monospacedDigit())
                    .foregroundStyle(Theme.inkSoft)
                    .padding(.top, 16)
                    .accessibilityIdentifier("welcome.model")
                    .announcesProgress(of: model)
            }
            if let fraction = Self.progress(speech) {
                ProgressTrack(fraction: fraction).padding(.top, fraction < 1 ? 8 : 16).accessibilityHidden(true)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .fixedSize(horizontal: false, vertical: true) // its words wrap, never cut: the example above gives way first
    }

    /// The wait's bar, 0 to 1: how far the download is while the bytes come, full while the files are checked; none once
    /// the engine loads, nor while something stops the download.
    static func progress(_ speech: SetupBlocker?) -> Double? {
        switch speech {
        case .downloading(let percent)?: Double(percent) / 100
        case .checking?: 1
        default: nil
        }
    }

    /// The wait has buttons: its one fix, or Change language while the download runs, waits or broke off.
    static func hasButtons(_ speech: SetupBlocker?) -> Bool {
        speech?.words(.welcome, model: "", size: 0).action != nil || speech?.offersLanguageChange == true
    }

    /// The wait's one fix, the big button, and Change language, quiet, whose sheet comes from it.
    private func waitButtons(_ speech: SetupBlocker?) -> some View {
        let words = speech?.words(.welcome, model: model.languageName, size: model.totalBytes)
        return VStack(spacing: 10) {
            if let action = words?.action { PrimaryActionButton(title: action.title, id: "welcome.fix") { fix(action) } }
            if speech?.offersLanguageChange == true {
                QuietActionButton(title: "Change language", id: "welcome.changeLanguage") { changingLanguage = true }
                    .changesLanguage($changingLanguage, models: models)
            }
        }
    }

    private var keyboardButtons: some View {
        VStack(spacing: 10) {
            PrimaryActionButton(title: page == .stillOff ? "Open Settings again" : "Open Settings", action: openSettings)
            QuietActionButton(title: "Not now", id: "welcome.notNow", action: finish)
        }
    }

    // MARK: Actions

    /// A language: its model becomes the one in use, any download of the one before stops, and its own download starts
    /// unless it is here (`SpeechModels.chooseLanguage`); the answers turn into the wait, which waits for that model.
    private func choose(_ chosen: SpeechModel) {
        models.chooseLanguage(chosen)
        step = .wait
    }

    /// The wait's one fix: the download again (Download, Try again), on mobile data, or the engine's load again.
    private func fix(_ action: SetupBlocker.Action) {
        switch action {
        case .download, .downloadAgain, .tryAgain: model.download()
        case .useMobileData: model.useMobileData()
        case .loadAgain: host.prepareEngine()
        case .openSettings, .tryKeyboard: break // never speech's
        }
    }

    /// Open Settings on the keyboard step: where the guide floats, it floats first when it can, then Settings opens; with
    /// the list, the still frame or no Picture in Picture, Settings opens at once, since no window can come.
    private func openSettings() {
        let floats = FloatingGuide.floats(reduceMotion: reduceMotion, voiceOver: voiceOver, paused: GuideView.pausedForTests,
                                          supported: AVPictureInPictureController.isPictureInPictureSupported())
        if floats { guide.float(host.status, then: SetupRows.openSettings) } else { SetupRows.openSettings() }
    }

    private func finish() {
        guide.land() // at once, as Back does: no Open Settings still on its way opens Settings over Home
        UserDefaults.standard.set(true, forKey: Self.doneKey)
        onDone()
    }
}

/// The wait's big line, for the eye (the heading and the model's line say it to VoiceOver): a waveform with how far the
/// download is; a spinner, an hourglass under Reduce Motion, alone while the files are checked and with 100% while the
/// engine loads; a green check with 100% once speech is ready; else the state's symbol, red for a fault (no space, the
/// check or the load failed) and navy for a wait or a pause, which go on.
private struct WaitFigure: View {
    let speech: SetupBlocker?
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        HStack(spacing: 12) {
            switch speech {
            case .downloading(let percent)?:
                glyph("waveform", Theme.primary)
                number("\(percent)%")
            case .checking?:
                spinner
            case .loading?:
                spinner
                number("100%")
            case nil:
                glyph("checkmark.circle.fill", Theme.success)
                number("100%")
            case let other?:
                Image(systemName: other.symbol)
                    .font(.system(size: 44))
                    .foregroundStyle(other.isProblem && other != .downloadPaused ? Theme.error : Theme.primary)
            }
        }
        .frame(minHeight: 68, alignment: .leading) // the number's height: the page does not jump between states
        .accessibilityHidden(true)
    }

    @ViewBuilder private var spinner: some View {
        if reduceMotion { glyph("hourglass", Theme.primary) } else { ProgressView().controlSize(.large).tint(Theme.primary) }
    }

    private func glyph(_ name: String, _ color: Color) -> some View {
        Image(systemName: name).font(.system(size: 34, weight: .semibold)).foregroundStyle(color)
    }

    private func number(_ text: String) -> some View {
        Text(text).font(.system(size: 56, weight: .bold).monospacedDigit()).foregroundStyle(Theme.ink)
    }
}

/// Step 1's EXAMPLE: Sam's message and an empty reply over the ThumbFree keyboard; its mic turns to recording; the words
/// appear, 1.3 s a beat, on a loop. On the wait (`frozen`), and with Reduce Motion, VoiceOver and `-TFGuidePaused`, it
/// holds on the typed words: a picture, while the real status is under it. It shrinks to the height the page leaves it.
private struct WelcomeExample: View {
    let frozen: Bool
    @State private var beat = WelcomeExampleScene.Beat.empty
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.accessibilityVoiceOverEnabled) private var voiceOver

    var body: some View {
        let still = frozen || reduceMotion || voiceOver || GuideView.pausedForTests
        // It shrinks to the room left and goes, leaving no gap, when less than 128 points is left (large text on a small
        // iPhone): the words, the language buttons and the wait come first.
        ViewThatFits(in: .vertical) {
            IllustrationFrame(label: "EXAMPLE",
                              description: "A chat with the ThumbFree keyboard: you tap its yellow mic, speak, and your words appear in the reply.",
                              id: "welcome.example") {
                DiagramScreen { Shrinks { WelcomeExampleScene(beat: still ? .typed : beat).dynamicTypeSize(.large) } }
                    .frame(minHeight: 62, idealHeight: 62) // with the label and insets, a frame from 128 points, then grows
            }
            .padding(.top, 16)
            Color.clear.frame(height: 0)
        }
        .task(id: still) {
            while !still, !Task.isCancelled {
                try? await Task.sleep(for: .seconds(TapTimeline.length))
                guard !Task.isCancelled else { return }
                withAnimation(.easeInOut(duration: 0.25)) {
                    beat = WelcomeExampleScene.Beat(rawValue: (beat.rawValue + 1) % 3) ?? .empty
                }
            }
        }
    }
}

/// The example's drawing, 322 points wide (the frame's drawing area on an iPhone 17 Pro, so it shows there at its own
/// size): the chat's card with Sam's message and the reply box, and under it the ThumbFree keyboard, as wide as the card,
/// its bar and first row of keys, cut by the screen's bottom between two rows; its bar follows the take.
private struct WelcomeExampleScene: View {
    enum Beat: Int { case empty, recording, typed }

    static let size = CGSize(width: 322, height: 204)

    let beat: Beat

    var body: some View {
        VStack(spacing: 8) {
            ChatDiagram {
                VStack(alignment: .leading, spacing: 8) {
                    IncomingDiagram()
                    DiagramTextField(text: beat == .typed ? GuideView.words : nil)
                }
            }
            KeyboardDiagram(kind: .thumbFree, mic: beat == .recording ? .recording : .ready,
                            status: beat == .recording ? KeyState.listening.text
                                : beat == .typed ? KeyState.ready.text : KeyState.startDictation.text)
        }
        .frame(width: 300)
        .padding(.top, 11)
        .frame(width: Self.size.width, height: Self.size.height, alignment: .top)
    }
}
