import SwiftUI
import TFCore

// Drawings of what happens outside ThumbFree (a chat, the keyboard, Settings, iOS's keyboard list), drawn in SwiftUI with
// no pictures of other companies' apps. Each sits in a soft labelled frame (`IllustrationFrame`) with thin light lines, so
// it reads as a picture and never as a box to type in or a switch to flip. Drawings never take taps, keep the large text
// size and scale as one piece, and VoiceOver reads each frame once, as one image. Corners are continuous; a screen panel
// on the frame's inset keeps its corners parallel to the frame's (`IllustrationFrame.innerRadius`), and what is drawn on
// it is less rounded the further in it sits: the chat's card (18), its bubble and reply box (14), a key (5).

/// A drawing's frame: the warm canvas, a 1-point line drawn inside its edge, continuous corners, a faint shadow (deeper
/// in Dark Mode, where a faint one would not show) and a small label (EXAMPLE, IN SETTINGS, HOW TO SWITCH) on the same
/// 16-point inset as the drawing. Nothing in it takes a tap; VoiceOver reads `description` once.
struct IllustrationFrame<Content: View>: View {
    static var radius: CGFloat { 24 }
    static var inset: CGFloat { 16 }
    /// The corner radius of a panel on the frame's inset: its corners run parallel to the frame's.
    static var innerRadius: CGFloat { radius - inset }

    let label: String
    let description: String
    let id: String
    /// VoiceOver reads the frame. Off while a `ViewThatFits` leaves the frame out: it keeps the frame it tried in the
    /// tree, unseen, and its VoiceOver element would stay over what took its place.
    var voiced = true
    @ViewBuilder let content: Content
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        let shape = RoundedRectangle(cornerRadius: Self.radius, style: .continuous)
        VStack(alignment: .leading, spacing: 12) {
            IllustrationLabel(text: label)
            content.frame(maxWidth: .infinity)
        }
        .padding(Self.inset)
        .frame(maxWidth: .infinity, alignment: .topLeading)
        .clipShape(shape) // nothing draws past the frame
        // Behind the clip, so the shadow is never cut.
        .background {
            shape.fill(Theme.canvas)
                .shadow(color: Theme.shadow.opacity(scheme == .dark ? 0.24 : 0.06), radius: scheme == .dark ? 12 : 8,
                        y: scheme == .dark ? 4 : 3)
        }
        .overlay { shape.strokeBorder(Theme.line, lineWidth: 1) }
        .allowsHitTesting(false)
        // One image to VoiceOver, exactly the frame's size: the drawing's own parts, even those it clips, would stretch it.
        .accessibilityHidden(true)
        .overlay {
            if voiced {
                Color.clear
                    .accessibilityElement()
                    .accessibilityLabel(description)
                    .accessibilityAddTraits(.isImage)
                    .accessibilityIdentifier(id)
            }
        }
    }
}

/// The frame's small label: fixed 11-point capitals in a muted capsule.
struct IllustrationLabel: View {
    let text: String

    var body: some View {
        Text(text)
            .font(.system(size: 11, weight: .semibold))
            .tracking(0.8)
            .foregroundStyle(Theme.inkSoft)
            .padding(.horizontal, 9)
            .frame(height: 22)
            .background(Theme.muted, in: .capsule)
    }
}

/// A cropped phone screen inside a frame: the page's color on the canvas, with no line of its own (the frame has one),
/// as wide as the frame's drawing area and its corners parallel to the frame's. Its drawing (`Shrinks`) keeps its own
/// size, or less: the screen is as tall as the drawing, which it centers and cuts at its edges (the keyboard at the
/// bottom).
struct DiagramScreen<Content: View>: View {
    @ViewBuilder let content: Content

    var body: some View {
        let shape = RoundedRectangle(cornerRadius: IllustrationFrame<EmptyView>.innerRadius, style: .continuous)
        content
            .frame(maxWidth: .infinity)
            .background(Theme.paper, in: shape)
            .clipShape(shape)
    }
}

