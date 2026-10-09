import SwiftUI
import TFCore

/// Shown when the keyboard opened the app to start a session, with automatic return. While the app is opening the host
/// it reads "Listening." and "Taking you back to WhatsApp…"; on any failure it changes to the swipe-back instructions
/// (naming the app when known) with an animated finger, and keeps listening.
struct SessionScreen: View {
    static let listening = "Listening."

    /// How the screen shows the way back to your app, in its words and its drawing alike.
    enum Way: Equatable {
        /// Swipe right along the bottom edge: an iPhone with a home indicator.
        case swipe
        /// Tap your app's name, which iOS writes at the top left, drawn ringed: an iPhone with a Home button, which has no
        /// such swipe, or VoiceOver on, where a real control is easier to reach than an edge gesture.
        case backLink
        /// The same words with no drawing: an iPad, where this iPhone app's window has neither the bottom edge nor the
        /// top left of the iPad's screen.
        case backLinkNoRing
    }

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
                    if let sub = Self.subLine(status: status, returnTrip: returnTrip, way: .swipe) {
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
                    if Self.showsCue(returnTrip, status: status) {
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

    /// The way back for this screen: the swipe on an iPhone with a home indicator, else iOS's link at the top left, ringed
    /// on an iPhone and only in words on an iPad.
    static func way(homeButton: Bool, voiceOver: Bool, iPad: Bool) -> Way {
        if iPad { return .backLinkNoRing }
        return homeButton || voiceOver ? .backLink : .swipe
    }

    /// The cue shows on every state except while we are actively leaving for the host, and while the mic starts on a
    /// manual return: iOS won't let ThumbFree start the mic from the background, so leaving then could lose the take.
    /// With no trip at all (a successful automatic return resolves it to nil once we leave the foreground), it still
    /// shows while a take or the session is live: nothing to name, but still something to go back to. Never once both
    /// are over.
    static func showsCue(_ trip: ReturnTrip?, status: HostStatus) -> Bool {
        if status.take == .recording && !status.micOn { return false }
        guard trip == nil else { return trip?.phase != .leaving }
        return isLive(status)
    }

    /// The big line. Leaving keeps it short ("Listening.") only once the mic art itself would show listening (the trip
    /// goes `.leaving` in `openLink`, before the mic delivers any audio): until then, the words and the bubble art
    /// must not disagree, so this falls through to the status line ("Starting the microphone").
    static func primaryLine(status: HostStatus, returnTrip: ReturnTrip?) -> String {
        returnTrip?.phase == .leaving && mode(for: status) == .stop ? listening : statusLine(status)
    }

    /// The line under it. Leaving: "Taking you back to WhatsApp…" (the app's name from the table). Swipe-back: the way
    /// back, naming the app when known (only a Debug build's fallback knows it). With no trip at all but a take or the
    /// session still live (a successful automatic return resolves the trip to nil once we leave the foreground), the
    /// generic line: no app name to give, but still something to go back to. Other live-session states, or nothing live
    /// at all, have no sub-line. iOS writes the app's name at the top left only when another app opened ThumbFree, so
    /// the back link's words also give the App Switcher.
    static func subLine(status: HostStatus, returnTrip: ReturnTrip?, way: Way) -> String? {
        if returnTrip?.phase == .leaving, let name = returnTrip?.appName { return String(localized: "Taking you back to \(name)…") }
        guard returnTrip?.phase == .swipeBack || (returnTrip == nil && isLive(status)) else { return nil }
        switch (way, returnTrip?.appName) {
        case (.swipe, let name?): return String(localized: "Swipe right along the bottom edge to go back to \(name).")
        case (.swipe, nil): return String(localized: "Swipe right along the bottom edge to go back.")
        case (_, let name?): return String(localized: "Tap \(name) at the top left to go back, or use the App Switcher.")
        case (_, nil): return String(localized: "Tap your app’s name at the top left to go back, or use the App Switcher.")
        }
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

/// The cue's motion: 3 rounds of 1.65 s, then the still. Each round is one beat of a tap's length (`TapTimeline.length`,
/// 1.3 s) that fades in over 0.12 s and out over its last 0.15 s, then 0.35 s with nothing drawn. In the swipe's beat
/// the ring lands with its ripple, slides right from 0.3 to 0.85 s (eased, its trail behind it) and holds; the back
/// link's ring taps as the guides' do (`TapTimeline`). The still is the beat's end, with a chevron after the swipe.
enum CueTimeline {
    static let rounds = 3
    static let round = 1.65
    static var length: Double { Double(rounds) * round }

    /// One frame of the cue.
    struct Shot: Equatable {
        /// Seconds into the round's beat; the beat's end in the still.
        let beat: Double
        let opacity: Double
        let still: Bool

        /// How far the swipe's ring has slid, 0 to 1; its trail runs from the start to the ring.
        var slide: Double {
            let t = min(max((beat - 0.3) / 0.55, 0), 1)
            return t * t * (3 - 2 * t)
        }

        /// The swipe's ripple as the ring lands, 0 to 1 of its way; nil before and after it.
        var ripple: Double? { (0.06..<0.36).contains(beat) ? (beat - 0.06) / 0.3 : nil }
    }

    /// The frame `time` seconds after the rounds began: nil while nothing is drawn between beats, the still from
    /// `length` on (pass `.infinity` for the still at once: Reduce Motion, VoiceOver).
    static func shot(at time: Double) -> Shot? {
        guard time < length else { return Shot(beat: TapTimeline.length, opacity: 1, still: true) }
        let beat = max(time, 0).truncatingRemainder(dividingBy: round)
        guard beat < TapTimeline.length else { return nil }
        return Shot(beat: beat, opacity: min(beat / 0.12, 1, (TapTimeline.length - beat) / 0.15), still: false)
    }
}
