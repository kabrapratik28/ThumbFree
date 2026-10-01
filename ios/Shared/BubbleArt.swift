import SwiftUI

/// The mic key drawn like the Android app's bubble (brand/bubble-idle.svg and bubble-recording.svg): a sunflower disc,
/// top to bottom #FFD35A to #FFB61E, with a navy key (#1F1B3A, top face #39335F) printed with a five-bar sound wave
/// (#FFC83D), tilted 9 degrees, over a soft shadow. Busy (the take is being transcribed): a turning arc around the
/// disc. Stop (every mic while it records): the disc red (#FF3B30) with a white rounded square, a stop key, in a soft
/// red glow that pulses. Drawn in code, so the keyboard and the app share it with no asset. The drawing is decorative:
/// the view that shows it carries the label.
struct BubbleArt: View {
    enum Mode: Equatable {
        case idle, busy, stop

        /// The keyboard's key follows its status line: a stop key while it records, then Transcribing's arc after the stop.
        init(_ state: KeyState) {
            switch state {
            case .listening: self = .stop
            case .transcribing, .gettingReady: self = .busy
            default: self = .idle
            }
        }
    }

    let mode: Mode
    @State private var turning = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        GeometryReader { geometry in
            let size = min(geometry.size.width, geometry.size.height)
            let s = size * 0.78 / 1024 // the disc is 78% of the key; the busy arc goes around it
            ZStack {
                if mode == .stop {
                    // The glow: a wider red disc, blurred, behind the key's own. It pulses with the status line's dot.
                    Circle().fill(Self.red).frame(width: 1200 * s, height: 1200 * s).blur(radius: 120 * s).modifier(Pulse())
                    Circle().fill(Self.red).frame(width: 1024 * s, height: 1024 * s)
                    RoundedRectangle(cornerRadius: 84 * s).fill(.white).frame(width: 380 * s, height: 380 * s)
                } else {
                    Circle()
                        .fill(LinearGradient(colors: [Self.rgb(0xFFD35A), Self.rgb(0xFFB61E)], startPoint: .top, endPoint: .bottom))
                        .frame(width: 1024 * s, height: 1024 * s)
                        .shadow(color: .black.opacity(0.22), radius: 18 * s, y: 12 * s)
                    Ellipse()
                        .fill(Self.rgb(0x1F1B3A).opacity(0.2))
                        .frame(width: 368 * s, height: 56 * s)
                        .offset(y: 240 * s)
                    ZStack {
                        RoundedRectangle(cornerRadius: 86 * s).fill(Self.rgb(0x1F1B3A)).frame(width: 420 * s, height: 366 * s)
                        RoundedRectangle(cornerRadius: 62 * s).fill(Self.rgb(0x39335F)).frame(width: 348 * s, height: 270 * s)
                            .offset(y: -30 * s)
                        HStack(spacing: 24 * s) {
                            // `id: \.offset`, not `\.self`: the heights repeat (70 and 130 twice), and a duplicate `id: \.self`
                            // makes SwiftUI warn "undefined results" and risks silently dropping one of the repeated bars.
                            ForEach(Array([70.0, 130, 180, 130, 70].enumerated()), id: \.offset) { _, height in
                                Capsule().fill(Self.rgb(0xFFC83D)).frame(width: 34 * s, height: height * s)
                            }
                        }
                        .offset(y: -30 * s)
                    }
                    .rotationEffect(.degrees(-9))
                    .offset(y: -75 * s)
                }
                switch mode {
                case .idle, .stop:
                    EmptyView()
                case .busy:
                    Circle()
                        .trim(from: 0, to: 0.28)
                        .stroke(Self.rgb(0x39335F), style: StrokeStyle(lineWidth: size * 0.05, lineCap: .round))
                        .padding(size * 0.035)
                        .rotationEffect(.degrees(turning ? 360 : 0))
                        .onAppear {
                            guard !reduceMotion else { return }
                            withAnimation(.linear(duration: 1).repeatForever(autoreverses: false)) { turning = true }
                        }
                        .onDisappear { turning = false } // so the next take's arc turns again
                }
            }
            .frame(width: geometry.size.width, height: geometry.size.height)
        }
        .aspectRatio(1, contentMode: .fit)
        .accessibilityHidden(true)
    }

    /// 0xRRGGBB as a color: the bubble looks the same in light and dark, as on Android.
    static func rgb(_ hex: UInt32) -> Color {
        Color(red: Double(hex >> 16 & 0xFF) / 255, green: Double(hex >> 8 & 0xFF) / 255, blue: Double(hex & 0xFF) / 255)
    }

    /// The bright red of the stop key, its glow and the status line's dot.
    static let red = rgb(0xFF3B30)

    /// The stop key's glow and the status line's dot pulse together: a gentle fade, 1.8 s out and back. Held still, fully
    /// shown, with Reduce Motion on.
    struct Pulse: ViewModifier {
        @Environment(\.accessibilityReduceMotion) private var reduceMotion

        func body(content: Content) -> some View {
            if reduceMotion {
                content
            } else {
                content.phaseAnimator([1, 0.35]) { view, opacity in view.opacity(opacity) } animation: { _ in .easeInOut(duration: 0.9) }
            }
        }
    }
}
