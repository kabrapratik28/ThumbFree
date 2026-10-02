import SwiftUI
import TFCore

/// Shown when the keyboard opened the app to start a session, with automatic return. While the app is opening the host
/// it reads "Listening." and "Taking you back to WhatsApp…"; on any failure it changes to the swipe-back instructions
/// (naming the app when known) with an animated finger, and keeps listening.
struct SessionScreen: View {
    static let listening = "Listening."

    let status: HostStatus
    let returnTrip: ReturnTrip?
    let onEnd: () -> Void
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.accessibilityVoiceOverEnabled) private var voiceOver

    var body: some View {
        GeometryReader { geo in
            ScrollView {
                VStack(spacing: 20) {
                    Spacer()
                    BubbleArt(mode: Self.mode(for: status)).frame(width: 140, height: 140)
                    Text(Self.primaryLine(status: status, returnTrip: returnTrip))
                        .font(.title2.weight(.semibold))
                        .multilineTextAlignment(.center)
                        .accessibilityIdentifier("session.title")
                    if let sub = Self.subLine(status: status, returnTrip: returnTrip) {
                        Text(sub)
                            .font(.body)
                            .foregroundStyle(Theme.inkSoft)
                            .multilineTextAlignment(.center)
                            .accessibilityIdentifier("session.subtitle")
                    }
                    if let line = Self.firstReturnLine(returnTrip: returnTrip) {
                        Text(line)
                            .font(.footnote)
                            .foregroundStyle(Theme.inkSoft)
                            .multilineTextAlignment(.center)
                    }
                    if Self.showsSwipeHint(returnTrip, status: status) {
                        SwipeBackHint(reduceMotion: reduceMotion || voiceOver).frame(height: 56).padding(.top, 4)
                    }
                    Spacer()
                    Button(action: onEnd) {
                        Text("End session").font(.headline).frame(maxWidth: .infinity).padding(.vertical, 8)
                    }
                    .buttonStyle(.borderedProminent)
                    .buttonBorderShape(.capsule)
                    .tint(Theme.primary)
                    .foregroundStyle(Theme.onPrimary)
                    .accessibilityIdentifier("session.end")
                }
                .padding()
                .frame(minHeight: geo.size.height)
            }
        }
        .foregroundStyle(Theme.ink)
        .background(Theme.paper)
    }

    /// The swipe hint shows on every state except while we are actively leaving for the host. With no trip at all (a
    /// successful automatic return resolves it to nil once we leave the foreground), it still shows while a take or the
    /// session is live: nothing to name, but still something to swipe back to. Never once both are over.
    static func showsSwipeHint(_ trip: ReturnTrip?, status: HostStatus) -> Bool {
        guard trip == nil else { return trip?.phase != .leaving }
        return isLive(status)
    }

    /// The big line. Leaving keeps it short ("Listening.") only once the mic art itself would show listening (the trip
    /// goes `.leaving` in `openLink`, before the mic delivers any audio): until then, the words and the bubble art
    /// must not disagree, so this falls through to the status line ("Starting the microphone").
    static func primaryLine(status: HostStatus, returnTrip: ReturnTrip?) -> String {
        returnTrip?.phase == .leaving && mode(for: status) == .stop ? listening : statusLine(status)
    }

    /// The line under it. Leaving: "Taking you back to WhatsApp…" (the app's name from the table). Swipe-back: the swipe
    /// instructions, naming the app when known. With no trip at all but a take or the session still live (a successful
    /// automatic return resolves the trip to nil once we leave the foreground), the generic swipe-back line: no app name
    /// to give, but still something to swipe back to. Other live-session states, or nothing live at all, have no sub-line.
    static func subLine(status: HostStatus, returnTrip: ReturnTrip?) -> String? {
        if returnTrip?.phase == .leaving, let name = returnTrip?.appName { return "Taking you back to \(name)…" }
        if returnTrip == nil { return isLive(status) ? "Swipe right along the bottom edge to go back to your app." : nil }
        guard returnTrip?.phase == .swipeBack else { return nil }
        if let name = returnTrip?.appName { return "Swipe right along the bottom edge to go back to \(name)." }
        return "Swipe right along the bottom edge to go back to your app."
    }

    /// The first time ThumbFree takes you back to an app, iOS may ask first: this line says to tap Open. Only while leaving
    /// for that app, never on the swipe-back screen (after a miss, the fallback or a Cancel, there is nothing to tap).
    static func firstReturnLine(returnTrip: ReturnTrip?) -> String? {
        returnTrip?.phase == .leaving && returnTrip?.firstReturn == true ? "If iOS asks, tap Open. It asks once." : nil
    }

    /// The status-driven big line for the swipe-back screen (the live-session states, as before).
    private static func statusLine(_ status: HostStatus) -> String {
        if let message = status.message { return message }
        switch status.take {
        case .recording: return status.micOn ? listening : "Starting the microphone"
        case .stopping, .transcribing, .delivering: return "Transcribing"
        case .idle: return "Ready, mic on."
        }
    }

    /// The mic art for the app's status: the keyboard's red stop key only while listening, the turning arc while the
    /// take is transcribed.
    static func mode(for status: HostStatus) -> BubbleArt.Mode {
        switch status.take {
        case .recording: status.micOn ? .stop : .idle
        case .stopping, .transcribing, .delivering: .busy
        case .idle: .idle
        }
    }

    /// A take recording (or being transcribed), or the session still open for one (the hot-mic window after a take, before
    /// the idle timeout closes it): there is still something to swipe back to, even with no trip to name it.
    private static func isLive(_ status: HostStatus) -> Bool { status.take != .idle || status.session != .off }
}

/// The swipe-back hint: the guides' swipe cue sliding right along the bottom edge. Still at the end of the swipe when
/// Reduce Motion or VoiceOver is on. Hidden from VoiceOver's own navigation either way (the sub-line already tells the
/// user what to do).
private struct SwipeBackHint: View {
    let reduceMotion: Bool

    var body: some View {
        GeometryReader { geo in
            let travel = geo.size.width * 0.5
            let y = geo.size.height / 2
            if reduceMotion {
                cue.position(x: geo.size.width / 2 + travel / 2, y: y)
            } else {
                KeyframeAnimator(initialValue: 0.0, repeating: true) { x in
                    cue.position(x: geo.size.width / 2 - travel / 2 + travel * x, y: y)
                } keyframes: { _ in
                    LinearKeyframe(0.0, duration: 0.3)
                    LinearKeyframe(1.0, duration: 1.2)
                    LinearKeyframe(1.0, duration: 0.6)
                }
            }
        }
        .accessibilityHidden(true)
    }

    private var cue: some View { TapCue(outline: Capsule(), gesture: "Swipe").frame(width: 44, height: 26) }
}
