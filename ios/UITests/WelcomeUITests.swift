import XCTest

/// The three first-run screens, offline: a small file from this bundle stands in for the speech model
/// (`-TFModelFixture`), and the fixed-text engine gets ready at once. The Simulator's list of keyboards names ThumbFree
/// (`tools/sim-enable-keyboard.sh`); `-AppleKeyboards` in the launch arguments stands in for a list without it (the
/// argument domain comes before the global one).
@MainActor final class WelcomeUITests: XCTestCase {
    static let notAdded = ["-AppleKeyboards", "(\"en_US@sw=QWERTY\")"]

    func launchWelcome(_ arguments: [String] = []) -> XCUIApplication {
        ThumbFreeUI.launch(welcome: true, arguments: ["-TFModelFixture", ThumbFreeUI.jfk] + arguments)
    }

    func title(_ app: XCUIApplication) -> XCUIElement { ThumbFreeUI.element("welcome.title", in: app) }
    func primary(_ app: XCUIApplication) -> XCUIElement { app.buttons["welcome.primary"] }

    /// Answers the system's microphone prompt.
    func answerMicPrompt(_ button: String) {
        let alert = XCUIApplication(bundleIdentifier: "com.apple.springboard").alerts.firstMatch
        XCTAssertTrue(alert.waitForExistence(timeout: 10), "no microphone prompt")
        alert.buttons[button].tap()
    }

