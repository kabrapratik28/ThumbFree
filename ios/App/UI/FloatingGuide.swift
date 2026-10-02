import AVKit
import SwiftUI
import os
import TFCore

/// The keyboard step's guide as a short silent video that keeps playing in a small floating window (Picture in Picture)
/// over Settings, so nobody has to remember the taps there. Its five beats, 1.3 s each, are drawn with the step's own
/// pieces (`GuideFrame`: the Settings row and switch, the tap cue) and made into a video on the iPhone (`video(_:in:)`).
/// This object plays it on a loop and floats it: Open Settings starts the window, then opens Settings; back in the app by
/// any route, or off the step, it closes.
@MainActor final class FloatingGuide: NSObject {
    /// A frame in points, the keyboard step's drawing area on an iPhone 17 Pro at its tallest, so the step shows the video
    /// at its own size; the video is three times that in pixels (966 x 822, even sizes for H.264), sharp on every iPhone's
    /// screen.
    static let size = CGSize(width: 322, height: 274)
    static let scale: CGFloat = 3
    /// Raised whenever the frames change, so a video made by an older version is never reused (the file name also has
    /// the app's build number, so an update never plays an old video).
    static let version = 4
    /// Seconds per beat: one tap's time.
    static let beat = TapTimeline.length
    /// Frames a second while a tap's ripple grows and its switch slides, so nothing steps.
    static let fps = 30.0
    static let captions = [
        String(localized: "Tap Keyboards."),
        String(localized: "Turn on ThumbFree."),
        String(localized: "Turn on Allow Full Access."),
        String(localized: "When iOS asks, tap Allow."),
        String(localized: "Tap ThumbFree at the top left to return."),
    ]
    /// The beat that names iOS's question: words and a shield, nothing to tap in the picture.
    static let allowBeat = 3

    /// What the keyboard step shows: a short list of the rows with Reduce Motion or VoiceOver (nothing moves), one still
    /// frame while paused for screenshots, else the video.
    enum Look: Equatable { case cards, still, video }

    nonisolated static func look(reduceMotion: Bool, voiceOver: Bool, paused: Bool) -> Look {
        reduceMotion || voiceOver ? .cards : paused ? .still : .video
    }

    /// The guide floats over Settings only while its video plays, and only where iOS has Picture in Picture.
    nonisolated static func floats(reduceMotion: Bool, voiceOver: Bool, paused: Bool, supported: Bool) -> Bool {
        supported && look(reduceMotion: reduceMotion, voiceOver: voiceOver, paused: paused) == .video
    }

    /// What decides how the step shows the guide (its look, the text size): the live value in front, and while the app is
    /// away the one held from its last moment in front, since a Reduce Motion, VoiceOver or text size change made in
    /// Settings would swap the video for the list and close the window. Never in front yet, the live value.
    nonisolated static func shown<T>(live: T, held: T?, active: Bool) -> T { active ? live : held ?? live }

    /// One frame of the video: its beat, the moment of the beat's tap it shows (`TapTimeline`), and how long it holds,
    /// in seconds.
    struct Shot: Equatable {
        let beat: Int
        var time = TapTimeline.length
        var hold = 0.0
    }

    /// The frames, each beat's adding up to `beat`: the cue held, then the ripple and the change at `fps`, then the result
    /// held. The beat that names iOS's question has no tap: one frame.
    static let shots: [Shot] = captions.indices.flatMap { beat -> [Shot] in
        guard beat != allowBeat else { return [Shot(beat: beat, hold: FloatingGuide.beat)] }
        let frames = Int(((TapTimeline.changeEnd - TapTimeline.rippleStart) * fps).rounded())
        let moves = (0..<frames).map { Shot(beat: beat, time: TapTimeline.rippleStart + Double($0) / fps, hold: 1 / fps) }
        return [Shot(beat: beat, time: 0, hold: TapTimeline.rippleStart)] + moves
            + [Shot(beat: beat, time: TapTimeline.changeEnd, hold: TapTimeline.length - TapTimeline.changeEnd)]
    }

    /// The frame shown while paused for screenshots: Allow Full Access turned on, or `-TFGuideBeat`'s beat.
    static var still: Shot {
        Shot(beat: GuideView.holdsBeat ? GuideView.heldBeat(count: captions.count) : 2)
    }

