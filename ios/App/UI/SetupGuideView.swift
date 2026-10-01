import SwiftUI

/// The keyboard step's setup guide: a drawn iPhone shows the taps in Settings, one beat every 1.2 s, each target ringed
/// and the rest of the page quieter: Keyboards, the ThumbFree switch, the Allow Full Access switch, and Allow in iOS's
/// alert. Under it, the same path in one line to keep in mind while in Settings, its step lit in time with the drawing.
/// Drawn in the brand colors, never pictures of Apple's screens. It runs on the walkthrough's `GuidePlayer`.
struct SetupGuideView: View {
    enum Scene: CaseIterable { case keyboards, turnOn, fullAccess, allow }

    typealias Beat = GuideBeat<Scene>

    static let beats: [Beat] = [
        Beat(scene: .keyboards, caption: "Tap Keyboards."),
        Beat(scene: .turnOn, caption: "Turn on ThumbFree."),
        Beat(scene: .fullAccess, caption: "Turn on Allow Full Access. The mic needs it."),
        Beat(scene: .allow, caption: "Tap Allow."),
    ]

    /// The path under the drawing, one step per beat.
    static let path = ["Keyboards", "ThumbFree", "Allow Full Access", "Allow"]

    @State private var beat: Int

    /// `fromFullAccess`: the keyboard is added already, so the guide starts where it is still needed.
    init(fromFullAccess: Bool = false) {
        _beat = State(initialValue: fromFullAccess ? 2 : 0)
    }

    var body: some View {
        VStack(spacing: 12) {
            GuidePlayer(beats: Self.beats, id: "setupGuide", interval: 1.2, index: $beat) { scene, moving in
                SetupPhone(scene: scene, moving: moving)
            }
            ViewThatFits(in: .horizontal) { // large text: two lines
                HStack(spacing: 3) { steps(0..<4) }
                VStack(alignment: .leading, spacing: 6) {
                    HStack(spacing: 3) { steps(0..<2) }
                    HStack(spacing: 3) { steps(2..<4) }
                }
            }
            .accessibilityElement(children: .ignore)
            .accessibilityLabel("In Settings: Keyboards, ThumbFree, Allow Full Access, then Allow.")
            .accessibilityIdentifier("setupGuide.path")
        }
    }

    /// The path's steps in `range` as chips, the current beat's lit, with a chevron before each but the first.
    private func steps(_ range: Range<Int>) -> some View {
        ForEach(range, id: \.self) { step in
            if step > 0 { Image(systemName: "chevron.forward").font(.system(size: 9, weight: .bold)).foregroundStyle(Theme.inkSoft) }
            Text(Self.path[step])
                .font(.caption2.weight(.semibold))
                .lineLimit(1)
                .padding(.horizontal, 5)
                .padding(.vertical, 4)
                .foregroundStyle(step == beat ? Theme.onSunflower : Theme.inkSoft)
                .background(step == beat ? Theme.sunflower : Theme.chip, in: .capsule)
        }
    }
}

/// "Put ThumbFree first": iOS has no default-keyboard setting for other keyboards, so this shows the closest thing,
/// moving ThumbFree to the top of the keyboard list, then a tip. The Try tab's "How to use it in other apps" shows it
/// after the walkthrough.
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

    @State private var beat = 0

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
            GuidePlayer(beats: Self.beats, id: "putFirst", index: $beat) { scene, moving in KeyboardListPhone(scene: scene, moving: moving) }
            Label(Self.tip, systemImage: "lightbulb").font(.subheadline)
        }
    }
}

/// The setup guide's drawing, only what the beat taps and about the size Settings draws it: where you are in Settings,
/// then the page's two rows, the one to tap ringed in sunflower and the other quieter; for Allow, iOS's alert card alone.
/// One size for every beat, so nothing under it moves.
private struct SetupPhone: View {
    let scene: SetupGuideView.Scene
    let moving: Bool

    private static let rowHeight: CGFloat = 46

    var body: some View {
        // A switch slides on within its 1.2 s beat, then holds past the beat's end, so it never starts over first; still
        // frames show it on.
        if moving, scene == .turnOn || scene == .fullAccess {
            KeyframeAnimator(initialValue: 0.0, repeating: true) { rows($0) } keyframes: { _ in
                LinearKeyframe(0.0, duration: 0.3)
                LinearKeyframe(1.0, duration: 0.15)
                LinearKeyframe(1.0, duration: 1.1)
            }
            .id(scene) // each beat starts its switch from off
        } else {
            rows(1)
        }
    }

    /// The beat with its switch `on` of the way on.
    private func rows(_ on: Double) -> some View {
        Group {
            if scene == .allow { // the question iOS asks when Allow Full Access is turned on
                GuideAlert(title: "Allow Full Access?", cancel: "Don't Allow", confirm: "Allow", moving: moving)
            } else {
                VStack(alignment: .leading, spacing: 8) {
                    Text(scene == .keyboards ? "Settings › ThumbFree" : "Settings › ThumbFree › Keyboards")
                        .font(.footnote.weight(.semibold))
                        .foregroundStyle(Theme.inkSoft)
                        .padding(.leading, 4)
                    if scene == .keyboards {
                        SettingsGroup {
                            row("Microphone") { SwitchArt(on: 1) }.opacity(0.4)
                            row("Keyboards") { Image(systemName: "chevron.forward").font(.subheadline.weight(.semibold)).foregroundStyle(Theme.inkSoft) }
                                .overlay(alignment: .trailing) { finger.padding(.trailing, 4) }
                        }
                        .overlay(alignment: .bottom) { ring }
                    } else {
                        SettingsGroup {
                            row("ThumbFree") { SwitchArt(on: scene == .turnOn ? on : 1).overlay { if scene == .turnOn { finger } } }
                                .opacity(scene == .turnOn ? 1 : 0.4)
                            row("Allow Full Access") {
                                SwitchArt(on: scene == .turnOn ? 0 : on).overlay { if scene == .fullAccess { finger } }
                            }
                            .opacity(scene == .fullAccess ? 1 : 0.4)
                        }
                        .overlay(alignment: scene == .turnOn ? .top : .bottom) { ring }
                    }
                }
                .padding(12)
                .background(Theme.card, in: .rect(cornerRadius: 20))
            }
        }
        .frame(width: 300, height: 150)
    }

