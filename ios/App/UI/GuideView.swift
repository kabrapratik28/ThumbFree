import SwiftUI
import TFCore

/// How dictating in another app goes, on Home: iOS has no floating bubble, so this shows the keyboard's flow in a drawn
/// chat (no pictures of other companies' apps) in an EXAMPLE frame, one beat at a time with a caption, on `GuidePlayer`.
struct GuideView: View {
    enum Scene { case tapBox, switchKeyboard, tapMic, goingBack, swipeBack, talking, tapStop, typed }

    typealias Beat = GuideBeat<Scene>

    /// The chat app the drawing stands for, as the captions and ThumbFree's round-trip screen name it: Messages, which
    /// ThumbFree takes you back to in every build (`ReturnTargets`).
    static let app = "Messages"
    static let bundleID = "com.apple.MobileSMS"
    /// What the chat's text box ends up holding.
    static let words = "Running ten minutes late, save me a seat"

    /// The beats, 1.4 s each; the globe's takes two, since it holds the globe and then picks ThumbFree. ThumbFree opens on
    /// the first tap of each session, not only the first time. As in the app, it takes you back by itself only with
    /// automatic return on and only to an app this build may open (`ReturnTargets.mayOpen`); otherwise the guide shows
    /// the swipe back, so it never promises a return the app won't make.
    static func beats(autoReturn: Bool) -> [Beat] {
        let returns = autoReturn && ReturnTargets.target(forBundleID: bundleID)
            .map { ReturnTargets.mayOpen($0, debugBuild: ReturnTargets.isDebugBuild) } == true
        return [
            Beat(scene: .tapBox, caption: "Tap a text box in any app."),
            Beat(scene: .switchKeyboard, caption: "Hold the globe, then choose ThumbFree.", length: 2),
            Beat(scene: .tapMic, caption: "Tap the yellow mic."),
            returns
                ? Beat(scene: .goingBack, caption: "The first tap of each session opens ThumbFree. It returns to \(returnApps).")
                : Beat(scene: .swipeBack, caption: "The first tap of each session opens ThumbFree. Swipe back to continue."),
            Beat(scene: .talking, caption: "Speak."),
            Beat(scene: .tapStop, caption: "Tap the red stop button."),
            Beat(scene: .typed, caption: "Your words appear."),
        ]
    }

    /// The apps every build takes you back to by itself, as the return beat names them: "Messages, Notes and Signal".
    static var returnApps: String {
        let names = ReturnTargets.all.filter { ReturnTargets.mayOpen($0, debugBuild: false) }.map(\.displayName)
        guard let last = names.last, names.count > 1 else { return names.joined() }
        return names.dropLast().joined(separator: ", ") + " and " + last
    }

    /// UI tests (Debug builds): `-TFGuidePaused YES` keeps every guide on its beat, as Reduce Motion does. Never paused
    /// so in a Release build.
    static var pausedForTests: Bool {
        #if DEBUG
        UserDefaults.standard.bool(forKey: "TFGuidePaused")
        #else
        false
        #endif
    }

    /// Screenshots (Debug builds): `-TFGuideBeat <n>` puts a guide on its beat n (from 1) of `count` and keeps it there
    /// (`holdsBeat`), and holds the keyboard step's still picture there. Otherwise the first. Never so in a Release build.
    static func heldBeat(count: Int) -> Int {
        #if DEBUG
        let beat = UserDefaults.standard.integer(forKey: "TFGuideBeat")
        return (1...count).contains(beat) ? beat - 1 : 0
        #else
        0
        #endif
    }

    /// Screenshots (Debug builds): `-TFGuideBeat` holds the guide on its beat, with the look it has while it plays (no
    /// Back and Next) and its tap done. Never so in a Release build.
    static var holdsBeat: Bool {
        #if DEBUG
        UserDefaults.standard.object(forKey: "TFGuideBeat") != nil
        #else
        false
        #endif
    }

