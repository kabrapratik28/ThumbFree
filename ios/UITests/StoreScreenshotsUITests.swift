import XCTest

/// The App Store screenshots' screens, not a test: skipped unless `TF_SCREENSHOTS` names a folder. `tools/store-screenshots.sh`
/// runs it on the "iPhone 17 Pro Max (store)" Simulator (6.9 inch, 1320 x 2868) with the store build's screens and a 9:41
/// status bar, then puts a caption above each screen. Voice to text in use, saved as 1-talk to 8-history: dictation in
/// Messages, its words in the message box, a long note in Reminders (the Simulator has no Notes), Home ready with Try it
/// and the walkthrough, the message box in Spanish, the keyboard's emoji and suggestions, the Dictionary, and History. Nothing
/// personal: JFK plays as the microphone (the script's `TF_SCREENSHOTS_AUDIO`, JFK over and over, so a take minutes into a
/// session still hears speech) and the fixed engine types made-up sample text. Maya is the made-up contact tools/store-sim.sh
/// adds; nothing is sent.
@MainActor final class StoreScreenshotsUITests: XCTestCase {
    /// No apostrophes or quotes: a launch argument's value is read as a property list string.
    static let message = "Running ten minutes late, save me a seat. Want me to grab you a coffee?"
    /// "Speak in 25 languages": a message in Spanish, one of the Multilingual model's languages.
    static let spanish = "Llego en diez minutos, guárdame un asiento, por favor."
    static let note = "Pack sunscreen, two towels, the blue cooler and snacks for the kids. Ask Sam to bring the grill and "
        + "charcoal. Check the tires and fill up the tank on Friday night, so we can leave at eight on Saturday and miss the traffic."
    /// History's takes, oldest first (History shows the newest first).
    static let history = ["Remind Sam to bring the tickets on Friday.", "Book the dentist for Thursday at four.",
                          "Pick up bread, bananas and coffee on the way home.", "Can you send me the slides from the meeting?",
                          "Running ten minutes late, save me a seat."]
    /// The Dictionary's made-up names and terms, pasted as one list.
    static let names = ["Anika", "Siobhan", "Dr. Nakamura", "Maple Street", "Project Juniper", "Priya"]
    private var folder = URL(fileURLWithPath: "/")
    private var audio = ""

