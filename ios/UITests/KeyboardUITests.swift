import XCTest

@MainActor final class KeyboardUITests: XCTestCase {
    func testTheKeysTypeIntoTheTryField() throws {
        KeyboardSetup.ensureReady()
        let app = ThumbFreeUI.launch()
        let field = ThumbFreeUI.element("try.field", in: app)
        field.tap()
        KeyboardSetup.switchToThumbFree(in: app)
        // The letters layer: type "hi". The field auto-capitalizes the first letter of the sentence, so it reads "Hi".
        for id in ["keyboard.key.h", "keyboard.key.i"] { ThumbFreeUI.element(id, in: app).tap() }
        // Waits first: these inserts cross from the keyboard extension's process into the app's, and an immediate read
        // can beat that hop on a loaded Mac.
        XCTAssertTrue(ThumbFreeUI.wait(for: field, toContain: "Hi", timeout: 5), "the field did not pick up the typed keys")
        XCTAssertEqual(field.value as? String, "Hi")
        // The 123 layer holds the punctuation; a period, then back to letters.
        ThumbFreeUI.element("keyboard.toNumbers", in: app).tap()
        ThumbFreeUI.element("keyboard.key.period", in: app).tap()
        XCTAssertTrue(ThumbFreeUI.wait(for: field, toContain: "Hi.", timeout: 5))
        ThumbFreeUI.element("keyboard.delete", in: app).tap()
        XCTAssertTrue(ThumbFreeUI.wait(for: field, toContain: "Hi", timeout: 5), "delete did not remove the last character")
        XCTAssertEqual(field.value as? String, "Hi")
    }

    // The return key inserts a newline and is labelled for the field; double space makes a full stop.
    func testReturnAndDoubleSpace() throws {
        KeyboardSetup.ensureReady()
        let app = ThumbFreeUI.launch()
        let field = ThumbFreeUI.element("try.field", in: app)
        field.tap()
        KeyboardSetup.switchToThumbFree(in: app)
        for id in ["keyboard.key.h", "keyboard.key.i"] { ThumbFreeUI.element(id, in: app).tap() }
        // Two quick spaces -> ". ". One double tap, not two taps: two separate taps land about 460 ms apart on this Mac,
        // too close to the 500 ms double-space window to be a steady test.
        ThumbFreeUI.element("keyboard.space", in: app).doubleTap()
        XCTAssertTrue(ThumbFreeUI.wait(for: field, toContain: "Hi. ", timeout: 5), "double space did not make a full stop")
        let ret = ThumbFreeUI.element("keyboard.return", in: app)
        XCTAssertEqual(ret.label, "return") // the practice box's return key type is the default one
        ret.tap()
        for id in ["keyboard.key.b", "keyboard.key.y", "keyboard.key.e"] { ThumbFreeUI.element(id, in: app).tap() }
        XCTAssertTrue(ThumbFreeUI.wait(for: field, toContain: "bye", timeout: 5), "the letters after return did not arrive")
        XCTAssertEqual(field.value as? String, "Hi. \nBye") // a new line starts a sentence, so "bye" gets its capital
    }

    // Apple's layer rule: a space typed on the 123 layer goes back to the letters.
    func testASpaceOnTheNumbersLayerGoesBackToLetters() throws {
        KeyboardSetup.ensureReady()
        let app = ThumbFreeUI.launch()
        let field = ThumbFreeUI.element("try.field", in: app)
        field.tap()
        KeyboardSetup.switchToThumbFree(in: app)
        ThumbFreeUI.element("keyboard.toNumbers", in: app).tap()
        ThumbFreeUI.element("keyboard.key.5", in: app).tap()
        ThumbFreeUI.element("keyboard.space", in: app).tap()
        let a = ThumbFreeUI.element("keyboard.key.a", in: app)
        XCTAssertTrue(a.waitForExistence(timeout: 3), "the letters did not come back after the space")
        a.tap()
        XCTAssertTrue(ThumbFreeUI.wait(for: field, toContain: "5 a", timeout: 5), "the letter after the space did not arrive")
        XCTAssertEqual(field.value as? String, "5 a")
    }