    @State private var beat = GuideView.heldBeat(count: 7)

    var body: some View {
        GuidePlayer(beats: Self.beats(autoReturn: AutoReturn.enabled), id: "guide", interval: 1.4, label: "EXAMPLE",
                    description: "A chat with the ThumbFree keyboard.", index: $beat) { scene, moving in
            WalkthroughScene(scene: scene, moving: moving)
        }
    }
}

/// A drawing shown as large as the space offered allows, up to its own size (or `maxHeight`) and keeping its shape: it
/// takes the largest box of the drawing's shape that fits, so a screen around it (`DiagramScreen`) is exactly as tall as
/// the drawing. In a page that must fit the screen it takes the height left over; where nothing limits the height (a
/// scroll view), its own size.
struct Shrinks<Drawing: View>: View {
    var maxHeight = CGFloat.infinity
    @ViewBuilder let drawing: Drawing
    /// The drawing's own size, measured once it is laid out.
    @State private var natural = CGSize.zero

    var body: some View {
        let measured = natural.width > 0 && natural.height > 0
        GeometryReader { space in
            let scale = measured ? min(1, space.size.width / natural.width, space.size.height / natural.height) : 1
            drawing
                .fixedSize()
                .onGeometryChange(for: CGSize.self) { $0.size } action: { natural = $0 }
                .scaleEffect(scale)
                .frame(width: space.size.width, height: space.size.height)
        }
        .frame(maxWidth: measured ? natural.width : nil, maxHeight: measured ? min(natural.height, maxHeight) : nil)
        .aspectRatio(measured ? natural.width / natural.height : nil, contentMode: .fit)
    }
}

/// One beat of a guide: what its drawing shows, and the caption under it.
struct GuideBeat<Scene: Equatable & Sendable>: Equatable, Sendable {
    let scene: Scene
    let caption: String
    /// How many beats' time it stays on screen: a drawing with more to show (the keyboard list) takes two.
    var length = 1.0
}

/// A guide's picture in its frame, its caption, and Back and Next: the walkthrough and "Put ThumbFree first" play on
/// it. It moves to the next beat every `interval` seconds, in a loop, with Back, Next and the count left out; the
/// picture takes no taps. With Reduce Motion, VoiceOver or Switch Control on (or, in Debug builds,
/// `-TFGuidePaused YES`) it never loops, and they show; the caption's accessibility action (Show Back and Next) stops the
/// loop too, for anyone else who steps through. VoiceOver reads the picture once and "Step 3 of 7." with the caption.
/// The drawing keeps the type size it was drawn at and shrinks to the height a page leaves it (each picture in its
/// `Shrinks`); the caption keeps to its lines (two, three above the default size), so a page never has to scroll.
struct GuidePlayer<Scene: Equatable & Sendable, Picture: View>: View {
    let beats: [GuideBeat<Scene>]
    /// Names the caption, Back and Next for UI tests: "guide" gives `guide.caption`, `guide.back` and `guide.next`.
    let id: String
    /// Seconds per beat; a drawing's own moves (a tap, a switch sliding) fit inside one.
    var interval = 2.8
    /// The frame's label and what VoiceOver reads for the picture.
    let label: String
    let description: String
    /// The beat on screen, kept by the guide's owner.
    @Binding var index: Int
    /// The drawing for a beat, and whether it may move: true only while the guide plays by itself.
    @ViewBuilder let picture: (Scene, Bool) -> Picture

