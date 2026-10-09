import XCTest

/// The screen the keyboard's first mic tap opens: "Starting the microphone" while the mic starts, then the way back to
/// your app, the swipe along the bottom edge on an iPhone with a home indicator, iOS's top-left link ringed on one with
/// a Home button, and only the link's words on an iPad. Trips from Messages come back by hand, as in the store build
/// (`turnOffAutoReturn`). A test for one kind of screen skips on the others.
@MainActor final class SessionScreenUITests: XCTestCase {
    /// This Simulator's screen: an iPad, an iPhone with a Home button (iOS 26 runs on one only, 667 points tall), or an
    /// iPhone with a home indicator.
    enum Screen { case homeIndicator, homeButton, iPad }

    static var screen: Screen {
        if UIDevice.current.model.hasPrefix("iPad") { return .iPad }
        return XCUIApplication(bundleIdentifier: "com.apple.springboard").frame.height <= 667 ? .homeButton : .homeIndicator
    }

    static let swipeLine = "Swipe right along the bottom edge to go back."
    static let backLinkLine = "Tap your app’s name at the top left to go back, or use the App Switcher."

    func testTheDictateLinkShowsTheSessionScreenUntilEndSession() throws {
        let app = ThumbFreeUI.launch()
        XCUIDevice.shared.system.open(try XCTUnwrap(URL(string: "thumbfree://dictate?take=\(UUID().uuidString)")))
        let listening = app.staticTexts["session.title"]
        XCTAssertTrue(ThumbFreeUI.wait(for: listening, toContain: "Listening", timeout: 10))
        // A plain link (no trusted host) shows the way back, not an automatic return.
        XCTAssertEqual(app.staticTexts["session.subtitle"].label, Self.screen == .homeIndicator ? Self.swipeLine : Self.backLinkLine)
        app.buttons["session.end"].tap()
        XCTAssertTrue(listening.waitForNonExistence(timeout: 5))
        XCTAssertTrue(ThumbFreeUI.onHome(app, timeout: 5), "not back on Home")
    }

    // Guards the ScrollView requirement: at the largest accessibility text size, the bubble art must never push the title
    // or the End button off screen for good, on the swipe-back state (the tallest realistic content: title and a wrapped
    // multi-line sub-line, before the End button).
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

    // An iPhone with a home indicator. While the mic starts (held 8 s here, longer than the cue's 3 rounds of 4.95 s) the
    // screen says so, with no words and no cue, the line's room kept; then the swipe words and the cue on the bottom edge,
    // playing from then on rather than from the screen's first moment. It rests after its rounds and plays again on a tap on the page, which
    // leaves the session on. End session, a quiet button at least 44 points each way, still ends it.
    func testTheSwipeCueWaitsForTheMicThenPlaysOnTheBottomEdge() throws {
        try XCTSkipUnless(Self.screen == .homeIndicator, "the swipe shows on an iPhone with a home indicator")
        let (app, _) = tripFromMessages(arguments: ["-TFMicDelayMs", "8000"])
        let title = app.staticTexts["session.title"], line = app.staticTexts["session.subtitle"]
        let cue = ThumbFreeUI.element("session.cue", in: app), end = app.buttons["session.end"]
        XCTAssertTrue(ThumbFreeUI.wait(for: title, toContain: "Starting the microphone", timeout: 10))
        let starting = Date(), titleTop = title.frame.minY
        ThumbFreeUI.shot("session-starting")
        // Each look reads the cue and the line before the title: the mic only comes on, so either one seen before a title
        // that still says Starting was there while the mic started.
        var early: Set<String> = []
        XCTAssertTrue(ThumbFreeUI.wait(until: 20) {
            let seen = [cue.exists ? "the cue" : nil, line.exists ? "the line" : nil].compactMap { $0 }
            if title.label.contains("Listening") { return true }
            early.formUnion(seen)
            return false
        }, "the mic did not come on: \(title.label)")
        XCTAssertEqual(early, [], "shown while the mic started")
        XCTAssertGreaterThan(Date().timeIntervalSince(starting), 4.95, "the mic came on too soon to tell when the cue starts")
        XCTAssertEqual(line.label, Self.swipeLine)
        XCTAssertEqual(title.frame.minY, titleTop, accuracy: 1, "the title moved when the line showed")
        XCTAssertTrue(cue.waitForExistence(timeout: 2), "no cue once the mic is on")
        XCTAssertEqual(cue.frame.maxY, app.frame.maxY, accuracy: 30, "the cue \(cue.frame) is not on the bottom edge")
        XCTAssertTrue(moves(cue), "the cue does not play once the mic is on")
        XCTAssertTrue(end.exists, "the title, the line and End session are not separate elements")
        XCTAssertGreaterThanOrEqual(end.frame.width, 44)
        XCTAssertGreaterThanOrEqual(end.frame.height, 44)
        XCTAssertTrue(ThumbFreeUI.wait(until: 15) { !self.moves(cue) }, "the cue does not rest after its rounds")
        ThumbFreeUI.shot("session-swipe-rest")
        title.tap()
        XCTAssertTrue(moves(cue), "a tap on the page does not play the cue again")
        XCTAssertTrue(title.label.contains("Listening"), "a tap on the page changed the session: \(title.label)")
        end.tap()
        XCTAssertTrue(title.waitForNonExistence(timeout: 5), "End session did not end the session")
    }

