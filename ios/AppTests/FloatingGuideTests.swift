import AVFoundation
import SwiftUI
import Testing
import TFCore
@testable import ThumbFree

/// The keyboard step's floating guide: what the step shows, when the guide floats, its beats, and the video it makes.
@MainActor @Suite final class FloatingGuideTests {
    let folder: URL

    init() throws { folder = try TestFiles.folder() }

    deinit { try? FileManager.default.removeItem(at: folder) }

    // Reduce Motion or VoiceOver: the short list. Paused for screenshots: one still frame. Else the video, which is the
    // only look that floats, and only where iOS has Picture in Picture.
    @Test func theGuideFloatsOnlyWhileItsVideoPlays() {
        #expect(FloatingGuide.look(reduceMotion: false, voiceOver: false, paused: false) == .video)
        #expect(FloatingGuide.look(reduceMotion: true, voiceOver: false, paused: false) == .cards)
        #expect(FloatingGuide.look(reduceMotion: false, voiceOver: true, paused: false) == .cards)
        #expect(FloatingGuide.look(reduceMotion: true, voiceOver: true, paused: true) == .cards)
        #expect(FloatingGuide.look(reduceMotion: false, voiceOver: false, paused: true) == .still)
        var floating: [[Bool]] = []
        for reduceMotion in [false, true] {
            for voiceOver in [false, true] {
                for paused in [false, true] {
                    for supported in [false, true]
                    where FloatingGuide.floats(reduceMotion: reduceMotion, voiceOver: voiceOver, paused: paused, supported: supported) {
                        floating.append([reduceMotion, voiceOver, paused, supported])
                    }
                }
            }
        }
        #expect(floating == [[false, false, false, true]])
    }

    // While the app is away, the step keeps the look and the text size it had as the app left, so a setting changed in
    // Settings never swaps the video for the list and closes the window; in front, the live ones apply. Never in front
    // yet, the live ones too.
    @Test func awayTheStepKeepsWhatItShowedAsTheAppLeft() {
        #expect(FloatingGuide.shown(live: FloatingGuide.Look.cards, held: .video, active: false) == .video)
        #expect(FloatingGuide.shown(live: FloatingGuide.Look.cards, held: .video, active: true) == .cards)
        #expect(FloatingGuide.shown(live: FloatingGuide.Look.video, held: nil, active: false) == .video)
        #expect(FloatingGuide.shown(live: DynamicTypeSize.accessibility3, held: .large, active: false) == .large)
        #expect(FloatingGuide.shown(live: DynamicTypeSize.accessibility3, held: .large, active: true) == .accessibility3)
    }

    // Open Settings opens Settings once: when the window floats, when it fails for good, or at the 1 s deadline. A
    // session mixed with others gets one try without mixing, on a failure or when the window is still impossible at
    // 0.4 s; Settings opening without the window lets the session go first, unless a window is on its way.
    @Test func settingsOpensOnceAndTheTryWithoutMixingComesOnce() {
        var floated = FloatingGuide.Launch(mixing: true)
        #expect(floated.on(.willStart) == .wait)
        #expect(floated.on(.started) == .open)
        #expect(floated.on(.failed) == .wait)
        #expect(floated.on(.deadline) == .wait)

        var refused = FloatingGuide.Launch(mixing: true)
        #expect(refused.on(.failed) == .retryAlone)
        #expect(!refused.mixing)
        #expect(refused.on(.failed) == .letGoAndOpen) // never a second try, even if the switch itself failed
        #expect(refused.on(.started) == .wait)

        var impossible = FloatingGuide.Launch(mixing: true)
        #expect(impossible.on(.stillImpossible) == .retryAlone)
        #expect(impossible.on(.stillImpossible) == .wait)
        #expect(impossible.on(.deadline) == .letGoAndOpen)
        #expect(impossible.on(.deadline) == .wait)

        var mic = FloatingGuide.Launch(mixing: false) // the mic's own session is never switched
        #expect(mic.on(.stillImpossible) == .wait)
        #expect(mic.on(.failed) == .letGoAndOpen)

        var slow = FloatingGuide.Launch(mixing: true)
        #expect(slow.on(.willStart) == .wait)
        #expect(slow.on(.deadline) == .open)

        var retried = FloatingGuide.Launch(mixing: true) // the first try was on its way, then refused: it no longer is
        #expect(retried.on(.willStart) == .wait)
        #expect(retried.on(.failed) == .retryAlone)
        #expect(retried.on(.deadline) == .letGoAndOpen)
    }