    @State private var stepping = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.accessibilityVoiceOverEnabled) private var voiceOver
    @Environment(\.accessibilitySwitchControlEnabled) private var switchControl
    /// Two lines of caption's room (more with `captionLines`), so the controls under it hold still from beat to beat.
    @ScaledMetric(relativeTo: .subheadline) private var captionHeight: CGFloat = 40
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    /// The caption's lines: two, and three above the default text size, where the longest caption (the return beat with
    /// its three apps) would need type smaller than the scale floor to fit two on the narrowest iPhones.
    static func captionLines(_ size: DynamicTypeSize) -> Int { size > .large ? 3 : 2 }

    private var playing: Bool {
        Self.plays(reduceMotion: reduceMotion, voiceOver: voiceOver, switchControl: switchControl,
                   paused: GuideView.pausedForTests, stepping: stepping)
    }

    /// Plays by itself unless motion is reduced, VoiceOver or Switch Control is on (they step through, and reach the
    /// controls a loop hides), a UI test paused it, or someone stepped with Back or Next.
    static func plays(reduceMotion: Bool, voiceOver: Bool, switchControl: Bool, paused: Bool, stepping: Bool) -> Bool {
        !stepping && !reduceMotion && !voiceOver && !switchControl && !paused
    }

    /// The accessibility action that stops the loop and brings Back and Next, for Voice Control and the rest.
    static var showControls: String { "Show Back and Next" }

    var body: some View {
        let beat = beats[index]
        VStack(alignment: .leading, spacing: 12) {
            IllustrationFrame(label: label, description: description, id: "\(id).picture") {
                picture(beat.scene, playing && !GuideView.holdsBeat).dynamicTypeSize(.large)
            }
            Text(beat.caption.keepingLastWordsTogether)
                .font(.subheadline)
                .foregroundStyle(Theme.ink)
                .lineLimit(Self.captionLines(dynamicTypeSize))
                .minimumScaleFactor(0.8) // the backstop: smaller type rather than words cut off
                .frame(maxWidth: .infinity, minHeight: captionHeight * CGFloat(Self.captionLines(dynamicTypeSize)) / 2, alignment: .topLeading)
                .accessibilityLabel("Step \(index + 1) of \(beats.count). \(beat.caption)")
                .accessibilityIdentifier("\(id).caption")
                .accessibilityActions { if playing { Button(Self.showControls) { stepping = true } } }
            let controls = ViewThatFits(in: .horizontal) { // large text: the count goes, then Back and Next go one under the other
                HStack { back; Spacer(); count; Spacer(); next }
                HStack { back; Spacer(); next }
                VStack(alignment: .leading, spacing: 16) { back; next }
            }
            .font(.subheadline.weight(.semibold))
            .foregroundStyle(Theme.primary)
            // Only when it does not play: their room goes to the picture, which on a small iPhone needs it. Those who
            // step through (Reduce Motion, VoiceOver, Switch Control) have them from the start.
            if !playing { controls }
        }
        .task(id: playing) {
            while playing, !GuideView.holdsBeat, !Task.isCancelled {
                try? await Task.sleep(for: .seconds(interval * beats[index].length))
                guard playing, !Task.isCancelled else { return }
                withAnimation(.easeInOut(duration: min(0.3, interval / 5))) { index = (index + 1) % beats.count }
            }
        }
    }

    /// Back and Next keep their small look in a tap target at least 44 points tall (`tapTarget`).
    private var back: some View {
        Button { step(-1) } label: { tapTarget(Label("Back", systemImage: "chevron.backward")) }
            .disabled(index == 0)
            // Looks disabled too: a foreground style (this row's) keeps SwiftUI from dimming it.
            .opacity(index == 0 ? 0.35 : 1)
            .accessibilityIdentifier("\(id).back")
    }

    private var count: some View {
        Text("\(index + 1) of \(beats.count)").font(.footnote).foregroundStyle(Theme.inkSoft).accessibilityHidden(true)
    }

    private var next: some View {
        Button { step(1) } label: { tapTarget(Label("Next", systemImage: "chevron.forward").labelStyle(.titleAndIcon)) }
            .disabled(index == beats.count - 1)
            .opacity(index == beats.count - 1 ? 0.35 : 1)
            .accessibilityIdentifier("\(id).next")
    }

    private func tapTarget(_ label: some View) -> some View {
        label.frame(minWidth: 44, minHeight: 44).contentShape(.rect)
    }

    private func step(_ delta: Int) {
        stepping = true
        withAnimation(reduceMotion ? nil : .easeInOut(duration: 0.3)) {
            index = min(max(index + delta, 0), beats.count - 1)
        }
        // Focus stays on the button pressed: without this, VoiceOver never hears the new step unless the user swipes
        // back to the caption by hand.
        AccessibilityNotification.Announcement("Step \(index + 1) of \(beats.count). \(beats[index].caption)").post()
    }
}

