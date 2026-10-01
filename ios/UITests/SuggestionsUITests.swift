import XCTest

/// Apple's suggestions and autocorrection in ThumbFree's keyboard, checked against Apple's keyboard on the iOS 26.5
/// Simulator.
@MainActor final class SuggestionsUITests: XCTestCase {
    // While a word is typed Apple's three places take the status's place: the word as typed, in quotes, then words it may
    // become. A tapped one takes the typed word's place and gets a space; a space typed next is dropped, and a comma takes
    // the space's place, as on Apple's.
    func testATappedSuggestionReplacesTheWord() throws {
        let (app, field) = tryField()
        XCTAssertTrue(ThumbFreeUI.element("keyboard.status", in: app).exists, "the status shows before the first key")
        type("hel", in: app)
        let typed = ThumbFreeUI.element("keyboard.suggestion.0", in: app)
        XCTAssertTrue(ThumbFreeUI.wait(for: typed, toContain: "\u{201C}Hel\u{201D}", timeout: 5), "no suggestions while typing")
        XCTAssertFalse(ThumbFreeUI.element("keyboard.status", in: app).exists, "the suggestions take the status's place")
        let hello = ThumbFreeUI.element("keyboard.suggestion.1", in: app)
        XCTAssertEqual(hello.label, "Hello")
        hello.tap()
        XCTAssertTrue(ThumbFreeUI.wait(for: field, toContain: "Hello ", timeout: 5))
        ThumbFreeUI.element("keyboard.space", in: app).tap()
        type("wor", in: app)
        let world = ThumbFreeUI.element("keyboard.suggestion.1", in: app)
        XCTAssertTrue(ThumbFreeUI.wait(for: world, toContain: "world", timeout: 5))
        world.tap()
        ThumbFreeUI.element("keyboard.toNumbers", in: app).tap()
        ThumbFreeUI.element("keyboard.key.comma", in: app).tap()
        XCTAssertTrue(ThumbFreeUI.wait(until: 5) { field.value as? String == "Hello world," }, "not Apple's spacing: \(field.value ?? "")")
    }

    // Apple's autocorrection: while "teh" is typed the correction is lit in the middle place, and the space types it in
    // the word's place; a comma ends a word the same way.
    func testATypoIsCorrectedAtTheWordsEnd() throws {
        let (app, field) = tryField()
        type("teh", in: app)
        let middle = ThumbFreeUI.element("keyboard.suggestion.1", in: app)
        XCTAssertTrue(ThumbFreeUI.wait(for: middle, toContain: "The", timeout: 5), "no correction offered")
        XCTAssertTrue(middle.isSelected, "the correction is not lit")
        ThumbFreeUI.element("keyboard.space", in: app).tap()
        XCTAssertTrue(ThumbFreeUI.wait(until: 5) { field.value as? String == "The " }, "teh was not corrected: \(field.value ?? "")")
        type("teh", in: app)
        ThumbFreeUI.element("keyboard.toNumbers", in: app).tap()
        ThumbFreeUI.element("keyboard.key.comma", in: app).tap()
        XCTAssertTrue(ThumbFreeUI.wait(until: 5) { field.value as? String == "The the," }, "not corrected before the comma: \(field.value ?? "")")
    }

    // No correction where the field turns it off: a web address field (ThumbFree's own rule), and a field whose app
    // turned autocorrection off, where Apple's bar still suggests but lights nothing.
    func testNoCorrectionWhereTheFieldTurnsItOff() throws {
        var (app, field) = tryField(["-TFFieldType", "3"]) // UIKeyboardType.URL
        type("teh", in: app) // the practice box capitalizes a sentence's first letter, whatever the keyboard type
        ThumbFreeUI.element("keyboard.key.period", in: app).tap()
        XCTAssertTrue(ThumbFreeUI.wait(until: 5) { field.value as? String == "Teh." }, "corrected in a web address: \(field.value ?? "")")
        app.terminate()
        (app, field) = tryField(["-TFAutocorrect", "NO"])
        type("teh", in: app)
        let middle = ThumbFreeUI.element("keyboard.suggestion.1", in: app)
        XCTAssertTrue(ThumbFreeUI.wait(for: middle, toContain: "The", timeout: 5), "no suggestions with autocorrection off")
        XCTAssertFalse(middle.isSelected, "lit where nothing will be corrected")
        ThumbFreeUI.element("keyboard.space", in: app).tap()
        XCTAssertTrue(ThumbFreeUI.wait(until: 5) { field.value as? String == "Teh " }, "corrected with autocorrection off: \(field.value ?? "")")
    }

