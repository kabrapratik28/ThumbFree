import XCTest

/// The first try (`TryItView`): reached with speech not there yet (an old saved step), it waits for speech, then shows its
/// box; each stage says its one thing; a picture left out on a small iPhone leaves VoiceOver too; Home offers Try it once
/// dictation works and until a take has given text, and "Try ThumbFree" while only the try is left; and a real keyboard
/// take in its box reaches "Your words appeared".
@MainActor final class TryItUITests: XCTestCase {
    func line(_ app: XCUIApplication) -> XCUIElement { ThumbFreeUI.element("try.line", in: app) }
    func field(_ app: XCUIApplication) -> XCUIElement { ThumbFreeUI.element("try.field", in: app) }

    /// Launches with the microphone's permission reset first: a refusal another test left would stop the try, though
    /// the audio comes from JFK all the same.
    func launch(welcome: Bool = false, realModel: Bool = false, _ arguments: [String]) -> XCUIApplication {
        XCUIApplication().resetAuthorizationStatus(for: .microphone)
        return ThumbFreeUI.launch(realModel: realModel, welcome: welcome, arguments: arguments)
    }

    /// Allows the microphone in Settings' Setup row: Home has no microphone card.
    func allowMic(_ app: XCUIApplication) {
        XCTAssertTrue(ThumbFreeUI.onHome(app))
        ThumbFreeUI.allowMicrophone(in: app)
    }

    // Step 3 reached with no download begun (a saved step held there) waits for speech: "Speech isn't ready" with
    // Download as the big button; once the model is in and ready, the box, with no row asking for Full Access (its box is
    // where the keyboard first comes up). Not now finishes the welcome, and Home offers the try again: "Try ThumbFree"
    // with Try it, since the microphone is still to be asked there, or Try it once ready.
    func testTheLastStepOffersTryItOnceTheModelIsReady() throws {
        let app = launch(welcome: true, ["-TFModelFixture", ThumbFreeUI.jfk, "-TFWelcomeStep", "2"])
        let fix = app.buttons["try.fix"]
        XCTAssertTrue(ThumbFreeUI.wait(for: line(app), toContain: "Speech isn", timeout: 10))
        XCTAssertEqual(fix.label, "Download")
        fix.tap()
        XCTAssertTrue(field(app).waitForExistence(timeout: 30), "the box never came")
        XCTAssertTrue(line(app).exists) // the switch, or the mic when the ThumbFree keyboard is already the one that comes up
        XCTAssertFalse(app.buttons["setup.fullAccess"].exists, "the try asks for Full Access its box confirms")
        ThumbFreeUI.shot("try-step3")
        app.buttons["try.notNow"].tap()
        XCTAssertTrue(ThumbFreeUI.onHome(app), "Not now did not open Home")
        XCTAssertFalse(ThumbFreeUI.element("welcome.title", in: app).exists)
        let fixAgain = app.buttons["home.fix"], tryIt = app.buttons["home.tryIt"]
        XCTAssertTrue(ThumbFreeUI.wait(until: 10) { tryIt.exists || (fixAgain.exists && fixAgain.label == "Try it") },
                      "Home does not offer the try again")
        XCTAssertTrue(ThumbFreeUI.wait(for: ThumbFreeUI.element("home.blocker", in: app), toContain: "Try ThumbFree", timeout: 5))
    }

    /// The iPhone keyboard is up: Apple's, or ThumbFree's with its mic.
    func keyboardUp(_ app: XCUIApplication) -> Bool {
        ThumbFreeUI.wait(until: 5) { app.keyboards.firstMatch.exists || app.buttons["keyboard.mic"].exists }
    }

    // The box takes the keyboard by itself, with no tap: on step 3, back in front after leaving ThumbFree, and in the try
    // Home offers ("Try ThumbFree", the microphone still to be asked), so the person never has to find the box to see the
    // keyboard and its globe.
    func testTheBoxTakesTheKeyboardWithNoTap() throws {
        let app = launch(welcome: true, ["-TFWelcomeStep", "2", "-TFSetupKeyboard", "ready"]) // the fixed engine: speech is ready
        XCTAssertTrue(field(app).waitForExistence(timeout: 10))
        XCTAssertTrue(keyboardUp(app), "no keyboard on step 3")
        XCUIDevice.shared.press(.home)
        app.activate()
        XCTAssertTrue(field(app).waitForExistence(timeout: 5))
        XCTAssertTrue(keyboardUp(app), "no keyboard back in ThumbFree")
        app.buttons["try.notNow"].tap() // ends the welcome
        let tryIt = app.buttons["home.fix"]
        XCTAssertTrue(ThumbFreeUI.wait(for: tryIt, toContain: "Try it", timeout: 10), "Home does not offer the try")
        tryIt.tap()
        XCTAssertTrue(field(app).waitForExistence(timeout: 5))
        XCTAssertTrue(keyboardUp(app), "no keyboard in Home's Try it")
    }