/// The walkthrough's drawing for one beat, on a cropped screen: a chat's card over the keyboard (Apple's until the globe
/// switches to ThumbFree's), with room under the reply box for the mic's Tap pill, the tap cue on what each beat taps,
/// and ThumbFree's round-trip screen while the first tap opens it. Before the box is tapped there is no keyboard: the
/// card holds Sam, his message and the box, and the keyboard comes up with the next beat, the box with it. Drawn at
/// 356 x 274 points and shown at most 254 tall, so its frame is at most 320: on an iPhone 17 Pro it fills Home's drawing
/// area (330 wide), its keyboard 300 points wide, as wide as the welcome's.
struct WalkthroughScene: View {
    let scene: GuideView.Scene
    /// False unless the walkthrough plays by itself: still pictures, no taps moving (Reduce Motion, VoiceOver, paused,
    /// or stepping with Back and Next).
    let moving: Bool

    static let size = CGSize(width: 356, height: 274)
    static let maxHeight: CGFloat = 254
    /// The keyboard's and the card's width: 300 points once shown 254 tall.
    static let contentWidth: CGFloat = 324
    /// The globe's beat: the hold and the list in its first 1.3 s, ThumbFree picked in its second, then ThumbFree's
    /// keyboard comes up.
    static let globeLength = 2.8
    static let thumbFreeUp = 2.3

    var body: some View {
        let length = scene == .switchKeyboard ? Self.globeLength : 1.4
        DiagramScreen {
            Shrinks(maxHeight: Self.maxHeight) {
                if moving, scene != .talking, scene != .typed, scene != .goingBack {
                    KeyframeAnimator(initialValue: 0.0, repeating: true) { drawing(at: $0) } keyframes: { _ in
                        LinearKeyframe(length, duration: length)
                        LinearKeyframe(length, duration: 1.0) // past the beat's end, so it never starts over first
                    }
                } else {
                    drawing(at: Self.stillTime(scene))
                }
            }
        }
    }

    private var inApp: Bool { scene == .goingBack || scene == .swipeBack }

    /// The moment a still picture shows: the tap done, with its ring; in the globe's beat ThumbFree picked in the list;
    /// for the stop, the red key still to tap, since the moment after it is another beat's.
    static func stillTime(_ scene: GuideView.Scene) -> Double {
        switch scene {
        case .switchKeyboard: 2.2
        case .tapStop: TapTimeline.rippleStart
        default: TapTimeline.length
        }
    }

    private func drawing(at time: Double) -> some View {
        let keyboardUp = scene != .tapBox
        return ZStack(alignment: .top) {
            ChatDiagram {
                VStack(alignment: .leading, spacing: 0) {
                    ContactDiagram()
                    Spacer(minLength: 0)
                    IncomingDiagram()
                    // Room for the box's Tap pill under the message.
                    DiagramTextField(cursor: TapTimeline.change(at: time) > 0)
                        .overlay {
                            TapCue(outline: RoundedRectangle(cornerRadius: 14, style: .continuous), ripple: TapTimeline.ripple(at: time))
                        }
                        .padding(.top, 36)
                }
            }
            .padding(.horizontal, (Self.size.width - Self.contentWidth) / 2)
            .padding(.vertical, 16)
            .opacity(keyboardUp ? 0 : 1)
            VStack(spacing: 16) {
                // The card runs off the top of the screen, the box at its foot; the pill over the mic sits under the box.
                ChatDiagram {
                    DiagramTextField(text: scene == .typed ? GuideView.words : nil).frame(maxHeight: .infinity, alignment: .bottom)
                }
                .frame(height: 86)
                keyboard(at: time)
            }
            .frame(width: Self.contentWidth, height: Self.size.height, alignment: .bottom)
            .offset(y: keyboardUp ? 0 : Self.size.height + 40) // all of it below the screen, the card's top too
            if inApp { sessionScreen(at: time).transition(.move(edge: .trailing)) }
        }
        .frame(width: Self.size.width, height: Self.size.height, alignment: .top)
    }

