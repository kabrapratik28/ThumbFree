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
        // The first time, a label says what the sparkle's tap and hold do; using the sparkle ends it for good.
        let hint = ThumbFreeUI.element("keyboard.cleanup.hint", in: app)
        XCTAssertTrue(hint.waitForExistence(timeout: 5), "no first-time label")
        ThumbFreeUI.shot("cleanup-1-sparkle")
        sparkle.tap()
        XCTAssertTrue(ThumbFreeUI.wait(until: 10) { (field.value as? String) == Self.tidied }, "not tidied: \(field.value ?? "")")
        let undo = ThumbFreeUI.element("keyboard.cleanup.undo", in: app)
        XCTAssertTrue(undo.waitForExistence(timeout: 5), "no Undo")
        XCTAssertFalse(hint.exists, "the first-time label outlived the first tidy")
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

    // The welcome's try (and Home's Try it): once the take's words show, the cue and the sparkle; a tap tidies the words
    // in the box, and the cue then says what Undo does.
    func testTheTryTeachesTheSparkle() throws {
        KeyboardSetup.ensureReady()
        // Not held at a stage: the try moves on to its end, where the beat is.
        let app = ThumbFreeUI.launch(arguments: ["-TFOpenTry", "YES", "-TFFakeText", "See you at six, no, seven.",
                                                 "-TFFakeCleanup", "See you at 7."])
        let field = ThumbFreeUI.element("try.field", in: app)
        XCTAssertTrue(field.waitForExistence(timeout: 10), "no try screen")
        field.tap()
        KeyboardSetup.switchToThumbFree(in: app)
        let mic = app.buttons["keyboard.mic"]
        mic.tap()
        XCTAssertTrue(ThumbFreeUI.wait(for: app.staticTexts["keyboard.status"], toContain: "Recording", timeout: 15))
        sleep(2)
        mic.tap()
        let cue = ThumbFreeUI.element("try.cleanup", in: app)
        XCTAssertTrue(ThumbFreeUI.wait(for: cue, toContain: "to tidy it. Hold it for styles.", timeout: 20), "no cue:\n\(app.debugDescription)")
        let sparkle = ThumbFreeUI.element("keyboard.cleanup", in: app)
        XCTAssertTrue(sparkle.waitForExistence(timeout: 10), "no sparkle in the try")
        ThumbFreeUI.shot("cleanup-try-1-cue")
        sparkle.tap()
        XCTAssertTrue(ThumbFreeUI.wait(until: 10) { (field.value as? String) == "See you at 7." }, "not tidied: \(field.value ?? "")")
        XCTAssertTrue(ThumbFreeUI.wait(for: cue, toContain: "Tidied. Undo brings back your words.", timeout: 5))
        ThumbFreeUI.shot("cleanup-try-2-tidied")
        app.buttons["try.done"].tap()
        XCTAssertTrue(field.waitForNonExistence(timeout: 5))
    }
}
