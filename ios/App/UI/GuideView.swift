import SwiftUI
import TFCore

/// How dictating in another app goes: iOS has no floating bubble, so this shows the keyboard's flow on the lower half of
/// a phone drawn in SwiftUI (no pictures of other companies' apps), in a chat, one beat at a time with a caption, on
/// `GuidePlayer`. The welcome flow's last step and the Try tab's "How to use it in other apps" both play it.
struct GuideView: View {
    enum Scene { case switchKeyboard, tapMic, goingBack, swipeBack, talking, tapStop, typed }

    typealias Beat = GuideBeat<Scene>

    /// The chat app the drawing stands for, as the captions and ThumbFree's round-trip screen name it: Messages, which
    /// ThumbFree takes you back to in every build (`ReturnTargets`).
    static let app = "Messages"
    static let bundleID = "com.apple.MobileSMS"
    /// What the chat's text box ends up holding.
    static let words = "Running ten minutes late, save me a seat"

    /// The beats. As in the app, ThumbFree takes you back by itself only with automatic return on and only to an app this
    /// build may open (`ReturnTargets.mayOpen`); otherwise the guide shows the swipe back, so it never promises a return
    /// the app won't make.
    static func beats(autoReturn: Bool) -> [Beat] {
        let returns = autoReturn && ReturnTargets.target(forBundleID: bundleID)
            .map { ReturnTargets.mayOpen($0, debugBuild: ReturnTargets.isDebugBuild) } == true
        return [
            Beat(scene: .switchKeyboard, caption: "Touch and hold the globe key, then pick ThumbFree.", length: 2),
            Beat(scene: .tapMic, caption: "Tap the mic on the ThumbFree keyboard."),
            returns
                ? Beat(scene: .goingBack, caption: "ThumbFree opens for a moment and takes you back. If iOS asks, tap Open.")
                : Beat(scene: .swipeBack, caption: "ThumbFree opens. Swipe right along the bottom edge to go back."),
            Beat(scene: .talking, caption: "Talk. ThumbFree listens."),
            Beat(scene: .tapStop, caption: "Tap the mic again to stop."),
            Beat(scene: .typed, caption: "Your words appear in the text box."),
        ]
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

    @State private var beat = 0

    var body: some View {
        GuidePlayer(beats: Self.beats(autoReturn: AutoReturn.enabled), id: "guide", interval: 1.4, index: $beat) { scene, moving in
            GuidePhone(scene: scene, moving: moving)
        }
    }
}

/// A drawing shown as large as the space offered allows, up to its own size and keeping its shape: in a page that must
/// fit the screen it takes the height left over; where nothing limits the height (a scroll view), its own size.
struct Shrinks<Drawing: View>: View {
    @ViewBuilder let drawing: Drawing
    /// The drawing's own size, measured once it is laid out.
    @State private var natural = CGSize.zero

    var body: some View {
        GeometryReader { space in
            let scale = natural.width > 0 && natural.height > 0
                ? min(1, space.size.width / natural.width, space.size.height / natural.height) : 1
            drawing
                .fixedSize()
                .onGeometryChange(for: CGSize.self) { $0.size } action: { natural = $0 }
                .scaleEffect(scale)
                .frame(width: space.size.width, height: space.size.height)
        }
        .frame(idealHeight: natural.height, maxHeight: natural.height > 0 ? natural.height : .infinity)
    }
}

/// One beat of a guide: what its drawn phone shows, and the caption under it.
struct GuideBeat<Scene: Equatable & Sendable>: Equatable, Sendable {
    let scene: Scene
    let caption: String
    /// How many beats' time it stays on screen: a drawing with more to show (the keyboard list) takes two.
    var length = 1.0
}

/// A guide's drawn phone, its caption and Back and Next: the walkthrough, the setup guide and "Put ThumbFree first" all
/// play on it. It moves to the next beat every `interval` seconds, in a loop. Back and Next step and stop the loop; with
/// Reduce Motion or VoiceOver on (or, in Debug builds, `-TFGuidePaused YES`) it never loops. VoiceOver reads "Step 3 of
/// 5." and the caption; the drawing is hidden from it and keeps the type size it was drawn at, so only the caption grows
/// with the text size. The drawing shrinks to the height a page leaves it (`Shrinks`), and the caption keeps to two
/// lines, so a welcome page never has to scroll.
struct GuidePlayer<Scene: Equatable & Sendable, Phone: View>: View {
    let beats: [GuideBeat<Scene>]
    /// Names the caption, Back and Next for UI tests: "guide" gives `guide.caption`, `guide.back` and `guide.next`.
    let id: String
    /// Seconds per beat; a drawing's own moves (a switch sliding, a finger swiping) fit inside one.
    var interval = 2.8
    /// The beat on screen, kept by the guide's owner: it can start elsewhere, and follow along (the setup guide lights
    /// its path with it).
    @Binding var index: Int
    /// The drawing for a beat, and whether it may move: true only while the guide plays by itself (false with Reduce
    /// Motion, VoiceOver, `-TFGuidePaused`, or once you step with Back or Next).
    @ViewBuilder let phone: (Scene, Bool) -> Phone