    /// Open Settings' start of the floating window, from the tap until Settings opens: Settings opens once (when the
    /// window floats, when it cannot, or at the deadline 1 s after the tap), and a session mixed with others gets one
    /// more try without mixing (when iOS refuses it, or still cannot float the window 0.4 s after it was asked for, before
    /// the deadline: `marks`). Before Settings opens without the window, the audio session is let go, so the person's
    /// music goes on. A window still on its way at the deadline keeps the session while Settings opens, but only until
    /// it fails or 1 s more has passed without it starting: a window that never starts never keeps the session for the
    /// Settings visit.
    struct Launch: Equatable {
        /// What happened: iOS will start the window, started it, failed to, still cannot after 0.4 s, 1 s passed, or 1 s
        /// more passed after Settings opened.
        enum Event { case willStart, started, failed, stillImpossible, deadline, graceOver }
        enum Action: Equatable { case wait, retryAlone, open, letGoAndOpen, letGo }

        /// The audio session is mixed with others: not with the mic's own session, nor after the retry.
        var mixing: Bool
        var retried = false
        var starting = false
        var started = false
        /// Settings is open.
        var done = false

        /// Settings opened while the window was on its way: its start or failure, or the grace's end, is still to come.
        var waitsForWindow: Bool { done && starting && !started }

        mutating func on(_ event: Event) -> Action {
            if done {
                guard waitsForWindow else { return .wait }
                switch event {
                case .started:
                    started = true
                    return .wait
                case .failed, .graceOver:
                    starting = false
                    return .letGo
                default:
                    return .wait
                }
            }
            switch event {
            case .willStart:
                starting = true
                return .wait
            case .started:
                started = true
                done = true
                return .open
            case .failed, .stillImpossible:
                if event == .failed { starting = false }
                if mixing, !retried {
                    mixing = false
                    retried = true
                    return .retryAlone
                }
                guard event == .failed else { return .wait } // still impossible alone: the deadline decides
                done = true
                return .letGoAndOpen
            case .deadline:
                done = true
                return starting ? .open : .letGoAndOpen // a window on its way keeps its session, for the grace only
            case .graceOver:
                return .wait
            }
        }
    }

    /// Open Settings tapped before the video's controller is made (the video is still being made): the tap is kept, up
    /// to 1 s. The controller first, the kept tap starts the window as a tap would now (`Launch`); the deadline first,
    /// Settings opens without the window. Either way once: a second tap while one is kept does nothing more, and back in
    /// the app or off the step (`land()`) it is dropped.
    struct Kept {
        enum Event { case tap, controller, deadline }
        enum Action: Equatable { case wait, keep, float, open }

        /// A tap waits for the controller.
        private(set) var waiting = false

        mutating func on(_ event: Event) -> Action {
            switch event {
            case .tap:
                if waiting { return .wait }
                waiting = true
                return .keep
            case .controller, .deadline:
                guard waiting else { return .wait }
                waiting = false
                return event == .controller ? .float : .open
            }
        }
    }

    /// Settings opens at the latest this long after the Open Settings tap, a tap kept for the controller included.
    nonisolated static let opensWithin = Duration.seconds(1)

    /// Open Settings' marks, from the tap: the deadline (`opensWithin`), however late the controller came for a kept tap,
    /// and the grace's end 1 s after it; the try without mixing 0.4 s after the window is first asked for (at the tap, or
    /// when the late controller came), only when that is before the deadline.
    nonisolated static func marks(tapped: ContinuousClock.Instant, asked: ContinuousClock.Instant)
        -> (retry: ContinuousClock.Instant?, deadline: ContinuousClock.Instant, grace: ContinuousClock.Instant) {
        let deadline = tapped + opensWithin, retry = asked + .milliseconds(400)
        return (retry < deadline ? retry : nil, deadline, deadline + .seconds(1))
    }

    /// The audio session is dictation's from the moment a session starts, before its audio flows: the guide never changes
    /// it then.
    nonisolated static func leavesAudio(_ status: HostStatus) -> Bool { status.micOn || status.session != .off }

    /// The guide's path (play, float, the window's start, failure and stop, a view going) logs at notice or error, which
    /// iOS keeps, so a phone's log shows why the window did or did not float. Numbers, booleans and error codes only.
    fileprivate static let log = Logger(subsystem: Brand.bundleID, category: "guide")

