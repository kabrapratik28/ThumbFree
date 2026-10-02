import AVFoundation
import SwiftUI
import Testing
import TFCore
import UIKit
@testable import ThumbFree

/// The walkthrough's beats, in the order the keyboard's flow goes: a text box is tapped, the globe switches to ThumbFree,
/// and its yellow mic starts; the first tap of each session opens ThumbFree, which takes you back by itself to the apps
/// every build returns to while automatic return is on; with it off, you swipe back. Also the welcome's rules: its steps,
/// the keyboard step's pages, the language choice's order and the speech model's line.
@MainActor @Suite struct GuideViewTests {
    @Test func withAutomaticReturnThumbFreeTakesYouBack() {
        let beats = GuideView.beats(autoReturn: true)
        #expect(beats.map(\.caption) == [
            "Tap a text box in any app.",
            "Hold the globe, then choose ThumbFree.",
            "Tap the yellow mic.",
            "The first tap of each session opens ThumbFree. It returns to Messages, Notes and Signal.",
            "Speak.",
            "Tap the red stop button.",
            "Your words appear.",
        ])
        #expect(beats.map(\.scene) == [.tapBox, .switchKeyboard, .tapMic, .goingBack, .talking, .tapStop, .typed])
        #expect(beats.map(\.length) == [1, 2, 1, 1, 1, 1, 1]) // the globe's hold and pick take two beats' time
    }

    @Test func withoutItYouSwipeBack() {
        let beats = GuideView.beats(autoReturn: false)
        #expect(beats[3].caption == "The first tap of each session opens ThumbFree. Swipe back to continue.")
        #expect(beats.map(\.scene) == [.tapBox, .switchKeyboard, .tapMic, .swipeBack, .talking, .tapStop, .typed])
    }

    // No caption is ever cut: each fits its lines at every standard text size on the narrowest page (Home's on an
    // SE-size iPhone, 335 points; the welcome's would be 327), at worst at the 0.8 scale floor.
    @Test func everyCaptionFitsItsLinesAtTheStandardSizes() {
        let sizes: [(DynamicTypeSize, UIContentSizeCategory)] = [
            (.xSmall, .extraSmall), (.small, .small), (.medium, .medium), (.large, .large), (.xLarge, .extraLarge),
            (.xxLarge, .extraExtraLarge), (.xxxLarge, .extraExtraExtraLarge),
        ]
        let captions = (GuideView.beats(autoReturn: true) + GuideView.beats(autoReturn: false)).map(\.caption)
        for (size, category) in sizes {
            let font = UIFont.preferredFont(forTextStyle: .subheadline, compatibleWith: UITraitCollection(preferredContentSizeCategory: category))
            let smallest = font.withSize(font.pointSize * 0.8)
            func height(_ text: String) -> CGFloat {
                (text as NSString).boundingRect(with: CGSize(width: 327, height: CGFloat.greatestFiniteMagnitude),
                                                options: .usesLineFragmentOrigin, attributes: [.font: smallest], context: nil).height
            }
            let lines = CGFloat(GuidePlayer<GuideView.Scene, WalkthroughScene>.captionLines(size))
            for caption in captions { #expect(height(caption) < height("A") * (lines + 0.5), "\(size): \(caption)") }
        }
        #expect(GuidePlayer<GuideView.Scene, WalkthroughScene>.captionLines(.large) == 2) // the default size keeps two
    }

    // The walkthrough hides Back and Next while it plays by itself, so it never plays for those who step through with an
    // assistive feature: Reduce Motion, VoiceOver, and Switch Control too. Anyone else can stop it with its accessibility
    // action; the picture itself takes no taps.
    @Test func theWalkthroughNeverPlaysForSwitchControl() {
        typealias Player = GuidePlayer<GuideView.Scene, WalkthroughScene>
        #expect(Player.plays(reduceMotion: false, voiceOver: false, switchControl: false, paused: false, stepping: false))
        #expect(!Player.plays(reduceMotion: false, voiceOver: false, switchControl: true, paused: false, stepping: false))
        #expect(!Player.plays(reduceMotion: false, voiceOver: true, switchControl: false, paused: false, stepping: false))
        #expect(!Player.plays(reduceMotion: true, voiceOver: false, switchControl: false, paused: false, stepping: false))
        #expect(!Player.plays(reduceMotion: false, voiceOver: false, switchControl: false, paused: false, stepping: true))
        #expect(Player.showControls == "Show Back and Next")
    }

    // The chat stands for Messages, which every build may open (a test iPhone confirmed it), so a store build's
    // walkthrough shows the return it makes.
    @Test func theChatIsAnAppEveryBuildReturnsTo() throws {
        let target = try #require(ReturnTargets.target(forBundleID: GuideView.bundleID))
        #expect(target.displayName == GuideView.app)
        #expect(ReturnTargets.mayOpen(target, debugBuild: false))
    }

    // The tap cue's yellow ring has a thin dark edge just outside it, with a gap, so it stands out on the light canvas.
    @Test func theTapCueHasADarkEdgeOnLight() throws {
        // A 40-point ring in a 60-point square: the yellow stroke's middle is 20 points from the center, the edge's 23.
        let renderer = ImageRenderer(content: TapCue(outline: Circle(), showsPill: false).frame(width: 40, height: 40).padding(10)
            .background(Theme.canvas).environment(\.colorScheme, .light))
        renderer.scale = 2
        let image = try #require(renderer.cgImage)
        // Redrawn into plain 8-bit RGBA, whatever the renderer's own format.
        let context = try #require(CGContext(data: nil, width: image.width, height: image.height, bitsPerComponent: 8,
                                             bytesPerRow: image.width * 4, space: CGColorSpaceCreateDeviceRGB(),
                                             bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue))
        context.draw(image, in: CGRect(x: 0, y: 0, width: image.width, height: image.height))
        let pixels = try #require(context.data).assumingMemoryBound(to: UInt8.self)
        func rgb(x: Int, y: Int) -> (r: Int, g: Int, b: Int) {
            let at = (y * image.width + x) * 4
            return (Int(pixels[at]), Int(pixels[at + 1]), Int(pixels[at + 2]))
        }
        let ring = rgb(x: (30 + 20) * 2, y: 30 * 2), edge = rgb(x: (30 + 23) * 2, y: 30 * 2)
        #expect(ring.g > 180, "the ring is not yellow: \(ring)")
        #expect(edge.g < ring.g - 60, "no dark edge outside the ring: \(edge) beside \(ring)")
    }

    // The welcome's steps come in order, under three parts of the bar (the wait for speech is part of getting ready), and
    // keep the numbers earlier versions saved, so an update in the middle of the flow comes back at its step: the
    // microphone's page, 3, is the wait now. VoiceOver hears the bar as the step's number and name.
    @Test func theStepsKeepTheirSavedNumbers() {
        #expect(WelcomeView.Step.allCases == [.welcome, .wait, .keyboard, .tryIt])
        #expect(WelcomeView.Step.allCases.map(\.rawValue) == [0, 3, 1, 2])
        #expect(WelcomeView.Step.allCases.map(\.part) == [0, 0, 1, 2])
        #expect(WelcomeView.Step.allCases.map { OnboardingHeader.label(step: $0.part, name: $0.name) } == [
            "Step 1 of 3, Get ready", "Step 1 of 3, Get ready", "Step 2 of 3, Add the ThumbFree keyboard", "Step 3 of 3, Try ThumbFree",
        ])
    }

    // Page 2 shows its guide until a trip away, even with the keyboard already in iOS's list (a reinstall); back in front
    // from the trip with the keyboard added, the try comes at once (its box is where the keyboard first comes up), and
    // with it still off, the guide again with where else to look. A keyboard seen with Full Access needs no page 2.
    @Test func thePageFollowsTheTripBackInFront() {
        func page(_ status: KeyboardStatus, wentAway: Bool) -> WelcomeView.KeyboardPage {
            WelcomeView.keyboardPage(from: .guide, status: status, wentAway: wentAway, active: true)
        }
        #expect(page(.notAdded, wentAway: false) == .guide)
        #expect(page(.added, wentAway: false) == .guide)
        #expect(page(.notAdded, wentAway: true) == .stillOff)
        #expect(page(.added, wentAway: true) == .done)
        #expect(page(.ready, wentAway: false) == .done)
        #expect(page(.ready, wentAway: true) == .done)
    }

    // While the app is away (in Settings, or on its way there), page 2 keeps exactly what it showed, guide included,
    // whatever the trip flag and the keyboard's status say then: a page that changed would take the guide away and close
    // the window floating over Settings. That was the failure after a reinstall, where iOS lists the keyboard before any
    // trip and the flag turned the page as the app left. Back in front, the page follows them.
    @Test func thePageNeverChangesWhileTheAppIsAway() {
        for current in [WelcomeView.KeyboardPage.guide, .stillOff] {
            for status in [KeyboardStatus.notAdded, .added, .ready] {
                for wentAway in [false, true] {
                    #expect(WelcomeView.keyboardPage(from: current, status: status, wentAway: wentAway, active: false) == current,
                            "\(current) \(status) \(wentAway)")
                }
            }
        }
        // The reinstall case: the keyboard listed, the flag set as the app left. The guide stays; back in front, the try.
        #expect(WelcomeView.keyboardPage(from: .guide, status: .added, wentAway: true, active: false) == .guide)
        #expect(WelcomeView.keyboardPage(from: .guide, status: .added, wentAway: true, active: true) == .done)
        // A second trip from the still-off page: still off while away, the try once back with the keyboard added.
        #expect(WelcomeView.keyboardPage(from: .stillOff, status: .added, wentAway: true, active: false) == .stillOff)
        #expect(WelcomeView.keyboardPage(from: .stillOff, status: .added, wentAway: true, active: true) == .done)
    }

    // Speech ready, the wait goes to page 2, or straight to the try when page 2 has nothing left to do (the keyboard seen
    // with Full Access, or added on an earlier trip). A flow saved on the old microphone page (3) is the wait now, so an
    // update in the middle of the flow waits for speech and goes on the same way.
    @Test func theWaitGoesOnToPageTwoOrTheTry() {
        #expect(WelcomeView.afterWait(.notAdded, wentAway: false) == .keyboard)
        #expect(WelcomeView.afterWait(.added, wentAway: false) == .keyboard) // listed after a reinstall: page 2 still
        #expect(WelcomeView.afterWait(.notAdded, wentAway: true) == .keyboard)
        #expect(WelcomeView.afterWait(.added, wentAway: true) == .tryIt)
        #expect(WelcomeView.afterWait(.ready, wentAway: false) == .tryIt)
        #expect(WelcomeView.Step(rawValue: 3) == .wait)
        #expect(WelcomeView.Step(rawValue: 3)?.part == 0)
    }

    // A flow saved past step 1 (an earlier version allowed it while speech still downloaded) comes back at the wait when
    // the model in use is not downloaded, or never loaded since its download: speech gets ready on step 1 first. A model
    // loaded before keeps the step (a reload is the try's small line). The first steps stay as saved.
    @Test func aFlowSavedPastStepOneGetsSpeechReadyFirst() throws {
        #expect(WelcomeView.restoredStep(.keyboard, downloaded: false, loadedBefore: false) == .wait)
        #expect(WelcomeView.restoredStep(.tryIt, downloaded: false, loadedBefore: false) == .wait)
        #expect(WelcomeView.restoredStep(.tryIt, downloaded: true, loadedBefore: false) == .wait)
        #expect(WelcomeView.restoredStep(.keyboard, downloaded: true, loadedBefore: true) == .keyboard)
        #expect(WelcomeView.restoredStep(.tryIt, downloaded: true, loadedBefore: true) == .tryIt)
        for step in [WelcomeView.Step.welcome, .wait] {
            #expect(WelcomeView.restoredStep(step, downloaded: false, loadedBefore: false) == step, "\(step)")
        }
        let suite = "restore-\(UUID().uuidString)"
        let defaults = try #require(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        let folder = try TestFiles.folder()
        defer { try? FileManager.default.removeItem(at: folder) }
        let model = SpeechModel(entries: SpeechModel.english, folder: folder.appendingPathComponent("en"), ready: false, defaults: defaults)
        defaults.set(WelcomeView.Step.tryIt.rawValue, forKey: WelcomeView.stepKey) // a v3 try, the download unfinished
        WelcomeView.restoreStep(model: model, defaults: defaults)
        #expect(defaults.integer(forKey: WelcomeView.stepKey) == WelcomeView.Step.wait.rawValue)
        defaults.set(3, forKey: WelcomeView.stepKey) // the old microphone page: the wait already
        WelcomeView.restoreStep(model: model, defaults: defaults)
        #expect(defaults.integer(forKey: WelcomeView.stepKey) == WelcomeView.Step.wait.rawValue)
    }

    // The wait's bar: how far the download is, full while the files are checked, and none once the engine loads or while
    // something stops the download. Its buttons: the one fix, or Change language while the download runs, waits or broke
    // off; none while the files are checked or the engine loads.
    @Test func theWaitShowsItsBarAndItsButtons() {
        #expect(WelcomeView.progress(.downloading(percent: 42)) == 0.42)
        #expect(WelcomeView.progress(.checking) == 1)
        #expect(WelcomeView.progress(.loading) == nil) // the spinner and 100% say it
        #expect(WelcomeView.progress(nil) == nil) // speech ready
        for stop in [SetupBlocker.waitingForWifi, .waitingForConnection, .downloadPaused, .noSpace(needed: 1), .checkFailed,
                     .loadFailed, .speechMissing] {
            #expect(WelcomeView.progress(stop) == nil, "\(stop)")
        }
        #expect(WelcomeView.hasButtons(.downloading(percent: 42))) // Change language
        #expect(WelcomeView.hasButtons(.waitingForConnection))
        #expect(WelcomeView.hasButtons(.loadFailed)) // Try again
        #expect(!WelcomeView.hasButtons(.checking))
        #expect(!WelcomeView.hasButtons(.loading))
        #expect(!WelcomeView.hasButtons(nil))
    }

    // The phone's guess comes first, filled: the multilingual model when one of the iPhone's languages is one of its own
    // other than English. The choices say what each covers, with its size from the catalog, kept on one line.
    @Test func thePhonesGuessComesFirst() throws {
        #expect(WelcomeView.languageOrder(multilingualFirst: SpeechModels.startsMultilingual(["de-DE", "en-US"])) == [true, false])
        #expect(WelcomeView.languageOrder(multilingualFirst: SpeechModels.startsMultilingual(["en-GB"])) == [false, true])
        let folder = try TestFiles.folder()
        defer { try? FileManager.default.removeItem(at: folder) }
        let english = SpeechModel(entries: SpeechModel.english, folder: folder.appendingPathComponent("en"), ready: false)
        let multilingual = SpeechModel(entries: SpeechModel.multilingual, folder: folder.appendingPathComponent("multi"), ready: false)
        #expect(english.languageName == "English")
        #expect(english.languageDetail == "Best for English")
        #expect(english.languageSize == "about\u{00A0}465\u{00A0}MB")
        #expect(multilingual.languageName == "Other languages")
        #expect(multilingual.languageDetail == "Spanish, French, German and 21 more")
        #expect(multilingual.languageSize == "about\u{00A0}484\u{00A0}MB")
    }

    // A title never leaves one word alone on its last line: its last two words are bound by a no-break space.
    @Test func aTitleKeepsItsLastTwoWordsTogether() {
        #expect("Your voice becomes text in any app.".keepingLastWordsTogether == "Your voice becomes text in any\u{00A0}app.")
        #expect("Microphone is off".keepingLastWordsTogether == "Microphone is\u{00A0}off")
        #expect("Done".keepingLastWordsTogether == "Done")
    }

    // The speech model's line: how far the download is, or its state in the screens' own few words.
    @Test func theModelLineSaysWhereSpeechIs() {
        func line(_ phase: SpeechModel.Phase, waiting: SpeechModel.Waiting? = nil, engine: EnginePhase = .noModel) -> String {
            ModelProgressLine.line("English", phase: phase, waiting: waiting, engine: engine, total: 465_000_000)
        }
        #expect(line(.downloading(done: 42, total: 100)) == "English · 42%")
        #expect(line(.downloading(done: 42, total: 100), waiting: .wifi) == "English · Waiting for Wi-Fi")
        #expect(line(.downloading(done: 42, total: 100), waiting: .connection) == "English · Waiting for a connection")
        #expect(line(.downloading(done: 100, total: 100)) == "English · Checking the download")
        #expect(line(.missing) == "English · about 465\u{00A0}MB")
        #expect(line(.failed(.interrupted)) == "English · Download paused")
        #expect(line(.failed(.noSpace(needed: 1))) == "English · Not enough space")
        #expect(line(.ready, engine: .loading) == "English · Downloaded")
        #expect(line(.ready, engine: .failed) == "English · Couldn’t get speech ready")
        #expect(ModelProgressLine.percent(.downloading(done: 42, total: 100)) == 42)
        #expect(ModelProgressLine.percent(.downloading(done: 100, total: 100)) == nil) // checking: no bar
        #expect(ModelProgressLine.percent(.ready) == nil)
    }

    // VoiceOver hears each tenth of a model's download once: a download that starts over (Use mobile data, a resume)
    // says nothing until it passes the highest tenth heard, and each model has its own.
    @Test func voiceOverHearsEachTenthOnce() {
        let model = UUID().uuidString, other = UUID().uuidString
        #expect(!ProgressAnnouncer.hears(0, of: model)) // under 10%
        #expect(ProgressAnnouncer.hears(1, of: model))
        #expect(ProgressAnnouncer.hears(4, of: model))
        #expect(!ProgressAnnouncer.hears(4, of: model))
        #expect(!ProgressAnnouncer.hears(-1, of: model)) // no percentage: the files are checked, or it waits
        #expect(!ProgressAnnouncer.hears(1, of: model)) // started over
        #expect(!ProgressAnnouncer.hears(4, of: model))
        #expect(ProgressAnnouncer.hears(5, of: model))
        #expect(ProgressAnnouncer.hears(1, of: other))
    }
}
