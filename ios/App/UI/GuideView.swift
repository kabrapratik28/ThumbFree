import SwiftUI
import TFCore

/// How to dictate in another app, on Home: four steps in words in an IN ANY APP frame, the keyboard step's look
/// (`StepList`). No drawing: a drawn globe or mic on a screen with no keyboard looks like a control that does nothing,
/// which App Review held in the try. Also the guides' test hooks.
struct GuideView: View {
    static let steps = [
        String(localized: "Open Messages or another app, and tap where you type."),
        String(localized: "Hold the globe key, then choose ThumbFree."),
        String(localized: "Tap the yellow mic. If ThumbFree opens, go back to your app."),
        String(localized: "Speak, then tap the red stop button."),
    ]
    /// What the welcome's example chat's box ends up holding.
    static let words = "Running ten minutes late, save me a seat"

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

    var body: some View {
        IllustrationFrame(label: "IN ANY APP", description: Self.steps.joined(separator: " "), id: "guide.steps") {
            StepList(steps: Self.steps)
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

/// A guide's picture in its frame, its caption, and Back and Next: "Put ThumbFree first" plays on it. It moves to the next beat every `interval` seconds, in a loop, with Back, Next and the count left out; the
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

    /// The caption's lines: two, and three above the default text size, so a longer caption never needs type smaller
    /// than the scale floor to fit on the narrowest iPhones.
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
