import XCTest

@MainActor final class SessionScreenUITests: XCTestCase {
    func testTheDictateLinkShowsTheSessionScreenUntilEndSession() throws {
        let app = ThumbFreeUI.launch()
        XCUIDevice.shared.system.open(try XCTUnwrap(URL(string: "thumbfree://dictate?take=\(UUID().uuidString)")))
        let listening = app.staticTexts["session.title"]
        XCTAssertTrue(ThumbFreeUI.wait(for: listening, toContain: "Listening", timeout: 10))
        // A plain link (no trusted host) shows the swipe-back instructions, not an automatic return.
        XCTAssertTrue(app.staticTexts["session.subtitle"].label.contains("Swipe right"))
        app.buttons["session.end"].tap()
        XCTAssertTrue(listening.waitForNonExistence(timeout: 5))
        XCTAssertTrue(ThumbFreeUI.onHome(app, timeout: 5), "not back on Home")
    }

    // Guards the ScrollView requirement: at the largest accessibility text size, the 140pt bubble art must never push
    // the title or the End button off screen for good, on the swipe-back state (the tallest realistic content: title,
    // a wrapped multi-line sub-line, and the swipe-hint finger, all before the End button).
    func testTheSwipeBackScreenReachesTitleAndEndAtTheLargestAccessibilityTextSize() throws {
        let app = ThumbFreeUI.launch(arguments: ["-UIPreferredContentSizeCategoryName", "UICTContentSizeCategoryAccessibilityXXXL"])
        XCUIDevice.shared.system.open(try XCTUnwrap(URL(string: "thumbfree://dictate?take=\(UUID().uuidString)")))
        let title = app.staticTexts["session.title"]
        XCTAssertTrue(ThumbFreeUI.wait(for: title, toContain: "Listening", timeout: 10))
        XCTAssertTrue(title.isHittable, "the title must stay reachable at the largest accessibility text size")
        let end = app.buttons["session.end"]
        if !end.isHittable { app.swipeUp() } // the ScrollView: scroll down if the bubble art pushed the button off screen
        XCTAssertTrue(ThumbFreeUI.wait(until: 5) { end.isHittable },
                       "End session must be reachable (scrolling if needed) at the largest accessibility text size")
    }
}