    private var player: AVQueuePlayer?
    private var looper: AVPlayerLooper?
    private var pip: AVPictureInPictureController?
    /// Watches whether iOS can float the window, so Open Settings starts it the moment it can.
    private var possible: NSKeyValueObservation?
    /// Open Settings' start of the window while it is under way, its Settings, and its marks (`marks`); before the
    /// controller is made, the kept tap's Settings and deadline.
    private var launch: Launch?
    private var opening: (() -> Void)?
    private var timer: Task<Void, Never>?
    /// Open Settings tapped before the controller was made (`Kept`): the status it was tapped with, and when.
    private var kept = Kept()
    private var keptTap = (status: HostStatus(), at: ContinuousClock.now)
    /// The window was asked to start with the audio session as it is now.
    private var asked = false
    /// The options the guide made the audio session active with; nil while it has not.
    private var audio: AVAudioSession.CategoryOptions?
    /// The app has left the front: coming back closes the window.
    private var away = false

    /// Plays `url` on a loop, muted, in `layer`; with `floats`, ready to float it over other apps. A view the page makes
    /// again takes the guide over from the one before (`again` in the log).
    func play(_ url: URL, in layer: AVPlayerLayer, floats: Bool) {
        let again = player != nil
        let player = AVQueuePlayer()
        player.isMuted = true
        player.preventsDisplaySleepDuringVideoPlayback = false // a guide on a loop never keeps the screen on
        looper = AVPlayerLooper(player: player, templateItem: AVPlayerItem(url: url))
        layer.player = player
        layer.videoGravity = .resizeAspect
        player.play()
        self.player = player
        let pip = floats ? AVPictureInPictureController(playerLayer: layer) : nil
        Self.log.notice("Guide: play, again \(again, privacy: .public), floats \(floats, privacy: .public), controller \(pip != nil, privacy: .public)")
        guard let pip else { return }
        pip.canStartPictureInPictureAutomaticallyFromInline = true
        pip.requiresLinearPlayback = true // no skip buttons on a 5 s loop
        pip.delegate = self
        possible = pip.observe(\.isPictureInPicturePossible, options: [.new]) { @Sendable [weak self] _, change in
            guard change.newValue == true else { return }
            Task { @MainActor in self?.ask() }
        }
        self.pip = pip
        take(.controller) // an Open Settings tapped while the video was being made floats it now
    }

    /// Open Settings, tapped at `tapped`: the window floats first when it can, then `open` runs, at the latest 1 s after
    /// the tap (`Launch`, `marks`). Picture in Picture needs an active audio session that can play: the guide's own, mixed
    /// with others first, so the person's music keeps playing. While a dictation session starts or runs (`leavesAudio`),
    /// its session is left alone: it plays already once the mic is on. A second tap while one is under way does nothing
    /// more. Only where the guide floats (`floats`): a tap before the video's controller is made waits for it (`Kept`),
    /// which then has only the time left.
    func float(_ status: HostStatus, tapped: ContinuousClock.Instant = .now, then open: @escaping () -> Void) {
        guard let pip else {
            Self.log.notice("Guide: float, controller false")
            guard kept.on(.tap) == .keep else { return }
            keptTap = (status, tapped)
            opening = open
            timer = Task {
                try? await Task.sleep(until: tapped + Self.opensWithin)
                guard !Task.isCancelled else { return }
                take(.deadline) // Settings opens without the window
            }
            return
        }
        guard launch == nil else { return }
        let leaves = Self.leavesAudio(status)
        launch = Launch(mixing: !leaves)
        opening = open
        asked = false
        if !leaves { setAudio(.mixWithOthers) }
        Self.log.notice("Guide: float, possible \(pip.isPictureInPicturePossible, privacy: .public)")
        ask()
        let marks = Self.marks(tapped: tapped, asked: .now)
        timer = Task {
            if let retry = marks.retry {
                try? await Task.sleep(until: retry)
                guard !Task.isCancelled else { return }
                if self.pip?.isPictureInPicturePossible != true { handle(.stillImpossible) }
            }
            try? await Task.sleep(until: marks.deadline)
            guard !Task.isCancelled else { return }
            handle(.deadline) // Settings opens anyway
            try? await Task.sleep(until: marks.grace)
            guard !Task.isCancelled else { return }
            handle(.graceOver) // a window still not started lets the session go
        }
    }

