import XCTest

@MainActor final class HistoryUITests: XCTestCase {
    // One take from the Try tab: History shows it under Today as typed, Copy copies it, Transcribe again keeps what
    // was typed, and Clear all asks first.
    func testHistoryShowsCopiesAndClearsATake() throws {
        let app = ThumbFreeUI.launch()
        let mic = ThumbFreeUI.element("try.mic", in: app)
        mic.tap()
        XCTAssertTrue(ThumbFreeUI.wait(for: ThumbFreeUI.element("try.status", in: app), toContain: "Listening", timeout: 10))
        sleep(2)
        mic.tap()
        XCTAssertTrue(ThumbFreeUI.wait(for: ThumbFreeUI.element("try.field", in: app), toContain: "ask not what your country", timeout: 20))
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
