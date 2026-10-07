import XCTest

/// On a real iPhone: the foreground call, then adding the probe keyboard with Full Access in Settings, the keyboard's
/// Clean and Burst, then ten calls from the background. Prints PROBE lines for the log.
final class ProbeUITests: XCTestCase {
    let raw = "Yes, yes, I booked a table for, like, seven, no, seven thirty at the Italian place on Main Street."

    func testDevice() throws {
        var app = launch()
        print("PROBE APP STATUS:", app.staticTexts["app.status"].label)
        app.buttons["fg.run"].tap()
        let fg = app.staticTexts["fg.result"]
        _ = XCTWaiter().wait(for: [XCTNSPredicateExpectation(predicate: NSPredicate(format: "label BEGINSWITH 'fg '"), object: fg)], timeout: 90)
        print("PROBE FG RESULT:", fg.label)
        addKeyboardWithFullAccess()
        app = launch()
        let field = app.textFields["field"]
        XCTAssertTrue(field.waitForExistence(timeout: 10))
        field.tap()
        let clean = app.buttons["fm.clean"]
        for _ in 0..<8 where !clean.waitForExistence(timeout: 1.5) {
            let tip = app.buttons["Continue"]
            if tip.exists, tip.isHittable { tip.tap() }
            app.buttons["Next keyboard"].firstMatch.tap()
        }
        let status = app.staticTexts["fm.status"]
        print("PROBE KB BEFORE:", clean.exists ? status.label : "keyboard never showed")
        if clean.exists {
            clean.tap()
            _ = XCTWaiter().wait(for: [XCTNSPredicateExpectation(predicate: NSPredicate(format: "label BEGINSWITH 'done' OR label BEGINSWITH 'error'"), object: status)], timeout: 90)
            print("PROBE KB CLEAN:", status.label.replacingOccurrences(of: "\n", with: " | "))
            print("PROBE KB FIELD:", field.value as? String ?? "")
            app.buttons["fm.burst"].tap()
            _ = XCTWaiter().wait(for: [XCTNSPredicateExpectation(predicate: NSPredicate(format: "label BEGINSWITH 'burst done'"), object: status)], timeout: 120)
            print("PROBE KB BURST:", status.label.replacingOccurrences(of: "\n", with: " | "))
        }
        app.buttons["bg.arm"].tap()
        XCUIDevice.shared.press(.home)
        sleep(38)
        app.activate()
        let bg = app.staticTexts["bg.result"]
        _ = XCTWaiter().wait(for: [XCTNSPredicateExpectation(predicate: NSPredicate(format: "label CONTAINS 'call9'"), object: bg)], timeout: 30)
        print("PROBE BG LOG:", bg.label.replacingOccurrences(of: "\n", with: " | "))
    }

    private func launch() -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = ["-raw", raw, "-armed", "NO", "-bgLog", "none"]
        app.launch()
        return app
    }

    /// Settings > General > Keyboard > Keyboards > Add New Keyboard > FMKeyboard, then Allow Full Access.
    private func addKeyboardWithFullAccess() {
        let settings = XCUIApplication(bundleIdentifier: "com.apple.Preferences")
        settings.terminate()
        settings.launch()
        settings.staticTexts["General"].tap()
        settings.staticTexts["Keyboard"].tap()
        settings.cells["KEYBOARDS"].tap()
        let row = settings.cells["io.github.kabrapratik28.fmprobe.keyboard"]
        if !row.waitForExistence(timeout: 4) {
            settings.cells["AddNewKeyboard"].tap()
            let pick = settings.staticTexts["FMKeyboard"].firstMatch
            if !pick.waitForExistence(timeout: 5) { settings.swipeUp() }
            pick.tap()
        }
        XCTAssertTrue(row.waitForExistence(timeout: 10), "probe keyboard not added: \(settings.debugDescription)")
        row.tap()
        let fullAccess = settings.switches["Allow Full Access"]
        XCTAssertTrue(fullAccess.waitForExistence(timeout: 5))
        if fullAccess.value as? String != "1" {
            fullAccess.coordinate(withNormalizedOffset: CGVector(dx: 0.93, dy: 0.5)).tap()
            let allow = settings.alerts.buttons["Allow"]
            if allow.waitForExistence(timeout: 3) { allow.tap() }
        }
        print("PROBE FULL ACCESS:", fullAccess.value as? String ?? "?")
        settings.terminate()
    }
}