    // On a small iPhone (an SE) the welcome's step 3, its bar of steps and the keyboard leave no room for HOW TO SWITCH:
    // the picture goes, and VoiceOver finds nothing of it over the box. Skipped on a taller iPhone, where it has room.
    func testALeftOutPictureLeavesVoiceOverToo() throws {
        let app = launch(welcome: true, ["-TFWelcomeStep", "2", "-TFSetupKeyboard", "ready", "-TFTryStage", "switchKeyboard",
                                         "-TFGuidePaused", "YES"])
        XCTAssertTrue(field(app).waitForExistence(timeout: 5))
        try XCTSkipIf(app.windows.firstMatch.frame.height > 700, "a taller iPhone has room for the picture")
        XCTAssertTrue(keyboardUp(app), "no keyboard")
        XCTAssertTrue(ThumbFreeUI.wait(until: 5) { !ThumbFreeUI.element("try.picture", in: app).exists },
                      "the left-out picture is still in VoiceOver's tree")
    }

    // Each stage, held for the test, shows its line (VoiceOver's words for the globe): the switch with its picture, the
    // mic, the take recording and turning into text, and the end, with the keyboard down and Done in place of Not now.
    func testEachStageShowsItsLine() throws {
        let lines = ["switchKeyboard": "Double-tap and hold the globe key at the bottom left, then choose ThumbFree.",
                     "tapMic": "Tap the yellow mic.",
                     "recording": "Speak, then tap the red stop button.",
                     "transcribing": "Turning speech into text…",
                     "worked": "Your words appeared. You’re ready."]
        for stage in ["switchKeyboard", "tapMic", "recording", "transcribing", "worked"] {
            let app = launch(["-TFOpenTry", "YES", "-TFTryStage", stage, "-TFGuidePaused", "YES"])
            XCTAssertTrue(ThumbFreeUI.wait(for: line(app), toContain: lines[stage] ?? "", timeout: 5), stage)
            XCTAssertTrue(field(app).exists, stage)
            XCTAssertEqual(ThumbFreeUI.element("try.picture", in: app).exists, stage == "switchKeyboard", stage)
            XCTAssertEqual(app.buttons["try.done"].exists, stage == "worked", stage)
            XCTAssertEqual(app.buttons["try.notNow"].exists, stage != "worked", stage)
            if stage == "switchKeyboard" {
                XCTAssertLessThanOrEqual(ThumbFreeUI.element("try.picture", in: app).frame.height, 152.5, "the helper is taller than 152 points")
            }
            if stage == "worked" {
                XCTAssertFalse(app.keyboards.firstMatch.exists || app.buttons["keyboard.mic"].exists, "the keyboard is up")
            } else {
                XCTAssertGreaterThanOrEqual(app.buttons["try.notNow"].frame.height, 44, "Not now's tap target is under 44 points")
            }
            app.terminate()
        }
    }

    // Home offers Try it only once dictation works (the microphone allowed, the keyboard seen, speech ready), and only
    // until a take has given text (a count kept across launches, set here through the launch arguments); before, its one
    // card says what is missing. Not now closes the try.
    func testHomeOffersTryItUntilATakeGaveText() throws {
        let app = launch(["-TFModelFixture", ThumbFreeUI.jfk, "-TFSetupKeyboard", "ready"])
        let tryIt = app.buttons["home.tryIt"]
        allowMic(app)
        let card = ThumbFreeUI.element("home.blocker", in: app)
        XCTAssertTrue(ThumbFreeUI.wait(for: card, toContain: "Speech isn", timeout: 5))
        XCTAssertFalse(tryIt.exists, "Try it before the model is in")
        app.buttons["home.fix"].tap() // Download
        XCTAssertTrue(tryIt.waitForExistence(timeout: 30), "the model did not come, or Home is not ready")
        XCTAssertGreaterThanOrEqual(tryIt.frame.height, 44, "Try it's tap target is under 44 points")
        XCTAssertTrue(ThumbFreeUI.element("home.ready", in: app).exists)
        ThumbFreeUI.shot("try-home")
        tryIt.tap()
        XCTAssertTrue(field(app).waitForExistence(timeout: 5))
        app.buttons["try.notNow"].tap()
        XCTAssertTrue(field(app).waitForNonExistence(timeout: 5))
        XCTAssertTrue(tryIt.exists)
        app.terminate()
        let later = ThumbFreeUI.launch(arguments: ["-TFTextTakes", "1", "-TFSetupKeyboard", "ready"]) // the microphone stays allowed
        XCTAssertTrue(ThumbFreeUI.element("home.ready", in: later).waitForExistence(timeout: 10), "Home is not ready, so the check below proves nothing")
        XCTAssertFalse(later.buttons["home.tryIt"].exists, "Try it after a take gave text")
        XCTAssertTrue(later.buttons["home.putFirst"].exists)
    }

    // Home offers Try it once the engine is ready on this iPhone, so after a launch it gets the engine ready itself (the
    // fixed-text engine is ready at once; nothing else here loads it).
    func testHomeGetsTheEngineReadyForTryIt() throws {
        let app = launch(["-TFSetupKeyboard", "ready"])
        allowMic(app)
        XCTAssertTrue(app.buttons["home.tryIt"].waitForExistence(timeout: 10), "Home never got the engine ready for Try it")
    }

