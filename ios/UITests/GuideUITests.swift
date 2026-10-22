import XCTest

/// "How to use it in other apps", the steps Home shows once dictation works (`GuideView`), and Home's link to putting
/// ThumbFree first.
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

    // Home says how to use ThumbFree in other apps in four steps of words, in an IN ANY APP frame: nothing drawn to hold
    // or tap, no Back and Next, nothing cut off.
    func testHomeSaysTheStepsInWords() throws {
        let app = launchReadyHome()
        let steps = ThumbFreeUI.element("guide.steps", in: app)
        XCTAssertTrue(steps.waitForExistence(timeout: 5))
        XCTAssertEqual(steps.label, "Open Messages or another app, and tap where you type. Hold the globe key, then choose "
            + "ThumbFree. Tap the yellow mic. If ThumbFree opens, go back to your app. Speak, then tap the red stop button.")
        XCTAssertFalse(app.buttons["guide.next"].exists, "Back and Next on Home")
        XCTAssertFalse(ThumbFreeUI.element("guide.picture", in: app).exists, "a drawing on Home")
        XCTAssertTrue(app.frame.contains(steps.frame), "the steps \(steps.frame) are cut off")
        ThumbFreeUI.shot("home-steps")
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