    // Get started asks for the microphone and starts the download; the keyboard step can be skipped; the last step has
    // no way on until the model is in and ready, then Start opens the Try tab, and the flow never opens by itself again.
    func testThreeStepsEndOnTheTryTabAndDoNotComeBack() throws {
        XCUIApplication().resetAuthorizationStatus(for: .microphone)
        let app = launchWelcome()
        XCTAssertTrue(ThumbFreeUI.wait(for: title(app), toContain: "Talk. It types.", timeout: 10))
        XCTAssertTrue(app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", "downloads now, over Wi-Fi.")).firstMatch.exists)
        primary(app).tap() // Get started
        answerMicPrompt("Allow")
        XCTAssertTrue(ThumbFreeUI.wait(for: title(app), toContain: "Add the ThumbFree keyboard", timeout: 5))
        app.buttons["welcome.skip"].tap()
        XCTAssertTrue(ThumbFreeUI.wait(for: title(app), toContain: "How it works", timeout: 5))
        XCTAssertTrue(ThumbFreeUI.element("guide.caption", in: app).exists)
        XCTAssertTrue(ThumbFreeUI.wait(for: primary(app), toContain: "Start", timeout: 30))
        XCTAssertTrue(ThumbFreeUI.wait(for: ThumbFreeUI.element("model.status", in: app), toContain: "The model is ready", timeout: 5))
        primary(app).tap() // Start
        XCTAssertTrue(ThumbFreeUI.element("try.field", in: app).waitForExistence(timeout: 5))
        XCTAssertTrue(ThumbFreeUI.wait(for: ThumbFreeUI.element("try.status", in: app), toContain: "Tap to talk", timeout: 5))
        app.terminate()
        let again = XCUIApplication()
        again.launchArguments = ["-TFFakeEngine", "YES", "-TFModelFixture", ThumbFreeUI.jfk]
        again.launch()
        XCTAssertTrue(ThumbFreeUI.element("try.field", in: again).waitForExistence(timeout: 10))
        XCTAssertFalse(title(again).exists)
    }

    // Get started first says in a plain line that iOS will ask for the microphone and why (no picture of the prompt),
    // then iOS's own prompt opens over it; once answered, the keyboard step.
    func testGetStartedSaysTheMicrophonePromptIsNext() throws {
        XCUIApplication().resetAuthorizationStatus(for: .microphone)
        let app = launchWelcome()
        XCTAssertTrue(primary(app).waitForExistence(timeout: 10))
        primary(app).tap() // Get started
        XCTAssertTrue(ThumbFreeUI.wait(for: ThumbFreeUI.element("welcome.micCaption", in: app),
                                       toContain: "iOS will ask to use the microphone. ThumbFree needs it to hear you.", timeout: 3))
        XCTAssertFalse(app.staticTexts["Tap Allow."].exists)
        let alert = XCUIApplication(bundleIdentifier: "com.apple.springboard").alerts.firstMatch
        XCTAssertTrue(alert.waitForExistence(timeout: 10), "no microphone prompt")
        ThumbFreeUI.shot("welcome-mic") // the line, dimmed, above iOS's own prompt
        alert.buttons["Allow"].tap()
        XCTAssertTrue(ThumbFreeUI.wait(for: title(app), toContain: "Add the ThumbFree keyboard", timeout: 5))
    }

    // The keyboard step goes on by itself once ThumbFree's keyboard has come up with Full Access anywhere (here in
    // Settings' search field, as it would in any app), seen when you come back.
    func testTheKeyboardStepGoesOnOnceTheKeyboardComesUp() throws {
        KeyboardSetup.ensureReady() // Full Access, so the keyboard's appearance leaves its mark
        let app = launchWelcome()
        XCTAssertTrue(primary(app).waitForExistence(timeout: 10))
        primary(app).tap() // Get started
        answerMicPromptIfAsked()
        XCTAssertTrue(ThumbFreeUI.wait(for: title(app), toContain: "Add the ThumbFree keyboard", timeout: 5))
        let settings = XCUIApplication(bundleIdentifier: "com.apple.Preferences")
        settings.launch()
        let search = settings.searchFields.firstMatch
        XCTAssertTrue(search.waitForExistence(timeout: 5))
        search.tap()
        KeyboardSetup.switchToThumbFree(in: settings)
        app.activate()
        XCTAssertTrue(ThumbFreeUI.wait(for: title(app), toContain: "How it works", timeout: 5), "the keyboard step did not go on by itself")
        settings.terminate() // its search field would bring the keyboard up again in later tests
    }

    // iOS may end the app while you are in Settings and the keyboard: back on the keyboard step with the keyboard
    // already confirmed, it goes on by itself, as it does without the restart.
    func testTheKeyboardStepGoesOnAfterARelaunch() throws {
        KeyboardSetup.ensureReady() // Full Access, so the keyboard's appearance leaves its mark
        let app = launchWelcome()
        XCTAssertTrue(primary(app).waitForExistence(timeout: 10))
        primary(app).tap() // Get started
        answerMicPromptIfAsked()
        XCTAssertTrue(ThumbFreeUI.wait(for: title(app), toContain: "Add the ThumbFree keyboard", timeout: 5))
        app.terminate()
        let settings = XCUIApplication(bundleIdentifier: "com.apple.Preferences")
        settings.launch()
        let search = settings.searchFields.firstMatch
        XCTAssertTrue(search.waitForExistence(timeout: 5))
        search.tap()
        KeyboardSetup.switchToThumbFree(in: settings)
        settings.terminate() // its search field would bring the keyboard up again in later tests
        let again = XCUIApplication()
        again.launchArguments = ["-TFFakeEngine", "YES", "-TFWelcomeDone", "NO", "-TFModelFixture", ThumbFreeUI.jfk]
        again.launch()
        XCTAssertTrue(ThumbFreeUI.wait(for: title(again), toContain: "How it works", timeout: 10), "the keyboard step did not go on after the relaunch")
    }

    // A keyboard not in iOS's list: the setup guide, paused, walks the taps in Settings with Next and Back, its path lit
    // step by step (read as one sentence), under Open Settings and a small Skip.
    func testTheKeyboardStepShowsTheSetupGuide() throws {
        let app = launchWelcome(Self.notAdded + ["-TFGuidePaused", "YES"])
        XCTAssertTrue(primary(app).waitForExistence(timeout: 10))
        primary(app).tap() // Get started
        answerMicPromptIfAsked()
        XCTAssertTrue(ThumbFreeUI.wait(for: title(app), toContain: "Add the ThumbFree keyboard", timeout: 5))
        XCTAssertEqual(primary(app).label, "Open Settings")
        XCTAssertGreaterThanOrEqual(app.buttons["welcome.skip"].frame.height, 44, "Skip's tap target is under 44 points")
        XCTAssertGreaterThanOrEqual(app.buttons["setupGuide.next"].frame.height, 44, "Next's tap target is under 44 points")
        XCTAssertTrue(ThumbFreeUI.wait(for: ThumbFreeUI.element("setupGuide.path", in: app),
                                       toContain: "In Settings: Keyboards, ThumbFree, Allow Full Access, then Allow.", timeout: 3))
        let caption = ThumbFreeUI.element("setupGuide.caption", in: app)
        XCTAssertTrue(ThumbFreeUI.wait(for: caption, toContain: "Step 1 of 4. Tap Keyboards.", timeout: 5))
        let next = app.buttons["setupGuide.next"]
        for (step, words) in ["Turn on ThumbFree.", "Turn on Allow Full Access.", "Tap Allow."].enumerated() {
            next.tap()
            XCTAssertTrue(ThumbFreeUI.wait(for: caption, toContain: "Step \(step + 2) of 4. \(words)", timeout: 3))
        }
        XCTAssertFalse(next.isEnabled)
        app.buttons["setupGuide.back"].tap()
        XCTAssertTrue(ThumbFreeUI.wait(for: caption, toContain: "Step 3 of 4.", timeout: 3))
        app.buttons["welcome.skip"].tap()
        XCTAssertTrue(ThumbFreeUI.wait(for: title(app), toContain: "How it works", timeout: 5))
    }

    // The big button is Open Settings until you come back from a trip away, even with the keyboard already in iOS's
    // list (the Simulator's, as after a reinstall). Back with it added but not seen with Full Access: Continue, Open
    // Settings under it, and the guide from Allow Full Access, still so after a restart.
    func testContinueComesOnlyAfterATripToSettings() throws {
        let settings = XCUIApplication(bundleIdentifier: "com.apple.Preferences")
        settings.terminate() // an earlier test's search field there would bring up ThumbFree's keyboard, and its mark
        let app = launchWelcome(["-TFGuidePaused", "YES"])
        XCTAssertTrue(primary(app).waitForExistence(timeout: 10))
        primary(app).tap() // Get started
        answerMicPromptIfAsked()
        XCTAssertTrue(ThumbFreeUI.wait(for: title(app), toContain: "Add the ThumbFree keyboard", timeout: 5))
        let caption = ThumbFreeUI.element("setupGuide.caption", in: app)
        XCTAssertTrue(ThumbFreeUI.wait(for: caption, toContain: "Step 1 of 4. Tap Keyboards.", timeout: 5))
        XCTAssertEqual(primary(app).label, "Open Settings")
        XCTAssertTrue(app.buttons["welcome.skip"].exists)
        primary(app).tap() // Open Settings
        XCTAssertTrue(settings.wait(for: .runningForeground, timeout: 10))
        app.activate() // the app switcher's way back
        XCTAssertTrue(ThumbFreeUI.wait(for: primary(app), toContain: "Continue", timeout: 5))
        XCTAssertEqual(app.buttons["welcome.secondary"].label, "Open Settings")
        XCTAssertGreaterThanOrEqual(app.buttons["welcome.secondary"].frame.height, 44, "the second button's tap target is under 44 points")
        XCTAssertTrue(ThumbFreeUI.wait(for: caption, toContain: "Step 3 of 4. Turn on Allow Full Access.", timeout: 5))
        XCTAssertTrue(ThumbFreeUI.element("setupGuide.path", in: app).exists)
        XCTAssertEqual(app.textFields.count + app.textViews.count, 0, "a text box on the keyboard step")
        settings.terminate()
        app.terminate()
        let again = XCUIApplication()
        again.launchArguments = ["-TFFakeEngine", "YES", "-TFWelcomeDone", "NO", "-TFModelFixture", ThumbFreeUI.jfk]
        again.launch()
        XCTAssertTrue(ThumbFreeUI.wait(for: primary(again), toContain: "Continue", timeout: 10))
        primary(again).tap() // Continue
        XCTAssertTrue(ThumbFreeUI.wait(for: title(again), toContain: "How it works", timeout: 5))
    }

    // A refused microphone still goes on. Back from Settings by any route with the keyboard still not added, the step
    // says where else to look, and Open Settings stays the big button.
    func testARefusedMicrophoneGoesOnAndTheKeyboardStepSaysWhereToLook() throws {
        XCUIApplication().resetAuthorizationStatus(for: .microphone)
        let settings = XCUIApplication(bundleIdentifier: "com.apple.Preferences")
        settings.terminate() // an earlier test's search field there would bring up ThumbFree's keyboard, and its mark
        let app = launchWelcome(Self.notAdded)
        let help = app.staticTexts.matching(NSPredicate(format: "label BEGINSWITH %@", "Don't see Keyboards?")).firstMatch
        XCTAssertTrue(primary(app).waitForExistence(timeout: 10))
        primary(app).tap() // Get started
        answerMicPrompt("Don’t Allow")
        XCTAssertTrue(ThumbFreeUI.wait(for: title(app), toContain: "Add the ThumbFree keyboard", timeout: 5))
        XCTAssertFalse(help.exists)
        primary(app).tap() // Open Settings
        XCTAssertTrue(settings.wait(for: .runningForeground, timeout: 10))
        app.activate() // the app switcher's way back, not the link
        XCTAssertTrue(help.waitForExistence(timeout: 5))
        XCTAssertEqual(primary(app).label, "Open Settings")
    }

    // The keyboard's "get the model" link, before Get started, goes to the last step, which offers the download.
    func testTheModelLinkOpensTheLastStep() throws {
        let app = launchWelcome()
        XCTAssertTrue(ThumbFreeUI.wait(for: title(app), toContain: "Talk. It types.", timeout: 10))
        XCUIDevice.shared.system.open(try XCTUnwrap(URL(string: "thumbfree://model")))
        XCTAssertTrue(ThumbFreeUI.wait(for: title(app), toContain: "How it works", timeout: 10))
        XCTAssertTrue(ThumbFreeUI.wait(for: primary(app), toContain: "Download", timeout: 5))
        primary(app).tap()
        XCTAssertTrue(ThumbFreeUI.wait(for: primary(app), toContain: "Start", timeout: 30))
    }

    // The step reached is saved: iOS restarts the app when a permission changes in Settings, and the flow comes back there.
    func testAFlowLeftHalfwayComesBackAtItsStep() throws {
        let app = launchWelcome(Self.notAdded)
        XCTAssertTrue(primary(app).waitForExistence(timeout: 10))
        primary(app).tap() // Get started
        answerMicPromptIfAsked()
        XCTAssertTrue(ThumbFreeUI.wait(for: title(app), toContain: "Add the ThumbFree keyboard", timeout: 5))
        app.terminate()
        let again = XCUIApplication()
        again.launchArguments = ["-TFFakeEngine", "YES", "-TFWelcomeDone", "NO", "-TFModelFixture", ThumbFreeUI.jfk] + Self.notAdded
        again.launch()
        XCTAssertTrue(ThumbFreeUI.wait(for: title(again), toContain: "Add the ThumbFree keyboard", timeout: 10))
    }

    /// Allows the microphone if iOS asks (an earlier test may have answered already).
    func answerMicPromptIfAsked() {
        let allow = XCUIApplication(bundleIdentifier: "com.apple.springboard").alerts.firstMatch.buttons["Allow"]
        if allow.waitForExistence(timeout: 3) { allow.tap() }
    }
}