    // With Reduce Motion (no launch argument: Settings turns it on) the cue shows its still at once, and a tap on the page
    // does not play it.
    func testWithReduceMotionTheCueStaysStill() throws {
        try XCTSkipUnless(Self.screen == .homeIndicator, "the swipe shows on an iPhone with a home indicator")
        Self.setReduceMotion(true)
        addTeardownBlock { @MainActor in Self.setReduceMotion(false) }
        let app = ThumbFreeUI.launch()
        XCUIDevice.shared.system.open(try XCTUnwrap(URL(string: "thumbfree://dictate?take=\(UUID().uuidString)")))
        let title = app.staticTexts["session.title"]
        XCTAssertTrue(ThumbFreeUI.wait(for: title, toContain: "Listening", timeout: 10))
        let cue = ThumbFreeUI.element("session.cue", in: app)
        XCTAssertTrue(cue.waitForExistence(timeout: 2), "no cue once the mic is on")
        XCTAssertFalse(moves(cue), "the cue plays with Reduce Motion on")
        title.tap()
        XCTAssertFalse(moves(cue), "a tap on the page plays the cue with Reduce Motion on")
        app.buttons["session.end"].tap()
    }

    // An iPhone with a Home button has no edge to swipe: the words say to tap your app's name at the top left, and the
    // ring sits around the name iOS writes there, which takes you back; it plays its rounds, then rests. The line and End
    // session stay in reach.
    func testOnAnIPhoneWithAHomeButtonTheRingCirclesTheBackLink() throws {
        try XCTSkipUnless(Self.screen == .homeButton, "the ring shows on an iPhone with a Home button")
        let (app, messages) = tripFromMessages()
        let title = app.staticTexts["session.title"], line = app.staticTexts["session.subtitle"]
        XCTAssertTrue(ThumbFreeUI.wait(for: title, toContain: "Listening", timeout: 10))
        XCTAssertEqual(line.label, Self.backLinkLine)
        XCTAssertTrue(line.isHittable && app.buttons["session.end"].isHittable, "the line or End session is out of reach")
        let cue = ThumbFreeUI.element("session.cue", in: app)
        XCTAssertTrue(cue.waitForExistence(timeout: 2), "no ring once the mic is on")
        let link = XCUIApplication(bundleIdentifier: "com.apple.springboard").buttons["breadcrumb"]
        XCTAssertTrue(link.waitForExistence(timeout: 5), "iOS shows no link back to Messages")
        XCTAssertTrue(cue.frame.contains(link.frame), "the ring \(cue.frame) misses iOS's link \(link.frame)")
        XCTAssertTrue(moves(cue), "the ring does not play once the mic is on")
        XCTAssertTrue(ThumbFreeUI.wait(until: 15) { !self.moves(cue) }, "the ring does not rest after its rounds")
        ThumbFreeUI.shot("session-home-button")
        link.tap()
        XCTAssertTrue(messages.wait(for: .runningForeground, timeout: 5), "the link did not go back to Messages")
    }

    // On an iPad this iPhone app's window has neither the iPad's bottom edge nor its top left: the words give the back
    // link and the App Switcher, with no cue. A cold trip (ThumbFree not running) from Messages, back through the App
    // Switcher, and the take's words arrive there.
    func testOnAnIPadTheWordsGiveTheBackLinkWithNoCue() throws {
        try XCTSkipUnless(Self.screen == .iPad, "an iPad shows the words alone")
        KeyboardSetup.ensureReady()
        let app = ThumbFreeUI.launch(arguments: ["-TFKeepSetup", "YES"]) // iOS starts the closed app with no arguments
        turnOffAutoReturn(in: app)
        app.terminate()
        let messages = XCUIApplication(bundleIdentifier: "com.apple.MobileSMS")
        let field = ThumbFreeUI.conversation("555-1212", in: messages)
        clear(field, in: messages)
        messages.buttons["keyboard.mic"].tap()
        XCTAssertTrue(app.wait(for: .runningForeground, timeout: 15), "ThumbFree did not open")
        let title = app.staticTexts["session.title"]
        XCTAssertTrue(ThumbFreeUI.wait(for: title, toContain: "Listening", timeout: 15))
        XCTAssertEqual(app.staticTexts["session.subtitle"].label, Self.backLinkLine)
        XCTAssertFalse(ThumbFreeUI.element("session.cue", in: app).waitForExistence(timeout: 2), "a cue on an iPad")
        ThumbFreeUI.shot("session-ipad")
        messages.activate() // the App Switcher's way back
        let status = messages.staticTexts["keyboard.status"]
        XCTAssertTrue(ThumbFreeUI.wait(for: status, toContain: "Recording", timeout: 10), "not recording: \(status.label)")
        messages.buttons["keyboard.mic"].tap()
        XCTAssertTrue(ThumbFreeUI.wait(for: field, toContain: "ask not what your country can do for you", timeout: 20),
                      "the take's words did not arrive: \(field.value ?? "")")
        clear(field, in: messages)
    }