    /// Starts the window for Open Settings once iOS can float it, once per audio session setting: iOS promises an
    /// answer only to a start made while it is possible.
    private func ask() {
        guard let launch, !launch.done, !asked, let pip, pip.isPictureInPicturePossible else { return }
        asked = true
        pip.startPictureInPicture()
    }

    private func handle(_ event: Launch.Event) {
        guard var launch else { return }
        let action = launch.on(event)
        // Kept while Settings is open with the window on its way, for its start, its failure or the grace's end.
        self.launch = launch.done && !launch.waitsForWindow ? nil : launch
        if self.launch == nil { timer?.cancel() }
        switch action {
        case .wait: break
        case .retryAlone:
            setAudio([])
            asked = false
            ask()
        case .open: openNow()
        case .letGoAndOpen:
            setAudio(nil)
            openNow()
        case .letGo: setAudio(nil)
        }
    }

    /// The kept tap's end: the controller is made, and the window floats for the tap in the time left of its 1 s; or the
    /// 1 s has passed, and Settings opens without the window.
    private func take(_ event: Kept.Event) {
        switch kept.on(event) {
        case .float:
            timer?.cancel()
            guard let open = opening else { return }
            opening = nil
            float(keptTap.status, tapped: keptTap.at, then: open)
        case .open: openNow()
        case .wait, .keep: break
        }
    }

    /// Follows the app: back in front after leaving it, the window closes, and the video plays inline again from its
    /// first beat, even if it was paused with the window's own pause button.
    func scene(_ phase: ScenePhase) {
        if phase == .background { away = true }
        guard phase == .active, away else { return }
        away = false
        land()
        player?.seek(to: .zero) // back from Settings, the guide starts again at its first beat
        player?.play()
    }

    /// The window closes and the audio session is let go, so music paused for it goes on. A start still on its way, or a
    /// tap kept for the controller, is dropped too: back in the app, its timer must not open Settings or pause music
    /// later, and the next tap starts anew.
    func land() {
        timer?.cancel()
        launch = nil
        kept = Kept()
        opening = nil
        pip?.stopPictureInPicture()
        setAudio(nil)
    }

    /// Off the step: the video stops and the guide lets go of the window and the audio session. A layer the guide no
    /// longer plays in (a view the page made again, gone after the new one began) only stops its own player.
    func stop(_ layer: AVPlayerLayer) {
        let current = layer.player === player
        Self.log.notice("Guide: stop, current \(current, privacy: .public)")
        guard current else {
            layer.player?.pause()
            return
        }
        player?.pause()
        land()
        possible = nil
        pip = nil
        looper = nil
        player = nil
    }

    private func openNow() {
        opening?()
        opening = nil
    }

    /// Makes the audio session active for playback with `options`, or with nil inactive again, if the guide made it so
    /// and it is still the guide's.
    private func setAudio(_ options: AVAudioSession.CategoryOptions?) {
        let session = AVAudioSession.sharedInstance()
        do {
            if let options {
                try session.setCategory(.playback, mode: .moviePlayback, options: options)
                try session.setActive(true)
            } else if audio != nil, session.category == .playback {
                // The session is shared: once the mic has taken it (.playAndRecord, a dictate link's session started
                // meanwhile), making it inactive would stop the mic's engine and end that session. The guide then only
                // forgets it made the session active.
                try session.setActive(false, options: .notifyOthersOnDeactivation)
            }
            audio = options
        } catch {
            Self.log.error("Guide: audio session \(options?.rawValue ?? 0, privacy: .public) failed: \((error as NSError).code, privacy: .public)")
        }
    }
}

extension FloatingGuide: @preconcurrency AVPictureInPictureControllerDelegate {
    func pictureInPictureControllerWillStartPictureInPicture(_ controller: AVPictureInPictureController) {
        handle(.willStart)
    }

    func pictureInPictureControllerDidStartPictureInPicture(_ controller: AVPictureInPictureController) {
        Self.log.notice("Guide: window started, mixed with others \(self.audio == .mixWithOthers, privacy: .public)")
        handle(.started)
    }

