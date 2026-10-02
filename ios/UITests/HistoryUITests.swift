import XCTest

@MainActor final class HistoryUITests: XCTestCase {
    // One take with the keyboard in the try screen's box: back on Home the session goes on until End session; History
    // shows the take under Today as typed, Copy copies it, Transcribe again keeps what was typed, and Clear all asks first.
    func testHistoryShowsCopiesAndClearsATake() throws {
        KeyboardSetup.ensureReady()
        let (app, field) = ThumbFreeUI.launchTry()
        field.tap()
        KeyboardSetup.switchToThumbFree(in: app)
        let mic = app.buttons["keyboard.mic"]
        mic.tap()
        XCTAssertTrue(ThumbFreeUI.wait(for: app.staticTexts["keyboard.status"], toContain: "Recording", timeout: 15))
        sleep(2)
        mic.tap()
        XCTAssertTrue(ThumbFreeUI.wait(for: field, toContain: "ask not what your country", timeout: 20))
        app.buttons["try.notNow"].tap()
        let end = app.buttons["home.endSession"]
        XCTAssertTrue(end.waitForExistence(timeout: 5), "Home does not show the live session")
        XCTAssertTrue(app.staticTexts["Ready, mic on"].exists)
        end.tap()
        XCTAssertTrue(end.waitForNonExistence(timeout: 5), "End session did not end the session")
        app.tabBars.buttons["History"].tap()
        XCTAssertTrue(app.staticTexts["Today"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.staticTexts["Typed"].exists)
        app.buttons["Copy"].tap()
        XCTAssertTrue(app.buttons["Copied"].waitForExistence(timeout: 2))
        app.buttons["Transcribe again"].tap()
        XCTAssertTrue(app.staticTexts["Transcribed again. This text wasn't typed in."].waitForExistence(timeout: 20))
        XCTAssertTrue(app.staticTexts["Typed"].exists) // a typed take keeps its status
        app.buttons["history.clearAll"].tap()
        let alert = app.alerts["Delete all takes?"]
        XCTAssertTrue(alert.waitForExistence(timeout: 5))
        XCTAssertTrue(alert.staticTexts["This deletes 1 take and its recording from this iPhone. It can't be undone."].exists)
        alert.buttons["Delete all"].tap()
        XCTAssertTrue(app.staticTexts["No takes yet"].waitForExistence(timeout: 5))
    }

    // The "every page scrolls" rule: at the largest accessibility text size, the empty page's Try it must
    // still be reachable, scrolled to if it does not fit.
    func testTryItStaysReachableAtTheLargestTextSize() throws {
        let app = ThumbFreeUI.launch(arguments: ["-UIPreferredContentSizeCategoryName", "UICTContentSizeCategoryAccessibilityXXXL"])
        app.tabBars.buttons["History"].tap()
        let tryIt = app.buttons["Try it"]
        XCTAssertTrue(tryIt.waitForExistence(timeout: 5))
        if !tryIt.isHittable { app.swipeUp() }
        XCTAssertTrue(tryIt.isHittable)
    }
}