    // Apple's undo: a delete right after a correction offers the word as typed in the bar's first place (Apple's keyboard
    // shows it in a bubble under the word, which a keyboard cannot draw); a tap puts it back, without a space, and it is
    // never corrected after.
    func testADeleteAfterACorrectionOffersTheTypedWordBack() throws {
        let (app, field) = tryField()
        type("so teh ", in: app)
        XCTAssertTrue(ThumbFreeUI.wait(until: 5) { field.value as? String == "So the " }, "teh was not corrected: \(field.value ?? "")")
        ThumbFreeUI.element("keyboard.delete", in: app).tap()
        let undo = ThumbFreeUI.element("keyboard.suggestion.0", in: app)
        XCTAssertTrue(ThumbFreeUI.wait(for: undo, toContain: "Undo correction, teh", timeout: 5), "the typed word was not offered back")
        undo.tap()
        XCTAssertTrue(ThumbFreeUI.wait(until: 5) { field.value as? String == "So teh" }, "the undo did not put it back: \(field.value ?? "")")
        type(" teh ", in: app)
        XCTAssertTrue(ThumbFreeUI.wait(until: 5) { field.value as? String == "So teh teh " }, "a kept word was corrected: \(field.value ?? "")")
        // A UI test's launch (-TFResetState) makes the keyboard forget its kept words: "teh" is corrected again.
        app.terminate()
        let (again, fresh) = tryField()
        type("teh ", in: again)
        XCTAssertTrue(ThumbFreeUI.wait(until: 5) { fresh.value as? String == "The " }, "the kept words outlived the reset: \(fresh.value ?? "")")
    }

    // A correction's undo ends at any outside change, even a tap in the text box that leaves the caret where it was (the
    // same end of text, as at another spot that ends alike): a delete then only deletes, and the bar shows the word.
    func testATapInTheTextEndsTheUndo() throws {
        let (app, field) = tryField()
        type("so teh ", in: app)
        XCTAssertTrue(ThumbFreeUI.wait(until: 5) { field.value as? String == "So the " }, "teh was not corrected: \(field.value ?? "")")
        field.tap()
        ThumbFreeUI.element("keyboard.delete", in: app).tap()
        XCTAssertTrue(ThumbFreeUI.wait(until: 5) { field.value as? String == "So the" }, "not one delete: \(field.value ?? "")")
        let first = ThumbFreeUI.element("keyboard.suggestion.0", in: app)
        XCTAssertTrue(ThumbFreeUI.wait(for: first, toContain: "\u{201C}the\u{201D}", timeout: 5), "the undo outlived the tap: \(first.label)")
    }

    // Nothing is corrected while the recording line has the bar's place (a take records), as behind the delivery chip:
    // no lit place shows what the space would do, and no undo could show after it.
    func testNothingIsCorrectedWhileATakeRecords() throws {
        let (app, field) = tryField()
        let mic = app.buttons["keyboard.mic"], status = app.staticTexts["keyboard.status"]
        mic.tap()
        XCTAssertTrue(ThumbFreeUI.wait(until: 15) { status.label.hasPrefix("Recording") }, "the mic did not start a take: \(status.label)")
        type("teh ", in: app)
        XCTAssertTrue(ThumbFreeUI.wait(until: 5) { field.value as? String == "Teh " }, "corrected behind the recording line: \(field.value ?? "")")
        XCTAssertTrue(status.label.hasPrefix("Recording"), "the take ended before the space: \(status.label)")
        mic.tap() // the take ends
        XCTAssertTrue(ThumbFreeUI.wait(until: 20) { !status.exists || !status.label.hasPrefix("Recording") }, "the take did not end")
    }

    // After the delivery chip's Copy or Dismiss the places come back lit where the word's end will correct them: behind
    // the chip nothing was lit or corrected, and the places made there must not stay unlit while the next space corrects.
    // The chip comes up for a take pinned before the keyboard came back on screen (a switch away and back mid-take). The
    // session ends first: a live one rewrites its status every second, which refreshes the places anyway.
    func testThePlacesComeBackLitAfterTheChip() throws {
        let (app, field) = tryField()
        let middle = ThumbFreeUI.element("keyboard.suggestion.1", in: app)
        for button in ["Copy", "Dismiss"] {
            showChip(in: app)
            endSession(in: app, field: field)
            type("teh", in: app)
            app.buttons[button].tap()
            XCTAssertTrue(ThumbFreeUI.wait(for: middle, toContain: "the", timeout: 5), "no places after \(button)")
            XCTAssertTrue(middle.isSelected, "the correction is not lit after \(button)")
            ThumbFreeUI.element("keyboard.space", in: app).tap()
        }
        XCTAssertTrue(ThumbFreeUI.wait(until: 5) { field.value as? String == "The the " }, "not corrected: \(field.value ?? "")")
    }