    // Once delete has fired, sliding the finger off it onto a letter only stops it: the release types nothing.
    func testSlidingOffDeleteTypesNothing() throws {
        KeyboardSetup.ensureReady()
        let app = ThumbFreeUI.launch()
        let field = ThumbFreeUI.element("try.field", in: app)
        field.tap()
        KeyboardSetup.switchToThumbFree(in: app)
        // A word with no m, long enough that a stray delete repeat never empties the field (an empty field's value is
        // its placeholder, which has an m).
        for letter in "hello" { ThumbFreeUI.element("keyboard.key.\(letter)", in: app).tap() }
        XCTAssertTrue(ThumbFreeUI.wait(for: field, toContain: "Hello", timeout: 5), "the letters did not arrive")
        // Held under the 500 ms before delete repeats, then slid onto the m key next to it and let go there.
        ThumbFreeUI.element("keyboard.delete", in: app)
            .press(forDuration: 0.1, thenDragTo: ThumbFreeUI.element("keyboard.key.m", in: app), withVelocity: .fast, thenHoldForDuration: 0)
        XCTAssertTrue(ThumbFreeUI.wait(until: 5) { !((field.value as? String) ?? "").hasPrefix("Hello") }, "delete did not fire at the touch")
        Thread.sleep(forTimeInterval: 0.5) // a letter typed on release would have arrived by now
        let text = field.value as? String ?? ""
        XCTAssertFalse(text.contains("m"), "the release typed the key the finger slid onto: \(text)")
    }

    // A double tap on shift locks capitals, also at the start of a sentence, where one capital is already on.
    func testADoubleTapOnShiftLocksCapitals() throws {
        KeyboardSetup.ensureReady()
        let app = ThumbFreeUI.launch()
        let field = ThumbFreeUI.element("try.field", in: app)
        field.tap()
        KeyboardSetup.switchToThumbFree(in: app)
        ThumbFreeUI.element("keyboard.shift", in: app).doubleTap()
        for id in ["keyboard.key.o", "keyboard.key.k"] { ThumbFreeUI.element(id, in: app).tap() }
        XCTAssertTrue(ThumbFreeUI.wait(for: field, toContain: "ok", timeout: 5), "the letters did not arrive")
        XCTAssertEqual(field.value as? String, "OK", "caps lock did not hold for both letters")
    }

    // An automatic capital goes away as well as coming on: deleting back past the space after a full stop turns it off,
    // and "Hi." with no space after it does not end a sentence, so the next letter stays lowercase.
    func testAnAutomaticCapitalGoesAwayWhenTheSentenceEndIsDeleted() throws {
        KeyboardSetup.ensureReady()
        let app = ThumbFreeUI.launch()
        let field = ThumbFreeUI.element("try.field", in: app)
        field.tap()
        KeyboardSetup.switchToThumbFree(in: app)
        for id in ["keyboard.key.h", "keyboard.key.i"] { ThumbFreeUI.element(id, in: app).tap() }
        ThumbFreeUI.element("keyboard.space", in: app).doubleTap()
        XCTAssertTrue(ThumbFreeUI.wait(for: field, toContain: "Hi. ", timeout: 5), "double space did not make a full stop")
        ThumbFreeUI.element("keyboard.delete", in: app).tap()
        ThumbFreeUI.element("keyboard.key.x", in: app).tap()
        XCTAssertTrue(ThumbFreeUI.wait(for: field, toContain: "Hi.x", timeout: 5), "the letter did not arrive")
        XCTAssertEqual(field.value as? String, "Hi.x")
    }

    // Holding delete repeats it, and it must stop the moment it is released, including the very next tap: the bug
    // this guards against left a leaked repeat loop that both kept deleting and swallowed the next press.
    func testHoldingDeleteRepeatsAndStopsOnRelease() throws {
        KeyboardSetup.ensureReady()
        let app = ThumbFreeUI.launch()
        let field = ThumbFreeUI.element("try.field", in: app)
        field.tap()
        KeyboardSetup.switchToThumbFree(in: app)
        // Long enough that a 1.2 s hold (about 1 + (1200 - 500) / 100 = 8 deletes, Apple's pace) cannot empty it, leaving
        // something for the "next tap" check below.
        let seedCount = 18
        let a = ThumbFreeUI.element("keyboard.key.a", in: app)
        for _ in 0..<seedCount { a.tap() }
        XCTAssertTrue(ThumbFreeUI.wait(until: 5) { (field.value as? String)?.count == seedCount })

        ThumbFreeUI.element("keyboard.delete", in: app).press(forDuration: 1.2) // 500 ms to the first repeat, then every 100 ms
        XCTAssertTrue(ThumbFreeUI.wait(until: 5) { let n = (field.value as? String)?.count ?? seedCount; return n > 0 && n <= seedCount - 3 },
                      "holding delete did not remove several characters")

        let afterHold = field.value as? String
        Thread.sleep(forTimeInterval: 0.5) // longer than one repeat tick; the count must not keep dropping
        XCTAssertEqual(field.value as? String, afterHold, "delete kept repeating after release")

        ThumbFreeUI.element("keyboard.delete", in: app).tap()
        XCTAssertTrue(ThumbFreeUI.wait(until: 5) { (field.value as? String)?.count == (afterHold?.count ?? 1) - 1 },
                      "the next tap after a held delete did not register")
    }

