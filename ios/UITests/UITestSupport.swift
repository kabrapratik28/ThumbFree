import XCTest

/// How every UI test starts ThumbFree: JFK from this test bundle instead of the microphone, the fixed-text engine
/// (unless `realModel`), no state left from an earlier test, and the welcome flow already done (unless `welcome`).
/// `AppEnvironment` reads the arguments when it builds the app's session host.
@MainActor enum ThumbFreeUI {
    static func launch(realModel: Bool = false, welcome: Bool = false, arguments: [String] = []) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments += ["-TFAudioFile", jfk, "-TFResetState", "YES", "-TFWelcomeDone", welcome ? "NO" : "YES"] + arguments
        if !realModel { app.launchArguments += ["-TFFakeEngine", "YES"] }
        if let models = ProcessInfo.processInfo.environment["TF_MODELS_DIR"] { app.launchEnvironment["TF_MODELS_DIR"] = models }
        app.launch()
        return app
    }

    /// JFK in this test bundle: the microphone's audio, and with `-TFModelFixture` the "model" to download.
    static var jfk: String { Bundle(for: Token.self).path(forResource: "jfk", ofType: "wav") ?? "" }

    /// An element by accessibility identifier, whatever its type.
    static func element(_ id: String, in app: XCUIApplication) -> XCUIElement {
        app.descendants(matching: .any).matching(identifier: id).firstMatch
    }

    /// Waits until the element's value or label contains `text`, ignoring case.
    static func wait(for element: XCUIElement, toContain text: String, timeout: TimeInterval) -> Bool {
        let predicate = NSPredicate(format: "value CONTAINS[c] %@ OR label CONTAINS[c] %@", text, text)
        return XCTWaiter().wait(for: [XCTNSPredicateExpectation(predicate: predicate, object: element)], timeout: timeout) == .completed
    }

    /// Types with the system keyboard: when an earlier test left the ThumbFree keyboard up, the system's globe ("Next
    /// keyboard") switches away from it first, so the typing never depends on ThumbFree's own keys. One look that finds
    /// no mic ends the search.
    static func type(_ text: String, into field: XCUIElement, in app: XCUIApplication) {
        field.tap()
        for _ in 0..<4 {
            guard app.buttons["keyboard.mic"].waitForExistence(timeout: 1) else { break }
            app.buttons["Next keyboard"].tap()
        }
        field.typeText(text)
    }

    /// Messages from the Home Screen (so its status bar has no link back to ThumbFree), in the Simulator's sample
    /// conversation whose name contains `name`, with the ThumbFree keyboard up. Returns the message box. Nothing is sent.
    static func conversation(_ name: String, in messages: XCUIApplication) -> XCUIElement {
        XCUIDevice.shared.press(.home)
        messages.launch()
        let back = messages.buttons["BackButton"]
        if back.waitForExistence(timeout: 3) { back.tap() } // an earlier run left a conversation open
        let row = messages.cells.containing(NSPredicate(format: "label CONTAINS %@", name)).firstMatch
        XCTAssertTrue(row.waitForExistence(timeout: 10), "no conversation with \(name):\n\(messages.debugDescription)")
        row.tap()
        let field = messages.textFields["messageBodyField"]
        XCTAssertTrue(field.waitForExistence(timeout: 10), "no message box")
        field.tap()
        KeyboardSetup.switchToThumbFree(in: messages)
        return field
    }

    /// With `TF_SHOTS` naming a folder (for xcodebuild, `TEST_RUNNER_TF_SHOTS`), saves the screen there as `<name>.png`,
    /// for the review screenshots; otherwise does nothing.
    static func shot(_ name: String) {
        guard let folder = ProcessInfo.processInfo.environment["TF_SHOTS"] else { return }
        try? XCUIScreen.main.screenshot().pngRepresentation.write(to: URL(fileURLWithPath: folder).appendingPathComponent(name + ".png"))
    }

    /// Waits until `condition` returns true, for checks `wait(for:toContain:)` cannot express (counts, absence, ...).
    static func wait(until timeout: TimeInterval, condition: @escaping () -> Bool) -> Bool {
        let predicate = NSPredicate { _, _ in condition() }
        return XCTWaiter().wait(for: [XCTNSPredicateExpectation(predicate: predicate, object: nil)], timeout: timeout) == .completed
    }

    /// One finger for `fingers(_:)`: down at `point` `down` seconds in, to `move.to` at `move.at` if given, up at `up`.
    struct Finger {
        let point: CGPoint, down: Double, up: Double
        var move: (to: CGPoint, at: Double)?
    }

    /// Fingers at once, which XCTest's public API cannot do, through XCTest's own event synthesis (`XCPointerEventPath` and
    /// `XCSynthesizedEventRecord`, found at run time); skipped with an Xcode that lacks them, failed if the synthesis errs
    /// or times out. List the finger that goes down first first: the other way round it went down in the wrong place. On
    /// the iOS 26.5 Simulator a later finger also taps its point once more, down and up in the same instant, at the earlier
    /// finger's last event before it goes down (its touch-down, or a move): a text box ignores that tap, but a key takes it.
    static func fingers(_ fingers: [Finger]) throws {
        let selector = NSSelectorFromString
        guard let pathClass = NSClassFromString("XCPointerEventPath"), let recordClass = NSClassFromString("XCSynthesizedEventRecord"),
              pathClass.instancesRespond(to: selector("initForTouchAtPoint:offset:")), pathClass.instancesRespond(to: selector("liftUpAtOffset:")),
              pathClass.instancesRespond(to: selector("moveToPoint:atOffset:")),
              recordClass.instancesRespond(to: selector("initWithName:interfaceOrientation:")),
              recordClass.instancesRespond(to: selector("addPointerEventPath:")),
              XCUIDevice.shared.responds(to: selector("eventSynthesizer")),
              let synthesizer = XCUIDevice.shared.perform(selector("eventSynthesizer"))?.takeUnretainedValue(),
              synthesizer.responds(to: selector("synthesizeEvent:completion:")) else {
            throw XCTSkip("this Xcode has no event synthesis for two fingers at once")
        }
        let paths = unsafeBitCast(pathClass, to: PointerEventPath.Type.self)
        let event = unsafeBitCast(recordClass, to: SynthesizedEvent.Type.self)
            .init(name: "fingers", interfaceOrientation: UIInterfaceOrientation.portrait.rawValue)
        for finger in fingers {
            let path = paths.init(touchAt: finger.point, offset: finger.down)
            if let move = finger.move { path.move(to: move.to, atOffset: move.at) }
            path.liftUp(atOffset: finger.up)
            event.addPointerEventPath(path)
        }
        // A synthesis that fails or never finishes fails the test: one that checks that nothing happened would pass.
        let done = XCTestExpectation(description: "the fingers ran")
        unsafeBitCast(synthesizer, to: EventSynthesizer.self).synthesizeEvent(event) { ran, error in
            if !ran || error != nil { XCTFail("the fingers did not run: \(error?.localizedDescription ?? "no error given")") }
            done.fulfill()
        }
        if XCTWaiter().wait(for: [done], timeout: (fingers.map(\.up).max() ?? 0) + 10) != .completed { XCTFail("the fingers did not finish") }
    }

    private final class Token {}
}

/// XCTest's own touch paths, events and event synthesizer (private, found at run time by `ThumbFreeUI.fingers(_:)`).
@objc private protocol PointerEventPath {
    @objc(initForTouchAtPoint:offset:) init(touchAt point: CGPoint, offset: Double)
    @objc(moveToPoint:atOffset:) func move(to point: CGPoint, atOffset offset: Double)
    @objc(liftUpAtOffset:) func liftUp(atOffset offset: Double)
}

@objc private protocol SynthesizedEvent {
    @objc(initWithName:interfaceOrientation:) init(name: String, interfaceOrientation: Int)
    @objc(addPointerEventPath:) func addPointerEventPath(_ path: PointerEventPath)
}

@objc private protocol EventSynthesizer {
    @objc(synthesizeEvent:completion:) func synthesizeEvent(_ event: SynthesizedEvent, completion: @escaping @Sendable (Bool, Error?) -> Void)
}