    // A lone "i" becomes "I" at a word's end, a comma or a full stop ("so do I.", as Apple writes it); a letter typed
    // right after that full stop makes it an abbreviation, whose "i" goes back ("i.e.").
    func testALoneIBecomesIButIEStays() throws {
        let (app, field) = tryField()
        let key = { (id: String) in ThumbFreeUI.element(id, in: app).tap() }
        type("so i", in: app)
        key("keyboard.toNumbers"); key("keyboard.key.comma"); key("keyboard.space") // a space on 123 goes back to the letters
        type("do i", in: app)
        key("keyboard.toNumbers"); key("keyboard.key.period")
        XCTAssertTrue(ThumbFreeUI.wait(until: 5) { field.value as? String == "So I, do I." }, "no I before the full stop: \(field.value ?? "")")
        key("keyboard.toLetters")
        type("e", in: app)
        key("keyboard.toNumbers"); key("keyboard.key.period")
        XCTAssertTrue(ThumbFreeUI.wait(until: 5) { field.value as? String == "So I, do i.e." }, "not i.e.: \(field.value ?? "")")
    }

    // The typed word, tapped in its quotes, stays as typed with its space, and is never corrected after.
    func testATappedTypedWordIsKept() throws {
        let (app, field) = tryField()
        type("so wrod", in: app)
        let typed = ThumbFreeUI.element("keyboard.suggestion.0", in: app)
        XCTAssertTrue(ThumbFreeUI.wait(for: typed, toContain: "\u{201C}wrod\u{201D}", timeout: 5))
        typed.tap()
        type("wrod ", in: app)
        XCTAssertTrue(ThumbFreeUI.wait(until: 5) { field.value as? String == "So wrod wrod " }, "the kept word was corrected: \(field.value ?? "")")
    }

    // iOS's lexicon, as on Apple's keyboard: the Simulator's own text replacement "omw" becomes "On my way!" at the space,
    // a delete offers "omw" back (a phrase: no word ends at the caret), and the kept shortcut then stays; a near miss of a
    // contact's name (the Simulator's sample contact Hank Zakroff) becomes the name, and the name with a bare s its
    // possessive, with the field's curly apostrophe. A Simulator without them fails here.
    func testATextReplacementAndAContactsNameComeFromTheLexicon() throws {
        let (app, field) = tryField()
        type("omw", in: app)
        let middle = ThumbFreeUI.element("keyboard.suggestion.1", in: app)
        XCTAssertTrue(ThumbFreeUI.wait(for: middle, toContain: "On my way!", timeout: 5), "the text replacement was not offered")
        XCTAssertTrue(middle.isSelected)
        type(" ", in: app)
        XCTAssertTrue(ThumbFreeUI.wait(until: 5) { field.value as? String == "On my way! " }, "not replaced: \(field.value ?? "")")
        ThumbFreeUI.element("keyboard.delete", in: app).tap()
        let undo = ThumbFreeUI.element("keyboard.suggestion.0", in: app)
        XCTAssertTrue(ThumbFreeUI.wait(for: undo, toContain: "Undo correction, Omw", timeout: 5), "the shortcut was not offered back")
        undo.tap()
        XCTAssertTrue(ThumbFreeUI.wait(until: 5) { field.value as? String == "Omw" }, "the undo did not put it back: \(field.value ?? "")")
        type(" zakrof ", in: app)
        XCTAssertTrue(ThumbFreeUI.wait(until: 5) { field.value as? String == "Omw Zakroff " }, "not from the lexicon: \(field.value ?? "")")
        type("zakroffs ", in: app)
        XCTAssertTrue(ThumbFreeUI.wait(until: 5) { field.value as? String == "Omw Zakroff Zakroff\u{2019}s " }, "no possessive: \(field.value ?? "")")
    }

