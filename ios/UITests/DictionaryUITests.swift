import XCTest

@MainActor final class DictionaryUITests: XCTestCase {
    // Add, a duplicate, Paste a list with its count, Try a phrase, Edit and Delete.
    func testTheDictionaryTabAddsPastesEditsAndDeletes() throws {
        let app = ThumbFreeUI.launch()
        app.tabBars.buttons["Dictionary"].tap()
        XCTAssertTrue(app.staticTexts["No words yet"].waitForExistence(timeout: 5))
        let field = ThumbFreeUI.element("dictionary.field", in: app)
        ThumbFreeUI.type("Kubernetes", into: field, in: app)
        app.buttons["dictionary.add"].tap()
        XCTAssertTrue(ThumbFreeUI.wait(for: ThumbFreeUI.element("dictionary.message", in: app), toContain: "Added “Kubernetes”.", timeout: 5))
        XCTAssertTrue(app.staticTexts["1 word"].exists)
        field.typeText("kubernetes")
        XCTAssertTrue(ThumbFreeUI.wait(for: ThumbFreeUI.element("dictionary.message", in: app), toContain: "Already in your Dictionary.", timeout: 5))
        XCTAssertFalse(app.buttons["dictionary.add"].isEnabled)

        app.buttons["dictionary.paste"].tap()
        let paste = ThumbFreeUI.element("dictionary.pasteField", in: app)
        XCTAssertTrue(paste.waitForExistence(timeout: 5))
        ThumbFreeUI.type("Siobhan, Nguyen, kubernetes", into: paste, in: app)
        XCTAssertTrue(ThumbFreeUI.wait(for: ThumbFreeUI.element("dictionary.pasteSummary", in: app),
                                       toContain: "2 will be added. 1 duplicate will be skipped.", timeout: 5))
        app.buttons["dictionary.pasteAdd"].tap()
        XCTAssertTrue(app.staticTexts["3 words"].waitForExistence(timeout: 5))

        ThumbFreeUI.type("ask siobhan about kubernetis", into: ThumbFreeUI.element("dictionary.phrase", in: app), in: app)
        XCTAssertTrue(ThumbFreeUI.wait(for: ThumbFreeUI.element("dictionary.phraseResult", in: app),
                                       toContain: "ThumbFree would type: ask Siobhan about Kubernetes", timeout: 5))

        app.buttons["Edit Nguyen"].tap()
        let edit = ThumbFreeUI.element("dictionary.editField", in: app)
        XCTAssertTrue(edit.waitForExistence(timeout: 5))
        edit.coordinate(withNormalizedOffset: CGVector(dx: 0.95, dy: 0.5)).tap() // the cursor at the end
        edit.typeText(" Tran")
        app.buttons["dictionary.save"].tap()
        XCTAssertTrue(app.staticTexts["Nguyen Tran"].waitForExistence(timeout: 5))

        app.buttons["Delete Siobhan"].tap()
        XCTAssertTrue(app.staticTexts["2 words"].waitForExistence(timeout: 5))
        XCTAssertFalse(app.staticTexts["Siobhan"].exists)
    }
}