    func testStoreScreenshots() throws {
        guard let path = ProcessInfo.processInfo.environment["TF_SCREENSHOTS"] else {
            throw XCTSkip("Not a test: tools/store-screenshots.sh runs it to capture the App Store screenshots.")
        }
        folder = URL(fileURLWithPath: path, isDirectory: true)
        audio = ProcessInfo.processInfo.environment["TF_SCREENSHOTS_AUDIO"] ?? ThumbFreeUI.jfk
        _ = launch(Self.message, reset: true) // installs ThumbFree on a new Simulator: Settings lists the keyboard only then
        KeyboardSetup.ensureReady()
        // Talking in Messages, then its words in the message box. The session starts in ThumbFree (a take in the try
        // screen's box), as in the store build, so each tap in Messages starts at once.
        take(in: launch(Self.message, reset: true), typing: Self.message)
        let messages = XCUIApplication(bundleIdentifier: "com.apple.MobileSMS")
        var field = ThumbFreeUI.conversation("555-1212", in: messages)
        let mic = messages.buttons["keyboard.mic"]
        let status = messages.staticTexts["keyboard.status"]
        XCTAssertTrue(ThumbFreeUI.wait(for: status, toContain: "Ready, mic on", timeout: 10), "the session is not live: \(status.label)")
        mic.tap()
        XCTAssertTrue(ThumbFreeUI.wait(for: status, toContain: "3 seconds", timeout: 10), "not recording: \(status.label)")
        try save("1-talk")
        mic.tap()
        XCTAssertTrue(ThumbFreeUI.wait(for: field, toContain: "save me a seat", timeout: 20), "the take was not typed in Messages")
        try save("2-typed")
        // The same message box, emptied, and a take in Spanish: the session starts again in ThumbFree with the Spanish text.
        clear(field, in: messages)
        take(in: launch(Self.spanish, reset: false), typing: Self.spanish)
        field = ThumbFreeUI.conversation("555-1212", in: messages)
        XCTAssertTrue(ThumbFreeUI.wait(for: status, toContain: "Ready, mic on", timeout: 10), "the session is not live: \(status.label)")
        mic.tap()
        XCTAssertTrue(ThumbFreeUI.wait(for: status, toContain: "Recording", timeout: 10), "not recording: \(status.label)")
        sleep(2)
        mic.tap()
        XCTAssertTrue(ThumbFreeUI.wait(for: field, toContain: "un asiento", timeout: 20), "the Spanish take was not typed in Messages")
        try save("5-languages")
        // The keyboard's suggestions and emoji, in the other sample conversation: a word finished from the bar, an emoji,
        // and the bar offering the next word.
        field = ThumbFreeUI.conversation("564-8583", in: messages)
        type("happy birt", in: messages)
        let birthday = messages.descendants(matching: .any).matching(NSPredicate(format: "identifier BEGINSWITH 'keyboard.suggestion.' AND label == 'birthday'")).firstMatch
        if birthday.waitForExistence(timeout: 5) { birthday.tap() } else { type("hday ", in: messages) }
        ThumbFreeUI.element("keyboard.emoji", in: messages).tap()
        XCTAssertTrue(ThumbFreeUI.element("keyboard.emoji.picker", in: messages).waitForExistence(timeout: 5), "no emoji picker")
        ThumbFreeUI.element("keyboard.emoji.category.smileysAndPeople", in: messages).tap()
        ThumbFreeUI.element("keyboard.emoji.1F60A", in: messages).tap()
        ThumbFreeUI.element("keyboard.toLetters", in: messages).tap()
        type(" see you tomor", in: messages)
        XCTAssertTrue(ThumbFreeUI.wait(for: ThumbFreeUI.element("keyboard.suggestion.1", in: messages), toContain: "tomorrow", timeout: 5),
                      "no suggestions: \(field.value ?? "")")
        try save("6-keyboard")
        // A long note: a reminder's notes in Reminders' Details, dictated in one take.
        take(in: launch(Self.note, reset: false), typing: Self.note)
        let reminders = XCUIApplication(bundleIdentifier: "com.apple.reminders")
        _ = newReminder(in: reminders)
        KeyboardSetup.switchToThumbFree(in: reminders)
        type("lake trip", in: reminders) // the keyboard's automatic capital: Lake trip
        reminders.buttons["Edit Details"].firstMatch.tap()
        let note = reminders.tables["ReminderDetail.ID.DetailsTable"].textFields["Notes text view"] // not the list's, behind it
        XCTAssertTrue(note.waitForExistence(timeout: 5), "no Details sheet:\n\(reminders.debugDescription)")
        sleep(1) // a tap while the sheet still slides up lands nowhere
        // Near the field's start: a tap at its middle does not start the edit (checked on iOS 26.5).
        for _ in 0..<3 where !reminders.buttons["Next keyboard"].firstMatch.waitForExistence(timeout: 2) {
            note.coordinate(withNormalizedOffset: CGVector(dx: 0.1, dy: 0.5)).tap()
        }
        KeyboardSetup.switchToThumbFree(in: reminders)
        let noteMic = reminders.buttons["keyboard.mic"]
        XCTAssertTrue(ThumbFreeUI.wait(for: reminders.staticTexts["keyboard.status"], toContain: "Ready, mic on", timeout: 10))
        noteMic.tap()
        XCTAssertTrue(ThumbFreeUI.wait(for: reminders.staticTexts["keyboard.status"], toContain: "Recording", timeout: 10))
        sleep(2)
        noteMic.tap()
        XCTAssertTrue(ThumbFreeUI.wait(for: note, toContain: "sunscreen", timeout: 20), "the note was not typed in Reminders")
        try save("3-note")
        reminders.buttons["Done"].firstMatch.tap() // Reminders reopens a Details sheet left open
        // The app in use, not a welcome screen: Home ready, as a person sees it after setup: the "Ready · works offline"
        // chip, Try it (offered until a take gives text, so a reset) and the walkthrough as it plays, held on its third
        // beat, the yellow mic. Opened fresh from the Home Screen, so no session is on. The reset also drops the keyboard's
        // mark from the steps above, so -TFSetupKeyboard holds the keyboard ready, as it is; the mic grant stays.
        XCUIDevice.shared.press(.home)
        let home = launch(Self.message, reset: true, arguments: ["-TFSetupKeyboard", "ready", "-TFGuideBeat", "3"])
        XCTAssertTrue(ThumbFreeUI.element("home.ready", in: home).waitForExistence(timeout: 10), "no ready chip:\n\(home.debugDescription)")
        XCTAssertTrue(ThumbFreeUI.element("home.tryIt", in: home).exists, "no Try it")
        try save("4-private")
        // History, from nothing: one take in the try screen's box for each sample.
        var app = XCUIApplication()
        for (index, text) in Self.history.enumerated() {
            app = launch(text, reset: index == 0)
            take(in: app, typing: text)
        }
        app.buttons["try.notNow"].tap() // the try screen covers the tab bar
        app.tabBars.buttons["History"].tap()
        XCTAssertTrue(app.staticTexts["Today"].waitForExistence(timeout: 5))
        try save("8-history")
        // The Dictionary, last, so its names never touch the takes above: the list pasted with Paste a list, typed with
        // iOS's keyboard (ThumbFreeUI.type), as DictionaryUITests does.
        app.tabBars.buttons["Dictionary"].tap()
        app.buttons["dictionary.paste"].tap()
        let paste = ThumbFreeUI.element("dictionary.pasteField", in: app)
        XCTAssertTrue(paste.waitForExistence(timeout: 5), "no Paste a list sheet")
        ThumbFreeUI.type(Self.names.joined(separator: ", "), into: paste, in: app)
        XCTAssertTrue(ThumbFreeUI.wait(for: ThumbFreeUI.element("dictionary.pasteSummary", in: app),
                                       toContain: "\(Self.names.count) will be added.", timeout: 5), "the list was not typed")
        app.buttons["dictionary.pasteAdd"].tap()
        XCTAssertTrue(paste.waitForNonExistence(timeout: 5), "the Paste a list sheet stayed")
        XCTAssertTrue(app.staticTexts["\(Self.names.count) words"].waitForExistence(timeout: 5), "the names were not added")
        // Opened again, the tab has no "Added 6 words." line, so all six fit above the tab bar.
        let dictionary = launch(Self.message, reset: false, arguments: [])
        dictionary.tabBars.buttons["Dictionary"].tap()
        XCTAssertTrue(dictionary.staticTexts["\(Self.names.count) words"].waitForExistence(timeout: 5), "the names were not kept")
        try save("7-dictionary")
    }