    // The keyboard in another app (the Settings search field) opens ThumbFree with the dictate link.
    func testTheMicOpensThumbFreeFromAnotherApp() throws {
        KeyboardSetup.ensureReady()
        let app = ThumbFreeUI.launch()
        let (settings, _) = settingsSearchWithThumbFree()
        opensThumbFree(app, tapping: settings.buttons["keyboard.mic"])
        let listening = app.staticTexts["session.title"]
        app.buttons["session.end"].tap()
        XCTAssertTrue(listening.waitForNonExistence(timeout: 5), "End session did not close the session screen")
        XCTAssertTrue(ThumbFreeUI.element("try.field", in: app).exists)
        // settingsSearchWithThumbFree already switched to the ThumbFree keyboard once, in Settings: its
        // viewWillAppear wrote the keyboard's mark, so the Try tab's setup card no longer asks for the keyboard,
        // per the contract in docs/contract.md.
        let keyboardLine = app.descendants(matching: .any)
            .matching(NSPredicate(format: "label CONTAINS[c] %@ OR label CONTAINS[c] %@", "switch to ThumbFree", "Add the keyboard")).firstMatch
        XCTAssertTrue(ThumbFreeUI.wait(until: 5) { !keyboardLine.exists },
                      "the Try tab still asked for the keyboard after it appeared in another app")
    }

    // ThumbFree is not running (iOS ended it, or it was swiped away): the mic opens it, the take starts, and its text
    // goes to the field where the stop was tapped (the cold-take rule). iOS starts the app with no launch arguments, so
    // `-TFKeepSetup` keeps the fixed engine and JFK for that launch.
    func testTheMicOpensThumbFreeWhenItIsNotRunning() throws {
        KeyboardSetup.ensureReady()
        let app = ThumbFreeUI.launch(arguments: ["-TFKeepSetup", "YES"])
        XCTAssertTrue(ThumbFreeUI.element("try.field", in: app).waitForExistence(timeout: 10))
        app.terminate()
        XCTAssertEqual(app.state, .notRunning)
        let (settings, search) = settingsSearchWithThumbFree()
        let mic = settings.buttons["keyboard.mic"]
        opensThumbFree(app, tapping: mic)
        settings.activate() // back to Settings, as the status bar's link does
        XCTAssertTrue(ThumbFreeUI.wait(for: settings.staticTexts["keyboard.status"], toContain: "Recording", timeout: 10))
        mic.tap()
        XCTAssertTrue(ThumbFreeUI.wait(for: search, toContain: "ask not what your country can do for you", timeout: 20))
    }

    // ThumbFree died during a take. For a few seconds its last status still names that take, so the keyboard's press and
    // link name it too: the new launch starts a fresh take for the link, shows the session screen for it, and its text
    // goes where the stop is tapped.
    func testTheMicStartsAFreshTakeAfterThumbFreeDiedDuringOne() throws {
        KeyboardSetup.ensureReady()
        let app = ThumbFreeUI.launch(arguments: ["-TFKeepSetup", "YES"])
        ThumbFreeUI.element("try.mic", in: app).tap()
        XCTAssertTrue(ThumbFreeUI.wait(for: ThumbFreeUI.element("try.status", in: app), toContain: "Listening", timeout: 10))
        let (settings, search) = settingsSearchWithThumbFree() // the take keeps recording in the background
        let status = settings.staticTexts["keyboard.status"]
        XCTAssertTrue(ThumbFreeUI.wait(for: status, toContain: "Recording", timeout: 10))
        app.terminate() // during the take
        // Checked at once: a predicate wait polls about once a second. Skip rather than fail when terminate itself was
        // slow enough that the status already passed 5 s: the unit test (KeyStateTests) still covers that rule.
        try XCTSkipUnless(status.label.contains("Recording"),
                           "the run was too slow: the keyboard's status was already over 5 s old")
        let mic = settings.buttons["keyboard.mic"]
        opensThumbFree(app, tapping: mic)
        settings.activate()
        XCTAssertTrue(ThumbFreeUI.wait(for: status, toContain: "Recording", timeout: 10))
        mic.tap()
        XCTAssertTrue(ThumbFreeUI.wait(for: search, toContain: "ask not what your country can do for you", timeout: 20))
    }

