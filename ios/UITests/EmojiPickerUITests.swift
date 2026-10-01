import XCTest

/// The emoji key and ThumbFree's emoji picker (Apple's emoji key and Emoji keyboard, inside ours).
@MainActor final class EmojiPickerUITests: XCTestCase {
    // Apple's bottom row on every layer: the emoji key sits between 123 (or ABC) and space, named for VoiceOver.
    func testTheEmojiKeyIsOnEveryLayer() throws {
        let (app, _) = tryFieldWithThumbFree()
        let emojiKey = ThumbFreeUI.element("keyboard.emoji", in: app)
        XCTAssertTrue(emojiKey.waitForExistence(timeout: 3), "no emoji key on the letters")
        XCTAssertEqual(emojiKey.label, "Emoji")
        ThumbFreeUI.element("keyboard.toNumbers", in: app).tap()
        XCTAssertTrue(ThumbFreeUI.element("keyboard.key.1", in: app).waitForExistence(timeout: 3))
        XCTAssertTrue(emojiKey.exists, "no emoji key on 123")
        ThumbFreeUI.element("keyboard.toSymbols", in: app).tap()
        XCTAssertTrue(ThumbFreeUI.element("keyboard.key.hash", in: app).waitForExistence(timeout: 3))
        XCTAssertTrue(emojiKey.exists, "no emoji key on #+=")
    }

    // The emoji key opens the picker in the keys' place, a tapped emoji goes into the field, the picker's delete takes it
    // back, and ABC returns to the letters (from the #+= layer too, as on Apple's keyboard).
    func testAnEmojiTypesAndDeleteTakesItBack() throws {
        let (app, field) = tryFieldWithThumbFree()
        for id in ["keyboard.key.h", "keyboard.key.i"] { ThumbFreeUI.element(id, in: app).tap() }
        XCTAssertTrue(ThumbFreeUI.wait(for: field, toContain: "Hi", timeout: 5), "the letters did not arrive")
        ThumbFreeUI.element("keyboard.toNumbers", in: app).tap()
        ThumbFreeUI.element("keyboard.toSymbols", in: app).tap()
        openPicker(app)
        XCTAssertFalse(ThumbFreeUI.element("keyboard.key.hash", in: app).exists, "the keys still show under the picker")
        ThumbFreeUI.element("keyboard.emoji.category.smileysAndPeople", in: app).tap()
        ThumbFreeUI.element("keyboard.emoji.1F600", in: app).tap()
        XCTAssertTrue(ThumbFreeUI.wait(for: field, toContain: "Hi\u{1F600}", timeout: 5), "the emoji did not arrive")
        ThumbFreeUI.element("keyboard.delete", in: app).tap()
        XCTAssertTrue(ThumbFreeUI.wait(until: 5) { field.value as? String == "Hi" }, "delete did not take the emoji back")
        ThumbFreeUI.element("keyboard.toLetters", in: app).tap()
        let a = ThumbFreeUI.element("keyboard.key.a", in: app)
        XCTAssertTrue(a.waitForExistence(timeout: 3), "ABC did not come back to the letters")
        XCTAssertFalse(ThumbFreeUI.element("keyboard.emoji.picker", in: app).exists)
        a.tap()
        XCTAssertTrue(ThumbFreeUI.wait(for: field, toContain: "Hia", timeout: 5), "the letter after the picker did not arrive")
    }

    // Holding the picker's delete repeats, as the keys' delete does, and stops the moment it is let go.
    func testHoldingThePickersDeleteRepeatsAndStops() throws {
        let (app, field) = tryFieldWithThumbFree()
        for id in ["keyboard.key.h", "keyboard.key.i"] { ThumbFreeUI.element(id, in: app).tap() }
        openPicker(app)
        ThumbFreeUI.element("keyboard.emoji.category.smileysAndPeople", in: app).tap()
        let grinning = ThumbFreeUI.element("keyboard.emoji.1F600", in: app)
        for _ in 0..<12 { grinning.tap() }
        XCTAssertTrue(ThumbFreeUI.wait(until: 5) { (field.value as? String)?.count == 14 }, "the emoji did not all arrive")
        ThumbFreeUI.element("keyboard.delete", in: app).press(forDuration: 1.0) // 500 ms to the first repeat, then every 100 ms
        XCTAssertTrue(ThumbFreeUI.wait(until: 5) { ((field.value as? String)?.count ?? 14) <= 11 }, "holding delete did not repeat")
        let afterHold = field.value as? String
        XCTAssertEqual(afterHold?.hasPrefix("Hi"), true, "the repeat ran past the emoji: \(afterHold ?? "")")
        Thread.sleep(forTimeInterval: 0.5) // longer than one repeat tick
        XCTAssertEqual(field.value as? String, afterHold, "delete kept repeating after it was let go")
    }

    // Sliding off the picker's delete stops it at once, as on the keys: a finger that wanders off deletes nothing more.
    func testSlidingOffThePickersDeleteStopsIt() throws {
        let (app, field) = tryFieldWithThumbFree()
        for id in ["keyboard.key.h", "keyboard.key.i"] { ThumbFreeUI.element(id, in: app).tap() }
        openPicker(app)
        ThumbFreeUI.element("keyboard.emoji.category.smileysAndPeople", in: app).tap()
        let grinning = ThumbFreeUI.element("keyboard.emoji.1F600", in: app)
        for _ in 0..<8 { grinning.tap() }
        XCTAssertTrue(ThumbFreeUI.wait(until: 5) { (field.value as? String)?.count == 10 }, "the emoji did not all arrive")
        // Held under the 500 ms before delete repeats, dragged up onto the grid, and held there past several repeats.
        ThumbFreeUI.element("keyboard.delete", in: app)
            .press(forDuration: 0.1, thenDragTo: grinning, withVelocity: .fast, thenHoldForDuration: 1.0)
        Thread.sleep(forTimeInterval: 0.5)
        XCTAssertEqual((field.value as? String)?.count, 9, "delete kept going after the finger slid off it")
    }

    // Sliding onto the neighboring icon stops delete too, at once: well inside the 70 pt slop UIControl's own
    // `.touchDragExit` allows on iPhone, but a different key all the same.
    func testSlidingOntoTheNeighboringIconStopsDelete() throws {
        let (app, field) = tryFieldWithThumbFree()
        for id in ["keyboard.key.h", "keyboard.key.i"] { ThumbFreeUI.element(id, in: app).tap() }
        openPicker(app)
        ThumbFreeUI.element("keyboard.emoji.category.smileysAndPeople", in: app).tap()
        let grinning = ThumbFreeUI.element("keyboard.emoji.1F600", in: app)
        for _ in 0..<8 { grinning.tap() }
        XCTAssertTrue(ThumbFreeUI.wait(until: 5) { (field.value as? String)?.count == 10 }, "the emoji did not all arrive")
        let flags = ThumbFreeUI.element("keyboard.emoji.category.flags", in: app) // delete's neighbor in the bar
        ThumbFreeUI.element("keyboard.delete", in: app)
            .press(forDuration: 0.1, thenDragTo: flags, withVelocity: .fast, thenHoldForDuration: 1.0)
        Thread.sleep(forTimeInterval: 0.5)
        XCTAssertEqual((field.value as? String)?.count, 9, "delete kept going after the finger slid onto the next icon")
    }