    @State private var stepping = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.accessibilityVoiceOverEnabled) private var voiceOver
    /// Two lines of caption, so the controls under it hold still from beat to beat.
    @ScaledMetric(relativeTo: .subheadline) private var captionHeight: CGFloat = 40

    /// Plays by itself unless motion is reduced, VoiceOver is on, a UI test paused it, or you stepped with Back or Next.
    private var playing: Bool { !stepping && !reduceMotion && !voiceOver && !GuideView.pausedForTests }

    var body: some View {
        let beat = beats[index]
        VStack(alignment: .leading, spacing: 12) {
            Shrinks { phone(beat.scene, playing).dynamicTypeSize(.large) }
                .frame(maxWidth: .infinity)
                .accessibilityHidden(true)
            Text(beat.caption)
                .font(.subheadline)
                .lineLimit(2)
                .minimumScaleFactor(0.8) // the largest standard sizes on the smallest iPhone: smaller type, never a third line
                .frame(maxWidth: .infinity, minHeight: captionHeight, alignment: .topLeading)
                .accessibilityLabel("Step \(index + 1) of \(beats.count). \(beat.caption)")
                .accessibilityIdentifier("\(id).caption")
            ViewThatFits(in: .horizontal) { // large text: the count goes, then Back and Next go one under the other
                HStack { back; Spacer(); count; Spacer(); next }
                HStack { back; Spacer(); next }
                VStack(alignment: .leading, spacing: 16) { back; next }
            }
            .font(.subheadline.weight(.semibold))
            .foregroundStyle(Theme.primary)
        }
        .task(id: playing) {
            while playing, !Task.isCancelled {
                try? await Task.sleep(for: .seconds(interval * beats[index].length))
                guard playing, !Task.isCancelled else { return }
                withAnimation(.easeInOut(duration: min(0.5, interval / 5))) { index = (index + 1) % beats.count }
            }
        }
    }

