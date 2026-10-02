import XCTest

/// The App Store preview video, not a test: skipped unless `TF_STORE_VIDEO` names a folder holding `plan.json` and the
/// voice it names. `tools/store-video.sh` makes both, records the Simulator's screen while this runs, and cuts the preview
/// from the times this writes to `events.json`. ThumbFree's real engine types what the voice says: the voice plays as the
/// microphone (`-TFAudioFile`) from the moment the session starts, so every tap is timed against the voice's clock.
/// The store build's flow: the session starts in ThumbFree (a take in the try screen's box, which also readies the
/// engine), then Messages, where each tap starts a take at once. Maya is a made-up contact the script adds; nothing is sent.
/// Optional: the listing has screenshots only, no App Preview video. Kept working, for when a video is wanted.
@MainActor final class StoreVideoUITests: XCTestCase {
    struct Plan: Decodable {
        /// A spoken line: where it starts and ends in the voice WAV, in seconds, and words its text must contain.
        struct Line: Decodable { let start, end: Double; let expect: String }
        let voice: String      // the WAV that plays as the microphone
        let warmup: Line       // the take in the try screen's box: starts the session and readies the engine
        let lines: [Line]      // the takes in Messages
        let conversation: String // part of the conversation's name in Messages' list
        let lead, stopAfter, hold: Double // the tap this long before a line, the stop this long after it, the last frames
    }

    private var events: [String: Double] = [:]

    func testStoreVideo() throws {
        guard let path = ProcessInfo.processInfo.environment["TF_STORE_VIDEO"] else {
            throw XCTSkip("Not a test: tools/store-video.sh runs it to record the App Store preview video.")
        }
        let folder = URL(fileURLWithPath: path, isDirectory: true)
        let plan = try JSONDecoder().decode(Plan.self, from: Data(contentsOf: folder.appendingPathComponent("plan.json")))
        let app = XCUIApplication()
        app.launchArguments = ["-TFAudioFile", plan.voice, "-TFResetState", "YES", "-TFWelcomeDone", "YES"] + ThumbFreeUI.tryArguments // the real engine
        app.launch() // installs ThumbFree on a new Simulator: Settings lists the keyboard only then
        KeyboardSetup.ensureReady()
        app.launch()
        // The session: its first take, with the keyboard in the try screen's box. The voice starts with the tap, and the
        // engine is ready once the warm-up line is typed in the box.
        let box = ThumbFreeUI.element("try.field", in: app)
        XCTAssertTrue(box.waitForExistence(timeout: 10), "no try screen:\n\(app.debugDescription)")
        box.tap()
        KeyboardSetup.switchToThumbFree(in: app)
        let boxMic = app.buttons["keyboard.mic"]
        boxMic.tap()
        let clock = Date() // the voice's second 0, give or take the tap's own time: each line has a second to spare
        mark("sessionStart")
        XCTAssertTrue(ThumbFreeUI.wait(for: app.staticTexts["keyboard.status"], toContain: "Recording", timeout: 10))
        wait(until: clock + plan.warmup.end + plan.stopAfter)
        boxMic.tap()
        XCTAssertTrue(ThumbFreeUI.wait(for: box, toContain: plan.warmup.expect, timeout: 120), "the engine did not type the warm-up line")
        // Messages, in Maya's conversation.
        let messages = XCUIApplication(bundleIdentifier: "com.apple.MobileSMS")
        let field = ThumbFreeUI.conversation(plan.conversation, in: messages)
        let mic = messages.buttons["keyboard.mic"]
        let status = messages.staticTexts["keyboard.status"]
        XCTAssertTrue(ThumbFreeUI.wait(for: status, toContain: "Ready, mic on", timeout: 10), "the session is not live: \(status.label)")
        mark("messagesReady")
        let frame = mic.frame
        events["micX"] = frame.midX
        events["micY"] = frame.midY
        events["micSize"] = frame.height
        events["screenWidth"] = messages.frame.width // points: the recording is this times its scale wide
        for (index, line) in plan.lines.enumerated() {
            let take = "take\(index + 1)"
            XCTAssertLessThan(Date(), clock + line.start - plan.lead, "too slow for the voice: raise the silence before line \(index + 1)")
            wait(until: clock + line.start - plan.lead)
            let before = field.value as? String ?? ""
            mark(take + "Tap")
            mic.tap()
            XCTAssertTrue(ThumbFreeUI.wait(for: status, toContain: "Recording", timeout: 5), "\(take) did not start: \(status.label)")
            wait(until: clock + line.end + plan.stopAfter)
            mark(take + "Stop")
            mic.tap()
            // Your own recording has no expected words: then any new text will do.
            let typed = line.expect.isEmpty ? ThumbFreeUI.wait(until: 30) { (field.value as? String ?? before) != before }
                : ThumbFreeUI.wait(for: field, toContain: line.expect, timeout: 30)
            XCTAssertTrue(typed, "\(take) was not typed: \(field.value ?? "")")
            mark(take + "Text")
        }
        Thread.sleep(forTimeInterval: plan.hold)
        mark("end")
        let json = try JSONSerialization.data(withJSONObject: events, options: [.prettyPrinted, .sortedKeys])
        try json.write(to: folder.appendingPathComponent("events.json"))
        try (field.value as? String ?? "").write(to: folder.appendingPathComponent("typed.txt"), atomically: true, encoding: .utf8)
    }

    /// The wall-clock time of a step, for the script to find it in the recording.
    private func mark(_ name: String) { events[name] = Date().timeIntervalSince1970 }

    private func wait(until time: Date) {
        let seconds = time.timeIntervalSinceNow
        if seconds > 0 { Thread.sleep(forTimeInterval: seconds) }
    }
}
