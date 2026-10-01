import XCTest

/// The keyboard follows the field as Apple's does: the practice box takes each keyboard type with `-TFFieldType` (a
/// `UIKeyboardType` raw value), and the keys are Apple's for it.
@MainActor final class FieldLayoutUITests: XCTestCase {
    // An email field has Apple's @ and . beside a shorter space bar; a web address field has . / .com and no space bar.
    func testEmailAndWebAddressFieldsHaveApplesKeys() throws {
        let (app, email) = field(type: 7) // UIKeyboardType.emailAddress
        for id in ["keyboard.key.a", "keyboard.key.at", "keyboard.key.b", "keyboard.key.period", "keyboard.key.c"] { ThumbFreeUI.element(id, in: app).tap() }
        XCTAssertTrue(ThumbFreeUI.wait(for: email, toContain: "a@b.c", timeout: 5), "the email keys did not type")
        app.terminate()
        let (web, address) = field(type: 3) // UIKeyboardType.URL
        XCTAssertFalse(ThumbFreeUI.element("keyboard.space", in: web).exists, "a web address field has no space bar")
        for id in ["keyboard.key.a", "keyboard.key.periodcom"] { ThumbFreeUI.element(id, in: web).tap() }
        XCTAssertTrue(ThumbFreeUI.wait(for: address, toContain: "a.com", timeout: 5), ".com did not type")
    }

    // A number field gets Apple's digit pad (no letters, no return key), and a decimal field its decimal point.
    func testNumberFieldsGetApplesDigitPad() throws {
        let (app, number) = field(type: 4) // UIKeyboardType.numberPad
        XCTAssertFalse(ThumbFreeUI.element("keyboard.key.q", in: app).exists, "the letters show in a number field")
        XCTAssertFalse(ThumbFreeUI.element("keyboard.return", in: app).exists, "Apple's digit pad has no return key")
        for id in ["keyboard.key.4", "keyboard.key.2"] { ThumbFreeUI.element(id, in: app).tap() }
        XCTAssertTrue(ThumbFreeUI.wait(for: number, toContain: "42", timeout: 5), "the digits did not type")
        app.terminate()
        let (pad, decimal) = field(type: 8) // UIKeyboardType.decimalPad
        for id in ["keyboard.key.3", "keyboard.key.period", "keyboard.key.5"] { ThumbFreeUI.element(id, in: pad).tap() }
        XCTAssertTrue(ThumbFreeUI.wait(for: decimal, toContain: "3.5", timeout: 5), "the decimal point did not type")
    }

    // Twitter's @ and # take the return key's place; web search's . sits before Go; the ASCII-capable keyboard has no
    // emoji key; a numbers-and-punctuation field opens on 123.
    func testTheOtherFieldsGetApplesBottomRows() throws {
        var (app, _) = field(type: 9) // UIKeyboardType.twitter
        XCTAssertTrue(ThumbFreeUI.element("keyboard.key.hash", in: app).exists, "no # key in a Twitter field")
        XCTAssertFalse(ThumbFreeUI.element("keyboard.return", in: app).exists, "Twitter's letters have no return key")
        app.terminate()
        (app, _) = field(type: 10) // UIKeyboardType.webSearch
        XCTAssertEqual(ThumbFreeUI.element("keyboard.return", in: app).label, "Go")
        XCTAssertTrue(ThumbFreeUI.element("keyboard.key.period", in: app).exists, "no . key in a web search field")
        app.terminate()
        (app, _) = field(type: 1) // UIKeyboardType.asciiCapable
        XCTAssertFalse(ThumbFreeUI.element("keyboard.emoji", in: app).exists, "the ASCII-capable keyboard has no emoji key")
        app.terminate()
        (app, _) = field(type: 2) // UIKeyboardType.numbersAndPunctuation
        XCTAssertTrue(ThumbFreeUI.element("keyboard.key.1", in: app).waitForExistence(timeout: 3), "it did not open on 123")
        XCTAssertFalse(ThumbFreeUI.element("keyboard.key.q", in: app).exists)
    }

    /// The Try tab's field as keyboard type `type`, with ThumbFree's keyboard up.
    private func field(type: Int) -> (XCUIApplication, XCUIElement) {
        KeyboardSetup.ensureReady()
        let app = ThumbFreeUI.launch(arguments: ["-TFFieldType", String(type)])
        let field = ThumbFreeUI.element("try.field", in: app)
        field.tap()
        KeyboardSetup.switchToThumbFree(in: app)
        return (app, field)
    }
}
