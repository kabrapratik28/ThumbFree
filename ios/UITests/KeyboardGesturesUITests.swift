import XCTest

/// Apple's gestures on ThumbFree's keys, checked against what Apple's keyboard does on the iOS 26.5 Simulator.
@MainActor final class KeyboardGesturesUITests: XCTestCase {
    // Apple's long press: e's alternatives open over it in Apple's order (ë é e è ...), and sliding onto é and letting go
    // types it. At the start of a sentence they are capitals, as on Apple's.
    func testALongPressOffersApplesAccents() throws {
        let (app, field) = tryField()
        let e = ThumbFreeUI.element("keyboard.key.e", in: app)
        XCTAssertTrue(e.waitForExistence(timeout: 3))
        let origin = app.coordinate(withNormalizedOffset: .zero)
        let start = origin.withOffset(CGVector(dx: e.frame.midX, dy: e.frame.midY))
        let accent = origin.withOffset(CGVector(dx: e.frame.midX - e.frame.width - 4, dy: e.frame.midY)) // the cell left of e's
        start.press(forDuration: 0.8, thenDragTo: accent, withVelocity: .default, thenHoldForDuration: 0.2)
        XCTAssertTrue(ThumbFreeUI.wait(until: 5) { field.value as? String == "\u{00C9}" }, "the long press did not type É")
        start.press(forDuration: 0.8, thenDragTo: accent, withVelocity: .default, thenHoldForDuration: 0.2)
        XCTAssertTrue(ThumbFreeUI.wait(until: 5) { field.value as? String == "\u{00C9}\u{00E9}" }, "the second is not a small é")
        // Let go beyond the row's end: the key itself, as on Apple's.
        let outside = origin.withOffset(CGVector(dx: app.windows.firstMatch.frame.width - 8, dy: e.frame.midY))
        start.press(forDuration: 0.8, thenDragTo: outside, withVelocity: .default, thenHoldForDuration: 0.2)
        XCTAssertTrue(ThumbFreeUI.wait(until: 5) { field.value as? String == "\u{00C9}\u{00E9}e" }, "a release outside the row did not type e")
    }

    // Letting go away from the alternatives, as on Apple's (read on the iOS 26.5 Simulator): just below the key types the
    // key itself, well below it types nothing, and above the row (over the app) the key itself again.
    func testLettingGoAwayFromTheAlternativesAsApplesDoes() throws {
        let (app, field) = tryField()
        let e = ThumbFreeUI.element("keyboard.key.e", in: app).frame
        let origin = app.coordinate(withNormalizedOffset: .zero)
        let start = origin.withOffset(CGVector(dx: e.midX, dy: e.midY))
        let accent = e.midX - e.width - 4 // é's cell, as in the test above
        for (y, text, place) in [(e.maxY + 15, "E", "just below the key"), (e.maxY + 100, "E", "well below the key"),
                                 (e.minY - 150, "Ee", "above the row")] {
            start.press(forDuration: 0.8, thenDragTo: origin.withOffset(CGVector(dx: accent, dy: y)), withVelocity: .default, thenHoldForDuration: 0.2)
            XCTAssertTrue(ThumbFreeUI.wait(until: 5) { field.value as? String == text }, "let go \(place): \(field.value ?? "")")
        }
    }

    // A long press with no drag still lands on the alternatives row's own starting cell (Apple's own key, picked rather
    // than typed by the release-outside-the-row path); the apostrophe's is itself (‘ ’ ' with the plain ' as the start,
    // tools/keyboard/apple-alternates.txt), so this exercises KeyplaneView.pick's chosen-alternate branch. Picking it
    // still returns to the letters layer, exactly as a plain tap on it does (KeyLayer.after, Shared/Keyplane.swift).
    func testPickingTheApostrophesOwnAlternateReturnsToLetters() throws {
        let (app, field) = tryField()
        ThumbFreeUI.element("keyboard.toNumbers", in: app).tap()
        let apostrophe = ThumbFreeUI.element("keyboard.key.apostrophe", in: app)
        XCTAssertTrue(apostrophe.waitForExistence(timeout: 3))
        let origin = app.coordinate(withNormalizedOffset: .zero)
        origin.withOffset(CGVector(dx: apostrophe.frame.midX, dy: apostrophe.frame.midY)).press(forDuration: 0.8)
        XCTAssertTrue(ThumbFreeUI.wait(until: 5) { field.value as? String == "'" }, "the picked alternate did not type '")
        XCTAssertTrue(ThumbFreeUI.element("keyboard.toNumbers", in: app).waitForExistence(timeout: 3),
                      "the layer did not return to letters after the picked alternate")
    }