    // VoiceOver reads each emoji's Unicode name, and each is a keyboard key; the category icons jump to their category,
    // and the lit one follows the grid when it is dragged too, with that category's title pinned at the left.
    func testEveryEmojiIsNamedAndTheCategoriesJump() throws {
        let (app, _) = tryFieldWithThumbFree()
        openPicker(app)
        let smileys = ThumbFreeUI.element("keyboard.emoji.category.smileysAndPeople", in: app)
        smileys.tap()
        let grinning = ThumbFreeUI.element("keyboard.emoji.1F600", in: app)
        XCTAssertTrue(grinning.waitForExistence(timeout: 3))
        XCTAssertEqual(grinning.label, "grinning face")
        XCTAssertEqual(grinning.elementType, .key)
        XCTAssertTrue(ThumbFreeUI.wait(until: 3) { smileys.isSelected }, "the category on screen is not marked")
        XCTAssertEqual(smileys.label, "Smileys & People")
        let flags = ThumbFreeUI.element("keyboard.emoji.category.flags", in: app)
        flags.tap()
        let whiteFlag = ThumbFreeUI.element("keyboard.emoji.1F3F3_FE0F", in: app) // Apple's flags start with the white flag
        XCTAssertTrue(whiteFlag.waitForExistence(timeout: 3), "Flags did not jump to the flags")
        XCTAssertEqual(whiteFlag.label, "white flag")
        XCTAssertTrue(ThumbFreeUI.wait(until: 3) { flags.isSelected && !smileys.isSelected }, "the lit category did not follow")
        // Food & Drink, then drags (not taps) until Activity reaches the left edge.
        ThumbFreeUI.element("keyboard.emoji.category.foodAndDrink", in: app).tap()
        let activity = ThumbFreeUI.element("keyboard.emoji.category.activity", in: app)
        let origin = app.coordinate(withNormalizedOffset: .zero)
        let y = ThumbFreeUI.element("keyboard.emoji.1F34F", in: app).frame.midY // Apple's first food, the green apple
        for _ in 0..<20 where !activity.isSelected {
            origin.withOffset(CGVector(dx: 300, dy: y))
                .press(forDuration: 0.05, thenDragTo: origin.withOffset(CGVector(dx: 100, dy: y)), withVelocity: .slow, thenHoldForDuration: 0.3)
        }
        XCTAssertTrue(activity.isSelected, "the lit category did not follow a drag")
        let title = app.otherElements.matching(NSPredicate(format: "label == %@", "Activity")).firstMatch
        XCTAssertTrue(title.exists && title.frame.minX < 30, "Activity's title is not pinned at the left")
    }

    // Apple's Emoji keyboard is 53 pt taller than its letters (measured on the iPhone 16; its top meets Apple's on the
    // iPhone Air too): opening the picker moves the keyboard's top up by that much, the mic with the bar, and ABC puts it
    // back. The grid is Apple's for the iPhone's width: 4 rows 52.7 pt apart in 42 pt columns on the iPhone Air, 5 rows
    // 38.5 pt apart in 46 pt columns on a narrower iPhone such as the iPhone 16.
    func testThePickerIsApplesHeightWithApplesRows() throws {
        let (app, _) = tryFieldWithThumbFree()
        let mic = ThumbFreeUI.element("keyboard.mic", in: app)
        XCTAssertTrue(mic.waitForExistence(timeout: 3))
        let before = mic.frame
        openPicker(app)
        ThumbFreeUI.element("keyboard.emoji.category.smileysAndPeople", in: app).tap()
        XCTAssertTrue(ThumbFreeUI.wait(until: 3) { abs(before.minY - mic.frame.minY - 53) < 0.5 }, "the keyboard did not grow by Apple's 53 pt")
        let narrow = app.windows.firstMatch.frame.width < 414
        assertApplesGrid(in: app, rows: narrow ? 5 : 4, pitch: narrow ? 38.5 : 52.67, column: narrow ? 46 : 42, gap: 4)
        ThumbFreeUI.element("keyboard.toLetters", in: app).tap()
        XCTAssertTrue(ThumbFreeUI.wait(until: 3) { mic.frame == before }, "ABC did not bring back the letters' height")
    }

    // In landscape too (in Safari's address field, since ThumbFree's own screens stay upright), Apple's Emoji keyboard is
    // taller than its letters: the picker moves the keyboard's top up by 63 pt, to Apple's, with Apple's 3 rows 45.3 pt
    // apart in 40 pt columns and its gap of 7 pt above the bar, and ABC puts it back.
    func testThePickerIsApplesHeightInLandscape() throws {
        KeyboardSetup.ensureReady()
        let safari = XCUIApplication(bundleIdentifier: "com.apple.mobilesafari")
        safari.launch()
        let address = safari.textFields.matching(NSPredicate(format: "label == %@", "Address")).firstMatch
        XCTAssertTrue(address.waitForExistence(timeout: 5), "Safari's address field did not show")
        address.tap()
        KeyboardSetup.switchToThumbFree(in: safari)
        XCUIDevice.shared.orientation = .landscapeLeft
        addTeardownBlock { XCUIDevice.shared.orientation = .portrait }
        let mic = ThumbFreeUI.element("keyboard.mic", in: safari)
        XCTAssertTrue(ThumbFreeUI.wait(until: 5) { mic.frame.height == 36 }, "the keyboard did not turn") // the bar's landscape height
        let before = mic.frame
        openPicker(safari)
        ThumbFreeUI.element("keyboard.emoji.category.smileysAndPeople", in: safari).tap()
        XCTAssertTrue(ThumbFreeUI.wait(until: 3) { abs(before.minY - mic.frame.minY - 63) < 0.5 }, "the keyboard did not grow by 63 pt")
        assertApplesGrid(in: safari, rows: 3, pitch: 45.33, column: 40, gap: 7)
        ThumbFreeUI.element("keyboard.toLetters", in: safari).tap()
        XCTAssertTrue(ThumbFreeUI.wait(until: 3) { mic.frame == before }, "ABC did not bring back the letters' height")
    }

    // Landscape's results row is 38 pt, not portrait's 41, so its emoji draw smaller there and none loses its bottom edge
    // (portrait's 41.5 pt emoji cut off the walking cat's feet in it). Read from a picture of the first result, the cat
    // face: its yellow ends 2 pt above the cell's bottom, which keeps the walking cat's feet, drawn lower, inside theirs.
    func testTheSearchResultsFitTheRowInLandscape() throws {
        KeyboardSetup.ensureReady()
        let safari = XCUIApplication(bundleIdentifier: "com.apple.mobilesafari")
        safari.launch()
        let address = safari.textFields.matching(NSPredicate(format: "label == %@", "Address")).firstMatch
        XCTAssertTrue(address.waitForExistence(timeout: 5), "Safari's address field did not show")
        address.tap()
        KeyboardSetup.switchToThumbFree(in: safari)
        XCUIDevice.shared.orientation = .landscapeLeft
        addTeardownBlock { XCUIDevice.shared.orientation = .portrait }
        let mic = ThumbFreeUI.element("keyboard.mic", in: safari)
        XCTAssertTrue(ThumbFreeUI.wait(until: 5) { mic.frame.height == 36 }, "the keyboard did not turn") // the bar's landscape height
        openPicker(safari)
        search("cat", in: safari)
        let face = ThumbFreeUI.element("keyboard.emoji.result.0", in: safari)
        XCTAssertTrue(ThumbFreeUI.wait(until: 3) { face.label == "cat face" }, "the first result is \(face.label)")
        XCTAssertEqual(face.frame.height, 38, "the results row is not landscape's 38 pt")
        let bottom = try XCTUnwrap(Self.yellowBottomMargin(in: face.screenshot().image), "no cat face in the first result")
        XCTAssertGreaterThanOrEqual(bottom, 2, "the emoji reach the bottom of the row, which cuts the lowest ones off")
        ThumbFreeUI.element("keyboard.return", in: safari).tap() // Done
    }

    // Apple's picker reopens where it was left: Flags, then ABC and the emoji key again, and Flags is still lit.
    func testThePickerReopensWhereItWasLeft() throws {
        let (app, _) = tryFieldWithThumbFree()
        openPicker(app)
        let flags = ThumbFreeUI.element("keyboard.emoji.category.flags", in: app)
        flags.tap()
        XCTAssertTrue(ThumbFreeUI.wait(until: 3) { flags.isSelected })
        ThumbFreeUI.element("keyboard.toLetters", in: app).tap()
        openPicker(app)
        XCTAssertTrue(ThumbFreeUI.wait(until: 3) { flags.isSelected }, "the picker did not reopen where it was left")
    }