    /// The keyboard of the beat: Apple's while the globe is held and the list is up, then ThumbFree's, its mic following
    /// the take.
    private func keyboard(at time: Double) -> some View {
        let first = min(time, TapTimeline.length), second = max(time - TapTimeline.length, 0)
        let globe = scene == .switchKeyboard && time < Self.thumbFreeUp
        let mic: MicDiagram.Mode = switch scene {
        case .goingBack, .swipeBack, .talking: .recording
        case .tapStop: TapTimeline.change(at: time) > 0 ? .busy : .recording
        default: .ready
        }
        let status = switch scene {
        case .talking, .goingBack, .swipeBack: KeyState.listening.text
        case .tapStop: TapTimeline.change(at: time) > 0 ? KeyState.transcribing.text : KeyState.listening.text
        case .typed: KeyState.ready.text
        default: KeyState.startDictation.text
        }
        let cue: KeyboardDiagram.Cue? = globe ? .globe("Hold") : scene == .tapMic || scene == .tapStop ? .mic : nil
        return KeyboardDiagram(width: Self.contentWidth, kind: globe ? .apple : .thumbFree, mic: mic, status: status, cue: cue,
                               time: globe ? first : time)
            .overlay(alignment: .topLeading) {
                if globe {
                    let up = min(max((first - TapTimeline.rippleEnd) / 0.25, 0), 1)
                    KeyboardPickerDiagram(lit: 2 * TapTimeline.change(at: second), picked: second >= TapTimeline.changeEnd)
                        .opacity(up)
                        .position(x: 85, y: 98) // above the globe, over the letters, as iOS shows it
                }
            }
    }

    /// ThumbFree's round-trip screen in its own words (`SessionScreen`'s), over the drawing: "Listening" and "Taking you
    /// back to Messages…" while it takes you back; the swipe-back line when it can't, with a swipe cue along the bottom.
    private func sessionScreen(at time: Double) -> some View {
        let trip = ReturnTrip(appName: GuideView.app, phase: scene == .goingBack ? .leaving : .swipeBack, firstReturn: false)
        let listening = HostStatus(micOn: true, take: .recording)
        return ZStack(alignment: .bottom) {
            Theme.paper // ThumbFree's own page in front, filling the screen: no line of its own
            VStack(spacing: 10) {
                MicDiagram(mode: .recording).scaleEffect(1.6).frame(width: 52, height: 52)
                Text(SessionScreen.primaryLine(status: listening, returnTrip: trip)).font(.system(size: 15, weight: .semibold))
                if let line = SessionScreen.subLine(status: listening, returnTrip: trip, way: .swipe) {
                    Text(line).font(.system(size: 13)).foregroundStyle(Theme.inkSoft)
                }
            }
            .multilineTextAlignment(.center)
            .foregroundStyle(Theme.ink)
            .padding(.horizontal, 24)
            .frame(maxHeight: .infinity)
            if scene == .swipeBack {
                let x = moving ? min(max((time - 0.15) / 0.8, 0), 1) : 1
                TapCue(outline: Capsule(), gesture: "Swipe").frame(width: 44, height: 26).offset(x: -90 + 180 * x, y: -14)
            }
        }
    }
}