/// The timing of one tap in a picture, 1.3 s in all: the cue holds 0.25 s, its ripple grows over 0.35 s, the control
/// changes over 0.25 s (eased in and out), and the result holds 0.45 s. A still picture (Reduce Motion, VoiceOver)
/// shows `length`: the result, with the ring and its pill.
enum TapTimeline {
    static let length = 1.3
    static let rippleStart = 0.25
    static let rippleEnd = 0.6
    static let changeEnd = 0.85

    /// How far the ripple has grown at `time` seconds into the tap, 0 to 1; nil before and after it.
    static func ripple(at time: Double) -> Double? {
        (rippleStart..<rippleEnd).contains(time) ? (time - rippleStart) / (rippleEnd - rippleStart) : nil
    }

    /// How far the control has changed at `time`: 0 before, 1 once done, eased so nothing jumps.
    static func change(at time: Double) -> Double {
        let t = min(max((time - rippleEnd) / (changeEnd - rippleEnd), 0), 1)
        return t * t * (3 - 2 * t)
    }

    /// How pressed a tapped row or link is at `time`, 0 to 1: it darkens with the ripple and lets go as the change ends,
    /// smoothly both ways.
    static func press(at time: Double) -> Double {
        let t = (time - rippleStart) / (changeEnd - rippleStart)
        return (0...1).contains(t) ? sin(.pi * t) : 0
    }
}

/// Where a picture's beat asks for a tap, a hold or a swipe: a 3-point sunflower ring around the target with a soft glow
/// and, a 1-point gap outside it, a thin edge, and a small pill beside it naming the gesture. It replaces any drawn
/// finger, which looked like a switch's thumb and hid the target. It takes no input.
struct TapCue<Outline: InsettableShape>: View {
    /// Where the pill sits: above the target, or beside it where something above leaves no room.
    enum PillEdge { case top, leading, trailing }

    let outline: Outline
    var gesture = "Tap"
    /// The tap's ripple, 0 to 1 of its way: a ring from 5 points inside the target's edge to 5 points outside it (on a
    /// 36 to 40 point circle, about 75% to 125%), fading. On a wide row it never crosses the words. Nil draws none.
    var ripple: Double?
    var showsPill = true
    var pillEdge = PillEdge.top

    var body: some View {
        ZStack {
            outline.stroke(Theme.sunflower, lineWidth: 3).shadow(color: Theme.sunflower.opacity(0.18), radius: 8)
            outline.inset(by: -3).stroke(Theme.cueEdge, lineWidth: 1) // a 1-point gap outside the yellow
            if let ripple {
                outline.inset(by: 5 - 10 * ripple).stroke(Theme.sunflower.opacity(0.9 * (1 - ripple)), lineWidth: 2.5)
            }
        }
        .overlay(alignment: pillEdge == .top ? .top : pillEdge == .leading ? .leading : .trailing) {
            // In a zero-size frame at the target's edge, so the pill sits just outside the ring whatever its width.
            if showsPill {
                switch pillEdge {
                case .top: CuePill(text: gesture).fixedSize().frame(height: 0, alignment: .bottom).offset(y: -7)
                case .leading: CuePill(text: gesture).fixedSize().frame(width: 0, alignment: .trailing).offset(x: -8)
                case .trailing: CuePill(text: gesture).fixedSize().frame(width: 0, alignment: .leading).offset(x: 8)
                }
            }
        }
    }
}

/// The tap cue's pill: "Tap", "Hold" or "Swipe", ink with light words, sunflower with dark words in Dark Mode.
struct CuePill: View {
    static let height: CGFloat = 22

    let text: String
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        Text(text)
            .font(.system(size: 12, weight: .semibold))
            .foregroundStyle(scheme == .dark ? Theme.onSunflower : Theme.onPrimary)
            .padding(.horizontal, 8)
            .frame(height: Self.height)
            .background(scheme == .dark ? Theme.sunflower : Theme.ink, in: .capsule)
    }
}