    // Frequently Used counts each pick: a picked emoji shows there the next time the picker opens, and a second pick never
    // moves it later. Kept in the keyboard's own storage, so earlier runs may have left others in it.
    func testFrequentlyUsedCountsEachPick() throws {
        let (app, field) = tryFieldWithThumbFree()
        var places: [Int] = []
        for _ in 0..<2 {
            openPicker(app)
            ThumbFreeUI.element("keyboard.emoji.category.animalsAndNature", in: app).tap()
            ThumbFreeUI.element("keyboard.emoji.1F436", in: app).tap() // dog face, first of Apple's Animals & Nature
            ThumbFreeUI.element("keyboard.toLetters", in: app).tap()
            openPicker(app)
            ThumbFreeUI.element("keyboard.emoji.category.recents", in: app).tap()
            let dog = try XCTUnwrap(recent("dog face", in: app), "the pick did not show in Frequently Used")
            places.append(Int(dog.identifier.split(separator: ".").last ?? "") ?? -1)
            ThumbFreeUI.element("keyboard.toLetters", in: app).tap()
        }
        XCTAssertLessThanOrEqual(places[1], places[0], "a second pick moved it later")
        XCTAssertTrue(ThumbFreeUI.wait(for: field, toContain: "\u{1F436}\u{1F436}", timeout: 5))
    }

    // Apple's Search Emoji: the field in the bar opens the results row above the letters (Apple's search keys: no emoji key,
    // a Done key), the keys type into the search in small letters even from caps lock, a result goes into the text, and
    // Done goes back to the letters, as Apple's does.
    func testSearchFindsAnEmojiByItsName() throws {
        let (app, field) = tryFieldWithThumbFree()
        let mic = ThumbFreeUI.element("keyboard.mic", in: app)
        XCTAssertTrue(mic.waitForExistence(timeout: 3))
        let before = mic.frame
        for id in ["keyboard.key.h", "keyboard.key.i"] { ThumbFreeUI.element(id, in: app).tap() }
        ThumbFreeUI.element("keyboard.shift", in: app).doubleTap() // caps lock
        openPicker(app)
        search("cat", in: app)
        XCTAssertFalse(ThumbFreeUI.element("keyboard.emoji", in: app).exists, "the search keys have an emoji key")
        XCTAssertEqual(ThumbFreeUI.element("keyboard.return", in: app).label, "Done")
        let first = ThumbFreeUI.element("keyboard.emoji.result.0", in: app)
        XCTAssertTrue(ThumbFreeUI.wait(until: 3) { first.label == "cat face" }, "the best match is not first")
        // Apple's results row: 41 pt tall, 43 pt cells 10 pt apart.
        XCTAssertEqual(ThumbFreeUI.element("keyboard.emoji.results", in: app).frame.height, 41)
        XCTAssertEqual(ThumbFreeUI.element("keyboard.emoji.result.1", in: app).frame.minX - first.frame.minX, 53, accuracy: 0.5)
        XCTAssertEqual(field.value as? String, "Hi", "the search's letters went into the text")
        XCTAssertEqual(ThumbFreeUI.element("keyboard.emoji.search", in: app).value as? String, "cat") // small letters
        first.tap()
        expect(field, "Hi\u{1F431}", "the result")
        ThumbFreeUI.element("keyboard.delete", in: app).tap() // edits the search, not the text
        XCTAssertTrue(ThumbFreeUI.wait(until: 3) { ThumbFreeUI.element("keyboard.emoji.search", in: app).value as? String == "ca" })
        XCTAssertEqual(field.value as? String, "Hi\u{1F431}")
        ThumbFreeUI.element("keyboard.emoji.search.clear", in: app).tap()
        XCTAssertTrue(ThumbFreeUI.wait(until: 3) { ThumbFreeUI.element("keyboard.emoji.search", in: app).value as? String == "" })
        ThumbFreeUI.element("keyboard.return", in: app).tap()
        XCTAssertTrue(ThumbFreeUI.element("keyboard.key.q", in: app).waitForExistence(timeout: 3), "Done did not go back to the letters")
        XCTAssertFalse(ThumbFreeUI.element("keyboard.emoji.picker", in: app).exists)
        XCTAssertTrue(ThumbFreeUI.element("keyboard.emoji", in: app).exists, "the emoji key did not come back")
        XCTAssertFalse(ThumbFreeUI.element("keyboard.emoji.results", in: app).exists, "the results stayed after Done")
        XCTAssertFalse(ThumbFreeUI.element("keyboard.emoji.search", in: app).exists, "the search field stayed after Done")
        XCTAssertEqual(ThumbFreeUI.element("keyboard.return", in: app).label, "return", "the return key did not go back to return")
        XCTAssertTrue(ThumbFreeUI.element("keyboard.status", in: app).exists, "the status did not come back")
        XCTAssertEqual(mic.frame, before, "the keyboard's height did not go back to the letters'")
    }

    // Tapping the text box ends Search Emoji, as Done does and as Apple's does: delete and every key act on the text
    // again, never on the search. A result the keyboard types itself keeps the search up.
    func testTappingTheTextBoxEndsTheSearch() throws {
        let (app, field) = tryFieldWithThumbFree()
        type("hi", in: app)
        expect(field, "Hi", "the letters")
        openPicker(app)
        search("cat", in: app)
        let query = ThumbFreeUI.element("keyboard.emoji.search", in: app)
        let results = ThumbFreeUI.element("keyboard.emoji.results", in: app)
        XCTAssertTrue(ThumbFreeUI.wait(until: 3) { query.value as? String == "cat" })
        XCTAssertEqual(field.value as? String, "Hi", "the search's letters went into the text")
        ThumbFreeUI.element("keyboard.emoji.result.0", in: app).tap() // the cat face, typed by the keyboard itself
        expect(field, "Hi\u{1F431}", "the result")
        XCTAssertTrue(results.exists && query.value as? String == "cat", "typing a result ended the search")
        field.tap()
        ThumbFreeUI.element("keyboard.delete", in: app).tap()
        expect(field, "Hi", "delete after the tap on the text box")
        XCTAssertFalse(query.exists, "the search stayed up")
        XCTAssertFalse(results.exists, "the results stayed up")
        type("s", in: app) // the keys act on the text again
        expect(field, "His", "a letter after the search ended")
    }

    // A tap that only moves the caret ends the search too, and the keys then type at the new caret. iOS
    // 26.5 tells the keyboard of that move as a text change (checked here and in a Safari text area).
    func testACaretMoveEndsTheSearch() throws {
        let (app, field) = tryFieldWithThumbFree()
        type("hi there", in: app)
        expect(field, "Hi there", "the letters")
        openPicker(app)
        search("cat", in: app)
        field.coordinate(withNormalizedOffset: .zero).withOffset(CGVector(dx: 4, dy: 11)).tap() // on "Hi", away from the caret
        XCTAssertTrue(ThumbFreeUI.wait(until: 3) { !ThumbFreeUI.element("keyboard.emoji.search", in: app).exists }, "the search stayed up")
        type("o", in: app)
        XCTAssertTrue(ThumbFreeUI.wait(until: 5) { (field.value as? String)?.count == 9 }, "the letter did not go into the text")
        let text = field.value as? String ?? ""
        XCTAssertTrue(text.hasSuffix(" there"), "the letter did not go in at the moved caret: \(text)")
    }

