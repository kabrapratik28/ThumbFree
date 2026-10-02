import SwiftUI

/// The keyboard step's picture of Settings in its IN SETTINGS frame: the floating guide's video on a loop, the same
/// one that floats over Settings once you go there (`FloatingGuide`), or one still frame while paused for screenshots.
/// With Reduce Motion or VoiceOver, or when the page leaves too little room for the video to read (large text on a small
/// iPhone), a short numbered list of the rows, with nothing moving; with less room still, nothing, and the step's words
/// say what to do. VoiceOver reads it once. While the app is away it keeps the look it had as the app left, so the video
/// floating over Settings stays.
struct SetupGuideView: View {
    /// The rows of the short list, in order.
    static let names = ["Keyboards", "ThumbFree", "Allow Full Access", "Allow", "Return to ThumbFree"]
    /// What VoiceOver reads for the picture, once.
    static let description = "In Settings, open Keyboards, turn on ThumbFree, turn on Allow Full Access, tap Allow, then return to ThumbFree."
    /// The least height the video shows at; its frame is then 250 points, and at most 340 (the video's own 274).
    static let videoMinHeight: CGFloat = 184

    let guide: FloatingGuide
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.accessibilityVoiceOverEnabled) private var voiceOver
    @Environment(\.scenePhase) private var scenePhase
    /// The look while the app was last in front, which holds while it is away (`FloatingGuide.shown`).
    @State private var heldLook: FloatingGuide.Look?

    var body: some View {
        let live = FloatingGuide.look(reduceMotion: reduceMotion, voiceOver: voiceOver, paused: GuideView.pausedForTests)
        let look = FloatingGuide.shown(live: live, held: heldLook, active: scenePhase == .active)
        ViewThatFits(in: .vertical) {
            if look != .cards {
                IllustrationFrame(label: "IN SETTINGS", description: Self.description,
                                  id: look == .still ? "welcome.guideStill" : "welcome.guide") {
                    FloatingGuideView(guide: guide).frame(minHeight: Self.videoMinHeight, idealHeight: Self.videoMinHeight)
                }
            }
            IllustrationFrame(label: "IN SETTINGS", description: Self.description, id: "welcome.guideList") { SetupGuideList() }
            Color.clear.frame(height: 0)
        }
        .onChange(of: scenePhase == .active ? live : nil, initial: true) { _, now in if let now { heldLook = now } }
    }
}