// MARK: Settings

/// A drawn Settings switch, 44 x 26 points with a 22-point knob, `on` from 0 to 1 (between, it slides): muted when
/// off, iOS's green when on, with no shadow and no outline.
struct SettingsSwitchDiagram: View {
    let on: Double

    var body: some View {
        Capsule()
            .fill(Theme.muted)
            .overlay(Capsule().fill(Theme.switchOn).opacity(on))
            .overlay(alignment: .leading) {
                Circle().fill(Theme.knob).frame(width: 22, height: 22).padding(.horizontal, 2).offset(x: 18 * on)
            }
            .frame(width: 44, height: 26)
    }
}

/// One row of Settings drawn large, alone in its group: 52 points tall and up to 276 wide, its title in 17-point type
/// and a chevron or a switch at its end, on the drawing surface with a fine line and 14-point corners. The tap cue rings
/// what the beat taps, the row itself for a chevron or else its switch, `time` seconds into the tap (`TapTimeline`): the
/// ripple, then the row's press or the switch sliding on.
struct SettingsRowDiagram: View {
    enum End: Equatable { case chevron, toggle }

    static let height: CGFloat = 52
    static let radius: CGFloat = 14
    /// How far a tap cue's pill reaches above the row: the room a picture leaves for it.
    static let pillRoom: CGFloat = 35

    let title: LocalizedStringKey
    var end = End.chevron
    var time = TapTimeline.length

    var body: some View {
        let shape = RoundedRectangle(cornerRadius: Self.radius, style: .continuous)
        let ripple = TapTimeline.ripple(at: time)
        HStack(spacing: 8) {
            Text(title).font(.system(size: 17)).foregroundStyle(Theme.ink).lineLimit(1)
            Spacer(minLength: 8)
            switch end {
            case .chevron:
                Image(systemName: "chevron.forward").font(.system(size: 12, weight: .semibold)).foregroundStyle(Theme.inkSoft)
            case .toggle:
                SettingsSwitchDiagram(on: TapTimeline.change(at: time))
                    .overlay { TapCue(outline: Capsule(), ripple: ripple).padding(-4) }
            }
        }
        .padding(.horizontal, 14)
        .frame(maxWidth: 276)
        .frame(height: Self.height)
        .background {
            shape.fill(Theme.surface)
            if end == .chevron { shape.fill(Theme.muted.opacity(TapTimeline.press(at: time))) } // pressed, as in Settings
        }
        .overlay(shape.strokeBorder(Theme.line, lineWidth: 0.75))
        .overlay {
            // 4 points outside the row, its corners still parallel to the row's.
            if end == .chevron { TapCue(outline: RoundedRectangle(cornerRadius: Self.radius + 4, style: .continuous), ripple: ripple).padding(-4) }
        }
    }
}

// MARK: Chat and keyboard

/// A drawn chat's card: what it holds (Sam's message, the reply box) on the drawing surface, 14 points in, with a
/// 1-point line and 18-point corners, rounder than the bubble and the box inside it.
struct ChatDiagram<Content: View>: View {
    @ViewBuilder let content: Content

    var body: some View {
        let shape = RoundedRectangle(cornerRadius: 18, style: .continuous)
        content
            .padding(14)
            .background(Theme.surface, in: shape)
            .overlay(shape.strokeBorder(Theme.line, lineWidth: 1))
    }
}

/// A drawn chat's top: the made-up contact, Sam.
struct ContactDiagram: View {
    var body: some View {
        HStack(spacing: 8) {
            Text("S").font(.system(size: 11, weight: .semibold)).foregroundStyle(Theme.onSunflower)
                .frame(width: 20, height: 20).background(Theme.sunflower, in: .circle)
            Text("Sam").font(.system(size: 13, weight: .semibold)).foregroundStyle(Theme.ink)
        }
    }
}