    // A tap on the text box right after a pick ends the search too, with the caret where the pick left it: the keyboard's
    // own insert brings iOS no notice (checked on the iOS 26.5 Simulator), so the tap's notice is never taken for it.
    func testATapRightAfterAPickEndsTheSearch() throws {
        let (app, field) = tryFieldWithThumbFree()
        type("hi", in: app)
        openPicker(app)
        search("cat", in: app)
        let result = ThumbFreeUI.element("keyboard.emoji.result.0", in: app)
        XCTAssertTrue(ThumbFreeUI.wait(until: 3) { result.label == "cat face" })
        // Both taps by position, so no reading of the screen comes between them: the second lands at once (under half a
        // second), at the end of the text, where the pick left the caret.
        let origin = app.coordinate(withNormalizedOffset: .zero)
        let (picked, box) = (result.frame, field.frame)
        origin.withOffset(CGVector(dx: picked.midX, dy: picked.midY)).tap()
        origin.withOffset(CGVector(dx: box.maxX - 20, dy: box.maxY - 20)).tap()
        expect(field, "Hi\u{1F431}", "the pick")
        ThumbFreeUI.element("keyboard.delete", in: app).tap()
        expect(field, "Hi", "delete right after the tap")
        XCTAssertFalse(ThumbFreeUI.element("keyboard.emoji.search", in: app).exists, "the search stayed up")
    }

    // However a search ends, what is still under a finger ends with it: delete held in the search stops
    // repeating when a second finger taps the text box, not going on into the text, and a letter held then is not typed
    // into the text when it is let go. Done ends a search the same way (`endSearch`), but XCTest cannot tap it cleanly
    // with a second finger (see `ThumbFreeUI.fingers(_:)`: on Done its extra tap ends the search early, then the real
    // tap types a return).
    func testTheSearchsEndLetsGoOfTheKeysStillHeld() throws {
        let (app, field) = tryFieldWithThumbFree()
        type("hello", in: app)
        expect(field, "Hello", "the letters")
        let query = ThumbFreeUI.element("keyboard.emoji.search", in: app)
        let box = field.frame
        let end = CGPoint(x: box.maxX - 20, y: box.maxY - 20) // at the end of the text, where the caret is
        for (key, seconds, after) in [("keyboard.delete", 2.5, 1.2), ("keyboard.key.x", 1.5, 0.5)] {
            openPicker(app)
            search("cat", in: app)
            XCTAssertTrue(ThumbFreeUI.wait(until: 3) { query.value as? String == "cat" })
            let frame = ThumbFreeUI.element(key, in: app).frame
            try ThumbFreeUI.fingers([.init(point: CGPoint(x: frame.midX, y: frame.midY), down: 0, up: seconds),
                                     .init(point: end, down: after, up: after + 0.1)])
            XCTAssertTrue(ThumbFreeUI.wait(until: 3) { !query.exists }, "the tap on the text box did not end the search")
            expect(field, "Hello", "\(key) held while the search ended")
        }
    }

    // The caret, as Apple's: at the start of an empty search, right after the magnifier and before the words "Search
    // Emoji"; then right after the last letter typed, and after a typed space too.
    func testTheCaretStartsAtTheStartThenFollowsTheText() throws {
        let (app, _) = tryFieldWithThumbFree()
        openPicker(app)
        let field = ThumbFreeUI.element("keyboard.emoji.search", in: app)
        field.tap()
        XCTAssertTrue(ThumbFreeUI.element("keyboard.emoji.results", in: app).waitForExistence(timeout: 3), "the search did not open")
        let empty = try XCTUnwrap(caretX(in: field), "no caret in the empty search")
        XCTAssertTrue((20...30).contains(empty), "the caret is not at the start, after the magnifier: \(empty) pt in")
        let body = UIFont.preferredFont(forTextStyle: .body)
        let width = { (text: String) in (text as NSString).size(withAttributes: [.font: body]).width }
        type("cat", in: app)
        XCTAssertTrue(ThumbFreeUI.wait(until: 3) { field.value as? String == "cat" })
        let cat = try XCTUnwrap(caretX(in: field), "no caret after the letters")
        XCTAssertEqual(cat - empty, width("cat") - 1, accuracy: 1.5, "the caret is not right after the letters")
        type(" ", in: app)
        XCTAssertTrue(ThumbFreeUI.wait(until: 3) { field.value as? String == "cat " })
        let space = try XCTUnwrap(caretX(in: field), "no caret after the space")
        XCTAssertEqual(space - cat, width(" "), accuracy: 1, "the caret did not move past the space")
    }

    // Mixed typing: text, an emoji, text again, then delete back through it, each delete taking one letter or the whole
    // emoji. The text box's exact text is checked after every step.
    func testTextEmojiTextThenDeleteBackOneAtATime() throws {
        let (app, field) = tryFieldWithThumbFree()
        type("hi", in: app)
        expect(field, "Hi", "the letters")
        openPicker(app)
        ThumbFreeUI.element("keyboard.emoji.category.smileysAndPeople", in: app).tap()
        ThumbFreeUI.element("keyboard.emoji.1F600", in: app).tap()
        expect(field, "Hi\u{1F600}", "the emoji")
        ThumbFreeUI.element("keyboard.toLetters", in: app).tap()
        type(" there", in: app)
        var text = "Hi\u{1F600} there"
        expect(field, text, "the letters after the emoji")
        for _ in 0..<8 { // " there", the emoji, then the i
            ThumbFreeUI.element("keyboard.delete", in: app).tap()
            text.removeLast()
            expect(field, text, "a delete")
        }
    }

    // A skin tone, a flag and a family between letters: each goes in whole, and one delete takes each out whole.
    func testATonedEmojiAFlagAndAFamilyGoInAndOutWhole() throws {
        let (app, field) = tryFieldWithThumbFree()
        // A thumbs up picked once puts it in Frequently Used, whatever earlier runs left there; delete takes it back out.
        openPicker(app)
        search("thumbs up", in: app)
        ThumbFreeUI.element("keyboard.emoji.result.0", in: app).tap()
        ThumbFreeUI.element("keyboard.return", in: app).tap() // Done
        ThumbFreeUI.element("keyboard.delete", in: app).tap()
        type("a", in: app)
        var text = "A"
        expect(field, text, "the first letter")
        openPicker(app)
        ThumbFreeUI.element("keyboard.emoji.category.recents", in: app).tap()
        try XCTUnwrap(recent("thumbs up", in: app), "no thumbs up in Frequently Used").press(forDuration: 0.8)
        ThumbFreeUI.element("keyboard.emoji.tone.3", in: app).tap()
        text += "\u{1F44D}\u{1F3FD}"
        expect(field, text, "the thumbs up with a medium skin tone")
        ThumbFreeUI.element("keyboard.toLetters", in: app).tap()
        type("b", in: app)
        text += "b"
        expect(field, text, "a letter after the tone")
        openPicker(app)
        search("united states", in: app)
        ThumbFreeUI.element("keyboard.emoji.result.0", in: app).tap()
        text += "\u{1F1FA}\u{1F1F8}"
        expect(field, text, "the flag")
        ThumbFreeUI.element("keyboard.return", in: app).tap() // Done
        type("c", in: app)
        text += "c"
        expect(field, text, "a letter after the flag")
        openPicker(app)
        search("family", in: app)
        // Apple's keyboard offers only its families of adults and children (three people joined into one emoji).
        app.descendants(matching: .any).matching(NSPredicate(format: "identifier BEGINSWITH %@ AND label == %@",
                                                             "keyboard.emoji.result.", "family: adult, adult, child")).firstMatch.tap()
        text += "\u{1F9D1}\u{200D}\u{1F9D1}\u{200D}\u{1F9D2}"
        expect(field, text, "the family")
        ThumbFreeUI.element("keyboard.return", in: app).tap() // Done
        type("d", in: app)
        text += "d"
        expect(field, text, "a letter after the family")
        for _ in 0..<6 { // d, the family, c, the flag, b, the thumbs up
            ThumbFreeUI.element("keyboard.delete", in: app).tap()
            text.removeLast()
            expect(field, text, "a delete")
        }
        // The plain thumbs up again forgets the tone, for the next run.
        openPicker(app)
        ThumbFreeUI.element("keyboard.emoji.category.recents", in: app).tap()
        try XCTUnwrap(recent("thumbs up", in: app)).press(forDuration: 0.8)
        ThumbFreeUI.element("keyboard.emoji.tone.0", in: app).tap()
    }

