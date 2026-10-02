import XCTest

/// The walkthrough, "How to use it in other apps", which Home plays once dictation works (`GuideView`), and Home's link
/// to putting ThumbFree first.
@MainActor final class GuideUITests: XCTestCase {
    /// Home with dictation working: the keyboard held as seen with Full Access, the fixed engine (no model to fetch), and
    /// the microphone allowed in Settings' Setup row (Home has no microphone card).
    func launchReadyHome(_ arguments: [String] = []) -> XCUIApplication {
        XCUIApplication().resetAuthorizationStatus(for: .microphone)
        let app = ThumbFreeUI.launch(arguments: ["-TFSetupKeyboard", "ready"] + arguments)
        XCTAssertTrue(ThumbFreeUI.onHome(app))
        ThumbFreeUI.allowMicrophone(in: app)
        XCTAssertTrue(ThumbFreeUI.element("home.ready", in: app).waitForExistence(timeout: 10), "Home is not ready")
        return app
    }

    // Paused, Next walks the seven beats in order, a text box first, and Back goes back.
    func testTheGuideStepsThroughEveryBeat() throws {
        let app = launchReadyHome(["-TFGuidePaused", "YES", "-TFAutoReturn", "YES"])
        let caption = ThumbFreeUI.element("guide.caption", in: app)
        XCTAssertTrue(ThumbFreeUI.wait(for: caption, toContain: "Step 1 of 7. Tap a text box in any app.", timeout: 5))
        let next = app.buttons["guide.next"]
        XCTAssertGreaterThanOrEqual(next.frame.height, 44, "Next's tap target is under 44 points")
        XCTAssertGreaterThanOrEqual(app.buttons["guide.back"].frame.height, 44, "Back's tap target is under 44 points")
        for (step, words) in ["Hold the globe, then choose ThumbFree.", "Tap the yellow mic.",
                              "The first tap of each session opens ThumbFree. It returns to", "Speak.",
                              "Tap the red stop button.", "Your words appear."].enumerated() {
            next.tap()
            XCTAssertTrue(ThumbFreeUI.wait(for: caption, toContain: "Step \(step + 2) of 7. \(words)", timeout: 3))
        }
        XCTAssertFalse(next.isEnabled)
        app.buttons["guide.back"].tap()
        XCTAssertTrue(ThumbFreeUI.wait(for: caption, toContain: "Step 6 of 7.", timeout: 3))
        XCTAssertFalse(app.buttons["Browser"].exists, "the walkthrough is chat only")
        XCTAssertLessThanOrEqual(ThumbFreeUI.element("guide.picture", in: app).frame.height, 320.5, "the walkthrough's frame is taller than 320 points")
    }

    // While the walkthrough plays by itself, Back, Next and the count are hidden and it moves on by itself; its picture
    // takes no taps, so a tap there brings nothing. Reduce Motion, VoiceOver, Switch Control and the test hook get Back
    // and Next (testTheGuideStepsThroughEveryBeat), and Voice Control the caption's Show Back and Next.
    func testTheGuidePlaysWithoutControlsAndItsPictureTakesNoTaps() throws {
        let app = launchReadyHome()
        let caption = ThumbFreeUI.element("guide.caption", in: app)
        let next = app.buttons["guide.next"]
        XCTAssertTrue(caption.waitForExistence(timeout: 5))
        XCTAssertFalse(next.exists, "Next shows while the walkthrough plays by itself")
        let shown = caption.label
        XCTAssertTrue(ThumbFreeUI.wait(until: 4) { caption.label != shown }, "the walkthrough does not play by itself")
        ThumbFreeUI.element("guide.picture", in: app).tap()
        XCTAssertFalse(next.waitForExistence(timeout: 2), "a tap on the picture brought Back and Next")
    }

    // The return beat follows the setting: with automatic return off, the swipe back.
    func testTheReturnBeatFollowsTheSetting() throws {
        let app = launchReadyHome(["-TFGuidePaused", "YES", "-TFAutoReturn", "NO"])
        let caption = ThumbFreeUI.element("guide.caption", in: app)
        XCTAssertTrue(ThumbFreeUI.wait(for: caption, toContain: "Step 1 of 7.", timeout: 5))
        for _ in 0..<3 { app.buttons["guide.next"].tap() }
        XCTAssertTrue(ThumbFreeUI.wait(for: caption, toContain: "Step 4 of 7. The first tap of each session opens ThumbFree. Swipe back to continue.", timeout: 3))
    }

    // Once nothing is missing (the microphone allowed, the keyboard come up with Full Access, the model ready) and a take
    // has given text, Home says so and links to putting ThumbFree first: its guide and the tip.
    func testOnceSetUpHomeLinksToPuttingThumbFreeFirst() throws {
        KeyboardSetup.ensureReady() // Full Access, so the keyboard leaves its mark when it comes up
        XCUIApplication().resetAuthorizationStatus(for: .microphone)
        let (app, field) = ThumbFreeUI.launchTry(arguments: ["-TFGuidePaused", "YES", "-TFTextTakes", "1"])
        field.tap()
        KeyboardSetup.switchToThumbFree(in: app) // the keyboard's mark
        app.buttons["try.notNow"].tap() // Home's card is under the try screen
        XCTAssertTrue(ThumbFreeUI.onHome(app))
        ThumbFreeUI.allowMicrophone(in: app)
        XCTAssertTrue(ThumbFreeUI.element("home.ready", in: app).waitForExistence(timeout: 5), "Home still says what is missing")
        XCTAssertFalse(app.buttons["home.tryIt"].exists, "Try it after a take gave text")
        app.buttons["home.putFirst"].tap()
        XCTAssertTrue(ThumbFreeUI.wait(for: ThumbFreeUI.element("putFirst.caption", in: app), toContain: "Step 1 of 4.", timeout: 5))
        XCTAssertTrue(app.staticTexts["Touch and hold the globe key to jump straight to ThumbFree."].exists)
    }
}