/// Sam's message in the drawn chat.
struct IncomingDiagram: View {
    var body: some View {
        Text("Are you on your way?")
            .font(.system(size: 13))
            .foregroundStyle(Theme.ink)
            .padding(.horizontal, 12)
            .padding(.vertical, 7)
            .background(Theme.bubble, in: .rect(cornerRadius: 14, style: .continuous))
    }
}

/// The drawn chat's reply box: plain, with no focus ring and no "Message" placeholder, so it never looks like a box to
/// type in. The cursor shows only in the beat that taps it; typed words keep to one line, a little smaller where needed.
struct DiagramTextField: View {
    var text: String?
    var cursor = false

    var body: some View {
        let shape = RoundedRectangle(cornerRadius: 14, style: .continuous)
        HStack(spacing: 0) {
            if let text {
                Text(text).font(.system(size: 14)).foregroundStyle(Theme.ink).lineLimit(1).minimumScaleFactor(0.85)
            }
            if cursor { Capsule().fill(Theme.primary).frame(width: 2, height: 18) }
            Spacer(minLength: 0)
        }
        .padding(.horizontal, 14)
        .frame(height: 42)
        .background(Theme.surface, in: shape)
        .overlay(shape.strokeBorder(Theme.line, lineWidth: 0.75))
    }
}

/// The drawn keyboard's yellow mic: sunflower when ready, a red ring with a stop square while recording, and an arc
/// while the take turns into text.
struct MicDiagram: View {
    enum Mode: Equatable { case ready, recording, busy }

    let mode: Mode

    var body: some View {
        ZStack {
            switch mode {
            case .ready:
                Circle().fill(Theme.sunflower)
                Image(systemName: "mic.fill").font(.system(size: 14, weight: .semibold)).foregroundStyle(Theme.onSunflower)
            case .recording:
                Circle().fill(Theme.surface)
                Circle().strokeBorder(Theme.recording, lineWidth: 3)
                RoundedRectangle(cornerRadius: 2.5, style: .continuous).fill(Theme.recording).frame(width: 10, height: 10)
            case .busy:
                Circle().fill(Theme.sunflower)
                Circle().trim(from: 0, to: 0.3).stroke(Theme.primary, style: StrokeStyle(lineWidth: 2.5, lineCap: .round))
                    .rotationEffect(.degrees(-90)).padding(3)
            }
        }
        .frame(width: 32, height: 32)
    }
}

/// A simple keyboard, 192 points tall and as wide as it is drawn (300 by default): enough to recognise, clearly a
/// picture. ThumbFree's has its bar (the status in the keyboard's own words, and the yellow mic); Apple's has its empty
/// suggestions bar and its own dictation mic beside the globe. Three rows of letters and the globe row; a wider drawing
/// widens the keys, never reflows them.
struct KeyboardDiagram: View {
    enum Kind: Equatable { case apple, thumbFree }
    /// The beat's cue: on the mic, or on the globe with its gesture.
    enum Cue: Equatable {
        case mic
        case globe(String)
    }

    static let height: CGFloat = 192
    static let barHeight: CGFloat = 38
    static let rowsHeight: CGFloat = 112
    /// The globe's middle, in the drawing's points; the mic's is 32 points in from the right.
    static let globe = CGPoint(x: 26, y: 171)

    var width: CGFloat = 300
    var kind = Kind.thumbFree
    var mic = MicDiagram.Mode.ready
    var status = KeyState.startDictation.text
    var cue: Cue?
    /// Seconds into the cue's tap.
    var time = TapTimeline.length

    /// The mic's middle, in the drawing's points.
    var micPoint: CGPoint { CGPoint(x: width - 32, y: 19) }
    private var keyWidth: CGFloat { (width - 8 - 9 * 4) / 10 }

