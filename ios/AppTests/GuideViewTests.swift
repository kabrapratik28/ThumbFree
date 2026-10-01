import Testing
@testable import ThumbFree

/// The walkthrough's beats, in the order the keyboard's flow goes: the globe switches to ThumbFree first; ThumbFree takes
/// you back by itself to Messages while automatic return is on; with it off, you swipe back.
@MainActor @Suite struct GuideViewTests {
    @Test func withAutomaticReturnThumbFreeTakesYouBack() {
        let beats = GuideView.beats(autoReturn: true)
        #expect(beats.map(\.caption) == [
            "Touch and hold the globe key, then pick ThumbFree.",
            "Tap the mic on the ThumbFree keyboard.",
            "ThumbFree opens for a moment and takes you back. If iOS asks, tap Open.",
            "Talk. ThumbFree listens.",
            "Tap the mic again to stop.",
            "Your words appear in the text box.",
        ])
        #expect(beats.map(\.scene) == [.switchKeyboard, .tapMic, .goingBack, .talking, .tapStop, .typed])
        #expect(beats.map(\.length) == [2, 1, 1, 1, 1, 1]) // the keyboard list takes two beats' time
    }

    @Test func withoutItYouSwipeBack() {
        let beats = GuideView.beats(autoReturn: false)
        #expect(beats[2].caption == "ThumbFree opens. Swipe right along the bottom edge to go back.")
        #expect(beats.map(\.scene) == [.switchKeyboard, .tapMic, .swipeBack, .talking, .tapStop, .typed])
    }

    // The chat stands for Messages, which every build may open (a test iPhone confirmed it), so a store build's
    // walkthrough shows the return it makes.
    @Test func theChatIsAnAppEveryBuildReturnsTo() throws {
        let target = try #require(ReturnTargets.target(forBundleID: GuideView.bundleID))
        #expect(target.displayName == GuideView.app)
        #expect(ReturnTargets.mayOpen(target, debugBuild: false))
    }

    // The walkthrough draws the real keyboard's letters layer as Apple's iOS 26 keys: every key but space (blank, as on
    // Apple's) has a title or a symbol, and shift, delete, return and the emoji key are Apple's symbols.
    @Test func theDrawnKeyboardHasACapOnEveryKey() {
        for key in Keyplane.rows(.letters, showsGlobe: false).joined() where key != .space {
            let cap = GuidePhone.cap(key)
            #expect(!cap.title.isEmpty || cap.symbol != nil, "\(key) is drawn blank")
        }
        #expect(GuidePhone.cap(.space).title.isEmpty && GuidePhone.cap(.space).symbol == nil)
        #expect(GuidePhone.cap(.emoji).symbol == "face.smiling")
        #expect([GuidePhone.cap(.shift).symbol, GuidePhone.cap(.delete).symbol, GuidePhone.cap(.ret).symbol] == ["shift", "delete.left", "return.left"])
    }

    // The welcome's last step has no way on while the model downloads or gets ready: Start comes once it is ready, and
    // a stuck download or load offers its fix.
    @Test func theLastStepWaitsForTheModel() {
        #expect(WelcomeView.next(.downloading(done: 1, total: 4), waiting: nil, engine: .noModel) == nil)
        #expect(WelcomeView.next(.downloading(done: 4, total: 4), waiting: nil, engine: .noModel) == nil) // checking the files
        #expect(WelcomeView.next(.downloading(done: 1, total: 4), waiting: .connection, engine: .noModel) == nil)
        #expect(WelcomeView.next(.downloading(done: 1, total: 4), waiting: .wifi, engine: .noModel) == .useMobileData)
        #expect(WelcomeView.next(.ready, waiting: nil, engine: .unloaded) == nil)
        #expect(WelcomeView.next(.ready, waiting: nil, engine: .loading) == nil)
        #expect(WelcomeView.next(.ready, waiting: nil, engine: .warming) == nil)
        #expect(WelcomeView.next(.ready, waiting: nil, engine: .readyNeuralEngine) == .start)
        #expect(WelcomeView.next(.ready, waiting: nil, engine: .readyCPU) == .start)
        #expect(WelcomeView.next(.ready, waiting: nil, engine: .failed) == .loadAgain)
        #expect(WelcomeView.next(.failed(.noInternet), waiting: nil, engine: .noModel) == .tryAgain)
        #expect(WelcomeView.next(.missing, waiting: nil, engine: .noModel) == .download)
    }

    // The line under Get started says what downloads, how big, and on which network, before it starts.
    @Test func getStartedSaysWhatDownloads() {
        #expect(WelcomeView.downloadLine(size: "465 MB", wifiOnly: true) == "The speech model (about 465 MB) downloads now, over Wi-Fi.")
        #expect(WelcomeView.downloadLine(size: "465 MB", wifiOnly: false) == "The speech model (about 465 MB) downloads now.")
    }
}