    // Apple's trackpad: hold space and the keys go blank, then dragging moves the cursor; a letter typed after lands earlier
    // in the text, and letting go types no space.
    func testHoldingSpaceMovesTheCursor() throws {
        let (app, field) = tryField()
        for letter in "abcdefghij" { ThumbFreeUI.element("keyboard.key.\(letter)", in: app).tap() }
        XCTAssertTrue(ThumbFreeUI.wait(for: field, toContain: "abcdefghij", timeout: 5))
        let space = ThumbFreeUI.element("keyboard.space", in: app).frame
        let origin = app.coordinate(withNormalizedOffset: .zero)
        origin.withOffset(CGVector(dx: space.midX, dy: space.midY))
            .press(forDuration: 0.8, thenDragTo: origin.withOffset(CGVector(dx: space.midX - 40, dy: space.midY)),
                   withVelocity: XCUIGestureVelocity(40), thenHoldForDuration: 0.3)
        ThumbFreeUI.element("keyboard.key.x", in: app).tap()
        XCTAssertTrue(ThumbFreeUI.wait(for: field, toContain: "x", timeout: 5))
        let text = (field.value as? String ?? "").lowercased()
        let x = text.distance(from: text.startIndex, to: text.firstIndex(of: "x") ?? text.endIndex)
        XCTAssertEqual(text.replacingOccurrences(of: "x", with: ""), "abcdefghij", "the trackpad typed something: \(text)")
        XCTAssertTrue((3...8).contains(x), "the cursor did not move back a few letters: \(text)")
    }

    // Up and down go through the line breaks: from the end of the third line a drag up by a line's height lands at the end
    // of the second (shorter) line, as Apple's does.
    func testHoldingSpaceMovesUpALine() throws {
        let (app, field) = tryField()
        for line in ["abc", "defgh", "ijklmno"] {
            for letter in line { ThumbFreeUI.element("keyboard.key.\(letter)", in: app).tap() }
            if line != "ijklmno" { ThumbFreeUI.element("keyboard.return", in: app).tap() }
        }
        XCTAssertTrue(ThumbFreeUI.wait(for: field, toContain: "ijklmno", timeout: 5))
        let space = ThumbFreeUI.element("keyboard.space", in: app).frame
        let origin = app.coordinate(withNormalizedOffset: .zero)
        origin.withOffset(CGVector(dx: space.midX, dy: space.midY))
            .press(forDuration: 0.8, thenDragTo: origin.withOffset(CGVector(dx: space.midX, dy: space.midY - 35)),
                   withVelocity: XCUIGestureVelocity(40), thenHoldForDuration: 0.3)
        ThumbFreeUI.element("keyboard.key.x", in: app).tap()
        XCTAssertTrue(ThumbFreeUI.wait(for: field, toContain: "defghx", timeout: 5), "the cursor did not go up a line: \(field.value ?? "")")
    }

    // Apple's delete: held past twenty characters it takes whole words, so what is left ends at a word, never inside one.
    // The words are long: one character at a time, 3.6 s takes at most 32 characters; whole words take far more.
    func testHoldingDeleteTakesWholeWords() throws {
        KeyboardSetup.ensureReady()
        let (app, field) = ThumbFreeUI.launchTry()
        let seed = "extraordinary responsibility international environmental understanding representative communication neighborhood"
        ThumbFreeUI.type(seed, into: field, in: app) // with the system keyboard, then ThumbFree's
        KeyboardSetup.switchToThumbFree(in: app)
        ThumbFreeUI.element("keyboard.delete", in: app).press(forDuration: 3.6)
        Thread.sleep(forTimeInterval: 0.5)
        let left = (field.value as? String ?? "").lowercased()
        XCTAssertGreaterThanOrEqual(seed.count - left.count, 45, "delete did not go on to whole words: \(left)")
        XCTAssertTrue(!left.isEmpty && seed.hasPrefix(left), "what is left is not the start of the text: \(left)")
        XCTAssertEqual(seed.dropFirst(left.count).first, " ", "delete stopped inside a word: \(left)")
    }