    /// Back and Next keep their small look in a tap target at least 44 points tall (`tapTarget`).
    private var back: some View {
        Button { step(-1) } label: { tapTarget(Label("Back", systemImage: "chevron.backward")) }
            .disabled(index == 0)
            // Looks disabled too: a foreground style (this row's, and the welcome flow's) keeps SwiftUI from dimming it.
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

/// Where a finger touches in a guide's drawing: a soft dot, pulsing for a tap. A finger that moves (a swipe, a drag)
/// holds still: its pulse would make it lag behind.
struct GuideFinger: View {
    let pulsing: Bool

    var body: some View {
        PhaseAnimator(pulsing ? [1.0, 0.75] : [1.0]) { scale in
            Circle()
                .fill(Theme.ink.opacity(0.28))
                .overlay(Circle().stroke(Theme.ink.opacity(0.6), lineWidth: 2))
                .frame(width: 30, height: 30)
                .scaleEffect(scale)
        } animation: { _ in .easeInOut(duration: 0.5) }
    }
}

/// What a guide's beat asks you to tap (a row, a switch, a key, a button): a sunflower ring with a soft glow, so the eye
/// finds it before the finger does.
struct GuideRing<Outline: Shape>: View {
    let outline: Outline

    var body: some View {
        outline.stroke(Theme.sunflower, lineWidth: 3).shadow(color: Theme.sunflower.opacity(0.9), radius: 5)
    }
}

/// A system alert in a guide's drawing, the card alone at the size iOS draws it: its question, and two buttons, the
/// second ringed, with the finger on it.
struct GuideAlert: View {
    let title: String
    let cancel: String
    let confirm: String
    /// False with Reduce Motion or VoiceOver: the finger holds still.
    let moving: Bool

    var body: some View {
        VStack(spacing: 0) {
            Text(title).font(.subheadline.weight(.semibold)).multilineTextAlignment(.center).padding(.horizontal, 16).padding(.vertical, 18)
            Rectangle().fill(Theme.chip).frame(height: 1)
            HStack(spacing: 0) {
                Text(cancel).frame(maxWidth: .infinity)
                Rectangle().fill(Theme.chip).frame(width: 1)
                Text(confirm).fontWeight(.bold).frame(maxWidth: .infinity, maxHeight: .infinity)
                    .overlay { GuideRing(outline: RoundedRectangle(cornerRadius: 12)).padding(5) }
                    .overlay { GuideFinger(pulsing: moving) }
            }
            .foregroundStyle(Theme.primary)
            .frame(height: 48)
        }
        .frame(width: 270)
        .background(Theme.paper, in: .rect(cornerRadius: 16))
        .shadow(color: .black.opacity(0.15), radius: 8, y: 2)
    }
}

/// The walkthrough's drawing for one beat, only what matters: a chat's text box over the keyboard (Apple's until the
/// globe switches to ThumbFree's), nearly at their real size, and ThumbFree's round-trip screen while the first tap
/// opens it.
struct GuidePhone: View {
    let scene: GuideView.Scene
    /// False unless the walkthrough plays by itself: still frames, no pulses or swipes (Reduce Motion, VoiceOver, paused,
    /// or stepping with Back and Next).
    let moving: Bool

    /// ThumbFree's own screen is in front: the first tap opened it to start the mic.
    private var inApp: Bool { scene == .goingBack || scene == .swipeBack }

    var body: some View {
        // The globe beat, two beats long, as iOS does it: the finger touches and holds the globe, the list of keyboards
        // comes up, the finger slides to ThumbFree, and ThumbFree's keyboard comes up. A still frame shows ThumbFree
        // picked in the list.
        if scene == .switchKeyboard {
            if moving {
                KeyframeAnimator(initialValue: 0.0, repeating: true) { phone(picking: $0) } keyframes: { _ in
                    LinearKeyframe(1.0, duration: 0.35) // the finger on the globe
                    LinearKeyframe(2.0, duration: 0.45) // held: the list comes up
                    LinearKeyframe(3.0, duration: 0.7) // the slide to ThumbFree
                    LinearKeyframe(3.5, duration: 0.4) // ThumbFree picked
                    LinearKeyframe(4.0, duration: 1.5) // let go: ThumbFree's keyboard, past the beat's end, so it never starts over first
                }
            } else {
                phone(picking: 3.2)
            }
        } else {
            phone(picking: nil)
        }
    }

    /// The drawing; `picking`, only in the globe beat: where the pick has got to, 0 to 4 (see `body`).
    private func phone(picking: Double?) -> some View {
        VStack(spacing: 8) {
            textBox
            keyboard(picking: picking)
        }
        .frame(width: Self.drawingWidth)
        .overlay { if inApp { sessionScreen.transition(.move(edge: .trailing)) } }
    }

    private var textBox: some View {
        let typed = scene == .typed
        return HStack {
            Text(typed ? GuideView.words : "Message").font(.subheadline).foregroundStyle(typed ? Theme.ink : Theme.inkSoft).lineLimit(1)
            Spacer(minLength: 0)
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 9)
        .overlay(Capsule().stroke(Theme.ink, lineWidth: 1))
    }

    // The keyboard is drawn at the size the real one has on a 420-point-wide iPhone (`KeyboardViewController`: a 48-point
    // bar over 212 points of keys; iOS's globe row under them) and scaled into the phone, so it keeps the real keyboard's
    // proportions. The colors are `KeyplaneView`'s keys on the keyboard's glass, as the Simulator draws them.
    private static let width: CGFloat = 420
    private static let barHeight: CGFloat = 48
    private static let keysHeight: CGFloat = 212
    private static let globeRowHeight: CGFloat = 74
    /// The drawing's width: the page's on the smallest iPhone, so the keys read nearly as large as real ones.
    private static let drawingWidth: CGFloat = 327
    private static let scale: CGFloat = drawingWidth / width
    private static let keyFrames = KeyLayout(layer: .letters, showsGlobe: false,
                                             bounds: CGRect(x: 0, y: 0, width: width, height: keysHeight)).frames
    private static let key = Color(light: 0xFFFFFF, dark: 0x3D3D3D) // Apple's iOS 26 keys: every key one color
    private static let glass = Color(light: 0xE2E4E8, dark: 0x171717)

    /// Apple's keyboard, or ThumbFree's: the same Apple rows under a bar, Apple's empty (its predictions) or ThumbFree's
    /// (the short status at the left, the small mic at the top right), so the two are one height. On a Face ID iPhone iOS
    /// draws the globe in a row under the keys, for both. In the globe beat, Apple's keyboard until the finger lets go
    /// of ThumbFree in the list.
    private func keyboard(picking: Double?) -> some View {
        let thumbFree = (picking ?? 4) >= 3.5
        let height = Self.barHeight + Self.keysHeight + Self.globeRowHeight
        return VStack(spacing: 0) {
            if thumbFree { bar } else { Color.clear.frame(height: Self.barHeight) }
            keyRows
            Image(systemName: "globe").font(.system(size: 26)).position(x: 42, y: 34).frame(height: Self.globeRowHeight)
        }
        .foregroundStyle(Color.primary)
        .frame(width: Self.width, height: height)
        .background(Self.glass, in: .rect(cornerRadius: 22))
        .scaleEffect(Self.scale, anchor: .topLeading)
        .frame(width: Self.width * Self.scale, height: height * Self.scale, alignment: .topLeading)
        .overlay(alignment: .topLeading) { // at the drawing's own size, over the scaled keys
            if let picking, picking < 3.5 {
                let globe = Self.globe
                if picking < 2 { GuideRing(outline: Circle()).frame(width: 40, height: 40).position(globe) }
                if picking >= 1 { keyboardList(picked: picking >= 2.8) }
                // Held on the globe, then sliding to ThumbFree's row: a moving finger holds still, no pulse.
                let slide = min(max(picking - 2, 0), 1)
                GuideFinger(pulsing: false)
                    .position(x: globe.x + (Self.pickPoint.x - globe.x) * slide, y: globe.y + (Self.pickPoint.y - globe.y) * slide)
            } else if scene == .tapMic || scene == .tapStop {
                let mic = CGPoint(x: (Self.width - 34) * Self.scale, y: Self.barHeight / 2 * Self.scale)
                GuideRing(outline: Circle()).frame(width: 40, height: 40).position(mic)
                GuideFinger(pulsing: moving).position(mic)
            }
        }
    }

    /// The globe key's middle, at the drawing's own size.
    private static let globe = CGPoint(x: 42 * scale, y: (barHeight + keysHeight + 34) * scale)
    private static let listRow: CGFloat = 36
    private static let listFrame = CGRect(x: 10, y: globe.y - 28 - (3 * listRow + 8), width: 170, height: 3 * listRow + 8)
    /// Where the finger lets go: ThumbFree's row, the list's last.
    private static let pickPoint = CGPoint(x: listFrame.minX + 60, y: listFrame.maxY - 4 - listRow / 2)

    /// The list iOS shows above the globe while you hold it, ThumbFree's row ringed as the target and filled once picked.
    private func keyboardList(picked: Bool) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            ForEach(["English (US)", "Emoji", "ThumbFree"], id: \.self) { name in
                let thumbFree = name == "ThumbFree"
                Text(name)
                    .font(.subheadline.weight(thumbFree && picked ? .semibold : .regular))
                    .foregroundStyle(thumbFree && picked ? Theme.onSunflower : Theme.ink)
                    .padding(.horizontal, 12)
                    .frame(maxWidth: .infinity, minHeight: Self.listRow, maxHeight: Self.listRow, alignment: .leading)
                    .background(thumbFree && picked ? Theme.sunflower : .clear, in: .rect(cornerRadius: 10))
                    .overlay { if thumbFree { GuideRing(outline: RoundedRectangle(cornerRadius: 10)) } }
            }
        }
        .padding(4)
        .frame(width: Self.listFrame.width, height: Self.listFrame.height)
        .background(Theme.paper, in: .rect(cornerRadius: 14))
        .shadow(color: .black.opacity(0.2), radius: 6, y: 2)
        .position(x: Self.listFrame.midX, y: Self.listFrame.midY)
    }

