import XCTest

/// Clean up in the try screen's box, with the app's fixed answer (`-TFFakeCleanup`: a Simulator has no model). After a
/// take the sparkle sits beside the mic; a tap replaces the take with the answer; Undo puts the take back; a hold opens
/// the style menu, which keeps the mic and closes with Cancel.
@MainActor final class CleanupUITests: XCTestCase {
    /// No apostrophes or quotes: a launch argument's value is read as a property list string.
    static let said = "so I I think we should meet at five no six"
    static let tidied = "I think we should meet at 6."

    func testTidyUndoAndTheStyleMenu() throws {
        KeyboardSetup.ensureReady()
        let (app, field) = ThumbFreeUI.launchTry(arguments: ["-TFFakeText", Self.said, "-TFFakeCleanup", Self.tidied])
        field.tap()
        KeyboardSetup.switchToThumbFree(in: app)
        let mic = app.buttons["keyboard.mic"]
        mic.tap()
        XCTAssertTrue(ThumbFreeUI.wait(for: app.staticTexts["keyboard.status"], toContain: "Recording", timeout: 15))
        sleep(2)
        mic.tap()
        XCTAssertTrue(ThumbFreeUI.wait(for: field, toContain: "five no six", timeout: 20), "the take was not typed")
        let typed = field.value as? String ?? ""

        let sparkle = ThumbFreeUI.element("keyboard.cleanup", in: app)
        XCTAssertTrue(sparkle.waitForExistence(timeout: 10), "no sparkle after the take:\n\(app.debugDescription)")
        ThumbFreeUI.shot("cleanup-1-sparkle")
        sparkle.tap()
        XCTAssertTrue(ThumbFreeUI.wait(until: 10) { (field.value as? String) == Self.tidied }, "not tidied: \(field.value ?? "")")
        let undo = ThumbFreeUI.element("keyboard.cleanup.undo", in: app)
        XCTAssertTrue(undo.waitForExistence(timeout: 5), "no Undo")
        ThumbFreeUI.shot("cleanup-2-undo")
        undo.tap()
        XCTAssertTrue(ThumbFreeUI.wait(until: 10) { (field.value as? String) == typed }, "not undone: \(field.value ?? "")")

        XCTAssertTrue(sparkle.waitForExistence(timeout: 5), "no sparkle after Undo")
        sparkle.press(forDuration: 1)
        XCTAssertTrue(ThumbFreeUI.element("keyboard.cleanup.style.shorter", in: app).waitForExistence(timeout: 5), "no style menu")
        XCTAssertTrue(mic.exists, "the style menu hid the mic")
        ThumbFreeUI.shot("cleanup-3-styles")
        ThumbFreeUI.element("keyboard.cleanup.cancel", in: app).tap()
        XCTAssertTrue(ThumbFreeUI.wait(until: 5) { !ThumbFreeUI.element("keyboard.cleanup.style.shorter", in: app).exists })
        XCTAssertTrue(sparkle.waitForExistence(timeout: 5), "no sparkle after Cancel")
        XCTAssertEqual(field.value as? String, typed, "Cancel changed the words")
    }
}
