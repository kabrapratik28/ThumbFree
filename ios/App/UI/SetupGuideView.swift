import SwiftUI

/// The keyboard step's taps in Settings: the floating guide's five sentences as a still numbered list in an IN SETTINGS
/// frame (`StepList`), so nobody has to read a step before it changes, and under it why Full Access, and what iOS's
/// warning about it means. Nothing in it looks like a row or a switch to tap. The guide's video still floats over
/// Settings (`FloatingGuide`): it plays unseen behind the list, since Picture in Picture floats a player that is on the
/// page. With less room (large text on a small iPhone) why Full Access goes first, then the list with its video, and the
/// step's words say what to do; what goes leaves VoiceOver too. While the app is away it keeps the look it had as the app
/// left, so the video floating over Settings stays.
struct SetupGuideView: View {
    /// What VoiceOver reads for the list, once.
    static let description = "In Settings, open Keyboards, turn on ThumbFree, turn on Allow Full Access, confirm, then return to ThumbFree."
    /// Why Full Access, and what iOS's warning when you turn it on means for ThumbFree.
    static let fullAccess = String(localized: "Full Access lets the keyboard get your words from the ThumbFree app. iOS warns that a keyboard could send what you type. ThumbFree never does.")

    let guide: FloatingGuide
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.accessibilityVoiceOverEnabled) private var voiceOver
    @Environment(\.scenePhase) private var scenePhase
    /// The look while the app was last in front, which holds while it is away (`FloatingGuide.shown`).
    @State private var heldLook: FloatingGuide.Look?
    /// The list, and why Full Access, have room. A `ViewThatFits` keeps what it left out in the accessibility tree,
    /// unseen, so each is taken out of VoiceOver while it has none.
    @State private var listFits = true
    @State private var noteFits = true

    var body: some View {
        let live = FloatingGuide.look(reduceMotion: reduceMotion, voiceOver: voiceOver, paused: GuideView.pausedForTests)
        let look = FloatingGuide.shown(live: live, held: heldLook, active: scenePhase == .active)
        VStack(alignment: .leading, spacing: 0) {
            ViewThatFits(in: .vertical) {
                IllustrationFrame(label: "IN SETTINGS", description: Self.description, id: "welcome.guide", voiced: listFits) {
                    StepList(steps: FloatingGuide.captions)
                }
                // ponytail: the video plays covered by the list's opaque frame so Picture in Picture can float it; if an
                // iOS ever refuses a covered player, Open Settings opens Settings without the window (`Launch`).
                .background {
                    if look == .video {
                        FloatingGuideView(guide: guide)
                            .padding(IllustrationFrame<EmptyView>.inset)
                            .allowsHitTesting(false)
                            .accessibilityHidden(true)
                    }
                }
                Color.clear.frame(height: 0)
            }
            .onGeometryChange(for: Bool.self) { $0.size.height > 0 } action: { listFits = $0 }
            .layoutPriority(1) // the list has the room first
            if listFits {
                ViewThatFits(in: .vertical) {
                    Text(Self.fullAccess)
                        .font(.footnote)
                        .foregroundStyle(Theme.inkSoft)
                        .fixedSize(horizontal: false, vertical: true)
                        .accessibilityHidden(!noteFits)
                        .accessibilityIdentifier("welcome.fullAccess")
                        .padding(.top, 12)
                    Color.clear.frame(height: 0)
                }
                .onGeometryChange(for: Bool.self) { $0.size.height > 0 } action: { noteFits = $0 }
            }
        }
        .onChange(of: scenePhase == .active ? live : nil, initial: true) { _, now in if let now { heldLook = now } }
    }
}

/// Steps in order, numbered, each a short sentence: the keyboard step's taps in Settings (`SetupGuideView`) and Home's
/// steps in another app (`GuideView`). Words only, so nothing in it looks like something to tap.
struct StepList: View {
    let steps: [String]
    /// The numbers' column, as wide as a number at the text size.
    @ScaledMetric(relativeTo: .subheadline) private var numberWidth: CGFloat = 18

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            ForEach(steps.indices, id: \.self) { index in
                HStack(alignment: .firstTextBaseline, spacing: 12) {
                    Text("\(index + 1)").font(.subheadline.weight(.semibold).monospacedDigit()).foregroundStyle(Theme.inkSoft)
                        .frame(width: numberWidth, alignment: .trailing)
                    Text(steps[index])
                        .font(.body.weight(.semibold))
                        .foregroundStyle(Theme.ink)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

/// "Put ThumbFree first": iOS has no default-keyboard setting for other keyboards, so this shows the closest thing,
/// moving ThumbFree to the top of the keyboard list, then a tip. The Home tab links to it under its steps.
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