    // The picker's delete goes back across emoji and letters alike, one at a time.
    func testThePickersDeleteGoesBackAcrossEmojiAndLetters() throws {
        let (app, field) = tryFieldWithThumbFree()
        type("ab", in: app)
        openPicker(app)
        ThumbFreeUI.element("keyboard.emoji.category.smileysAndPeople", in: app).tap()
        ThumbFreeUI.element("keyboard.emoji.1F600", in: app).tap()
        ThumbFreeUI.element("keyboard.toLetters", in: app).tap()
        type("c", in: app)
        openPicker(app)
        ThumbFreeUI.element("keyboard.emoji.1F603", in: app).tap() // the picker reopens where it was left
        var text = "Ab\u{1F600}c\u{1F603}"
        expect(field, text, "letters and emoji")
        for _ in 0..<4 { // the emoji, c, the emoji, b
            ThumbFreeUI.element("keyboard.delete", in: app).tap() // the picker's own delete, in its bar
            text.removeLast()
            expect(field, text, "a delete in the picker")
        }
        XCTAssertTrue(ThumbFreeUI.element("keyboard.emoji.picker", in: app).exists, "the picker went away")
    }

    // Letters, the 123 layer, the emoji key there, and ABC back to the letters; a one capital set before an emoji is spent
    // by it, as Apple's shift is off once an emoji goes in; and the automatic capital after a full stop that follows an
    // emoji, but not after an emoji that follows a full stop (all as Apple's keyboard does on the iOS 26.5 Simulator).
    func testLayersShiftAndCapitalsAroundEmoji() throws {
        let (app, field) = tryFieldWithThumbFree()
        type("hi", in: app)
        ThumbFreeUI.element("keyboard.toNumbers", in: app).tap()
        ThumbFreeUI.element("keyboard.key.period", in: app).tap()
        expect(field, "Hi.", "the full stop")
        openPicker(app) // the emoji key on the 123 layer
        ThumbFreeUI.element("keyboard.emoji.category.smileysAndPeople", in: app).tap()
        ThumbFreeUI.element("keyboard.emoji.1F600", in: app).tap()
        expect(field, "Hi.\u{1F600}", "the emoji")
        ThumbFreeUI.element("keyboard.toLetters", in: app).tap()
        XCTAssertTrue(ThumbFreeUI.element("keyboard.key.q", in: app).waitForExistence(timeout: 3), "ABC did not come back to the letters")
        ThumbFreeUI.element("keyboard.shift", in: app).tap() // one capital
        openPicker(app)
        ThumbFreeUI.element("keyboard.emoji.1F600", in: app).tap()
        ThumbFreeUI.element("keyboard.toLetters", in: app).tap()
        type("t", in: app)
        expect(field, "Hi.\u{1F600}\u{1F600}t", "a letter after an emoji spent the one capital")
        ThumbFreeUI.element("keyboard.toNumbers", in: app).tap()
        ThumbFreeUI.element("keyboard.key.period", in: app).tap()
        ThumbFreeUI.element("keyboard.space", in: app).tap() // back to the letters
        type("t", in: app)
        expect(field, "Hi.\u{1F600}\u{1F600}t. T", "the capital after the full stop")
        ThumbFreeUI.element("keyboard.toNumbers", in: app).tap()
        ThumbFreeUI.element("keyboard.key.period", in: app).tap()
        ThumbFreeUI.element("keyboard.space", in: app).tap()
        openPicker(app)
        ThumbFreeUI.element("keyboard.emoji.1F600", in: app).tap()
        ThumbFreeUI.element("keyboard.toLetters", in: app).tap()
        type(" t", in: app)
        expect(field, "Hi.\u{1F600}\u{1F600}t. T. \u{1F600} t", "no capital after an emoji that follows a full stop")
    }

    // A one capital set before a search is spent by a result, as by any emoji: the letter after Done is small. Caps lock
    // set before a search is still on after it.
    func testAResultSpendsAOneCapitalFromBeforeTheSearch() throws {
        let (app, field) = tryFieldWithThumbFree()
        type("hi", in: app)
        expect(field, "Hi", "the letters")
        let shift = ThumbFreeUI.element("keyboard.shift", in: app)
        let result = ThumbFreeUI.element("keyboard.emoji.result.0", in: app)
        for (tap, after) in [(false, "Hi\u{1F431}t"), (true, "Hi\u{1F431}t\u{1F431}T")] {
            if tap { shift.doubleTap() } else { shift.tap() } // caps lock, or one capital
            openPicker(app)
            search("cat", in: app)
            XCTAssertTrue(ThumbFreeUI.wait(until: 3) { result.label == "cat face" }, "no cat face first")
            result.tap()
            ThumbFreeUI.element("keyboard.return", in: app).tap() // Done
            type("t", in: app)
            expect(field, after, tap ? "caps lock, a result and Done" : "a one capital, a result and Done")
        }
    }

    // Apple's 123 rule holds during Search Emoji too, not only on the main keyboard: switching to 123
    // and pressing space with nothing typed there yet stays on 123; once a key is typed there, the next space goes back
    // to the letters, mirroring testASpaceRightAfter123StaysOn123 but with the query, not the text field, taking the keys.
    func testASpaceRightAfter123StaysOn123DuringSearch() throws {
        let (app, field) = tryFieldWithThumbFree()
        openPicker(app)
        search("", in: app)
        ThumbFreeUI.element("keyboard.toNumbers", in: app).tap()
        ThumbFreeUI.element("keyboard.space", in: app).tap()
        XCTAssertTrue(ThumbFreeUI.element("keyboard.key.1", in: app).waitForExistence(timeout: 3), "the space left 123 during search")
        ThumbFreeUI.element("keyboard.key.5", in: app).tap()
        ThumbFreeUI.element("keyboard.space", in: app).tap()
        XCTAssertTrue(ThumbFreeUI.element("keyboard.key.a", in: app).waitForExistence(timeout: 3), "the space after 5 stayed on 123 during search")
        XCTAssertEqual(ThumbFreeUI.element("keyboard.emoji.search", in: app).value as? String, " 5 ", "the digit and spaces did not reach the search query")
        XCTAssertEqual(field.value as? String, "", "the search's keys must not reach the text field")
    }

    // Apple's 123 rule after a search: a key typed on 123 during the search counts only until the letters come back
    // (Done), so 123 and a space right after it stay on 123 again.
    func testASpaceRightAfter123StaysOn123AfterASearch() throws {
        let (app, field) = tryFieldWithThumbFree()
        openPicker(app)
        search("", in: app)
        ThumbFreeUI.element("keyboard.toNumbers", in: app).tap()
        ThumbFreeUI.element("keyboard.key.1", in: app).tap()
        ThumbFreeUI.element("keyboard.return", in: app).tap() // Done
        ThumbFreeUI.element("keyboard.toNumbers", in: app).tap()
        ThumbFreeUI.element("keyboard.space", in: app).tap()
        XCTAssertTrue(ThumbFreeUI.element("keyboard.key.1", in: app).waitForExistence(timeout: 3), "the space left 123 after the search")
        XCTAssertFalse(ThumbFreeUI.element("keyboard.key.a", in: app).exists, "the space went back to the letters")
        expect(field, " ", "the space")
    }

