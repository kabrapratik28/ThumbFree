import SwiftUI

/// Clean up's round button, drawn at exactly the mic's geometry (`BubbleArt`): a disc 78% of the square with the mic's
/// soft shadow, so the two read as a pair of the same size. White with a fine line and an ink symbol, never yellow (a
/// yellow disc is the mic). The sparkle tidies, the arrow undoes, and while the app works the mic's own busy arc turns
/// around the disc. Decorative: the view that shows it carries the label.
struct SparkleArt: View {
    let mode: KeyboardClient.Sparkle
    @State private var turning = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        GeometryReader { geometry in
            let size = min(geometry.size.width, geometry.size.height)
            let s = size * 0.78 / 1024
            ZStack {
                Circle()
                    .fill(.white)
                    .frame(width: 1024 * s, height: 1024 * s)
                    .shadow(color: .black.opacity(0.22), radius: 18 * s, y: 12 * s)
                Circle()
                    .strokeBorder(BubbleArt.rgb(0xDADCE0), lineWidth: max(0.5, 10 * s))
                    .frame(width: 1024 * s, height: 1024 * s)
                Image(systemName: mode == .undo ? "arrow.uturn.backward" : "sparkles")
                    .font(.system(size: 440 * s, weight: .semibold))
                    .foregroundStyle(BubbleArt.rgb(0x1F1B3A))
                if mode == .working {
                    Circle()
                        .trim(from: 0, to: 0.28)
                        .stroke(BubbleArt.rgb(0x39335F), style: StrokeStyle(lineWidth: size * 0.05, lineCap: .round))
                        .padding(size * 0.035)
                        .rotationEffect(.degrees(turning ? 360 : 0))
                        .onAppear {
                            guard !reduceMotion else { return }
                            withAnimation(.linear(duration: 1).repeatForever(autoreverses: false)) { turning = true }
                        }
                        .onDisappear { turning = false }
                }
            }
            .frame(width: geometry.size.width, height: geometry.size.height)
        }
        .aspectRatio(1, contentMode: .fit)
        .accessibilityHidden(true)
    }
}