    var body: some View {
        VStack(spacing: 0) {
            bar.frame(height: Self.barHeight)
            rows.frame(height: Self.rowsHeight)
            globeRow.frame(height: 42)
        }
        .frame(width: width, height: Self.height)
        .background(Theme.keyboardGlass, in: UnevenRoundedRectangle(topLeadingRadius: 18, topTrailingRadius: 18, style: .continuous))
        .overlay(alignment: .topLeading) {
            switch cue {
            case .mic?:
                TapCue(outline: Circle(), ripple: TapTimeline.ripple(at: time)).frame(width: 40, height: 40).position(micPoint)
            case .globe(let gesture)?:
                TapCue(outline: Circle(), gesture: gesture, ripple: TapTimeline.ripple(at: time), pillEdge: .trailing)
                    .frame(width: 36, height: 36)
                    .position(Self.globe)
            case nil:
                EmptyView()
            }
        }
    }

    @ViewBuilder private var bar: some View {
        if kind == .thumbFree {
            HStack(spacing: 8) {
                Text(status).font(.system(size: 12)).foregroundStyle(Theme.ink).lineLimit(1).minimumScaleFactor(0.85)
                Spacer(minLength: 0)
                MicDiagram(mode: mic)
            }
            .padding(.leading, 12)
            .padding(.trailing, 16)
        } else {
            HStack(spacing: 0) { // Apple's suggestions bar, empty
                Spacer()
                Capsule().fill(Theme.line).frame(width: 1, height: 18)
                Spacer()
                Capsule().fill(Theme.line).frame(width: 1, height: 18)
                Spacer()
            }
        }
    }

    private var rows: some View {
        VStack(spacing: 4) {
            letters("qwertyuiop")
            letters("asdfghjkl")
            HStack(spacing: 4) {
                key(symbol: "shift", width: keyWidth * 1.4)
                letters("zxcvbnm")
                key(symbol: "delete.left", width: keyWidth * 1.4)
            }
        }
        .padding(.top, 8)
        .padding(.bottom, 6)
    }

    private func letters(_ row: String) -> some View {
        HStack(spacing: 4) { ForEach(Array(row), id: \.self) { key(String($0), width: keyWidth) } }
    }

    private func key(_ title: String = "", symbol: String? = nil, width: CGFloat) -> some View {
        Group {
            if let symbol { Image(systemName: symbol).font(.system(size: 12, weight: .medium)) } else {
                Text(title).font(.system(size: 13, weight: .medium))
            }
        }
        .foregroundStyle(Theme.ink)
        .frame(width: width, height: 30)
        .background(Theme.keyFace, in: .rect(cornerRadius: 5, style: .continuous))
        .shadow(color: Theme.shadow.opacity(0.12), radius: 0, y: 1)
    }

    private var globeRow: some View {
        ZStack {
            Image(systemName: "globe").font(.system(size: 17)).position(x: Self.globe.x, y: Self.globe.y - 150)
            RoundedRectangle(cornerRadius: 5, style: .continuous).fill(Theme.keyFace).frame(width: width * 0.44, height: 30)
                .shadow(color: Theme.shadow.opacity(0.12), radius: 0, y: 1)
                .position(x: width * 0.56, y: 17)
            if kind == .apple { Image(systemName: "mic").font(.system(size: 16)).position(x: width - 26, y: 21) }
        }
        .foregroundStyle(Theme.ink)
    }
}

/// The list iOS shows above the globe while you hold it: English (US), Emoji and ThumbFree. The lit row moves to
/// ThumbFree (`lit`, 0 to 2, between while it slides), which fills with sunflower once picked.
struct KeyboardPickerDiagram: View {
    static let names = ["English (US)", "Emoji", "ThumbFree"]
    /// The list's corner radius; its lit row's, on the 4-point inset, is 4 less: parallel corners.
    static let radius: CGFloat = 12

    var lit = 2.0
    var picked = true
    var rowHeight: CGFloat = 30

