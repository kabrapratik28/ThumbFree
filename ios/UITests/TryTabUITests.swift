import XCTest

@MainActor final class TryTabUITests: XCTestCase {
    // JFK plays as the microphone and the fixed-text engine answers: a tap, 3 s, a tap, and the words are in the box.
    // The Try tab stays simple: no Dictionary card after the first take (the Dictionary has its own tab).
    func testTheInAppMicTypesIntoThePracticeBoxAndHistoryKeepsIt() throws {
        let app = ThumbFreeUI.launch()
        let mic = ThumbFreeUI.element("try.mic", in: app)
        XCTAssertEqual(mic.label, "Start dictation")
        mic.tap()
        XCTAssertTrue(ThumbFreeUI.wait(for: ThumbFreeUI.element("try.status", in: app), toContain: "Listening", timeout: 10))
        XCTAssertTrue(app.staticTexts["Ready, mic on"].exists)
        XCTAssertEqual(mic.label, "Stop dictation", "VoiceOver does not hear the stop key while it records")
        sleep(3)
        mic.tap()
        XCTAssertTrue(ThumbFreeUI.wait(for: ThumbFreeUI.element("try.field", in: app), toContain: "ask not what your country", timeout: 20))
        app.tabBars.buttons["History"].tap()
        XCTAssertTrue(app.staticTexts["Typed"].waitForExistence(timeout: 5))
        app.tabBars.buttons["Try"].tap()
        XCTAssertTrue(ThumbFreeUI.element("try.field", in: app).waitForExistence(timeout: 5))
        XCTAssertFalse(app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", "spell your way")).firstMatch.exists)
    }

    // The setup card shows only what is missing. The keyboard follows iOS's list of keyboards and the keyboard's mark:
    // in the list (tools/sim-enable-keyboard.sh puts it there) but not seen yet (the reset deleted the mark), it points
    // at the box, with a small Open Settings for Full Access instead of Turn on; switching to ThumbFree there takes the
    // line away within a second, the app in front all along.
    func testTheKeyboardLineGoesWhenTheKeyboardComesUp() throws {
        KeyboardSetup.ensureReady()
        let app = ThumbFreeUI.launch()
        let pointer = label("Tap the box below and switch to ThumbFree", in: app)
        XCTAssertTrue(pointer.waitForExistence(timeout: 5), "no pointer at the box")
        XCTAssertFalse(app.buttons["setup.keyboard"].exists, "an added keyboard is not turned on again")
        XCTAssertTrue(app.buttons["setup.fullAccess"].exists, "no way to Allow Full Access")
        XCTAssertFalse(label("English speech model", in: app).exists, "a done item still shows")
        ThumbFreeUI.element("try.field", in: app).tap()
        KeyboardSetup.switchToThumbFree(in: app)
        XCTAssertTrue(pointer.waitForNonExistence(timeout: 3), "the line stayed; the keyboard says: \(app.staticTexts["keyboard.status"].label)")
        XCTAssertFalse(app.buttons["setup.fullAccess"].exists)
    }

    // Not in iOS's list (a launch argument stands in for it: the argument domain comes before the global one): the line
    // sends you to Settings.
    func testAKeyboardNotAddedIsTurnedOnInSettings() throws {
        let app = ThumbFreeUI.launch(arguments: ["-AppleKeyboards", "(\"en_US@sw=QWERTY\")"])
        XCTAssertTrue(label("Add the keyboard in Settings", in: app).waitForExistence(timeout: 5), app.debugDescription)
        app.buttons["setup.keyboard"].tap()
        XCTAssertTrue(XCUIApplication(bundleIdentifier: "com.apple.Preferences").wait(for: .runningForeground, timeout: 10))
    }

    /// The first element whose label contains `text` (a setup row reads its title and line as one).
    private func label(_ text: String, in app: XCUIApplication) -> XCUIElement {
        app.descendants(matching: .any).matching(NSPredicate(format: "label CONTAINS %@", text)).firstMatch
    }

    // No model yet (a small file from the test bundle stands in for it, fetched from disk): the Try tab offers it, the
    // mic waits, and once the model is in, the mic is ready.
    func testTheTryTabGetsAMissingModel() throws {
        let app = ThumbFreeUI.launch(arguments: ["-TFModelFixture", ThumbFreeUI.jfk])
        let status = ThumbFreeUI.element("try.status", in: app)
        XCTAssertTrue(ThumbFreeUI.wait(for: status, toContain: "Get the speech model above to start.", timeout: 10))
        let get = app.buttons["try.getModel"]
        XCTAssertTrue(get.waitForExistence(timeout: 5))
        get.tap()
        XCTAssertTrue(get.waitForNonExistence(timeout: 10))
        XCTAssertTrue(ThumbFreeUI.wait(for: status, toContain: "Tap to talk", timeout: 10))
    }
}