    /// Empties the message box with ThumbFree's delete key held: one character at a time, then whole words.
    private func clear(_ field: XCUIElement, in app: XCUIApplication) {
        let delete = ThumbFreeUI.element("keyboard.delete", in: app)
        func empty() -> Bool { ((field.value as? String) ?? "").isEmpty || field.value as? String == field.placeholderValue }
        for _ in 0..<3 where !empty() { delete.press(forDuration: 6) }
        XCTAssertTrue(empty(), "the message box was not emptied: \(field.value ?? "")")
    }

    /// A take with the ThumbFree keyboard in the try screen's box: its mic, 2 s of JFK, its mic again, and the fixed
    /// engine's text in the box. The first take of a launch starts the session, whose mic stays on for the keyboard's next
    /// taps in other apps.
    private func take(in app: XCUIApplication, typing text: String) {
        let field = ThumbFreeUI.element("try.field", in: app)
        XCTAssertTrue(field.waitForExistence(timeout: 10), "no try screen:\n\(app.debugDescription)")
        field.tap()
        KeyboardSetup.switchToThumbFree(in: app)
        let mic = app.buttons["keyboard.mic"]
        mic.tap()
        XCTAssertTrue(ThumbFreeUI.wait(for: app.staticTexts["keyboard.status"], toContain: "Recording", timeout: 15))
        sleep(2)
        mic.tap()
        // Starts with the words, so the box's placeholder never counts.
        XCTAssertTrue(ThumbFreeUI.wait(until: 20) { (field.value as? String)?.hasPrefix(String(text.prefix(12))) == true })
    }