    // With the keyboard not in iOS's list (a launch argument stands in for it), Home's one card says to add it, with Open
    // Settings, and Try it waits; a microphone not asked yet is no card before it.
    func testHomeWaitsForTheKeyboardInIOSsList() throws {
        let app = launch(["-AppleKeyboards", "(\"en_US@sw=QWERTY\")"])
        XCTAssertTrue(ThumbFreeUI.wait(for: ThumbFreeUI.element("home.blocker", in: app), toContain: "The keyboard is still off", timeout: 5))
        XCTAssertEqual(app.buttons["home.fix"].label, "Open Settings")
        XCTAssertFalse(app.buttons["home.tryIt"].exists, "Try it with the keyboard not added")
    }

    // Not now in the middle of a take cancels it: History keeps it as Cancelled, and Home still offers the try (once the
    // microphone is allowed), since no text came. Without the cancel, the take would record on with no box to type into,
    // and count once it ended.
    func testLeavingMidTakeCancelsIt() throws {
        XCUIApplication().resetAuthorizationStatus(for: .microphone) // KeyboardSetup's check opens the try too
        KeyboardSetup.ensureReady()
        let app = launch(["-TFOpenTry", "YES"])
        XCTAssertTrue(field(app).waitForExistence(timeout: 5))
        KeyboardSetup.switchToThumbFree(in: app)
        app.buttons["keyboard.mic"].tap()
        XCTAssertTrue(ThumbFreeUI.wait(for: app.staticTexts["keyboard.status"], toContain: "Recording", timeout: 15))
        app.buttons["try.notNow"].tap()
        XCTAssertTrue(field(app).waitForNonExistence(timeout: 5))
        app.tabBars.buttons["History"].tap()
        XCTAssertTrue(app.staticTexts["Cancelled"].waitForExistence(timeout: 5), "the take was not cancelled")
        app.tabBars.buttons["Home"].tap()
        allowMic(app)
        XCTAssertTrue(app.buttons["home.tryIt"].waitForExistence(timeout: 5), "Home stopped offering the try")
    }

    // In the box, the ThumbFree keyboard's take types into it (JFK plays as the microphone, the fixed-text engine answers;
    // with TEST_RUNNER_TF_MODELS_DIR set, the Mac's model): the line moves from the switch to the mic, to speaking while
    // the take records, to "Your words appeared", the keyboard goes down with the words left in the box, and Done ends
    // the try, after which Home, once the microphone is allowed, no longer offers it.
    func testAKeyboardTakeInTheBoxWorks() throws {
        let realModel = ProcessInfo.processInfo.environment["TF_MODELS_DIR"] != nil
        XCUIApplication().resetAuthorizationStatus(for: .microphone) // KeyboardSetup's check opens the try too
        KeyboardSetup.ensureReady()
        let app = launch(realModel: realModel, ["-TFOpenTry", "YES"])
        XCTAssertTrue(field(app).waitForExistence(timeout: 5))
        KeyboardSetup.switchToThumbFree(in: app)
        XCTAssertTrue(ThumbFreeUI.wait(for: line(app), toContain: "Tap the yellow mic", timeout: 5))
        let mic = app.buttons["keyboard.mic"]
        mic.tap()
        XCTAssertTrue(ThumbFreeUI.wait(for: app.staticTexts["keyboard.status"], toContain: "Recording", timeout: 15))
        XCTAssertTrue(ThumbFreeUI.wait(for: line(app), toContain: "Speak, then tap the red stop button.", timeout: 5))
        sleep(realModel ? 12 : 3) // JFK is 11 s
        mic.tap()
        XCTAssertTrue(ThumbFreeUI.wait(for: field(app), toContain: "ask not what your country can do for you", timeout: 120))
        XCTAssertTrue(ThumbFreeUI.wait(for: line(app), toContain: "Your words appeared. You’re ready.", timeout: 5))
        // Where Apple Intelligence is ready (a Mac that has it reports it to its Simulators), Clean up's beat keeps the
        // keyboard up for the sparkle (CleanupUITests); elsewhere it goes down.
        if ThumbFreeUI.element("try.cleanup", in: app).waitForExistence(timeout: 2) {
            XCTAssertTrue(mic.exists, "Clean up's beat took the keyboard down")
        } else {
            XCTAssertTrue(mic.waitForNonExistence(timeout: 5), "the keyboard stayed up")
        }
        XCTAssertTrue((field(app).value as? String ?? "").contains("ask not what your country can do for you"), "the words left the box")
        ThumbFreeUI.shot("try-worked")
        app.buttons["try.done"].tap()
        XCTAssertTrue(field(app).waitForNonExistence(timeout: 5))
        allowMic(app)
        XCTAssertTrue(ThumbFreeUI.element("home.ready", in: app).waitForExistence(timeout: 5))
        XCTAssertTrue(app.staticTexts["How to use it in other apps"].exists)
        XCTAssertFalse(app.buttons["home.tryIt"].exists, "Try it after a take gave text")
    }
}