    // ThumbFree went away with its session live. Its last status says "Ready, mic on", but the keyboard trusts that only
    // from a status under 5 s old, so it shows its idle words. Nothing takes the press within 150 ms, so the keyboard
    // opens ThumbFree instead of waiting for it.
    func testTheMicOpensThumbFreeWhenItsLiveSessionWentAway() throws {
        KeyboardSetup.ensureReady()
        let app = ThumbFreeUI.launch(arguments: ["-TFKeepSetup", "YES"])
        let tryMic = ThumbFreeUI.element("try.mic", in: app)
        tryMic.tap()
        XCTAssertTrue(ThumbFreeUI.wait(for: ThumbFreeUI.element("try.status", in: app), toContain: "Listening", timeout: 10))
        sleep(3)
        tryMic.tap()
        XCTAssertTrue(ThumbFreeUI.wait(for: ThumbFreeUI.element("try.field", in: app), toContain: "ask not what your country", timeout: 20))
        app.terminate()
        XCTAssertEqual(app.state, .notRunning)
        let (settings, _) = settingsSearchWithThumbFree()
        XCTAssertTrue(ThumbFreeUI.wait(for: settings.staticTexts["keyboard.status"], toContain: "Tap the mic. ThumbFree opens", timeout: 10))
        opensThumbFree(app, tapping: settings.buttons["keyboard.mic"])
    }

    // A take recording is plain to see: the status line says Recording with the take's time counting up, and the mic is
    // the stop key. With the emoji picker up, the recording line has the Search Emoji field's place until the take
    // ends.
    func testARecordingTakeShowsRecordingItsTimeAndTheStopKey() throws {
        KeyboardSetup.ensureReady()
        let app = ThumbFreeUI.launch()
        ThumbFreeUI.element("try.field", in: app).tap()
        KeyboardSetup.switchToThumbFree(in: app)
        let mic = app.buttons["keyboard.mic"]
        let status = app.staticTexts["keyboard.status"]
        let search = ThumbFreeUI.element("keyboard.emoji.search", in: app)
        /// The seconds in what VoiceOver hears: "Recording, 7 seconds. Speak now."
        func seconds() -> Int? { status.label.firstMatch(of: /^Recording, (\d+) seconds?\. Speak now\.$/).flatMap { Int($0.1) } }
        mic.tap()
        XCTAssertTrue(ThumbFreeUI.wait(until: 15) { seconds() != nil }, "the status line did not say Recording: \(status.label)")
        XCTAssertEqual(mic.label, "Stop dictation")
        let first = try XCTUnwrap(seconds())
        XCTAssertTrue(ThumbFreeUI.wait(until: 5) { (seconds() ?? 0) > first }, "the time did not count up from \(first) s")
        ThumbFreeUI.element("keyboard.emoji", in: app).tap()
        XCTAssertTrue(ThumbFreeUI.element("keyboard.emoji.picker", in: app).waitForExistence(timeout: 5))
        XCTAssertNotNil(seconds(), "the recording line went away with the picker up: \(status.label)")
        XCTAssertFalse(search.exists, "the Search Emoji field kept its place during the take")
        XCTAssertEqual(mic.label, "Stop dictation")
        mic.tap()
        XCTAssertTrue(search.waitForExistence(timeout: 10), "the Search Emoji field did not come back after the take")
    }

    /// The Settings search field, with the ThumbFree keyboard up.
    private func settingsSearchWithThumbFree() -> (XCUIApplication, XCUIElement) {
        let settings = XCUIApplication(bundleIdentifier: "com.apple.Preferences")
        settings.launch()
        let search = settings.searchFields.firstMatch
        XCTAssertTrue(search.waitForExistence(timeout: 5))
        search.tap()
        KeyboardSetup.switchToThumbFree(in: settings)
        return (settings, search)
    }

    /// Taps the keyboard's mic and waits for ThumbFree's session screen.
    private func opensThumbFree(_ app: XCUIApplication, tapping mic: XCUIElement) {
        mic.tap()
        XCTAssertTrue(app.wait(for: .runningForeground, timeout: 10))
        XCTAssertTrue(ThumbFreeUI.wait(for: app.staticTexts["session.title"], toContain: "Listening", timeout: 10))
    }
}
