import SwiftUI
import TFCore
import UIKit

/// Shown when the keyboard opened the app to start a session. While the mic starts it says so and nothing more; then it
/// shows the way back to your app (`Way`): words, and a cue drawn where the gesture happens, which plays 3 rounds, rests
/// on its still, and plays again on a tap on the page. For the swipe the words sit just above the bottom edge, over the
/// cue, End session under the title; elsewhere they sit under the title. With Debug automatic return it reads
/// "Listening." and "Taking you back to WhatsApp…" while the app is opening the host, and falls back to the way back on
/// any failure. It keeps listening throughout. End session is a quiet button: a big one invites a stray tap.
struct SessionScreen: View {
    static let listening = "Listening."

    /// How the screen shows the way back to your app, in its words and its drawing alike.
    enum Way: Equatable {
        /// Swipe right along the bottom edge: an iPhone with a home indicator.
        case swipe
        /// Tap your app's name, which iOS writes at the top left, drawn ringed: an iPhone with a Home button, which has no
        /// such swipe, or VoiceOver on, where a real control is easier to reach than an edge gesture.
        case backLink
        /// The App Switcher, in words only: an iPad, which opens this iPhone app in a window of its own, with no app's
        /// name at the top left and neither of the iPad's edges.
        case appSwitcher
    }