    var body: some View {
        let shape = RoundedRectangle(cornerRadius: Self.radius, style: .continuous)
        VStack(alignment: .leading, spacing: 0) {
            ForEach(Self.names.indices, id: \.self) { index in
                let chosen = picked && index == 2
                Text(Self.names[index])
                    .font(.system(size: 13, weight: chosen ? .semibold : .regular))
                    .foregroundStyle(chosen ? Theme.onSunflower : Theme.ink)
                    .padding(.horizontal, 10)
                    .frame(maxWidth: .infinity, minHeight: rowHeight, maxHeight: rowHeight, alignment: .leading)
            }
        }
        .background(alignment: .top) {
            RoundedRectangle(cornerRadius: Self.radius - 4, style: .continuous)
                .fill(picked ? Theme.sunflower : Theme.muted)
                .frame(height: rowHeight)
                .offset(y: rowHeight * lit)
        }
        .padding(4)
        .frame(width: 150)
        .background(Theme.surface, in: shape)
        .overlay(shape.strokeBorder(Theme.line, lineWidth: 0.75))
        .shadow(color: Theme.shadow.opacity(0.08), radius: 6, y: 2)
    }
}

/// The try's helper, HOW TO SWITCH: the bottom of the keyboard, its last row of keys and the globe under it, held, with
/// iOS's list up over the keys beside it, the lit row moving to ThumbFree. Two beats of 1.3 s: hold the globe and the list
/// comes up; then ThumbFree is picked. `time` runs over both, 0 to 2.6 s; a still picture shows the end. 322 x 86 points,
/// the frame's drawing area on an iPhone 17 Pro at its tallest, so it shows there at its own size, the keyboard's lower
/// corners parallel to the frame's.
struct GlobeHelperDiagram: View {
    static let length = 2 * TapTimeline.length
    static let size = CGSize(width: 322, height: 86)
    static let globe = CGPoint(x: 26, y: 64)

    var time = GlobeHelperDiagram.length

    var body: some View {
        let first = min(time, TapTimeline.length), second = max(time - TapTimeline.length, 0)
        let listUp = min(max((first - TapTimeline.rippleEnd) / 0.25, 0), 1)
        ZStack(alignment: .topLeading) {
            HStack(spacing: 4) {
                key(Text("123").font(.system(size: 13, weight: .medium)), width: 40)
                key(Image(systemName: "face.smiling").font(.system(size: 12, weight: .medium)), width: 34)
                key(Color.clear, width: 170) // the space bar
                key(Image(systemName: "return.left").font(.system(size: 12, weight: .medium)), width: 58)
            }
            .padding(.top, 8)
            .frame(width: Self.size.width, height: Self.size.height, alignment: .top)
            .background(Theme.keyboardGlass, in: UnevenRoundedRectangle(
                topLeadingRadius: 14, bottomLeadingRadius: IllustrationFrame<EmptyView>.innerRadius,
                bottomTrailingRadius: IllustrationFrame<EmptyView>.innerRadius, topTrailingRadius: 14, style: .continuous))
            Image(systemName: "globe").font(.system(size: 17)).foregroundStyle(Theme.ink).position(Self.globe)
            TapCue(outline: Circle(), gesture: "Hold", ripple: TapTimeline.ripple(at: first), pillEdge: .trailing)
                .frame(width: 34, height: 34)
                .position(Self.globe)
            KeyboardPickerDiagram(lit: 2 * TapTimeline.change(at: second), picked: second >= TapTimeline.changeEnd, rowHeight: 24)
                .scaleEffect(0.9 + 0.1 * listUp, anchor: .bottomLeading)
                .opacity(listUp)
                .offset(x: 104, y: 3)
        }
        .frame(width: Self.size.width, height: Self.size.height, alignment: .topLeading)
    }

    private func key(_ label: some View, width: CGFloat) -> some View {
        label.foregroundStyle(Theme.ink)
            .frame(width: width, height: 30)
            .background(Theme.keyFace, in: .rect(cornerRadius: 5, style: .continuous))
            .shadow(color: Theme.shadow.opacity(0.12), radius: 0, y: 1)
    }
}
