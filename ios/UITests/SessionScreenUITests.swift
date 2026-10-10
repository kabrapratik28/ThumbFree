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

    /// The words under the title on this Simulator's screen.
    static var wayBackLine: String {
        switch screen {
        case .homeIndicator: "Swipe right along the bottom edge to go back."
        case .homeButton: "Tap your app’s name at the top left to go back, or use the App Switcher."
        case .iPad: "Use the App Switcher to go back to your app."
        }
    }

    func testTheDictateLinkShowsTheSessionScreenUntilEndSession() throws {
        let app = ThumbFreeUI.launch()
        XCUIDevice.shared.system.open(try XCTUnwrap(URL(string: "thumbfree://dictate?take=\(UUID().uuidString)")))
        let listening = app.staticTexts["session.title"]
        XCTAssertTrue(ThumbFreeUI.wait(for: listening, toContain: "Listening", timeout: 10))
        // A plain link (no trusted host) shows the way back, not an automatic return.
        XCTAssertEqual(app.staticTexts["session.subtitle"].label, Self.wayBackLine)
        app.buttons["session.end"].tap()
        XCTAssertTrue(listening.waitForNonExistence(timeout: 5))
        XCTAssertTrue(ThumbFreeUI.onHome(app, timeout: 5), "not back on Home")
    }

    // Guards the ScrollView requirement: at the largest accessibility text size, the bubble art must never push the
    // title, End session or the way-back line off screen for good, on the swipe-back state (the tallest realistic
    // content: the title and a wrapped multi-line line). At the edge the line stays below End session and above the cue.
    func testTheSwipeBackScreenReachesTitleLineAndEndAtTheLargestAccessibilityTextSize() throws {
        let app = ThumbFreeUI.launch(arguments: ["-UIPreferredContentSizeCategoryName", "UICTContentSizeCategoryAccessibilityXXXL"])
        XCUIDevice.shared.system.open(try XCTUnwrap(URL(string: "thumbfree://dictate?take=\(UUID().uuidString)")))
        let title = app.staticTexts["session.title"]
        XCTAssertTrue(ThumbFreeUI.wait(for: title, toContain: "Listening", timeout: 10))
        let line = app.staticTexts["session.subtitle"], end = app.buttons["session.end"]
        XCTAssertEqual(line.label, Self.wayBackLine)
        for (element, name) in [(title, "the title"), (end, "End session"), (line, "the way-back line")] {
            XCTAssertTrue(Self.scroll(to: element, in: app), "\(name) must be reachable at the largest accessibility text size")
        }
        guard Self.screen == .homeIndicator else { return }
        let cue = ThumbFreeUI.element("session.cue", in: app)
        XCTAssertTrue(cue.waitForExistence(timeout: 2), "no cue once the mic is on")
        XCTAssertGreaterThanOrEqual(line.frame.minY, end.frame.maxY, "the line \(line.frame) overlaps End session \(end.frame)")
        XCTAssertLessThanOrEqual(line.frame.maxY, cue.frame.minY + 1, "the line \(line.frame) overlaps the cue \(cue.frame)")
    }

    // An iPhone with a home indicator. While the mic starts (held 12 s here, longer than the cue's 3 rounds of 8.85 s)
    // the screen says so, with no words and no cue, the line's room kept; then the cue on the bottom edge, one image the
    // size of its drawing, arrow included, playing from then on rather than from the screen's first moment: still past
    // option A's 4.95 s, at rest after 8.85 s, and again after a tap on the page, which leaves the session on. The words
    // sit at the edge: End session, a quiet button at least 44 points each way, right under the title; the line below
    // it, near the bottom and above the cue, whole and in reach.
    func testTheSwipeCueWaitsForTheMicThenPlaysOnTheBottomEdge() throws {
        try XCTSkipUnless(Self.screen == .homeIndicator, "the swipe shows on an iPhone with a home indicator")
        let (app, _) = tripFromMessages(arguments: ["-TFMicDelayMs", "12000"])
        let title = app.staticTexts["session.title"], line = app.staticTexts["session.subtitle"]
        let cue = ThumbFreeUI.element("session.cue", in: app), end = app.buttons["session.end"]
        XCTAssertTrue(ThumbFreeUI.wait(for: title, toContain: "Starting the microphone", timeout: 10))
        let starting = Date(), titleTop = title.frame.minY
        ThumbFreeUI.shot("session-starting")
        // Each look reads the cue and the line before the title: the mic only comes on, so either one seen before a
        // title that still says Starting was there while the mic started.
        var early: Set<String> = []
        XCTAssertTrue(ThumbFreeUI.wait(until: 25) {
            let seen = [cue.exists ? "the cue" : nil, line.exists ? "the line" : nil].compactMap { $0 }
            if title.label.contains("Listening") { return true }
            early.formUnion(seen)
            return false
        }, "the mic did not come on: \(title.label)")
        XCTAssertEqual(early, [], "shown while the mic started")
        let ready = Date()
        XCTAssertGreaterThan(ready.timeIntervalSince(starting), 8.85, "the mic came on too soon to tell when the cue starts")
        XCTAssertEqual(line.label, Self.wayBackLine)
        XCTAssertEqual(title.frame.minY, titleTop, accuracy: 1, "the title moved when the line showed")
        XCTAssertTrue(cue.waitForExistence(timeout: 2), "no cue once the mic is on")
        XCTAssertEqual(cue.frame.maxY, app.frame.maxY, accuracy: 30, "the cue \(cue.frame) is not on the bottom edge")
        checkOneCue(cue, in: app, atMost: CGSize(width: app.frame.width * 0.75, height: 140))
        XCTAssertGreaterThanOrEqual(app.frame.maxY - cue.frame.minY, 120, "the cue image \(cue.frame) leaves out the arrow")
        XCTAssertTrue(moves(cue), "the cue does not play once the mic is on")
        XCTAssertTrue(end.exists, "the title, the line and End session are not separate elements")
        XCTAssertGreaterThanOrEqual(end.frame.width, 44)
        XCTAssertGreaterThanOrEqual(end.frame.height, 44)
        let underTitle = end.frame.minY - title.frame.maxY
        XCTAssertTrue((0..<40).contains(underTitle), "End session \(end.frame) is not right under the title \(title.frame)")
        XCTAssertGreaterThanOrEqual(line.frame.minY, end.frame.maxY, "the line \(line.frame) is not below End session")
        XCTAssertLessThanOrEqual(app.frame.maxY - line.frame.maxY, 160, "the line \(line.frame) is not near the bottom edge")
        XCTAssertLessThanOrEqual(line.frame.maxY, cue.frame.minY + 1, "the line \(line.frame) overlaps the cue \(cue.frame)")
        XCTAssertTrue(app.frame.contains(line.frame), "the line \(line.frame) is cut off")
        for (element, name) in [(title, "the title"), (end, "End session"), (line, "the line")] {
            XCTAssertTrue(Self.scroll(to: element, in: app), "\(name) is out of reach")
        }
        // Past option A's deadline the cue still plays: its rounds began when the mic came on.
        Thread.sleep(forTimeInterval: max(0, 5.5 - Date().timeIntervalSince(ready)))
        XCTAssertTrue(moves(cue), "the cue stopped before its 3 rounds of 8.85 s")
        XCTAssertTrue(ThumbFreeUI.wait(until: 20) { !self.moves(cue) }, "the cue does not rest after its rounds")
        ThumbFreeUI.shot("session-swipe-rest")
        title.tap()
        XCTAssertTrue(moves(cue), "a tap on the page does not play the cue again")
        XCTAssertTrue(title.label.contains("Listening"), "a tap on the page changed the session: \(title.label)")
        end.tap()
        XCTAssertTrue(title.waitForNonExistence(timeout: 5), "End session did not end the session")
    }

    // With Reduce Motion (no launch argument: Settings turns it on) the cue shows its still at once, and a tap on the
    // page does not play it.
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
    // ring sits around the name iOS writes there, which takes you back; it plays its rounds, then rests. The line and
    // End session stay in reach.
    func testOnAnIPhoneWithAHomeButtonTheRingCirclesTheBackLink() throws {
        try XCTSkipUnless(Self.screen == .homeButton, "the ring shows on an iPhone with a Home button")
        let (app, messages) = tripFromMessages()
        let title = app.staticTexts["session.title"], line = app.staticTexts["session.subtitle"]
        XCTAssertTrue(ThumbFreeUI.wait(for: title, toContain: "Listening", timeout: 10))
        XCTAssertEqual(line.label, Self.wayBackLine)
        XCTAssertTrue(line.isHittable && app.buttons["session.end"].isHittable, "the line or End session is out of reach")
        let cue = ThumbFreeUI.element("session.cue", in: app)
        XCTAssertTrue(cue.waitForExistence(timeout: 2), "no ring once the mic is on")
        let link = XCUIApplication(bundleIdentifier: "com.apple.springboard").buttons["breadcrumb"]
        XCTAssertTrue(link.waitForExistence(timeout: 5), "iOS shows no link back to Messages")
        XCTAssertTrue(cue.frame.contains(link.frame), "the ring \(cue.frame) misses iOS's link \(link.frame)")
        checkOneCue(cue, in: app, atMost: CGSize(width: 100, height: 30))
        XCTAssertTrue(moves(cue), "the ring does not play once the mic is on")
        XCTAssertTrue(ThumbFreeUI.wait(until: 15) { !self.moves(cue) }, "the ring does not rest after its rounds")
        ThumbFreeUI.shot("session-home-button")
        link.tap()
        XCTAssertTrue(messages.wait(for: .runningForeground, timeout: 5), "the link did not go back to Messages")
    }

    // On an iPad iOS writes no app's name at the top left (it opens this iPhone app in a window of its own), and the
    // window has neither of the iPad's edges: the words give the App Switcher, with no cue. A cold trip (ThumbFree not
    // running) from Messages, back through the App Switcher, and the take's words arrive there.
    func testOnAnIPadTheWordsGiveTheAppSwitcherWithNoCue() throws {
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
        XCTAssertEqual(app.staticTexts["session.subtitle"].label, Self.wayBackLine)
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
        // A Form builds only the rows near the screen: down the page until the row is built (a tap then scrolls it into
        // reach).
        for _ in 0..<15 {
            guard !row.exists else { break }
            page(app, down: true)
        }
        let value = on ? "1" : "0"
        if row.value as? String != value { row.switches.firstMatch.tap() } // the row's own tap misses the switch
        XCTAssertTrue(ThumbFreeUI.wait(until: 10) { row.value as? String == value }, "automatic return is not \(on ? "on" : "off")")
        app.tabBars.buttons["Home"].tap()
    }

    /// Scrolls the page in short drags until `element` can take a tap, back up when it sits above the middle of the
    /// screen; whether it can.
    private static func scroll(to element: XCUIElement, in app: XCUIApplication) -> Bool {
        for _ in 0..<8 {
            if element.isHittable { return true }
            page(app, down: !(element.exists && element.frame.midY < app.frame.midY))
        }
        return element.isHittable
    }

    /// A quarter of the screen down the page, or back up: a drag that stops before it lifts, since a swipe can fling
    /// past a row on a small iPhone.
    private static func page(_ app: XCUIApplication, down: Bool) {
        let low = app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.7))
        let high = app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.45))
        (down ? low : high).press(forDuration: 0.05, thenDragTo: down ? high : low, withVelocity: .slow, thenHoldForDuration: 0.1)
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

    /// Whether the cue changes in 3 s of looks, one every 0.3 s, against the first. Longer than a round (2.95 s for the
    /// swipe, 1.65 s for the ring), so a playing cue always changes (two looks a set time apart can match: a fade-out
    /// and the next fade-in) and a resting one never does.
    private func moves(_ element: XCUIElement) -> Bool {
        let first = element.screenshot().pngRepresentation
        let end = Date().addingTimeInterval(3)
        while Date() < end {
            Thread.sleep(forTimeInterval: 0.3)
            if element.screenshot().pngRepresentation != first { return true }
        }
        return false
    }

    /// The cue is one VoiceOver image the size of its drawing, at most `size`: a second one, or one over the whole
    /// screen, fails.
    private func checkOneCue(_ cue: XCUIElement, in app: XCUIApplication, atMost size: CGSize) {
        XCTAssertEqual(app.descendants(matching: .any).matching(identifier: "session.cue").count, 1, "not exactly one cue image")
        XCTAssertLessThanOrEqual(cue.frame.width, size.width, "the cue image \(cue.frame) is wider than its drawing")
        XCTAssertLessThanOrEqual(cue.frame.height, size.height, "the cue image \(cue.frame) is taller than its drawing")
    }

    /// Empties the message box with the keyboard's delete held, so no draft carries over to the next run.
    private func clear(_ field: XCUIElement, in app: XCUIApplication) {
        let delete = ThumbFreeUI.element("keyboard.delete", in: app)
        func empty() -> Bool { ((field.value as? String) ?? "").isEmpty || field.value as? String == field.placeholderValue }
        for _ in 0..<3 where !empty() { delete.press(forDuration: 6) }
    }
}