    /// ThumbFree launched with `arguments` and automatic return off, then the keyboard's mic tapped in a Messages
    /// conversation: ThumbFree in front on its session screen, and Messages.
    private func tripFromMessages(arguments: [String] = []) -> (app: XCUIApplication, messages: XCUIApplication) {
        KeyboardSetup.ensureReady()
        let app = ThumbFreeUI.launch(arguments: arguments)
        turnOffAutoReturn(in: app)
        let messages = XCUIApplication(bundleIdentifier: "com.apple.MobileSMS")
        _ = ThumbFreeUI.conversation("555-1212", in: messages)
        messages.buttons["keyboard.mic"].tap()
        XCTAssertTrue(app.wait(for: .runningForeground, timeout: 10), "ThumbFree did not open")
        XCTAssertTrue(app.staticTexts["session.title"].waitForExistence(timeout: 10), "no session screen")
        return (app, messages)
    }

    /// Turns automatic return off with the Settings tab's switch, which the keyboard reads too and a cold launch keeps
    /// (`-TFAutoReturn NO` holds for one launch of the app only), so the trip comes back by hand as in the store build.
    /// The switch outlives the app, so it goes back on when the test ends.
    private func turnOffAutoReturn(in app: XCUIApplication) {
        Self.setAutoReturn(false, in: app)
        addTeardownBlock { @MainActor in
            // An iPad keeps Messages' window up beside ThumbFree's, its keyboard over ThumbFree's tab bar.
            XCUIApplication(bundleIdentifier: "com.apple.MobileSMS").terminate()
            Self.setAutoReturn(true, in: ThumbFreeUI.launch())
        }
    }

    private static func setAutoReturn(_ on: Bool, in app: XCUIApplication) {
        XCTAssertTrue(ThumbFreeUI.onHome(app), "not on Home") // a tap on the tab bar before then is lost
        app.tabBars.buttons["Settings"].tap()
        let row = ThumbFreeUI.element("settings.autoReturn", in: app)
        // A Form builds only the rows near the screen, and a swipe can fling past one on a small iPhone: short drags that
        // stop before they lift, until the row is built (a tap then scrolls it into reach).
        for _ in 0..<15 {
            guard !row.exists else { break }
            app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.7)).press(
                forDuration: 0.05, thenDragTo: app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.45)),
                withVelocity: .slow, thenHoldForDuration: 0.1)
        }
        let value = on ? "1" : "0"
        if row.value as? String != value { row.switches.firstMatch.tap() } // the row's own tap misses the switch
        XCTAssertTrue(ThumbFreeUI.wait(until: 10) { row.value as? String == value }, "automatic return is not \(on ? "on" : "off")")
        app.tabBars.buttons["Home"].tap()
    }

    /// Turns Reduce Motion on or off in the Settings app, as a person does.
    private static func setReduceMotion(_ on: Bool) {
        let settings = XCUIApplication(bundleIdentifier: "com.apple.Preferences")
        settings.terminate()
        settings.launch()
        settings.staticTexts["Accessibility"].tap()
        settings.staticTexts["Motion"].tap()
        let toggle = settings.switches["Reduce Motion"]
        XCTAssertTrue(toggle.waitForExistence(timeout: 5), "no Reduce Motion switch")
        if (toggle.value as? String == "1") != on {
            toggle.coordinate(withNormalizedOffset: CGVector(dx: 0.93, dy: 0.5)).tap() // a plain tap lands on the label
        }
        XCTAssertTrue(ThumbFreeUI.wait(until: 5) { (toggle.value as? String == "1") == on }, "Reduce Motion is not \(on ? "on" : "off")")
        settings.terminate()
    }

    /// Two looks at the cue 0.5 s apart differ while it plays (no half second of a round stands still) and match while
    /// it rests.
    private func moves(_ element: XCUIElement) -> Bool {
        let first = element.screenshot().pngRepresentation
        Thread.sleep(forTimeInterval: 0.5)
        return element.screenshot().pngRepresentation != first
    }

    /// Empties the message box with the keyboard's delete held, so no draft carries over to the next run.
    private func clear(_ field: XCUIElement, in app: XCUIApplication) {
        let delete = ThumbFreeUI.element("keyboard.delete", in: app)
        func empty() -> Bool { ((field.value as? String) ?? "").isEmpty || field.value as? String == field.placeholderValue }
        for _ in 0..<3 where !empty() { delete.press(forDuration: 6) }
    }
}