    // A window still on its way when Settings opens at the deadline keeps the audio session only until it fails or 1 s
    // more has passed without it starting: a window that never starts never keeps the session for the Settings visit.
    // One that starts keeps it.
    @Test func aWindowThatNeverStartsLetsTheSessionGo() {
        var never = FloatingGuide.Launch(mixing: true)
        #expect(never.on(.willStart) == .wait)
        #expect(never.on(.deadline) == .open)
        #expect(never.waitsForWindow)
        #expect(never.on(.graceOver) == .letGo)
        #expect(!never.waitsForWindow)
        #expect(never.on(.started) == .wait)

        var fails = FloatingGuide.Launch(mixing: true)
        #expect(fails.on(.willStart) == .wait)
        #expect(fails.on(.deadline) == .open)
        #expect(fails.on(.failed) == .letGo)
        #expect(fails.on(.graceOver) == .wait)

        var late = FloatingGuide.Launch(mixing: false)
        #expect(late.on(.willStart) == .wait)
        #expect(late.on(.deadline) == .open)
        #expect(late.on(.started) == .wait)
        #expect(late.on(.graceOver) == .wait) // the window floats: its session stays
        #expect(!late.waitsForWindow)

        var none = FloatingGuide.Launch(mixing: true)
        #expect(none.on(.deadline) == .letGoAndOpen)
        #expect(!none.waitsForWindow)
        #expect(none.on(.graceOver) == .wait)
    }

    // Open Settings tapped while the video is still being made waits for its controller, up to 1 s: the controller first
    // floats the window as a tap now would, the deadline first opens Settings without it, either way once, and a second
    // tap while one waits does nothing more. (Once the controller is made, a tap takes `Launch`'s way, not this one.)
    @Test func aTapBeforeTheControllerWaitsForItOnce() {
        var deadlineFirst = FloatingGuide.Kept()
        #expect(deadlineFirst.on(.tap) == .keep)
        #expect(deadlineFirst.on(.tap) == .wait)
        #expect(deadlineFirst.on(.deadline) == .open)
        #expect(deadlineFirst.on(.controller) == .wait)
        #expect(deadlineFirst.on(.deadline) == .wait)

        var controllerFirst = FloatingGuide.Kept()
        #expect(controllerFirst.on(.tap) == .keep)
        #expect(controllerFirst.on(.tap) == .wait)
        #expect(controllerFirst.on(.controller) == .float)
        #expect(controllerFirst.on(.deadline) == .wait)
        #expect(controllerFirst.on(.controller) == .wait)

        var untapped = FloatingGuide.Kept() // a controller made with no tap waiting floats nothing
        #expect(untapped.on(.controller) == .wait)
    }

    // Settings opens 1 s after the tap, however late the controller came: a tap kept for it leaves the window only the
    // time that is left. The try without mixing comes 0.4 s after the window is first asked for, only before the
    // deadline, and the grace ends 1 s after the deadline.
    @Test func settingsOpensOneSecondAfterTheTapHoweverLateTheControllerCame() {
        let tap = ContinuousClock.now
        let atOnce = FloatingGuide.marks(tapped: tap, asked: tap)
        #expect(atOnce.retry == tap + .milliseconds(400))
        #expect(atOnce.deadline == tap + .seconds(1))
        #expect(atOnce.grace == tap + .seconds(2))
        let soon = FloatingGuide.marks(tapped: tap, asked: tap + .milliseconds(500))
        #expect(soon.retry == tap + .milliseconds(900))
        #expect(soon.deadline == tap + .seconds(1))
        let late = FloatingGuide.marks(tapped: tap, asked: tap + .milliseconds(900))
        #expect(late.retry == nil)
        #expect(late.deadline == tap + .seconds(1))
        #expect(late.grace == tap + .seconds(2))
    }

    // With no controller (the video not made yet), Open Settings opens Settings once, at the 1 s deadline, however often
    // it is tapped; back in the app before the deadline (`land`), it does not open.
    @Test func aKeptTapOpensSettingsOnceAtItsDeadline() async throws {
        let guide = FloatingGuide()
        var opened = 0
        guide.float(HostStatus()) { opened += 1 }
        guide.float(HostStatus()) { opened += 1 }
        #expect(opened == 0)
        try await Task.sleep(for: .seconds(1.5))
        #expect(opened == 1)
        guide.float(HostStatus()) { opened += 1 }
        guide.land()
        try await Task.sleep(for: .seconds(1.5))
        #expect(opened == 1)
    }

    // Dictation owns the audio session from the moment a session starts, before its audio flows: the guide leaves it
    // alone then, not only while the mic is on.
    @Test func theGuideLeavesAStartingSessionsAudioAlone() {
        #expect(!FloatingGuide.leavesAudio(HostStatus()))
        #expect(FloatingGuide.leavesAudio(HostStatus(session: .starting)))
        #expect(FloatingGuide.leavesAudio(HostStatus(session: .ready, micOn: true)))
        #expect(FloatingGuide.leavesAudio(HostStatus(micOn: true)))
    }

    // A view the page makes again takes the guide over. The old view going then pauses only its own player, so the new
    // one plays on; the new one going stops the guide.
    @Test func anOldViewGoingLeavesTheNewOnePlaying() async throws {
        let url = try await FloatingGuide.video(.light, in: folder)
        let guide = FloatingGuide()
        let old = AVPlayerLayer(), new = AVPlayerLayer()
        guide.play(url, in: old, floats: false)
        guide.play(url, in: new, floats: false)
        guide.stop(old)
        #expect(old.player?.rate == 0)
        #expect(new.player?.rate == 1)
        guide.stop(new)
        #expect(new.player?.rate == 0)
    }

