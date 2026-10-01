import XCTest

@MainActor final class GuideUITests: XCTestCase {
    /// Opens "How to use it in other apps", paused (as with Reduce Motion), with automatic return on or off: at launch
    /// (`-TFOpenGuide`, Debug builds), or with a tap on the Try tab's link, as a user does (`byLink`).
    func openGuide(autoReturn: Bool, byLink: Bool = false) -> XCUIApplication {
        let app = ThumbFreeUI.launch(arguments: ["-TFGuidePaused", "YES", "-TFAutoReturn", autoReturn ? "YES" : "NO",
                                                 "-TFOpenGuide", byLink ? "NO" : "YES"])
        guard byLink else { return app }
        let link = app.buttons["try.guide"]
        // Slow swipes: a fast one can carry the link past the top, and a tap then lands while the page scrolls back.
        for _ in 0..<10 { if link.isHittable { break } else { app.swipeUp(velocity: .slow) } }
        sleep(1) // let the page stop scrolling: a tap on a moving page only stops it
        link.tap()
        return app
    }

    // The Try tab's link opens the walkthrough, chat only: Next walks its six beats in order, the globe first, and Back
    // goes back. Putting ThumbFree first comes after it, with one tip.
    func testTheGuideStepsThroughEveryBeat() throws {
        let app = openGuide(autoReturn: true, byLink: true)
        let caption = ThumbFreeUI.element("guide.caption", in: app)
        XCTAssertTrue(ThumbFreeUI.wait(for: caption, toContain: "Step 1 of 6. Touch and hold the globe key, then pick ThumbFree.", timeout: 5))
        let next = app.buttons["guide.next"]
        XCTAssertGreaterThanOrEqual(next.frame.height, 44, "Next's tap target is under 44 points")
        XCTAssertGreaterThanOrEqual(app.buttons["guide.back"].frame.height, 44, "Back's tap target is under 44 points")
        for (step, words) in ["Tap the mic on the ThumbFree keyboard.", "ThumbFree opens for a moment and takes you back.",
                              "Talk.", "Tap the mic again to stop.", "Your words appear"].enumerated() {
            next.tap()
            XCTAssertTrue(ThumbFreeUI.wait(for: caption, toContain: "Step \(step + 2) of 6. \(words)", timeout: 3))
        }
        XCTAssertFalse(next.isEnabled)
        app.buttons["guide.back"].tap()
        XCTAssertTrue(ThumbFreeUI.wait(for: caption, toContain: "Step 5 of 6.", timeout: 3))
        XCTAssertFalse(app.buttons["Browser"].exists, "the walkthrough is chat only")
        XCTAssertTrue(ThumbFreeUI.wait(for: ThumbFreeUI.element("putFirst.caption", in: app), toContain: "Step 1 of 4.", timeout: 3))
        XCTAssertTrue(app.staticTexts["Touch and hold the globe key to jump straight to ThumbFree."].exists)
    }

    // The return beat follows the setting: with automatic return off, the swipe back.
    func testTheReturnBeatFollowsTheSetting() throws {
        let app = openGuide(autoReturn: false)
        let caption = ThumbFreeUI.element("guide.caption", in: app)
        XCTAssertTrue(ThumbFreeUI.wait(for: caption, toContain: "Step 1 of 6.", timeout: 5))
        app.buttons["guide.next"].tap()
        app.buttons["guide.next"].tap()
        XCTAssertTrue(ThumbFreeUI.wait(for: caption, toContain: "Step 3 of 6. ThumbFree opens. Swipe right", timeout: 3))
    }
}
