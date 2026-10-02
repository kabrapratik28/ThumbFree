import XCTest

/// The first-run screens, offline: a small file from this bundle stands in for the speech model (`-TFModelFixture`),
/// and the fixed-text engine gets ready at once. Step 1 asks the language (the tap starts its download) and waits on the
/// same screen until speech is ready, then step 2 comes by itself and adds the keyboard; back from Settings with the
/// keyboard added, step 3, the try, opens at once. There is no microphone page: iOS asks at the try's first take. The
/// Simulator's list of keyboards names ThumbFree (`tools/sim-enable-keyboard.sh`); `-AppleKeyboards` in the launch
/// arguments stands in for a list without it (the argument domain comes before the global one).
@MainActor final class WelcomeUITests: XCTestCase {
    static let notAdded = ["-AppleKeyboards", "(\"en_US@sw=QWERTY\")"]

    func launchWelcome(_ arguments: [String] = []) -> XCUIApplication {
        ThumbFreeUI.launch(welcome: true, arguments: ["-TFModelFixture", ThumbFreeUI.jfk] + arguments)
    }

    /// The try held on step 3 (`-TFWelcomeStep 2`) with no test audio, so its first take asks iOS for the real microphone.
    func launchTryWithTheRealMicrophone() -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = ["-TFResetState", "YES", "-TFWelcomeDone", "NO", "-TFFakeEngine", "YES", "-TFWelcomeStep", "2"]
        app.launch()
        return app
    }

    func title(_ app: XCUIApplication) -> XCUIElement { ThumbFreeUI.element("welcome.title", in: app) }
    func primary(_ app: XCUIApplication) -> XCUIElement { app.buttons["welcome.primary"] }
    /// Step 1's wait: its heading.
    func waitHeading(_ app: XCUIApplication) -> XCUIElement { ThumbFreeUI.element("welcome.wait", in: app) }
    /// The try's heading (step 3): its line, or what stops it.
    func tryLine(_ app: XCUIApplication) -> XCUIElement { ThumbFreeUI.element("try.line", in: app) }
    func tryBox(_ app: XCUIApplication) -> XCUIElement { ThumbFreeUI.element("try.field", in: app) }
    func progress(_ app: XCUIApplication) -> String { ThumbFreeUI.element("welcome.progress", in: app).label }

    /// English: its download (the fixture) and the engine's load happen on step 1's wait, then step 2 comes by itself.
    func chooseEnglish(_ app: XCUIApplication) {
        let english = app.buttons["welcome.english"]
        XCTAssertTrue(english.waitForExistence(timeout: 10))
        english.tap()
        XCTAssertTrue(ThumbFreeUI.wait(for: title(app), toContain: "Add the ThumbFree keyboard", timeout: 20), "step 2 never came")
    }

    // The language starts the download, and step 1 waits on the same screen until speech is ready; then step 2 comes by
    // itself, whose Not now ends the welcome on Home; the flow never opens by itself again.
    func testThreeStepsEndOnHomeAndDoNotComeBack() throws {
        XCUIApplication().resetAuthorizationStatus(for: .microphone)
        let app = launchWelcome()
        XCTAssertTrue(ThumbFreeUI.wait(for: title(app), toContain: "Your voice becomes text in any app.", timeout: 10))
        XCTAssertTrue(app.staticTexts["Private. Works offline after setup."].exists)
        XCTAssertEqual(progress(app), "Step 1 of 3, Get ready")
        chooseEnglish(app)
        XCTAssertEqual(progress(app), "Step 2 of 3, Add the ThumbFree keyboard")
        app.buttons["welcome.notNow"].tap()
        XCTAssertTrue(ThumbFreeUI.onHome(app), "Not now did not open Home")
        app.terminate()
        let again = XCUIApplication()
        again.launchArguments = ["-TFFakeEngine", "YES", "-TFModelFixture", ThumbFreeUI.jfk]
        again.launch()
        XCTAssertTrue(ThumbFreeUI.onHome(again))
        XCTAssertFalse(title(again).exists)
    }

    // There is no microphone page: with the ThumbFree keyboard up in the try, a plain line says iOS will ask, and iOS's
    // own prompt comes only with the first tap of the yellow mic (no test audio here, so the take asks for the real
    // microphone). Behind the prompt the line stays on the mic, since nothing records yet. Refused, so the Mac's
    // microphone is never used, the try says the microphone is off.
    func testIOSAsksForTheMicrophoneAtTheFirstTake() throws {
        KeyboardSetup.ensureReady()
        XCUIApplication().resetAuthorizationStatus(for: .microphone)
        let app = launchTryWithTheRealMicrophone()
        XCTAssertTrue(tryBox(app).waitForExistence(timeout: 10))
        KeyboardSetup.switchToThumbFree(in: app)
        XCTAssertTrue(ThumbFreeUI.wait(for: tryLine(app), toContain: "Tap the yellow mic", timeout: 10))
        XCTAssertTrue(app.staticTexts["iOS will ask for microphone access."].exists)
        let alert = XCUIApplication(bundleIdentifier: "com.apple.springboard").alerts.firstMatch
        XCTAssertFalse(alert.exists, "iOS asked before the first take")
        app.buttons["keyboard.mic"].tap()
        XCTAssertTrue(alert.waitForExistence(timeout: 10), "no microphone prompt at the first take")
        XCTAssertEqual(tryLine(app).label, "Tap the yellow mic.", "the line moved on behind iOS's prompt")
        XCTAssertTrue(app.staticTexts["iOS will ask for microphone access."].exists)
        ThumbFreeUI.shot("try-mic-prompt") // iOS's own prompt over the try
        alert.buttons["Don’t Allow"].tap()
        XCTAssertTrue(ThumbFreeUI.wait(for: tryLine(app), toContain: "Microphone is off", timeout: 10))
        app.terminate()
        XCUIApplication().resetAuthorizationStatus(for: .microphone) // later tests need it unanswered
    }

    // A German iPhone gets Other languages first and filled, with what it covers and its size, and English under it; the
    // two answers are one height, and the question sits right under the example. The tap is the choice: English here.
    // The answers turn into the wait (the engine held loading keeps it there), and after a relaunch the welcome comes
    // back at the wait with English, German or not.
    func testStepOneNamesTheModelAndSwitchesToTheOther() throws {
        let german = ["-AppleLanguages", "(\"de-DE\")"]
        let app = launchWelcome(german + ["-TFGuidePaused", "YES", "-TFHoldEngine", "loading"])
        let other = app.buttons["welcome.otherLanguages"], english = app.buttons["welcome.english"]
        XCTAssertTrue(other.waitForExistence(timeout: 10))
        XCTAssertTrue(other.label.hasPrefix("Other languages. Spanish, French, German and 21 more, about"), other.label)
        XCTAssertTrue(english.label.hasPrefix("English. Best for English, about"), english.label) // the fixture's size
        XCTAssertLessThan(other.frame.minY, english.frame.minY, "the phone's guess is not first")
        XCTAssertEqual(other.frame.height, english.frame.height, accuracy: 0.5, "the answers are not one height")
        let example = ThumbFreeUI.element("welcome.example", in: app)
        let question = app.staticTexts["Which language do you speak?"]
        XCTAssertEqual(question.frame.minY - example.frame.maxY, 20, accuracy: 3, "the question is not right under the example")
        english.tap()
        XCTAssertTrue(ThumbFreeUI.wait(for: waitHeading(app), toContain: "Almost ready", timeout: 10))
        XCTAssertTrue(example.exists, "the example went with the answers")
        XCTAssertFalse(english.exists, "the answers stayed")
        app.terminate()
        let again = ThumbFreeUI.launch(welcome: true, reset: false, arguments: ["-TFModelFixture", ThumbFreeUI.jfk,
                                                                             "-TFHoldDownload", "30"] + german)
        XCTAssertTrue(ThumbFreeUI.wait(for: ThumbFreeUI.element("welcome.model", in: again), toContain: "English · 30%", timeout: 10))
    }

    // While the download runs (held at 30%), step 1's wait offers Change language: iOS's own sheet asks first, with both
    // choices, and Use Other languages starts that model's download in this one's place (the fixture is no multilingual
    // model, so its file check fails at once). VoiceOver hears the progress once.
    func testTheSwitchShowsWhileTheModelDownloads() throws {
        let app = launchWelcome(["-TFWelcomeStep", "3", "-TFHoldDownload", "30", "-TFGuidePaused", "YES"])
        XCTAssertTrue(ThumbFreeUI.wait(for: waitHeading(app), toContain: "Getting speech ready", timeout: 10))
        XCTAssertTrue(app.staticTexts["It downloads once. You can leave ThumbFree."].exists)
        let model = ThumbFreeUI.element("welcome.model", in: app)
        XCTAssertTrue(ThumbFreeUI.wait(for: model, toContain: "English · 30%", timeout: 5))
        let spoken = model.label + " " + (model.value as? String ?? "")
        XCTAssertEqual(spoken.components(separatedBy: "%").count, 2, "VoiceOver hears the progress more than once: \(spoken)")
        XCTAssertFalse(app.buttons["welcome.fix"].exists, "a big button for work under way")
        app.buttons["welcome.changeLanguage"].tap()
        let use = app.buttons["Use Other languages"]
        XCTAssertTrue(use.waitForExistence(timeout: 5), "no sheet offering the other model")
        XCTAssertTrue(app.buttons["Keep English"].exists, "the sheet does not offer to keep English")
        use.tap()
        XCTAssertTrue(ThumbFreeUI.wait(for: waitHeading(app), toContain: "check the download", timeout: 10),
                      "the other model's download did not replace the English one")
        XCTAssertEqual(app.buttons["welcome.fix"].label, "Download again")
    }

    // Step 2 sees the keyboard come up with Full Access anywhere (here in Settings' search field, as it would in any
    // app): back in ThumbFree, the try opens at once, with its box.
    func testTheKeyboardStepGoesOnOnceTheKeyboardComesUp() throws {
        KeyboardSetup.ensureReady() // Full Access, so the keyboard's appearance leaves its mark
        let app = launchWelcome()
        chooseEnglish(app)
        let settings = XCUIApplication(bundleIdentifier: "com.apple.Preferences")
        settings.launch()
        let search = settings.searchFields.firstMatch
        XCTAssertTrue(search.waitForExistence(timeout: 5))
        search.tap()
        KeyboardSetup.switchToThumbFree(in: settings)
        app.activate()
        XCTAssertTrue(tryBox(app).waitForExistence(timeout: 10), "step 2 did not open the try")
        XCTAssertEqual(progress(app), "Step 3 of 3, Try ThumbFree")
        settings.terminate() // its search field would bring the keyboard up again in later tests
    }

    // iOS may end ThumbFree while you are in Settings: relaunched on step 2 with the keyboard come up with Full Access,
    // the flow comes back and opens the try, as it does without the restart.
    func testTheKeyboardStepGoesOnAfterARelaunch() throws {
        KeyboardSetup.ensureReady() // Full Access, so the keyboard's appearance leaves its mark
        let app = launchWelcome()
        chooseEnglish(app)
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
        XCTAssertTrue(tryBox(again).waitForExistence(timeout: 15), "the try did not open after the relaunch")
    }

    // A keyboard not in iOS's list: Open Settings is the big button with a quiet Not now under it, which ends the welcome.
    // Home then shows one card, the keyboard to add, with Open Settings as its fix; a microphone not asked yet is no card
    // before it.
    func testTheKeyboardStepCanBeSkipped() throws {
        XCUIApplication().resetAuthorizationStatus(for: .microphone) // a refusal left by another test would be the card
        let app = launchWelcome(Self.notAdded + ["-TFGuidePaused", "YES"])
        chooseEnglish(app)
        XCTAssertEqual(progress(app), "Step 2 of 3, Add the ThumbFree keyboard")
        XCTAssertEqual(primary(app).label, "Open Settings")
        let notNow = app.buttons["welcome.notNow"]
        XCTAssertGreaterThanOrEqual(notNow.frame.height, 44, "Not now's tap target is under 44 points")
        notNow.tap()
        XCTAssertTrue(ThumbFreeUI.wait(for: ThumbFreeUI.element("home.blocker", in: app), toContain: "The keyboard is still off", timeout: 10))
        XCTAssertEqual(app.buttons["home.fix"].label, "Open Settings")
        XCTAssertFalse(app.buttons["home.tryIt"].exists)
    }

    // Step 2's big button is Open Settings until a trip away, even with the keyboard already in iOS's list (the
    // Simulator's, as after a reinstall): the list alone moves nothing, and step 2 has no text box. Back from the trip
    // with the keyboard added, the try opens at once, on step 3 of the bar and with no Back; after a restart, likewise.
    func testTheTryComesOnlyAfterATripToSettings() throws {
        let settings = XCUIApplication(bundleIdentifier: "com.apple.Preferences")
        settings.terminate() // an earlier test's search field there would bring up ThumbFree's keyboard, and its mark
        let app = launchWelcome(["-TFGuidePaused", "YES"])
        chooseEnglish(app)
        XCTAssertEqual(primary(app).label, "Open Settings")
        XCTAssertTrue(app.buttons["welcome.notNow"].exists)
        XCTAssertEqual(app.textFields.count + app.textViews.count, 0, "a text box on step 2")
        primary(app).tap() // Open Settings
        XCTAssertTrue(settings.wait(for: .runningForeground, timeout: 10))
        app.activate() // the app switcher's way back
        XCTAssertTrue(tryBox(app).waitForExistence(timeout: 10), "back with the keyboard added, the try did not open")
        XCTAssertEqual(progress(app), "Step 3 of 3, Try ThumbFree")
        XCTAssertFalse(app.buttons["welcome.back"].exists, "the try has a Back")
        settings.terminate()
        app.terminate()
        let again = XCUIApplication()
        again.launchArguments = ["-TFFakeEngine", "YES", "-TFWelcomeDone", "NO", "-TFModelFixture", ThumbFreeUI.jfk]
        again.launch()
        XCTAssertTrue(tryBox(again).waitForExistence(timeout: 15), "after a restart the try did not come back")
    }

    // Back from the Home Screen on step 2 (a trip by any route): with the keyboard added, the try opens; with it not
    // added, step 2 says it is still off. The page decides only once ThumbFree is in front again.
    func testBackInFrontStepTwoGoesOnOrSaysTheKeyboardIsStillOff() throws {
        for (status, lands) in [("added", "try"), ("notAdded", "still off")] {
            let app = launchWelcome(["-TFSetupKeyboard", status, "-TFGuidePaused", "YES"])
            chooseEnglish(app)
            XCUIDevice.shared.press(.home)
            app.activate()
            if lands == "try" {
                XCTAssertTrue(tryBox(app).waitForExistence(timeout: 10), "with the keyboard added, the try did not open")
            } else {
                XCTAssertTrue(ThumbFreeUI.wait(for: title(app), toContain: "The keyboard is still off", timeout: 10))
                XCTAssertEqual(primary(app).label, "Open Settings again")
            }
            app.terminate()
        }
    }

    // Back from Settings by any route with the keyboard still not added: step 2 says where else to look, Open Settings
    // again is the big button, and the guide is still there.
    func testBackWithTheKeyboardStillOffSaysWhereToLook() throws {
        let settings = XCUIApplication(bundleIdentifier: "com.apple.Preferences")
        settings.terminate() // an earlier test's search field there would bring up ThumbFree's keyboard, and its mark
        let app = launchWelcome(Self.notAdded)
        chooseEnglish(app)
        let help = app.staticTexts["In Settings, open General, Keyboard, Keyboards, then add ThumbFree."]
        XCTAssertFalse(help.exists)
        primary(app).tap() // Open Settings
        XCTAssertTrue(settings.wait(for: .runningForeground, timeout: 10))
        app.activate() // the app switcher's way back, not the link
        XCTAssertTrue(ThumbFreeUI.wait(for: title(app), toContain: "The keyboard is still off", timeout: 5))
        XCTAssertTrue(help.exists)
        XCTAssertEqual(primary(app).label, "Open Settings again")
        XCTAssertTrue(ThumbFreeUI.element("welcome.guide", in: app).exists || ThumbFreeUI.element("welcome.guideList", in: app).exists,
                      "the guide went")
        settings.terminate()
    }

    // Step 3 reached while speech still downloads (a step held there, as an older version could save it) waits for the
    // download, with the keyboard added (in iOS's list, not yet seen with Full Access): the percentage, Change language and
    // Not now, and no box, no big button and no line about the keyboard, since the box, once there, is where the
    // keyboard first comes up.
    func testTheLastStepWaitsForTheDownloadWithTheKeyboardAdded() throws {
        XCUIApplication().resetAuthorizationStatus(for: .microphone)
        let app = launchWelcome(["-TFWelcomeStep", "2", "-TFHoldDownload", "30", "-TFGuidePaused", "YES"])
        XCTAssertTrue(ThumbFreeUI.wait(for: tryLine(app), toContain: "Getting speech ready", timeout: 10))
        XCTAssertTrue(app.staticTexts["It downloads once. You can leave ThumbFree."].exists)
        XCTAssertTrue(app.buttons["try.changeLanguage"].exists)
        XCTAssertTrue(app.buttons["try.notNow"].exists)
        XCTAssertFalse(app.buttons["try.fix"].exists, "a big button for work under way")
        XCTAssertFalse(tryBox(app).exists, "a box before speech is ready")
        XCTAssertFalse(app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", "switch to ThumbFree")).firstMatch.exists,
                       "the words name a step the page does not take")
    }

    // A refused microphone stops the try: refused at iOS's prompt at the first take, "Microphone is off"; after a relaunch
    // too, with Open Settings as the big button and no box.
    func testTheLastStepShowsARefusedMicrophone() throws {
        KeyboardSetup.ensureReady()
        XCUIApplication().resetAuthorizationStatus(for: .microphone)
        let app = launchTryWithTheRealMicrophone()
        XCTAssertTrue(tryBox(app).waitForExistence(timeout: 10))
        KeyboardSetup.switchToThumbFree(in: app)
        XCTAssertTrue(ThumbFreeUI.wait(for: tryLine(app), toContain: "Tap the yellow mic", timeout: 10))
        app.buttons["keyboard.mic"].tap()
        ThumbFreeUI.answerMicPrompt("Don’t Allow")
        XCTAssertTrue(ThumbFreeUI.wait(for: tryLine(app), toContain: "Microphone is off", timeout: 10))
        app.terminate()
        let again = launchWelcome(["-TFWelcomeStep", "2", "-TFGuidePaused", "YES"])
        XCTAssertTrue(ThumbFreeUI.wait(for: tryLine(again), toContain: "Microphone is off", timeout: 10))
        XCTAssertTrue(again.staticTexts["Turn it on in Settings to try ThumbFree."].exists)
        XCTAssertEqual(again.buttons["try.fix"].label, "Open Settings")
        XCTAssertFalse(tryBox(again).exists, "a box with the microphone refused")
        again.terminate()
        XCUIApplication().resetAuthorizationStatus(for: .microphone) // later tests need it unanswered
    }

    // The keyboard's "get the model" link, before any language is chosen, leaves the welcome on its question: step 1 is
    // where speech gets ready. The language then starts the download, and the flow goes on.
    func testTheModelLinkLeavesTheWelcomeWhereItIs() throws {
        let app = launchWelcome()
        XCTAssertTrue(ThumbFreeUI.wait(for: title(app), toContain: "Your voice becomes text in any app.", timeout: 10))
        XCUIDevice.shared.system.open(try XCTUnwrap(URL(string: "thumbfree://model")))
        XCTAssertTrue(app.wait(for: .runningForeground, timeout: 5))
        Thread.sleep(forTimeInterval: 1) // the link's handling
        XCTAssertTrue(app.buttons["welcome.english"].exists, "the link left the language question")
        XCTAssertFalse(tryLine(app).exists, "the link opened the try")
        chooseEnglish(app)
    }

    // The step reached is saved: iOS restarts the app when a permission changes in Settings, and the flow comes back there.
    func testAFlowLeftHalfwayComesBackAtItsStep() throws {
        let app = launchWelcome(Self.notAdded)
        chooseEnglish(app)
        app.terminate()
        let again = XCUIApplication()
        again.launchArguments = ["-TFFakeEngine", "YES", "-TFWelcomeDone", "NO", "-TFModelFixture", ThumbFreeUI.jfk] + Self.notAdded
        again.launch()
        XCTAssertTrue(ThumbFreeUI.wait(for: title(again), toContain: "Add the ThumbFree keyboard", timeout: 15))
    }
}