    /// ThumbFree's bar as the keyboard draws it: the status in the keyboard's own words, and its mic, which follows it.
    private var bar: some View {
        HStack(spacing: 10) {
            Text(keyState.text).font(.footnote).lineLimit(2)
            Spacer(minLength: 0)
            BubbleArt(mode: BubbleArt.Mode(keyState)).frame(width: 40, height: 40).padding(4)
        }
        .padding(.horizontal, 10)
        .frame(height: Self.barHeight)
    }

    private var keyState: KeyState {
        switch scene {
        case .talking: .listening
        case .tapStop: .transcribing
        case .typed: .ready
        default: .startDictation
        }
    }

    /// Apple's letter rows, lowercase, where the real keyboard's `KeyLayout` puts them.
    private var keyRows: some View {
        ZStack {
            ForEach(Self.keyFrames, id: \.key) { place in
                let cap = Self.cap(place.key)
                Group {
                    if let symbol = cap.symbol { Image(systemName: symbol) } else { Text(cap.title) }
                }
                    .font(.system(size: cap.size))
                    .frame(width: place.frame.width, height: place.frame.height)
                    .background(Self.key, in: .rect(cornerRadius: 6))
                    .position(x: place.frame.midX, y: place.frame.midY)
            }
        }
        .frame(width: Self.width, height: Self.keysHeight)
    }