    // Apple's smart punctuation, which the app's text view leaves to a custom keyboard: curly quotes, ’ in a word, two
    // hyphens make a dash, and a correction's apostrophe is curly too ("dont": don’t).
    func testQuotesAndDashesAreApples() throws {
        let (app, field) = tryField()
        let key = { (id: String) in ThumbFreeUI.element(id, in: app).tap() }
        key("keyboard.toNumbers"); key("keyboard.key.quote"); key("keyboard.toLetters")
        type("hi", in: app)
        key("keyboard.toNumbers"); key("keyboard.key.quote")
        type(" don", in: app)
        key("keyboard.toNumbers"); key("keyboard.key.apostrophe") // an apostrophe goes back to the letters
        type("t ", in: app)
        key("keyboard.toNumbers"); key("keyboard.key.hyphen"); key("keyboard.key.hyphen"); key("keyboard.toLetters")
        type("a dont ", in: app)
        XCTAssertTrue(ThumbFreeUI.wait(until: 5) { field.value as? String == "\u{201C}Hi\u{201D} don\u{2019}t \u{2014}a don\u{2019}t " },
                      "not Apple's punctuation: \(field.value ?? "")")
    }

    // ThumbFree's Dictionary feeds the keyboard: a word added in the Dictionary tab is what its near miss becomes.
    func testTheDictionarysWordsFeedTheKeyboard() throws {
        KeyboardSetup.ensureReady()
        let app = ThumbFreeUI.launch()
        app.tabBars.buttons["Dictionary"].tap()
        ThumbFreeUI.type("Kubernetes", into: ThumbFreeUI.element("dictionary.field", in: app), in: app)
        app.buttons["dictionary.add"].tap()
        XCTAssertTrue(app.staticTexts["1 word"].waitForExistence(timeout: 5))
        app.terminate() // the keyboard covers the tab bar; a launch without -TFResetState keeps the Dictionary
        let again = XCUIApplication()
        again.launchArguments = ["-TFWelcomeDone", "YES"]
        again.launch()
        let field = ThumbFreeUI.element("try.field", in: again)
        field.tap()
        KeyboardSetup.switchToThumbFree(in: again)
        type("kubernets ", in: again)
        XCTAssertTrue(ThumbFreeUI.wait(until: 5) { field.value as? String == "Kubernetes " }, "not the Dictionary's word: \(field.value ?? "")")
    }

    /// A take started with the mic (a session's first, while the test audio still speaks), the keyboard switched away and
    /// back, then the mic again: the take was pinned before the keyboard came back, so its text is held back and the chip
    /// offers it.
    private func showChip(in app: XCUIApplication) {
        let mic = app.buttons["keyboard.mic"], status = app.staticTexts["keyboard.status"]
        mic.tap()
        XCTAssertTrue(ThumbFreeUI.wait(until: 15) { status.label.hasPrefix("Recording") }, "the mic did not start a take: \(status.label)")
        app.buttons["Next keyboard"].firstMatch.tap()
        KeyboardSetup.switchToThumbFree(in: app)
        mic.tap()
        XCTAssertTrue(app.buttons["keyboard.insertHere"].waitForExistence(timeout: 20), "no chip")
    }

    /// The Try tab's End session, which the page's scroll to the field leaves under the status bar while the keyboard is
    /// up (a tap there reaches the status bar): the page is dragged down to it first, slowly and from below the field (a
    /// drag that starts on the field moves its caret instead). If the drag hid the keyboard, a tap in the field brings it
    /// back.
    private func endSession(in app: XCUIApplication, field: XCUIElement) {
        let end = app.buttons["try.endSession"]
        let below = app.staticTexts["Only for practice. Nothing here is sent anywhere."].coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5))
        below.press(forDuration: 0.1, thenDragTo: below.withOffset(CGVector(dx: 0, dy: 300)), withVelocity: .slow, thenHoldForDuration: 0.5)
        end.tap()
        XCTAssertTrue(ThumbFreeUI.wait(until: 5) { !end.exists }, "the session did not end")
        if !app.buttons["keyboard.mic"].exists { field.tap() }
        XCTAssertTrue(app.buttons["keyboard.insertHere"].waitForExistence(timeout: 5), "the chip went away")
    }

    private func type(_ letters: String, in app: XCUIApplication) {
        for letter in letters { ThumbFreeUI.element(letter == " " ? "keyboard.space" : "keyboard.key.\(letter)", in: app).tap() }
    }

    /// The Try tab's field with the ThumbFree keyboard up.
    private func tryField(_ arguments: [String] = []) -> (XCUIApplication, XCUIElement) {
        KeyboardSetup.ensureReady()
        let app = ThumbFreeUI.launch(arguments: arguments)
        let field = ThumbFreeUI.element("try.field", in: app)
        field.tap()
        KeyboardSetup.switchToThumbFree(in: app)
        return (app, field)
    }
}
