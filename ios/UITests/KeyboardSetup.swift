import XCTest

/// Turns on the ThumbFree keyboard with Allow Full Access through the Settings app, once per test run, unless the keyboard
/// already has it (a Simulator keeps the grant). Checked on iOS 26.5; `tools/sim-enable-keyboard.sh` (run by
/// `tools/test-app.sh`) does the parts Settings cannot.
@MainActor enum KeyboardSetup {
    static let keyboardID = "io.github.kabrapratik28.thumbfree.keyboard"
    private static var ready = false

    static func ensureReady() {
        guard !ready else { return }
        if hasFullAccess() {
            ready = true
            return
        }
        let settings = XCUIApplication(bundleIdentifier: "com.apple.Preferences")
        settings.terminate()
        settings.launch()
        settings.staticTexts["General"].tap()
        settings.staticTexts["Keyboard"].tap()
        settings.cells["KEYBOARDS"].tap() // the nav bar also says "Keyboards", so tap the cell by its identifier
        let row = settings.cells[keyboardID]
        if !row.waitForExistence(timeout: 5) {
            settings.cells["AddNewKeyboard"].tap()
            settings.staticTexts["ThumbFree"].tap()
        }
        XCTAssertTrue(row.waitForExistence(timeout: 10))
        row.tap()
        let fullAccess = settings.switches["Allow Full Access"]
        XCTAssertTrue(fullAccess.waitForExistence(timeout: 5))
        if fullAccess.value as? String != "1" {
            // A plain tap lands on the label; the switch's knob is at the right end of the row.
            fullAccess.coordinate(withNormalizedOffset: CGVector(dx: 0.93, dy: 0.5)).tap()
            let allow = settings.alerts.buttons["Allow"]
            if allow.waitForExistence(timeout: 3) { allow.tap() }
        }
        XCTAssertEqual(fullAccess.value as? String, "1")
        settings.terminate()
        ready = true
    }

    /// Whether ThumbFree's keyboard, up in the app's Try field, has Full Access: its status line does not ask for it.
    private static func hasFullAccess() -> Bool {
        let app = ThumbFreeUI.launch()
        ThumbFreeUI.element("try.field", in: app).tap()
        switchToThumbFree(in: app)
        let status = app.staticTexts["keyboard.status"]
        return status.waitForExistence(timeout: 3) && !status.label.hasPrefix("Full Access is off")
    }

    /// Taps the system globe ("Next keyboard", below the keyboard) until ThumbFree's mic key shows. Short looks between
    /// taps (a wrong keyboard costs 1.5 s, not 3), and rounds enough to come back once to a keyboard that was still
    /// starting when a look gave up on it.
    static func switchToThumbFree(in app: XCUIApplication) {
        let mic = app.buttons["keyboard.mic"]
        for _ in 0..<6 {
            if mic.waitForExistence(timeout: 1.5) { return }
            app.buttons["Next keyboard"].firstMatch.tap() // more than one matches "Next keyboard" on some layers
        }
        XCTAssertTrue(mic.waitForExistence(timeout: 5), "the ThumbFree keyboard did not show")
    }
}