    // Five beats of 1.3 s: the three taps in Settings, iOS's question named in words, then the way back. A tap's ripple
    // and change are frames at 30 a second, so nothing steps: each switch goes from off to on; the question has no tap.
    // The still frame for screenshots is Allow Full Access, turned on.
    @Test func theBeatsAreTheTapsTheQuestionThenTheWayBack() {
        #expect(FloatingGuide.captions == [
            "Tap Keyboards.",
            "Turn on ThumbFree.",
            "Turn on Allow Full Access.",
            "When iOS asks, tap Allow.",
            "Tap ThumbFree at the top left to return.",
        ])
        #expect(FloatingGuide.beat == TapTimeline.length)
        #expect(FloatingGuide.shots.map(\.beat) == FloatingGuide.shots.map(\.beat).sorted())
        for beat in FloatingGuide.captions.indices {
            let shots = FloatingGuide.shots.filter { $0.beat == beat }
            #expect(abs(shots.map(\.hold).reduce(0, +) - FloatingGuide.beat) < 0.001, "beat \(beat)")
            #expect(shots.allSatisfy { abs($0.hold * 600 - ($0.hold * 600).rounded()) < 0.001 }, "beat \(beat): whole ticks")
            guard beat != FloatingGuide.allowBeat else {
                #expect(shots.count == 1)
                continue
            }
            let moving = shots.filter { (TapTimeline.rippleStart..<TapTimeline.changeEnd).contains($0.time) }
            #expect(moving.count == 18, "beat \(beat): 0.6 s at 30 frames a second")
            #expect(moving.allSatisfy { abs($0.hold - 1.0 / 30) < 0.0001 }, "beat \(beat)")
            #expect(shots.map(\.time) == shots.map(\.time).sorted(), "beat \(beat)")
            #expect(TapTimeline.change(at: shots.first?.time ?? 1) == 0 && TapTimeline.change(at: shots.last?.time ?? 0) == 1,
                    "beat \(beat): off, then on")
        }
        #expect(FloatingGuide.still == FloatingGuide.Shot(beat: 2))
    }

    // The video loads, lasts the five beats, is the frame's size in pixels and has no sound; nothing else is left behind.
    @Test(arguments: [ColorScheme.light, .dark]) func theVideoIsTheBeatsAtTheFrameSize(scheme: ColorScheme) async throws {
        let url = try await FloatingGuide.video(scheme, in: folder)
        let build = try #require(Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String)
        #expect(url.lastPathComponent == "setup-guide-\(scheme == .dark ? "dark" : "light")-v\(FloatingGuide.version)-build\(build).mp4")
        let asset = AVURLAsset(url: url)
        let duration = try await asset.load(.duration).seconds
        #expect(abs(duration - Double(FloatingGuide.captions.count) * FloatingGuide.beat) < 0.05)
        let video = try #require(try await asset.loadTracks(withMediaType: .video).first)
        let size = try await video.load(.naturalSize)
        #expect(size == CGSize(width: FloatingGuide.size.width * FloatingGuide.scale, height: FloatingGuide.size.height * FloatingGuide.scale))
        let sound = try await asset.loadTracks(withMediaType: .audio)
        #expect(sound.isEmpty)
        #expect(try FileManager.default.contentsOfDirectory(atPath: folder.path) == [url.lastPathComponent])
    }

    // The welcome makes the keyboard step's video during step 1 (the language and the wait for speech), so an Open
    // Settings right after the keyboard step comes up floats it at once; not when the step shows its list or a still
    // frame, nor later (the step's own view asks then).
    @Test func theWelcomeAsksForTheVideoOnStepOne() {
        #expect(WelcomeView.preparesGuide(.welcome, look: .video))
        #expect(WelcomeView.preparesGuide(.wait, look: .video))
        #expect(!WelcomeView.preparesGuide(.welcome, look: .cards))
        #expect(!WelcomeView.preparesGuide(.welcome, look: .still))
        #expect(!WelcomeView.preparesGuide(.keyboard, look: .video))
        #expect(!WelcomeView.preparesGuide(.tryIt, look: .video))
    }

    // A call while the video is being made waits for it: both get the one file, and nothing else is left behind.
    @Test func aCallWhileTheVideoIsMadeWaitsForIt() async throws {
        async let first = FloatingGuide.video(.light, in: folder)
        async let second = FloatingGuide.video(.light, in: folder)
        let urls = try await [first, second]
        #expect(urls[0] == urls[1])
        #expect(try FileManager.default.contentsOfDirectory(atPath: folder.path) == [urls[0].lastPathComponent])
    }

    // A video made before, by this version and build and in this scheme, is used as it is; another build makes its own.
    @Test func aVideoMadeBeforeIsReused() async throws {
        #expect(FloatingGuide.fileName(.dark, build: "4") != FloatingGuide.fileName(.dark, build: "5"))
        let url = folder.appendingPathComponent(FloatingGuide.fileName(.dark))
        try Data("made before".utf8).write(to: url)
        #expect(try await FloatingGuide.video(.dark, in: folder) == url)
        #expect(try Data(contentsOf: url) == Data("made before".utf8))
    }
}