    let status: HostStatus
    let returnTrip: ReturnTrip?
    let onEnd: () -> Void
    /// When the cue's rounds began: when it first showed with the app in front, or the last tap on the page.
    @State private var roundsFrom: Date?
    /// The rounds are over: the cue rests on its still, and its timeline stops.
    @State private var rested = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.accessibilityVoiceOverEnabled) private var voiceOver
    @Environment(\.scenePhase) private var phase

    var body: some View {
        GeometryReader { geo in
            let way = Self.way(homeButton: geo.safeAreaInsets.bottom == 0, voiceOver: voiceOver,
                               iPad: UIDevice.current.model.hasPrefix("iPad"))
            let cue = Self.showsCue(returnTrip, status: status)
            let still = reduceMotion || voiceOver
            // Only a drawn cue that may move plays rounds: none on an iPad, and the still with Reduce Motion or VoiceOver.
            let plays = cue && way != .appSwitcher && !still
            let atEdge = Self.wordsAtEdge(way: way, returnTrip: returnTrip)
            let sub = Self.subLine(status: status, returnTrip: returnTrip, way: way)
            // While the mic starts on a manual return the line keeps its room, unseen and unheard, so nothing moves when it
            // shows with the cue.
            let waits = !cue && returnTrip?.phase != .leaving
            // The cue's room at the bottom, outside the scrolling page: words never scroll through the arrow's drop.
            let room = atEdge ? max(0, GoBackCue.room - geo.safeAreaInsets.bottom) : 0
            VStack(spacing: 0) {
                ScrollView {
                    VStack(spacing: 0) {
                        Spacer()
                        VStack(spacing: 14) {
                            BubbleArt(mode: Self.mode(for: status)).frame(width: 104, height: 104)
                            Text(Self.testTitle ?? Self.primaryLine(status: status, returnTrip: returnTrip))
                                .font(.title2.weight(.semibold))
                                .accessibilityIdentifier("session.title")
                            if !atEdge, let sub { wayLine(sub, atEdge: false, waits: waits) }
                            if let line = Self.firstReturnLine(returnTrip: returnTrip) {
                                Text(line).font(.footnote).foregroundStyle(Theme.inkSoft)
                            }
                            if atEdge { QuietActionButton(title: "End session", id: "session.end", action: onEnd) }
                        }
                        .multilineTextAlignment(.center)
                        .frame(maxWidth: 320)
                        Spacer()
                        if atEdge {
                            // Part of the page, so at the largest text sizes the line grows upward and the page scrolls.
                            if let sub { wayLine(sub, atEdge: true, waits: waits) }
                        } else {
                            QuietActionButton(title: "End session", id: "session.end", action: onEnd).padding(.bottom, 26)
                        }
                    }
                    .padding(.horizontal, 24)
                    .frame(minHeight: geo.size.height - room)
                    .contentShape(.rect)
                    .onTapGesture(perform: replay)
                }
                .accessibilityIdentifier("session.page")
                if room > 0 { Color.clear.frame(height: room).contentShape(.rect).onTapGesture(perform: replay) }
            }
            .overlay {
                if cue, way != .appSwitcher {
                    let insets = geo.safeAreaInsets
                    // Nothing redraws once it rests, while the app is away, or before its rounds begin.
                    TimelineView(.animation(paused: still || rested || roundsFrom == nil || phase != .active)) { context in
                        let time = roundsFrom.map { context.date.timeIntervalSince($0) } ?? 0
                        let at = still || rested ? .infinity : time
                        GoBackCue(way: way, shot: CueTimeline.shot(at: at, way: way),
                                  arrow: way == .swipe ? CueTimeline.arrow(at: at) : nil,
                                  screen: CGSize(width: geo.size.width + insets.leading + insets.trailing,
                                                 height: geo.size.height + insets.top + insets.bottom),
                                  top: insets.top)
                    }
                    .ignoresSafeArea()
                }
            }
            // The rounds begin once a cue that plays shows with the app in front: once the mic is on, or after a fallback
            // or a prompt that came late, never while the screen waited.
            .onChange(of: plays && phase == .active, initial: true) { _, live in
                if live, roundsFrom == nil { roundsFrom = .now }
            }
            .task(id: roundsFrom) {
                guard let roundsFrom else { return }
                rested = false
                try? await Task.sleep(for: .seconds(max(0, CueTimeline.length(for: way) - Date.now.timeIntervalSince(roundsFrom))))
                if !Task.isCancelled { rested = true } // a tap meanwhile began new rounds
            }
        }
        .foregroundStyle(Theme.ink)
        .background(Theme.paper)
    }

    /// A tap on the page plays the cue's rounds again, once they have begun.
    private func replay() { if roundsFrom != nil { roundsFrom = .now } }

    /// UI tests (Debug builds): `-TFScreenTitle <text>` takes the title's place, as a long status message does, so a test
    /// can overflow the page. Never so in a Release build.
    private static var testTitle: String? {
        #if DEBUG
        UserDefaults.standard.string(forKey: "TFScreenTitle")
        #else
        nil
        #endif
    }

    /// The way-back line, under the title or at the edge: there in ink and semibold, left-aligned over the spot where the
    /// ring lands, its last two words kept together.
    private func wayLine(_ text: String, atEdge: Bool, waits: Bool) -> some View {
        Text(atEdge ? text.keepingLastWordsTogether : text)
            .font(atEdge ? .body.weight(.semibold) : .body)
            .foregroundStyle(atEdge ? Theme.ink : Theme.inkSoft)
            .multilineTextAlignment(atEdge ? .leading : .center)
            .frame(maxWidth: atEdge ? .infinity : nil, alignment: .leading)
            .padding(.horizontal, atEdge ? 4 : 0)
            .opacity(waits ? 0 : 1)
            .animation(.easeOut(duration: 0.2), value: waits)
            .accessibilityLabel(text)
            .accessibilityHidden(waits)
            .accessibilityIdentifier("session.subtitle")
    }

    /// The way back for this screen: the swipe on an iPhone with a home indicator, else iOS's link at the top left, ringed;
    /// on an iPad, the App Switcher.
    /// The way-back line sits just above the bottom edge, End session in the middle, only on the swipe's manual return:
    /// leaving for the app, the back link and the iPad keep the line under the title.
    static func wordsAtEdge(way: Way, returnTrip: ReturnTrip?) -> Bool { way == .swipe && returnTrip?.phase != .leaving }

    static func way(homeButton: Bool, voiceOver: Bool, iPad: Bool) -> Way {
        if iPad { return .appSwitcher }
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
    /// the back link's words also give the App Switcher; an iPad never writes it, so there the words give only that.
    static func subLine(status: HostStatus, returnTrip: ReturnTrip?, way: Way) -> String? {
        if returnTrip?.phase == .leaving, let name = returnTrip?.appName { return String(localized: "Taking you back to \(name)…") }
        guard returnTrip?.phase == .swipeBack || (returnTrip == nil && isLive(status)) else { return nil }
        switch (way, returnTrip?.appName) {
        case (.swipe, let name?): return String(localized: "Swipe right along the bottom edge to go back to \(name).")
        case (.swipe, nil): return String(localized: "Swipe right along the bottom edge to go back.")
        case (.appSwitcher, _): return String(localized: "Use the App Switcher to go back to your app.")
        case (.backLink, let name?): return String(localized: "Tap \(name) at the top left to go back, or use the App Switcher.")
        case (.backLink, nil): return String(localized: "Tap your app’s name at the top left to go back, or use the App Switcher.")
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

/// The way back drawn where it happens, in screen points: an arrow dropping onto the bottom edge, then the swipe along
/// the home indicator's line, or a ring around the name iOS writes at the top left. It takes no touches, and VoiceOver reads it as one picture the
/// size of the drawing.
private struct GoBackCue: View {
    let way: SessionScreen.Way
    /// The ring's frame to draw; nil while the arrow drops and between rounds, when it doesn't show.
    let shot: CueTimeline.Shot?
    /// The swipe's arrow; nil while the ring swipes and between rounds.
    let arrow: CueTimeline.Arrow?
    /// The whole screen, its safe areas included.
    let screen: CGSize
    /// The top safe area, which tells the screen's shape, and so where iOS writes its link.
    let top: CGFloat

    var body: some View {
        let bounds = way == .swipe ? swipeBand : Self.backLinkRing(top: top)
        ZStack(alignment: .topLeading) {
            Color.clear
            if let arrow {
                Image(systemName: "chevron.down")
                    .font(.system(size: 30, weight: .heavy))
                    .foregroundStyle(Theme.ink)
                    .shadow(color: Theme.sunflower.opacity(0.9), radius: 6)
                    .position(x: x0, y: y - 46 - arrow.lift) // at rest just over the spot where the ring lands
                    .opacity(arrow.opacity)
                    .accessibilityHidden(true)
            }
            if let shot {
                ZStack(alignment: .topLeading) {
                    if way == .swipe { swipe(shot) } else { ring(shot) }
                }
                .opacity(shot.opacity)
                .accessibilityHidden(true)
            }
            Color.clear
                .frame(width: bounds.width, height: bounds.height)
                .position(x: bounds.midX, y: bounds.midY)
                .accessibilityElement()
                .accessibilityLabel(way == .swipe
                                    ? Text("Picture: an arrow pointing down at the bottom edge, then a finger swiping right along it.")
                                    : Text("Picture: your app’s name at the top left."))
                .accessibilityAddTraits(.isImage)
                .accessibilityIdentifier("session.cue")
        }
        .allowsHitTesting(false)
    }

    /// The ring around the name iOS writes at the top left when another app opened ThumbFree ("◀ Messages"), wide enough
    /// for most names: in the 20-point status bar of an iPhone with a Home button, where the Wi-Fi symbol follows the
    /// name, else under the clock (a notch and a Dynamic Island alike).
    /// ponytail: fixed numbers measured on the iOS 26.5 Simulators (iPhone SE 3rd generation, iPhone 14, iPhone 17 Pro);
    /// look again after each iOS release.
    static func backLinkRing(top: CGFloat) -> CGRect {
        top <= 20 ? CGRect(x: 2, y: 0, width: 80, height: 20) : CGRect(x: 6, y: 27, width: 80, height: 24)
    }

    /// The home indicator's line, 12 points above the bottom, and the swipe's run along it, from 30 % to 82 % of the width.
    private var y: CGFloat { screen.height - 12 }
    private var x0: CGFloat { screen.width * 0.3 }
    private var x1: CGFloat { screen.width * 0.82 }

    /// How far up from the screen's bottom the swipe's drawing reaches: the arrow's drop, over the ring. The way-back line
    /// sits just above it.
    static let room: CGFloat = 128

    /// What the swipe covers: the arrow's drop, its trail, the ring with the pill above it and the chevron after it, down
    /// to the bottom.
    private var swipeBand: CGRect { CGRect(x: x0 - 27, y: screen.height - Self.room, width: x1 - x0 + 73, height: Self.room) }

    /// The ring lands on the line, slides right leaving a sunflower trail, and in the still has a chevron after it.
    @ViewBuilder private func swipe(_ shot: CueTimeline.Shot) -> some View {
        let x = x0 + (x1 - x0) * shot.slide
        Capsule().fill(Theme.sunflower.opacity(0.55))
            .frame(width: x - x0 + 8, height: 8)
            .position(x: (x0 + x) / 2, y: y)
        TapCue(outline: Capsule(), gesture: "Swipe", ripple: shot.ripple)
            .frame(width: 46, height: 22)
            .position(x: x, y: y)
        if shot.still {
            Image(systemName: "chevron.forward")
                .font(.system(size: 15, weight: .heavy))
                .foregroundStyle(Theme.cueEdge)
                .position(x: x + 38, y: y)
        }
    }

    /// The guides' tap on iOS's link: the ripple and a soft press, with a Tap pill under the ring.
    @ViewBuilder private func ring(_ shot: CueTimeline.Shot) -> some View {
        let ring = Self.backLinkRing(top: top)
        Capsule().fill(Theme.sunflower.opacity(0.28 * TapTimeline.press(at: shot.beat)))
            .frame(width: ring.width, height: ring.height)
            .position(x: ring.midX, y: ring.midY)
        TapCue(outline: Capsule(), ripple: TapTimeline.ripple(at: shot.beat), showsPill: false)
            .frame(width: ring.width, height: ring.height)
            .position(x: ring.midX, y: ring.midY)
        CuePill(text: "Tap").fixedSize().position(x: ring.midX, y: ring.maxY + 8 + CuePill.height / 2)
    }
}

/// The cue's motion: 3 rounds, then the still. The ring's beat is a tap's length (`TapTimeline.length`, 1.3 s), fading
/// in over 0.12 s and out over its last 0.15 s, then 0.35 s with nothing drawn; the swipe's round opens with the arrow's
/// beat of the same length. In the swipe's beat the ring lands with its ripple, slides right from 0.3 to 0.85 s (eased,
/// its trail behind it) and holds; the back link's ring taps as the guides' do (`TapTimeline`). The still is the beat's
/// end: the arrow at rest, and a chevron after the swipe.
enum CueTimeline {
    static let rounds = 3

    /// A round: the arrow's beat (the swipe's only), the ring's beat and the rest, 1.3 + 1.3 + 0.35 or 1.3 + 0.35 s.
    static func round(for way: SessionScreen.Way) -> Double { way == .swipe ? 2.95 : 1.65 }

    /// The rounds' length, after which the still shows.
    static func length(for way: SessionScreen.Way) -> Double { way == .swipe ? 8.85 : 4.95 }

    /// One frame of the ring.
    struct Shot: Equatable {
        /// Seconds into the ring's beat; the beat's end in the still.
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

    /// One frame of the swipe's arrow.
    struct Arrow: Equatable {
        /// How far above its rest the arrow is, in points.
        let lift: Double
        let opacity: Double
    }

    /// The ring's frame `time` seconds after the rounds began: nil while the arrow drops or nothing is drawn between
    /// beats, the still from the rounds' length on (pass `.infinity` for the still at once: Reduce Motion, VoiceOver).
    static func shot(at time: Double, way: SessionScreen.Way) -> Shot? {
        guard time < length(for: way) else { return Shot(beat: TapTimeline.length, opacity: 1, still: true) }
        let lead = way == .swipe ? TapTimeline.length : 0
        let beat = max(time, 0).truncatingRemainder(dividingBy: round(for: way)) - lead
        guard (0..<TapTimeline.length).contains(beat) else { return nil }
        return Shot(beat: beat, opacity: fade(beat), still: false)
    }

    /// The swipe's arrow `time` seconds after the rounds began: in each round's first beat, else nil; at rest in the
    /// still.
    static func arrow(at time: Double) -> Arrow? {
        guard time < length(for: .swipe) else { return Arrow(lift: 0, opacity: 1) }
        let beat = max(time, 0).truncatingRemainder(dividingBy: round(for: .swipe))
        guard beat < TapTimeline.length else { return nil }
        return Arrow(lift: lift(beat), opacity: fade(beat))
    }

    private static func fade(_ beat: Double) -> Double { min(beat / 0.12, 1, (TapTimeline.length - beat) / 0.15) }

    /// How far above its rest the arrow is `beat` seconds in: 50 points until 0.12 s, falling to rest by 0.42 s, then a
    /// hop of 16 points that lands by 0.78 s and one of 6 that settles at 1 s.
    private static func lift(_ beat: Double) -> Double {
        func ease(_ a: Double, _ b: Double, _ from: Double, _ to: Double, falling: Bool) -> Double {
            let t = min(max((beat - a) / (b - a), 0), 1)
            return from + (to - from) * (falling ? t * t : 1 - (1 - t) * (1 - t))
        }
        switch beat {
        case ..<0.12: return 50
        case ..<0.42: return ease(0.12, 0.42, 50, 0, falling: true)
        case ..<0.6: return ease(0.42, 0.6, 0, 16, falling: false)
        case ..<0.78: return ease(0.6, 0.78, 16, 0, falling: true)
        case ..<0.89: return ease(0.78, 0.89, 0, 6, falling: false)
        case ..<1: return ease(0.89, 1, 6, 0, falling: true)
        default: return 0
        }
    }
}
