import XCTest

@MainActor final class SettingsUITests: XCTestCase {
    // Settings as on Android, adapted to iOS: Setup, both speech models (the multilingual one folds its 25 languages, and
    // VoiceOver hears which model each button is for), Session length (5 minutes until you pick another), History, a row
    // that opens the Dictionary, About, and the welcome screens again.
    func testSettingsShowsEverySectionAndItsChoices() throws {
        let app = ThumbFreeUI.launch()
        app.tabBars.buttons["Settings"].tap()
        XCTAssertTrue(app.staticTexts["Microphone"].waitForExistence(timeout: 5))
        // In iOS's list (the Simulator's) but not seen since the reset: no box here, so any text box will do, and a small
        // Open Settings for Full Access.
        XCTAssertTrue(app.descendants(matching: .any).matching(NSPredicate(format: "label CONTAINS %@", "Tap a text box and switch to ThumbFree")).firstMatch.exists)
        XCTAssertTrue(app.buttons["setup.fullAccess"].exists)
        XCTAssertTrue(app.staticTexts["English (recommended)"].exists)
        XCTAssertTrue(app.staticTexts["Multilingual"].exists)
        XCTAssertTrue(app.buttons["Delete English"].exists)
        XCTAssertTrue(app.buttons["Delete Multilingual"].exists)
        let languages = find("settings.languages", in: app)
        languages.tap()
        XCTAssertTrue(ThumbFreeUI.wait(until: 5) { app.staticTexts.containing(NSPredicate(format: "label CONTAINS 'Maltese'")).count > 0 })
        languages.tap() // folded again: a shorter list to scroll
        let session = find("settings.session", in: app)
        XCTAssertTrue(ThumbFreeUI.wait(for: session, toContain: "5 minutes", timeout: 5))
        session.tap()
        app.buttons["2 minutes"].tap()
        XCTAssertTrue(ThumbFreeUI.wait(for: session, toContain: "2 minutes", timeout: 5))
        XCTAssertTrue(ThumbFreeUI.wait(for: find("settings.keepCount", in: app), toContain: "200 takes", timeout: 5))
        find("settings.dictionary", in: app).tap()
        XCTAssertTrue(ThumbFreeUI.element("dictionary.field", in: app).waitForExistence(timeout: 5))
        app.navigationBars.buttons.firstMatch.tap()
        XCTAssertTrue(find("settings.credits", in: app).exists)
        find("settings.welcome", in: app, above: true).tap()
        XCTAssertTrue(ThumbFreeUI.wait(for: ThumbFreeUI.element("welcome.title", in: app), toContain: "Talk. It types.", timeout: 5))
    }

    // Automatic return: on by default, and a plain switch to turn it off.
    func testAutoReturnIsOnAndCanBeTurnedOff() throws {
        let app = ThumbFreeUI.launch()
        app.tabBars.buttons["Settings"].tap()
        let toggle = find("settings.autoReturn", in: app)
        XCTAssertTrue(toggle.waitForExistence(timeout: 5))
        XCTAssertEqual(toggle.value as? String, "1")
        // The identifier sits on the row (label + switch merged for accessibility); that outer element's own tap
        // target doesn't reach the native control. The real switch is a distinct inner element; tap that instead.
        toggle.switches.firstMatch.tap()
        XCTAssertTrue(ThumbFreeUI.wait(until: 10) { toggle.value as? String == "0" })
        toggle.switches.firstMatch.tap()
        XCTAssertTrue(ThumbFreeUI.wait(until: 10) { toggle.value as? String == "1" })
    }

    // A model whose download failed offers Try again, and VoiceOver hears which model it is for. (The fixture is no
    // Multilingual model, so that download fails its file check at once.)
    func testAFailedDownloadNamesItsModel() throws {
        let app = ThumbFreeUI.launch(arguments: ["-TFModelFixture", ThumbFreeUI.jfk])
        app.tabBars.buttons["Settings"].tap()
        let download = app.buttons["Download Multilingual"]
        XCTAssertTrue(download.waitForExistence(timeout: 5))
        for _ in 0..<8 { if download.isHittable { break } else { app.swipeUp() } }
        download.tap()
        XCTAssertTrue(app.buttons["Try downloading Multilingual again"].waitForExistence(timeout: 10))
    }

    /// Scrolls the list toward the element until it is on screen: down the page, or up for one `above` the screen (a Form
    /// builds only the rows near the screen, so a row not built yet is where the test says); a row a swipe carried past
    /// the top is scrolled back to. It stops looking once the row shows (each look queries the screen).
    private func find(_ id: String, in app: XCUIApplication, above: Bool = false) -> XCUIElement {
        let element = ThumbFreeUI.element(id, in: app)
        for _ in 0..<12 {
            guard !(element.exists && element.isHittable) else { break }
            let up = element.exists ? element.frame.midY < app.frame.midY : above
            if up { app.swipeDown() } else { app.swipeUp() }
        }
        return element
    }
}