    /// A key's cap on the letters layer as `KeyplaneView` draws it: its title or SF Symbol, and font size. Apple's space
    /// bar is blank.
    static func cap(_ key: Key) -> (title: String, symbol: String?, size: CGFloat) {
        switch key {
        case .letter(let c): (String(c), nil, 22)
        case .shift: ("", "shift", 20)
        case .delete: ("", "delete.left", 20)
        case .toNumbers: ("123", nil, 16)
        case .emoji: ("", "face.smiling", 20)
        case .ret: ("", "return.left", 20)
        default: ("", nil, 16)                    // space, and keys not on the letters layer
        }
    }

    /// ThumbFree's round-trip screen in its own words (`SessionScreen`'s), over the drawing: "Listening." and "Taking you
    /// back to Messages…" while it takes you back; the swipe-back line when it can't, with a finger sliding right along
    /// the bottom edge.
    private var sessionScreen: some View {
        let trip = ReturnTrip(appName: GuideView.app, phase: scene == .goingBack ? .leaving : .swipeBack, firstReturn: false)
        let listening = HostStatus(micOn: true, take: .recording)
        return ZStack(alignment: .bottom) {
            RoundedRectangle(cornerRadius: 22).fill(Theme.paper).stroke(Theme.inkSoft.opacity(0.4), lineWidth: 1)
            VStack(spacing: 10) {
                BubbleArt(mode: .stop).frame(width: 76, height: 76)
                Group {
                    Text(SessionScreen.primaryLine(status: listening, returnTrip: trip)).font(.subheadline.weight(.semibold))
                    if let line = SessionScreen.subLine(status: listening, returnTrip: trip) {
                        Text(line).font(.footnote).foregroundStyle(Theme.inkSoft)
                    }
                }
                .multilineTextAlignment(.center)
                .padding(.horizontal, 24)
            }
            .frame(maxHeight: .infinity)
            if scene == .swipeBack, moving {
                KeyframeAnimator(initialValue: 0.0, repeating: true) { x in
                    GuideFinger(pulsing: false).offset(x: -100 + 200 * x, y: -16)
                } keyframes: { _ in // one swipe per 1.4 s beat, the last hold past its end so it never starts over first
                    LinearKeyframe(0.0, duration: 0.15)
                    LinearKeyframe(1.0, duration: 0.7)
                    LinearKeyframe(1.0, duration: 0.9)
                }
            } else if scene == .swipeBack { // a still frame: the finger where the swipe ends
                GuideFinger(pulsing: false).offset(x: 100, y: -16)
            }
        }
    }
}
