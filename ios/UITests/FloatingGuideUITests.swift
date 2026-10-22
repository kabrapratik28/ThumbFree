import XCTest

/// The keyboard step's guide: the taps in Settings as a still numbered list in an IN SETTINGS frame, under why a
/// keyboard, with why Full Access under it, and Open Settings, which floats the guide's video over Settings where iOS has
/// Picture in Picture and opens Settings all the same where it has not (the iPhone Simulator). Reduce Motion and
/// VoiceOver, which keep the video from floating, are not launch arguments: `FloatingGuideTests` checks that rule.
@MainActor final class FloatingGuideUITests: XCTestCase {
    static let why = "To type in other apps, ThumbFree uses its own keyboard. Add it in Settings:"

    /// The keyboard step, with ThumbFree not in iOS's list of keyboards.
    func launchKeyboardStep(_ arguments: [String] = []) -> XCUIApplication {
        ThumbFreeUI.launch(welcome: true, arguments: ["-TFWelcomeStep", "1", "-TFModelFixture", ThumbFreeUI.jfk,
                                                      "-AppleKeyboards", "(\"en_US@sw=QWERTY\")"] + arguments)
    }

    /// Two looks at `element` 1.5 s apart differ while something in it moves.
    func moves(_ element: XCUIElement) -> Bool {
        let first = element.screenshot().pngRepresentation
        Thread.sleep(forTimeInterval: 1.5)
        return element.screenshot().pngRepresentation != first
    }

    // Under the title and why a keyboard, the five taps as a still list, nothing moving (the video plays unseen behind
    // it, for the floating window), then why Full Access, above Open Settings, the big button, with Not now under it.
    func testTheKeyboardStepShowsTheStepsStillWithWhyFullAccess() {
        let app = launchKeyboardStep()
        let guide = ThumbFreeUI.element("welcome.guide", in: app)
        XCTAssertTrue(guide.waitForExistence(timeout: 10))
        XCTAssertEqual(ThumbFreeUI.element("welcome.title", in: app).label, "Add the ThumbFree keyboard")
        let why = app.staticTexts[Self.why], fullAccess = ThumbFreeUI.element("welcome.fullAccess", in: app)
        let primary = app.buttons["welcome.primary"]
        XCTAssertTrue(why.exists)
        XCTAssertEqual(fullAccess.label, "Full Access lets the keyboard get your words from the ThumbFree app. "
            + "iOS warns that a keyboard could send what you type. ThumbFree never does.")
        XCTAssertEqual(guide.label,
                       "In Settings, open Keyboards, turn on ThumbFree, turn on Allow Full Access, confirm, then return to ThumbFree.")
        XCTAssertEqual(primary.label, "Open Settings")
        XCTAssertTrue(app.buttons["welcome.notNow"].exists)
        XCTAssertLessThanOrEqual(why.frame.maxY, guide.frame.minY)
        XCTAssertLessThanOrEqual(guide.frame.maxY, fullAccess.frame.minY)
        XCTAssertLessThanOrEqual(fullAccess.frame.maxY, primary.frame.minY)
        XCTAssertFalse(moves(guide), "the steps move")
        ThumbFreeUI.shot("floating-guide-step")
    }

    // Open Settings opens Settings within the window's second (at once on the Simulator, which has no Picture in
    // Picture). Back in the app, with the keyboard still not added, the step says where else to look, over the same list.
    func testOpenSettingsOpensSettingsAndTheStepsStayOnBack() {
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
        settings.terminate()
    }
}