    private func row(_ title: String, @ViewBuilder accessory: @escaping () -> some View) -> some View {
        SettingsRow(title: title, font: .body, height: Self.rowHeight, accessory: accessory)
    }

    /// Around the one row of a two-row group that this beat taps.
    private var ring: some View {
        GuideRing(outline: RoundedRectangle(cornerRadius: 12)).frame(height: Self.rowHeight).padding(-2)
    }

    private var finger: some View { GuideFinger(pulsing: moving) }
}

/// "Put ThumbFree first"'s drawing: the keyboard list in Settings on a panel, its Edit, ThumbFree dragged to the top,
/// and Done.
private struct KeyboardListPhone: View {
    let scene: PutFirstView.Scene
    let moving: Bool

    private static let keyboards = ["English (US)", "Emoji", "ThumbFree"]
    private static let row: CGFloat = 39 // a row and the line under it

    var body: some View {
        if moving, scene == .drag {
            KeyframeAnimator(initialValue: 0.0, repeating: true) { screen($0) } keyframes: { _ in
                LinearKeyframe(0.0, duration: 0.6)
                CubicKeyframe(1.0, duration: 1.2)
                LinearKeyframe(1.0, duration: 1.4) // past the 2.8 s beat's end, so it never starts over first
            }
        } else {
            screen(1)
        }
    }

    /// The beat with ThumbFree `t` of the way from the bottom of the list (0) to the top (1) while it is dragged.
    private func screen(_ t: Double) -> some View {
        let editing = scene == .drag || scene == .done
        let slots = PutFirstView.slots(dragged: scene == .done ? 1 : scene == .drag ? t : 0)
        return VStack(alignment: .leading, spacing: 0) {
            VStack(alignment: .leading, spacing: 10) {
                HStack {
                    Text("◀ Keyboard")
                    Spacer()
                    Text(editing ? "Done" : "Edit").overlay { if scene == .edit || scene == .done { finger } }
                }
                .font(.caption2.weight(.semibold))
                .foregroundStyle(Theme.primary)
                .padding(.horizontal, 6)
                Text("Keyboards").font(.title3.bold())
                ZStack(alignment: .top) {
                    ForEach(Array(Self.keyboards.enumerated()), id: \.offset) { index, name in
                        let dragged = index == 2
                        SettingsRow(title: name, editing: editing) {
                            if editing {
                                Image(systemName: "line.3.horizontal")
                                    .font(.caption.weight(.semibold))
                                    .foregroundStyle(Theme.inkSoft)
                                    .overlay { if dragged, scene == .drag { GuideFinger(pulsing: false) } }
                            }
                        }
                        .shadow(color: .black.opacity(dragged && scene == .drag ? 0.2 : 0), radius: 4)
                        .offset(y: Self.row * slots[index])
                    }
                }
                .frame(height: 3 * Self.row - 1, alignment: .top)
                .clipShape(.rect(cornerRadius: 12))
                SettingsGroup { SettingsRow(title: "Add New Keyboard…") {} }
            }
            .padding(12)
        }
        .frame(width: 260)
        .background(Theme.card, in: .rect(cornerRadius: 20))
    }

    private var finger: some View { GuideFinger(pulsing: moving) }
}

/// A rounded group of rows, drawn like Settings: the thin gaps between rows show the page through.
private struct SettingsGroup<Rows: View>: View {
    @ViewBuilder let rows: () -> Rows

    var body: some View {
        VStack(spacing: 1) { rows() }.clipShape(.rect(cornerRadius: 12))
    }
}

/// One row in a drawn Settings page: its title and what sits at its end; while editing, a remove mark before it.
private struct SettingsRow<Accessory: View>: View {
    let title: String
    var editing = false
    var font = Font.caption
    var height: CGFloat = 38
    @ViewBuilder let accessory: () -> Accessory

    var body: some View {
        HStack(spacing: 8) {
            if editing { Image(systemName: "minus.circle.fill").font(.caption).foregroundStyle(Theme.error) }
            Text(title).font(font)
            Spacer(minLength: 0)
            accessory()
        }
        .padding(.horizontal, 12)
        .frame(height: height)
        .background(Theme.paper)
    }
}

/// A switch, `on` from 0 (off) to 1 (on); in between, it is sliding. On, it is iOS's own green, as the user will see
/// it in Settings, at iOS's size.
private struct SwitchArt: View {
    let on: Double

    var body: some View {
        Capsule()
            .fill(Theme.chip)
            .overlay(Capsule().fill(Color.green).opacity(on))
            .overlay(Capsule().stroke(Theme.inkSoft.opacity(0.3), lineWidth: 1))
            .overlay(alignment: .leading) { Circle().fill(Color.white).frame(width: 27, height: 27).padding(2).offset(x: 20 * on) }
            .frame(width: 51, height: 31) // iOS's own switch size
    }
}
