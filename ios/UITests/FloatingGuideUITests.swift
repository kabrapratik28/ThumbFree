import XCTest

/// The keyboard step's guide: a looping video of the taps in Settings in an IN SETTINGS frame under the step's Full
/// Access line, and Open Settings, which floats the guide over Settings where iOS has Picture in Picture and opens
/// Settings all the same where it has not (the iPhone Simulator); `-TFGuidePaused` holds one still frame. Reduce Motion
/// and VoiceOver, which bring the list back, are not launch arguments: `FloatingGuideTests` checks that rule.
@MainActor final class FloatingGuideUITests: XCTestCase {
    static let fullAccess = "Full Access lets ThumbFree send your words to its keyboard. Your voice stays on this iPhone."

    /// The keyboard step, with ThumbFree not in iOS's list of keyboards.
    func launchKeyboardStep(_ arguments: [String] = []) -> XCUIApplication {
        ThumbFreeUI.launch(welcome: true, arguments: ["-TFWelcomeStep", "1", "-TFModelFixture", ThumbFreeUI.jfk,
                                                      "-AppleKeyboards", "(\"en_US@sw=QWERTY\")"] + arguments)
    }

    /// Two looks at `element` 1.5 s apart differ while the video plays (every beat has its own caption) and match while
    /// it holds still.
    func moves(_ element: XCUIElement) -> Bool {
        let first = element.screenshot().pngRepresentation
        Thread.sleep(forTimeInterval: 1.5)
        return element.screenshot().pngRepresentation != first
    }

    // The guide plays under the title and the Full Access line, its frame at most 340 points tall, above Open Settings,
    // the big button, with Not now under it. The list of rows is not there.
    func testTheKeyboardStepShowsTheGuideAndTheFullAccessLine() {
        let app = launchKeyboardStep()
        let guide = ThumbFreeUI.element("welcome.guide", in: app)
        XCTAssertTrue(guide.waitForExistence(timeout: 10))
        XCTAssertEqual(ThumbFreeUI.element("welcome.title", in: app).label, "Add the ThumbFree keyboard")
        let line = app.staticTexts[Self.fullAccess]
        let primary = app.buttons["welcome.primary"]
        XCTAssertTrue(line.exists)
        XCTAssertEqual(primary.label, "Open Settings")
        XCTAssertTrue(app.buttons["welcome.notNow"].exists)
        XCTAssertLessThanOrEqual(line.frame.maxY, guide.frame.minY)
        XCTAssertLessThanOrEqual(guide.frame.maxY, primary.frame.minY)
        XCTAssertLessThanOrEqual(guide.frame.height, 340.5, "the guide's frame is taller than 340 points")
        XCTAssertFalse(ThumbFreeUI.element("welcome.guideList", in: app).exists, "the list shows")
        XCTAssertTrue(moves(guide), "the guide does not play")
        ThumbFreeUI.shot("floating-guide-step")
    }

    // Open Settings opens Settings within the window's second (at once on the Simulator, which has no Picture in
    // Picture). Back in the app, with the keyboard still not added, the step says where else to look and the guide
    // plays again.
    func testOpenSettingsOpensSettingsAndTheGuidePlaysOnBack() {
        let settings = XCUIApplication(bundleIdentifier: "com.apple.Preferences")
        let app = launchKeyboardStep()
        let guide = ThumbFreeUI.element("welcome.guide", in: app)
        XCTAssertTrue(guide.waitForExistence(timeout: 10))
        app.buttons["welcome.primary"].tap()
        XCTAssertTrue(settings.wait(for: .runningForeground, timeout: 3), "Settings did not open")
        ThumbFreeUI.shot("floating-guide-settings")
        app.activate() // the app switcher's way back
        XCTAssertTrue(ThumbFreeUI.wait(for: ThumbFreeUI.element("welcome.title", in: app), toContain: "The keyboard is still off", timeout: 5))
        XCTAssertTrue(app.staticTexts["In Settings, open General, Keyboard, Keyboards, then add ThumbFree."].exists)
        XCTAssertEqual(app.buttons["welcome.primary"].label, "Open Settings again")
        XCTAssertTrue(guide.waitForExistence(timeout: 5))
        XCTAssertTrue(moves(guide), "the guide does not play after the trip")
        settings.terminate()
    }

    // Paused for screenshots: one still frame (Allow Full Access, turned on) under the Full Access line.
    func testPausedShowsOneStillFrame() {
        let app = launchKeyboardStep(["-TFGuidePaused", "YES"])
        let still = ThumbFreeUI.element("welcome.guideStill", in: app)
        XCTAssertTrue(still.waitForExistence(timeout: 10))
        XCTAssertFalse(ThumbFreeUI.element("welcome.guide", in: app).exists)
        XCTAssertTrue(app.staticTexts[Self.fullAccess].exists)
        XCTAssertFalse(moves(still), "the still frame moves")
    }
}