    // And into a search: a key typed on 123 before the emoji key (on 123's bottom row too) counts only until the search
    // starts on the letters, so in the search 123 and a space right after it stay on 123.
    func testASpaceRightAfter123StaysOn123InASearchStartedFrom123() throws {
        let (app, field) = tryFieldWithThumbFree()
        ThumbFreeUI.element("keyboard.toNumbers", in: app).tap()
        ThumbFreeUI.element("keyboard.key.5", in: app).tap()
        openPicker(app)
        search("", in: app)
        ThumbFreeUI.element("keyboard.toNumbers", in: app).tap()
        ThumbFreeUI.element("keyboard.space", in: app).tap()
        XCTAssertTrue(ThumbFreeUI.element("keyboard.key.1", in: app).waitForExistence(timeout: 3), "the space left 123 in the search")
        XCTAssertFalse(ThumbFreeUI.element("keyboard.key.a", in: app).exists, "the space went back to the letters")
        XCTAssertEqual(ThumbFreeUI.element("keyboard.emoji.search", in: app).value as? String, " ", "the space did not reach the search")
        XCTAssertEqual(field.value as? String, "5", "the search's space reached the text")
    }

    // Apple's skin tones: a long press opens the tone picker, the tone picked is typed, and it is remembered for that
    // emoji: Frequently Used lists it once, in that tone, and the search shows it too. Picking the plain one again
    // forgets it, for the next run.
    func testALongPressPicksASkinToneThatIsRemembered() throws {
        let (app, field) = tryFieldWithThumbFree()
        openPicker(app)
        search("thumbs up", in: app)
        let result = ThumbFreeUI.element("keyboard.emoji.result.0", in: app)
        XCTAssertTrue(result.waitForExistence(timeout: 3))
        result.tap() // now in Frequently Used, whatever the earlier runs left there
        ThumbFreeUI.element("keyboard.return", in: app).tap() // Done: back to the letters
        openPicker(app)
        ThumbFreeUI.element("keyboard.emoji.category.recents", in: app).tap()
        try XCTUnwrap(recent("thumbs up", in: app), "no thumbs up in Frequently Used").press(forDuration: 0.8)
        let medium = ThumbFreeUI.element("keyboard.emoji.tone.3", in: app)
        XCTAssertTrue(medium.waitForExistence(timeout: 3), "the tone picker did not open")
        XCTAssertEqual(medium.label, "thumbs up: medium skin tone")
        medium.tap()
        XCTAssertTrue(ThumbFreeUI.wait(for: field, toContain: "\u{1F44D}\u{1F3FD}", timeout: 5), "the tone was not typed")
        XCTAssertFalse(medium.exists, "the tone picker stayed up")
        ThumbFreeUI.element("keyboard.toLetters", in: app).tap()
        openPicker(app)
        ThumbFreeUI.element("keyboard.emoji.category.recents", in: app).tap()
        let thumbs = app.descendants(matching: .any)
            .matching(NSPredicate(format: "identifier BEGINSWITH %@ AND label BEGINSWITH %@", "keyboard.emoji.recent.", "thumbs up"))
        XCTAssertEqual(thumbs.allElementsBoundByIndex.map(\.label), ["thumbs up: medium skin tone"], "Frequently Used does not list it once, in its tone")
        search("thumbs up", in: app)
        XCTAssertEqual(result.label, "thumbs up: medium skin tone", "the tone was not remembered")
        ThumbFreeUI.element("keyboard.return", in: app).tap()
        openPicker(app)
        ThumbFreeUI.element("keyboard.emoji.category.recents", in: app).tap()
        try XCTUnwrap(recent("thumbs up", in: app)).press(forDuration: 0.8)
        ThumbFreeUI.element("keyboard.emoji.tone.0", in: app).tap() // the plain one: forgets the tone
    }

    // Apple's two-person picker: a tone for each person, then the pair. The pair is typed and remembered as that emoji's
    // tone. The plain one forgets it again, for the next run.
    func testTwoPeopleGetATonePerPerson() throws {
        let (app, field) = tryFieldWithThumbFree()
        openPicker(app)
        search("handshake", in: app)
        let result = ThumbFreeUI.element("keyboard.emoji.result.0", in: app)
        XCTAssertTrue(result.waitForExistence(timeout: 3))
        result.tap() // into Frequently Used
        ThumbFreeUI.element("keyboard.return", in: app).tap()
        openPicker(app)
        ThumbFreeUI.element("keyboard.emoji.category.recents", in: app).tap()
        try XCTUnwrap(recent("handshake", in: app), "no handshake in Frequently Used").press(forDuration: 0.8)
        let light = ThumbFreeUI.element("keyboard.emoji.pair.left.0", in: app)
        XCTAssertTrue(light.waitForExistence(timeout: 3), "the two-person picker did not open")
        let preview = ThumbFreeUI.element("keyboard.emoji.pair.preview", in: app)
        XCTAssertEqual(preview.label, "Pick a tone for each person")
        light.tap()
        ThumbFreeUI.element("keyboard.emoji.pair.right.4", in: app).tap()
        XCTAssertEqual(preview.label, "handshake: light skin tone, dark skin tone")
        preview.tap()
        XCTAssertTrue(ThumbFreeUI.wait(for: field, toContain: "\u{1FAF1}\u{1F3FB}\u{200D}\u{1FAF2}\u{1F3FF}", timeout: 5), "the pair was not typed")
        XCTAssertFalse(light.exists, "the picker stayed up")
        search("handshake", in: app)
        XCTAssertEqual(result.label, "handshake: light skin tone, dark skin tone", "the pair was not remembered")
        ThumbFreeUI.element("keyboard.return", in: app).tap()
        openPicker(app)
        ThumbFreeUI.element("keyboard.emoji.category.recents", in: app).tap()
        try XCTUnwrap(recent("handshake", in: app)).press(forDuration: 0.8)
        ThumbFreeUI.element("keyboard.emoji.pair.plain", in: app).tap()
    }