/// The keyboard step's rows as a short numbered list, for Reduce Motion and VoiceOver: what to tap, in order.
private struct SetupGuideList: View {
    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            ForEach(SetupGuideView.names.indices, id: \.self) { index in
                HStack(spacing: 12) {
                    Text("\(index + 1)").font(.subheadline.weight(.semibold).monospacedDigit()).foregroundStyle(Theme.inkSoft)
                        .frame(width: 18, alignment: .trailing)
                    Text(SetupGuideView.names[index]).font(.body.weight(.semibold)).foregroundStyle(Theme.ink)
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

/// "Put ThumbFree first": iOS has no default-keyboard setting for other keyboards, so this shows the closest thing,
/// moving ThumbFree to the top of the keyboard list, then a tip. The Home tab links to it under the walkthrough.
struct PutFirstView: View {
    enum Scene: CaseIterable { case keyboards, edit, drag, done }

    typealias Beat = GuideBeat<Scene>

    static let beats: [Beat] = [
        Beat(scene: .keyboards, caption: "Open Settings, General, Keyboard, Keyboards."),
        Beat(scene: .edit, caption: "Tap Edit."),
        Beat(scene: .drag, caption: "Drag ThumbFree to the top."),
        Beat(scene: .done, caption: "Tap Done."),
    ]

    static let tip = "Touch and hold the globe key to jump straight to ThumbFree."

    @State private var beat = GuideView.heldBeat(count: PutFirstView.beats.count)

    /// Where the rows are, in rows from the top (English (US), Emoji, ThumbFree), while ThumbFree is dragged `t` of
    /// the way from the bottom to the top. A row ThumbFree passes moves down a slot in one step, as ThumbFree's middle
    /// crosses into it: two rows are never in one place, and each always shows at least half. (Any slide of two
    /// same-height rows past each other puts them in one place at some moment.)
    static func slots(dragged t: Double) -> [Double] {
        let thumbFree = 2 - 2 * t
        return [thumbFree < 0.5 ? 1 : 0, thumbFree < 1.5 ? 2 : 1, thumbFree]
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Put ThumbFree first").font(.headline).accessibilityAddTraits(.isHeader)
            Text("iOS has no setting for a default keyboard. The closest is putting ThumbFree at the top of your keyboards.")
                .font(.subheadline)
                .foregroundStyle(Theme.inkSoft)
            GuidePlayer(beats: Self.beats, id: "putFirst", label: "IN SETTINGS",
                        description: "Settings' list of keyboards, with ThumbFree dragged to the top.", index: $beat) { scene, moving in
                Shrinks { KeyboardListDiagram(scene: scene, moving: moving) }
            }
            Label(Self.tip, systemImage: "lightbulb").font(.subheadline)
        }
    }
}

/// "Put ThumbFree first"'s drawing: the keyboard list in Settings, its Edit, ThumbFree dragged to the top, and Done.
private struct KeyboardListDiagram: View {
    let scene: PutFirstView.Scene
    let moving: Bool

    private static let keyboards = ["English (US)", "Emoji", "ThumbFree"]
    private static let row: CGFloat = 44

    var body: some View {
        if moving, scene != .keyboards {
            KeyframeAnimator(initialValue: 0.0, repeating: true) { screen($0) } keyframes: { _ in
                LinearKeyframe(2.8, duration: 2.8)
                LinearKeyframe(2.8, duration: 1.4) // past the 2.8 s beat's end, so it never starts over first
            }
        } else {
            screen(2.8)
        }
    }

    /// The beat `time` seconds in: Edit and Done get a tap, and the drag moves ThumbFree up after its hold.
    private func screen(_ time: Double) -> some View {
        let editing = scene == .drag || scene == .done
        let dragged = scene == .done ? 1 : scene == .drag ? min(max((time - 0.6) / 1.2, 0), 1) : 0
        let slots = PutFirstView.slots(dragged: dragged)
        return VStack(alignment: .leading, spacing: 12) {
            HStack {
                HStack(spacing: 4) {
                    Image(systemName: "chevron.backward").font(.system(size: 13, weight: .semibold))
                    Text("Keyboard")
                }
                Spacer()
                Text(editing ? "Done" : "Edit")
                    .padding(.horizontal, 8)
                    .frame(height: 30)
                    .overlay {
                        if scene == .edit || scene == .done {
                            TapCue(outline: Capsule(), ripple: moving ? TapTimeline.ripple(at: time) : nil)
                        }
                    }
            }
            .font(.system(size: 15, weight: .semibold))
            .foregroundStyle(Theme.primary)
            .padding(.top, 26) // room for the cue's pill
            Text("Keyboards").font(.system(size: 22, weight: .bold)).foregroundStyle(Theme.ink)
            ZStack(alignment: .top) {
                ForEach(Array(Self.keyboards.enumerated()), id: \.offset) { index, name in
                    let thumbFree = index == 2
                    HStack(spacing: 10) {
                        if editing { Image(systemName: "minus.circle.fill").font(.system(size: 15)).foregroundStyle(Theme.error) }
                        Text(name).font(.system(size: 16)).foregroundStyle(Theme.ink)
                        Spacer(minLength: 0)
                        if editing {
                            Image(systemName: "line.3.horizontal").font(.system(size: 14, weight: .semibold)).foregroundStyle(Theme.inkSoft)
                                .frame(width: 30, height: 30)
                                .overlay {
                                    if thumbFree, scene == .drag {
                                        TapCue(outline: Circle(), gesture: "Hold", showsPill: dragged == 0).frame(width: 34, height: 34)
                                    }
                                }
                        }
                    }
                    .padding(.horizontal, 14)
                    .frame(height: Self.row)
                    .background(Theme.surface)
                    .overlay(alignment: .bottom) {
                        if !thumbFree || scene != .drag { Rectangle().fill(Theme.line).frame(height: 0.75) }
                    }
                    .shadow(color: Theme.shadow.opacity(thumbFree && scene == .drag ? 0.15 : 0), radius: 4)
                    .offset(y: Self.row * slots[index])
                    .zIndex(thumbFree ? 1 : 0)
                }
            }
            .frame(height: 3 * Self.row, alignment: .top)
            .clipShape(.rect(cornerRadius: 14, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).strokeBorder(Theme.line, lineWidth: 0.75))
        }
        .frame(width: 280)
    }
}