    /// Refused while mixed with others: iOS may float the window only for a session that plays alone, so `Launch`
    /// tries once more that way before Settings opens without it.
    func pictureInPictureController(_ controller: AVPictureInPictureController, failedToStartPictureInPictureWithError error: Error) {
        Self.log.error("Guide: window failed: \((error as NSError).domain, privacy: .public) \((error as NSError).code, privacy: .public)")
        handle(.failed)
    }

    func pictureInPictureControllerDidStopPictureInPicture(_ controller: AVPictureInPictureController) {
        Self.log.notice("Guide: window stopped")
        setAudio(nil) // closed in Settings with its X: the person's music goes on now
    }

    func pictureInPictureController(_ controller: AVPictureInPictureController,
                                    restoreUserInterfaceForPictureInPictureStopWithCompletionHandler done: @escaping (Bool) -> Void) {
        done(true) // the keyboard step is still behind it
    }
}

/// The keyboard step's guide: the video on a loop once it is made (its first frame until then; the welcome starts
/// making it on step 1), or with `-TFGuidePaused` one still frame. It sits on a screen panel as wide as its frame's
/// drawing area, its corners parallel to the frame's, as large as the room allows up to its own size, keeping its shape;
/// the panel is the video's own paper, so the video's edge never shows. The frame (`SetupGuideView`) says what it shows
/// to VoiceOver.
struct FloatingGuideView: View {
    let guide: FloatingGuide
    @State private var video: URL?
    @Environment(\.colorScheme) private var scheme
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.accessibilityVoiceOverEnabled) private var voiceOver
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        let paused = GuideView.pausedForTests
        let shape = RoundedRectangle(cornerRadius: IllustrationFrame<EmptyView>.innerRadius, style: .continuous)
        ZStack {
            // Under the video too: it shows until the video's first frame (the same picture) is on screen. Scaled to the
            // room as the video is.
            GeometryReader { space in
                GuideFrame(shot: paused ? FloatingGuide.still : FloatingGuide.shots[0])
                    .scaleEffect(space.size.width / FloatingGuide.size.width, anchor: .topLeading)
                    .frame(width: space.size.width, height: space.size.height, alignment: .topLeading)
            }
            if let video, !paused {
                GuideVideo(guide: guide, url: video, floats: FloatingGuide.floats(
                    reduceMotion: reduceMotion, voiceOver: voiceOver, paused: paused,
                    supported: AVPictureInPictureController.isPictureInPictureSupported()))
            }
        }
        .frame(maxWidth: FloatingGuide.size.width, maxHeight: FloatingGuide.size.height)
        .aspectRatio(FloatingGuide.size, contentMode: .fit)
        .frame(maxWidth: .infinity)
        .background(Theme.paper, in: shape)
        .clipShape(shape)
        // Set only with the app in front, in the scheme of that moment: iOS flips the scheme for its snapshots while the
        // app is away, and a video set then would make the guide's player in Settings. A video once set stays.
        .task(id: scenePhase == .active) {
            guard !paused, scenePhase == .active, video == nil else { return }
            do {
                let made = try await FloatingGuide.video(scheme)
                if !Task.isCancelled { video = made } // the app left meanwhile: set once it is back in front
            } catch {
                FloatingGuide.log.error("Guide: no video: \((error as NSError).code, privacy: .public)") // the first frame stays
            }
        }
    }
}

/// The video in a player layer, which Picture in Picture floats.
private struct GuideVideo: UIViewRepresentable {
    let guide: FloatingGuide
    let url: URL
    let floats: Bool

    /// A view whose own layer is the player layer, so it always fills the view.
    final class LayerView: UIView {
        override static var layerClass: AnyClass { AVPlayerLayer.self }
    }

    func makeCoordinator() -> FloatingGuide { guide }

    func makeUIView(context: Context) -> LayerView {
        let view = LayerView()
        if let layer = view.layer as? AVPlayerLayer { guide.play(url, in: layer, floats: floats) }
        return view
    }

    func updateUIView(_ view: LayerView, context: Context) {}

    static func dismantleUIView(_ view: LayerView, coordinator: FloatingGuide) {
        if let layer = view.layer as? AVPlayerLayer { coordinator.stop(layer) }
    }
}