    // Only an emoji with skin tones opens a tone picker on a long press. Held, any other emoji still types when let go,
    // and a hold that turns into a drag still scrolls the grid, typing nothing.
    func testAHeldEmojiWithoutTonesTypesAndAHoldThenDragScrolls() throws {
        let (app, field) = tryFieldWithThumbFree()
        openPicker(app)
        ThumbFreeUI.element("keyboard.emoji.category.smileysAndPeople", in: app).tap()
        let grinning = ThumbFreeUI.element("keyboard.emoji.1F600", in: app)
        XCTAssertTrue(grinning.waitForExistence(timeout: 3))
        grinning.press(forDuration: 0.8)
        XCTAssertTrue(ThumbFreeUI.wait(for: field, toContain: "\u{1F600}", timeout: 5), "a held emoji without tones typed nothing")
        let before = grinning.frame.minX
        let start = grinning.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5))
        start.press(forDuration: 0.6, thenDragTo: start.withOffset(CGVector(dx: 200, dy: 0)), withVelocity: .slow, thenHoldForDuration: 0.3)
        XCTAssertTrue(ThumbFreeUI.wait(until: 3) { !grinning.exists || grinning.frame.minX > before + 100 }, "a hold then a drag did not scroll")
        XCTAssertEqual(field.value as? String, "\u{1F600}", "a hold then a drag typed")
    }

    // The tone picker's slide, as Apple's: press, slide onto a tone and let go, and that tone is typed. A touch anywhere
    // else closes the picker and types nothing, not even the emoji touched. The plain one forgets the tone, for the next run.
    func testASlideTypesAToneAndATouchElsewhereTypesNothing() throws {
        let (app, field) = tryFieldWithThumbFree()
        for id in ["keyboard.key.h", "keyboard.key.i"] { ThumbFreeUI.element(id, in: app).tap() }
        openPicker(app)
        ThumbFreeUI.element("keyboard.emoji.category.activity", in: app).tap()
        let snowboarder = ThumbFreeUI.element("keyboard.emoji.1F3C2", in: app) // Activity's first emoji with tones
        XCTAssertTrue(snowboarder.waitForExistence(timeout: 3))
        snowboarder.press(forDuration: 0.8) // let go on the emoji: the tone picker stays up
        let medium = ThumbFreeUI.element("keyboard.emoji.tone.3", in: app)
        XCTAssertTrue(medium.waitForExistence(timeout: 3), "the tone picker did not open")
        let onMedium = app.coordinate(withNormalizedOffset: .zero).withOffset(CGVector(dx: medium.frame.midX, dy: medium.frame.midY))
        ThumbFreeUI.element("keyboard.emoji.26BD_FE0F", in: app).tap() // the soccer ball, away from the picker
        XCTAssertTrue(ThumbFreeUI.wait(until: 3) { !medium.exists }, "a touch elsewhere did not close the tone picker")
        XCTAssertEqual(field.value as? String, "Hi", "a touch elsewhere typed")
        snowboarder.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5))
            .press(forDuration: 0.8, thenDragTo: onMedium, withVelocity: .slow, thenHoldForDuration: 0.2)
        XCTAssertTrue(ThumbFreeUI.wait(for: field, toContain: "Hi\u{1F3C2}\u{1F3FD}", timeout: 5), "the slide did not type the tone")
        XCTAssertFalse(medium.exists, "the tone picker stayed up")
        snowboarder.press(forDuration: 0.8)
        ThumbFreeUI.element("keyboard.emoji.tone.0", in: app).tap() // the plain one: forgets the tone
    }

    // A tone picker left open closes as soon as a finger lands outside it, typing no emoji: on the keyboard's bar above the
    // picker too, where a tap on the mic only closes it (the next tap starts the take, or during a take stops it) and the
    // recording line is only words, and a finger's drag on the grid closes it and scrolls the grid on.
    func testATouchAnywhereElseClosesATonePicker() throws {
        let (app, field) = tryFieldWithThumbFree()
        type("hi", in: app)
        expect(field, "Hi", "the letters")
        openPicker(app)
        ThumbFreeUI.element("keyboard.emoji.category.activity", in: app).tap()
        // The person lifting weights: below the first row on every iPhone, so its tone picker stays in the grid.
        let lifter = ThumbFreeUI.element("keyboard.emoji.1F3CB_FE0F", in: app)
        let tone = ThumbFreeUI.element("keyboard.emoji.tone.0", in: app)
        let mic = app.buttons["keyboard.mic"]
        let status = app.staticTexts["keyboard.status"]
        func openTones() {
            lifter.press(forDuration: 0.8)
            XCTAssertTrue(tone.waitForExistence(timeout: 3), "the tone picker did not open")
        }
        openTones()
        mic.tap()
        XCTAssertTrue(ThumbFreeUI.wait(until: 3) { !tone.exists }, "a tap on the mic left the tone picker open")
        XCTAssertFalse(ThumbFreeUI.wait(until: 5) { status.exists && status.label.hasPrefix("Recording") }, "the tap that closed the tone picker started a take")
        mic.tap()
        XCTAssertTrue(ThumbFreeUI.wait(until: 15) { status.label.hasPrefix("Recording") }, "the mic did not start a take")
        openTones()
        status.tap()
        XCTAssertTrue(ThumbFreeUI.wait(until: 3) { !tone.exists }, "a tap on the recording line left the tone picker open")
        openTones()
        mic.tap()
        XCTAssertTrue(ThumbFreeUI.wait(until: 3) { !tone.exists }, "a tap on the mic left the tone picker open during the take")
        XCTAssertFalse(ThumbFreeUI.wait(until: 3) { !(status.exists && status.label.hasPrefix("Recording")) },
                       "the tap that closed the tone picker stopped the take")
        mic.tap() // the take stops, and its words are typed
        XCTAssertTrue(ThumbFreeUI.element("keyboard.emoji.search", in: app).waitForExistence(timeout: 20), "the take did not end")
        openTones()
        let before = lifter.frame.minX
        let start = app.coordinate(withNormalizedOffset: .zero).withOffset(CGVector(dx: app.frame.maxX - 40, dy: lifter.frame.midY))
        start.press(forDuration: 0.05, thenDragTo: start.withOffset(CGVector(dx: -150, dy: 0)), withVelocity: .slow, thenHoldForDuration: 0.3)
        XCTAssertFalse(tone.exists, "a drag on the grid left the tone picker open")
        XCTAssertTrue(ThumbFreeUI.wait(until: 3) { !lifter.exists || lifter.frame.minX < before - 100 }, "the drag did not scroll the grid")
        let text = field.value as? String ?? ""
        XCTAssertTrue(text.hasPrefix("Hi"), "the text changed: \(text)")
        XCTAssertFalse(text.contains("\u{1F3CB}"), "a touch that closed a tone picker typed its emoji: \(text)")
    }

    // A tone picker left open closes when Search Emoji opens: the first key goes into the search, never a tone into the text.
    func testSearchEmojiClosesATonePickerLeftOpen() throws {
        let (app, field) = tryFieldWithThumbFree()
        for id in ["keyboard.key.h", "keyboard.key.i"] { ThumbFreeUI.element(id, in: app).tap() }
        openPicker(app)
        ThumbFreeUI.element("keyboard.emoji.category.activity", in: app).tap()
        // The person lifting weights, let go on it: below the first row on every iPhone, so its tone picker stays in the
        // grid, clear of the search field (over the first row it reaches up into the bar, as a keyboard cannot draw higher).
        ThumbFreeUI.element("keyboard.emoji.1F3CB_FE0F", in: app).press(forDuration: 0.8)
        XCTAssertTrue(ThumbFreeUI.element("keyboard.emoji.tone.0", in: app).waitForExistence(timeout: 3), "the tone picker did not open")
        search("t", in: app)
        XCTAssertTrue(ThumbFreeUI.wait(until: 3) { ThumbFreeUI.element("keyboard.emoji.search", in: app).value as? String == "t" },
                      "the first key did not reach the search")
        XCTAssertEqual(field.value as? String, "Hi", "a key in the search typed into the text")
        ThumbFreeUI.element("keyboard.return", in: app).tap() // Done
    }

    // The tone pickers keep clear of the mic for an emoji right under it: the row of tones over the first row reaches up
    // into the bar, and the two-person picker always does. Each picker's edge ends left of the mic.
    func testTheTonePickersKeepClearOfTheMic() throws {
        let (app, _) = tryFieldWithThumbFree()
        openPicker(app)
        ThumbFreeUI.element("keyboard.emoji.category.activity", in: app).tap()
        let mic = ThumbFreeUI.element("keyboard.mic", in: app).frame
        // A finger's drag of 140 pt brings Activity's people, after its balls and boards, under the mic.
        let ball = ThumbFreeUI.element("keyboard.emoji.26BD_FE0F", in: app).frame // Apple's first activity, the soccer ball
        let origin = app.coordinate(withNormalizedOffset: .zero)
        origin.withOffset(CGVector(dx: 330, dy: ball.midY))
            .press(forDuration: 0.05, thenDragTo: origin.withOffset(CGVector(dx: 190, dy: ball.midY)), withVelocity: .slow, thenHoldForDuration: 0.3)
        // The first of these fully on screen and far enough right that a picker centered on it would reach the mic (and in
        // the soccer ball's row, the first, when `firstRow`).
        func underTheMic(_ codePoints: [String], firstRow: Bool = false) throws -> XCUIElement {
            try XCTUnwrap(codePoints.map { ThumbFreeUI.element("keyboard.emoji.\($0)", in: app) }
                .first { $0.exists && $0.frame.midX > mic.minX - 100 && $0.frame.maxX <= app.frame.maxX
                    && (!firstRow || abs($0.frame.midY - ball.midY) < 1) }, "no emoji under the mic")
        }
        // One person, in the first row (Apple's rows differ by iPhone): the person cartwheeling, the man bouncing a ball or
        // the man playing handball on the iPhone Air; the man cartwheeling or the woman playing handball on the iPhone 16.
        try underTheMic(["1F938", "26F9_FE0F_200D_2642_FE0F", "1F93E_200D_2642_FE0F", "1F938_200D_2642_FE0F", "1F93E_200D_2640_FE0F"],
                        firstRow: true).press(forDuration: 0.8)
        let tone5 = ThumbFreeUI.element("keyboard.emoji.tone.5", in: app)
        XCTAssertTrue(tone5.waitForExistence(timeout: 3), "the tone picker did not open")
        XCTAssertLessThan(tone5.frame.maxX + 6, mic.minX, "the tone picker covers the mic") // its edge, 6 pt past its last tone
        origin.withOffset(CGVector(dx: 30, dy: ball.midY)).tap() // a touch elsewhere closes it
        XCTAssertTrue(ThumbFreeUI.wait(until: 3) { !tone5.exists }, "the tone picker stayed up")
        // Two people, in any row: women, people and men wrestling.
        try underTheMic(["1F93C_200D_2640_FE0F", "1F93C", "1F93C_200D_2642_FE0F"]).press(forDuration: 0.8)
        let right4 = ThumbFreeUI.element("keyboard.emoji.pair.right.4", in: app)
        XCTAssertTrue(right4.waitForExistence(timeout: 3), "the two-person picker did not open")
        XCTAssertLessThan(right4.frame.maxX + 8, mic.minX, "the two-person picker covers the mic") // its edge, 8 pt past its last tone
    }

    /// The Try tab's field with the ThumbFree keyboard up.
    private func tryFieldWithThumbFree() -> (XCUIApplication, XCUIElement) {
        KeyboardSetup.ensureReady()
        let app = ThumbFreeUI.launch()
        let field = ThumbFreeUI.element("try.field", in: app)
        field.tap()
        KeyboardSetup.switchToThumbFree(in: app)
        return (app, field)
    }

    private func openPicker(_ app: XCUIApplication) {
        ThumbFreeUI.element("keyboard.emoji", in: app).tap()
        XCTAssertTrue(ThumbFreeUI.element("keyboard.emoji.picker", in: app).waitForExistence(timeout: 3), "the picker did not open")
    }

    /// The grid against Apple's (on Smileys & People): Apple's first smileys fill the first column's `rows` rows, `pitch`
    /// apart, the next column starts `column` to the right at the top, and the last row ends `gap` above Apple's 40 pt bar.
    private func assertApplesGrid(in app: XCUIApplication, rows: Int, pitch: CGFloat, column: CGFloat, gap: CGFloat) {
        let smileys = ["1F600", "1F603", "1F604", "1F601", "1F606", "1F979"].map { ThumbFreeUI.element("keyboard.emoji.\($0)", in: app).frame }
        XCTAssertEqual(Set(smileys[..<rows].map { $0.minX.rounded() }).count, 1, "Apple's first \(rows) smileys are not one column")
        XCTAssertEqual((smileys[rows - 1].midY - smileys[0].midY) / CGFloat(rows - 1), pitch, accuracy: 0.5, "the rows are not Apple's distance apart")
        XCTAssertEqual(smileys[rows].midY, smileys[0].midY, accuracy: 0.5, "the next column does not start at the top")
        XCTAssertEqual(smileys[rows].minX - smileys[0].minX, column, accuracy: 0.5, "the columns are not Apple's width")
        let bar = ThumbFreeUI.element("keyboard.toLetters", in: app).frame
        XCTAssertEqual(bar.height, 40, "the bar is not Apple's height")
        XCTAssertEqual(bar.minY - (smileys[rows - 1].midY + 16), gap, accuracy: 0.5, "the last row does not end Apple's gap above the bar") // 32 pt emoji
    }

    /// The Frequently Used cell whose name starts with `name`, if the picker shows one.
    private func recent(_ name: String, in app: XCUIApplication) -> XCUIElement? {
        let cell = app.descendants(matching: .any)
            .matching(NSPredicate(format: "identifier BEGINSWITH %@ AND label BEGINSWITH %@", "keyboard.emoji.recent.", name)).firstMatch
        return cell.waitForExistence(timeout: 3) ? cell : nil
    }

    /// Taps the Search Emoji field and types `query` on the letters.
    private func search(_ query: String, in app: XCUIApplication) {
        ThumbFreeUI.element("keyboard.emoji.search", in: app).tap()
        XCTAssertTrue(ThumbFreeUI.element("keyboard.emoji.results", in: app).waitForExistence(timeout: 3), "the search did not open")
        type(query, in: app)
    }

    /// Types `text` on ThumbFree's letters (small letters and spaces).
    private func type(_ text: String, in app: XCUIApplication) {
        for character in text {
            ThumbFreeUI.element(character == " " ? "keyboard.space" : "keyboard.key.\(character)", in: app).tap()
        }
    }

    /// Waits for the text box to hold exactly `text`.
    private func expect(_ field: XCUIElement, _ text: String, _ step: String, file: StaticString = #filePath, line: UInt = #line) {
        let matched = ThumbFreeUI.wait(until: 5) { field.value as? String == text }
        XCTAssertTrue(matched, "after \(step) the text is \(String(describing: field.value)), not \(text)", file: file, line: line)
    }

    /// Where the search field's caret starts, in points from the field's left edge, read from pictures of the field in the
    /// caret's blue (it blinks, so for up to about a second); nil when none shows it.
    private func caretX(in field: XCUIElement) -> CGFloat? {
        for _ in 0..<12 {
            if let x = Self.blueColumn(in: field.screenshot().image) { return x }
            Thread.sleep(forTimeInterval: 0.1)
        }
        return nil
    }

    /// The first column, in points, with the tint's blue across the picture's middle row (the caret's color: nothing else
    /// in the field is blue).
    private static func blueColumn(in image: UIImage) -> CGFloat? {
        guard let cg = image.cgImage else { return nil }
        let (width, height) = (cg.width, cg.height)
        var pixels = [UInt8](repeating: 0, count: width * height * 4)
        guard let context = CGContext(data: &pixels, width: width, height: height, bitsPerComponent: 8, bytesPerRow: width * 4,
                                      space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else { return nil }
        context.draw(cg, in: CGRect(x: 0, y: 0, width: width, height: height))
        let row = height / 2
        for x in 0..<width {
            let i = (row * width + x) * 4
            let (red, green, blue) = (Int(pixels[i]), Int(pixels[i + 1]), Int(pixels[i + 2]))
            if blue > 200, red < 80, (90...170).contains(green) { return CGFloat(x) / CGFloat(width) * image.size.width }
        }
        return nil
    }

    /// How far above the picture's bottom, in points, its last row with the cat face's yellow lies (nothing behind the
    /// keyboard is that yellow). Drawn upright first: a picture taken in landscape comes turned.
    private static func yellowBottomMargin(in image: UIImage) -> CGFloat? {
        let format = UIGraphicsImageRendererFormat()
        format.scale = image.scale
        guard let cg = UIGraphicsImageRenderer(size: image.size, format: format).image(actions: { _ in image.draw(at: .zero) }).cgImage
        else { return nil }
        let (width, height) = (cg.width, cg.height)
        var pixels = [UInt8](repeating: 0, count: width * height * 4)
        pixels.withUnsafeMutableBytes { buffer in
            CGContext(data: buffer.baseAddress, width: width, height: height, bitsPerComponent: 8, bytesPerRow: width * 4,
                      space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)?
                .draw(cg, in: CGRect(x: 0, y: 0, width: width, height: height))
        }
        let last = (0..<height).last { row in // rows run top down
            (0..<width).contains { x in
                let i = (row * width + x) * 4
                return pixels[i] > 180 && pixels[i + 1] > 120 && pixels[i + 2] < 90
            }
        }
        return last.map { CGFloat(height - 1 - $0) / image.scale }
    }
}