    /// ThumbFree with the fixed engine typing `text`, opened on the try screen unless `arguments` says otherwise; `reset`
    /// starts from nothing, else History keeps the earlier takes.
    private func launch(_ text: String, reset: Bool, arguments: [String] = ThumbFreeUI.tryArguments) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = ["-TFAudioFile", audio, "-TFFakeEngine", "YES", "-TFWelcomeDone", "YES", "-TFFakeText", text] + arguments
        if reset { app.launchArguments += ["-TFResetState", "YES"] }
        app.launch()
        return app
    }

    /// Letters and spaces with ThumbFree's own keys.
    private func type(_ letters: String, in app: XCUIApplication) {
        for letter in letters { ThumbFreeUI.element(letter == " " ? "keyboard.space" : "keyboard.key.\(letter)", in: app).tap() }
    }

    /// A new reminder in Reminders' list, past the first-launch welcome, with the keyboard up; its title field.
    private func newReminder(in reminders: XCUIApplication) -> XCUIElement {
        XCUIDevice.shared.press(.home) // opened from the Home Screen, Reminders shows no link back to ThumbFree
        reminders.launch()
        for label in ["Continue", "Not Now", "Get Started"] where reminders.buttons[label].waitForExistence(timeout: 2) {
            reminders.buttons[label].tap()
        }
        if reminders.textFields["Detail View Title Field"].waitForExistence(timeout: 2) { reminders.buttons["Done"].firstMatch.tap() }
        let add = reminders.buttons["New Reminder"]
        XCTAssertTrue(add.waitForExistence(timeout: 10), "no New Reminder button:\n\(reminders.debugDescription)")
        // An earlier run's reminder goes first (never a list: only reminder rows), so each run shows just its own.
        let old = reminders.cells.matching(NSPredicate(format: "identifier CONTAINS 'REMCDReminder'")).firstMatch
        for _ in 0..<10 where old.exists {
            old.swipeLeft()
            let delete = reminders.buttons["Delete"]
            if delete.waitForExistence(timeout: 3) { delete.tap() }
        }
        add.tap()
        let title = reminders.textFields["Title"]
        XCTAssertTrue(title.waitForExistence(timeout: 10), "no Title field:\n\(reminders.debugDescription)")
        if !reminders.keyboards.firstMatch.waitForExistence(timeout: 5) { title.tap() } // a second tap would open a menu
        let tip = reminders.buttons["Continue"] // iOS's one-time slide-to-type tip covers a new Simulator's first keyboard
        if tip.waitForExistence(timeout: 2) { tip.tap() }
        return title
    }

    /// The whole screen at its native size, as `<name>.png` in the folder and in the test's results.
    private func save(_ name: String) throws {
        let shot = XCUIScreen.main.screenshot()
        try shot.pngRepresentation.write(to: folder.appendingPathComponent(name + ".png"))
        let attachment = XCTAttachment(screenshot: shot)
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}