    // Apple's rule on 123: a space right after 123 stays on it, but once a key was typed there a space goes back to the
    // letters.
    func testASpaceRightAfter123StaysOn123() throws {
        let (app, field) = tryField()
        ThumbFreeUI.element("keyboard.toNumbers", in: app).tap()
        ThumbFreeUI.element("keyboard.space", in: app).tap()
        XCTAssertTrue(ThumbFreeUI.element("keyboard.key.1", in: app).waitForExistence(timeout: 3), "the space left 123")
        ThumbFreeUI.element("keyboard.key.5", in: app).tap()
        ThumbFreeUI.element("keyboard.space", in: app).tap()
        XCTAssertTrue(ThumbFreeUI.element("keyboard.key.a", in: app).waitForExistence(timeout: 3), "the space after 5 stayed on 123")
        XCTAssertTrue(ThumbFreeUI.wait(for: field, toContain: " 5 ", timeout: 5))
    }

    // Apple's quick slide: touch 123, slide onto 5 and let go: 5 is typed and the letters come back.
    func testSlidingFrom123TypesADigitAndGoesBack() throws {
        let (app, field) = tryField()
        let origin = app.coordinate(withNormalizedOffset: .zero)
        let numbers = ThumbFreeUI.element("keyboard.toNumbers", in: app).frame
        let five = ThumbFreeUI.element("keyboard.key.t", in: app).frame // 5 sits where t does
        origin.withOffset(CGVector(dx: numbers.midX, dy: numbers.midY))
            .press(forDuration: 0.1, thenDragTo: origin.withOffset(CGVector(dx: five.midX, dy: five.midY)), withVelocity: .default, thenHoldForDuration: 0.1)
        XCTAssertTrue(ThumbFreeUI.wait(for: field, toContain: "5", timeout: 5), "the slide did not type 5")
        XCTAssertTrue(ThumbFreeUI.element("keyboard.key.a", in: app).waitForExistence(timeout: 3), "the letters did not come back")
    }

    // The same from shift: one capital, then small letters again.
    func testSlidingFromShiftTypesOneCapital() throws {
        let (app, field) = tryField()
        for letter in "hey" { ThumbFreeUI.element("keyboard.key.\(letter)", in: app).tap() }
        XCTAssertTrue(ThumbFreeUI.wait(for: field, toContain: "hey", timeout: 5))
        let origin = app.coordinate(withNormalizedOffset: .zero)
        let shift = ThumbFreeUI.element("keyboard.shift", in: app).frame
        let a = ThumbFreeUI.element("keyboard.key.a", in: app).frame
        origin.withOffset(CGVector(dx: shift.midX, dy: shift.midY))
            .press(forDuration: 0.1, thenDragTo: origin.withOffset(CGVector(dx: a.midX, dy: a.midY)), withVelocity: .default, thenHoldForDuration: 0.1)
        ThumbFreeUI.element("keyboard.key.b", in: app).tap()
        XCTAssertTrue(ThumbFreeUI.wait(until: 5) { field.value as? String == "HeyAb" }, "not one capital: \(field.value ?? "")")
    }

    // A slide from shift that a second thumb rolls over types its capital once, before the other key: "Hi", never "hHi" or
    // "hiH". Two fingers through XCTest's own event synthesis, whose extra tap of the second finger's key
    // adds an i, so the test counts the h's.
    func testAShiftSlideRolledOverTypesItsCapitalOnce() throws {
        let (app, field) = tryField()
        for letter in "ok" { ThumbFreeUI.element("keyboard.key.\(letter)", in: app).tap() }
        ThumbFreeUI.element("keyboard.space", in: app).tap()
        XCTAssertTrue(ThumbFreeUI.wait(until: 5) { field.value as? String == "Ok " })
        let center = { (id: String) in ThumbFreeUI.element(id, in: app).frame.center }
        try ThumbFreeUI.fingers([.init(point: center("keyboard.shift"), down: 0, up: 1.4, move: (center("keyboard.key.h"), 0.4)),
                                 .init(point: center("keyboard.key.i"), down: 0.9, up: 1.0)])
        XCTAssertTrue(ThumbFreeUI.wait(until: 5) { (field.value as? String)?.hasPrefix("Ok Hi") == true }, "not \"Hi\": \(field.value ?? "")")
        Thread.sleep(forTimeInterval: 0.5) // the slide's finger has let go: nothing more goes in
        let typed = (field.value as? String ?? "").dropFirst(3)
        XCTAssertEqual(typed.filter { $0 == "h" || $0 == "H" }, "H", "the slide's letter did not go in once, as a capital: \(typed)")
    }

    // While the trackpad is on, a second finger on the mic does nothing: no take starts or stops. The space
    // finger moves a little once the trackpad is on, so XCTest's extra tap of the mic comes then too.
    func testTheMicDoesNothingWhileTheTrackpadIsOn() throws {
        let (app, field) = tryField()
        for letter in "hello" { ThumbFreeUI.element("keyboard.key.\(letter)", in: app).tap() }
        XCTAssertTrue(ThumbFreeUI.wait(until: 5) { field.value as? String == "Hello" })
        let space = ThumbFreeUI.element("keyboard.space", in: app).frame.center
        let mic = app.buttons["keyboard.mic"]
        try ThumbFreeUI.fingers([.init(point: space, down: 0, up: 2.5, move: (CGPoint(x: space.x + 2, y: space.y), 0.6)),
                                 .init(point: mic.frame.center, down: 1.0, up: 1.1)])
        Thread.sleep(forTimeInterval: 1.5) // a take's words go in at once with the fixed engine
        XCTAssertEqual(mic.label, "Start dictation", "the mic took a tap during the trackpad")
        XCTAssertEqual(field.value as? String, "Hello", "a take typed during the trackpad")
    }

    // From #+= on 123 a slide types the symbol and 123 comes back; 123 on #+= acts on release, so a slide from it types the
    // #+= key it ends on and stays there, as on Apple's.
    func testSlidingFromTheSymbolKeysAsApplesDoes() throws {
        let (app, field) = tryField()
        let origin = app.coordinate(withNormalizedOffset: .zero)
        let hash = ThumbFreeUI.element("keyboard.key.t", in: app).frame    // the first row's fifth key: # on #+=
        let percent = ThumbFreeUI.element("keyboard.key.y", in: app).frame // the sixth: % on #+=
        ThumbFreeUI.element("keyboard.toNumbers", in: app).tap()
        let symbols = ThumbFreeUI.element("keyboard.toSymbols", in: app).frame
        origin.withOffset(CGVector(dx: symbols.midX, dy: symbols.midY))
            .press(forDuration: 0.1, thenDragTo: origin.withOffset(CGVector(dx: percent.midX, dy: percent.midY)), withVelocity: .default, thenHoldForDuration: 0.1)
        XCTAssertTrue(ThumbFreeUI.wait(for: field, toContain: "%", timeout: 5), "the slide from #+= did not type %")
        XCTAssertTrue(ThumbFreeUI.element("keyboard.key.1", in: app).waitForExistence(timeout: 3), "123 did not come back")
        ThumbFreeUI.element("keyboard.toSymbols", in: app).tap()
        let numbers = ThumbFreeUI.element("keyboard.toNumbers", in: app).frame // 123 on #+=
        origin.withOffset(CGVector(dx: numbers.midX, dy: numbers.midY))
            .press(forDuration: 0.1, thenDragTo: origin.withOffset(CGVector(dx: hash.midX, dy: hash.midY)), withVelocity: .default, thenHoldForDuration: 0.1)
        XCTAssertTrue(ThumbFreeUI.wait(until: 5) { field.value as? String == "%#" }, "123 on #+= did not type the key it ended on")
        XCTAssertTrue(ThumbFreeUI.element("keyboard.key.hash", in: app).exists, "it left #+=")
    }

    /// The try screen's box with the ThumbFree keyboard up.
    private func tryField() -> (XCUIApplication, XCUIElement) {
        KeyboardSetup.ensureReady()
        let (app, field) = ThumbFreeUI.launchTry()
        field.tap()
        KeyboardSetup.switchToThumbFree(in: app)
        return (app, field)
    }
}

private extension CGRect {
    var center: CGPoint { CGPoint(x: midX, y: midY) }
}
