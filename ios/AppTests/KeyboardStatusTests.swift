import Foundation
import Testing
import TFCore
@testable import ThumbFree

/// The keyboard's three setup states: not in iOS's list, in it but not seen with Full Access, and ready once its mark
/// is there, as long as the list still has it. A suite and a folder of this test's own stand in for the global
/// preferences and the App Group.
@MainActor @Suite struct KeyboardStatusTests {
    @Test func theListSaysAddedAndTheMarkSaysReady() throws {
        let suite = TestFiles.defaultsSuite("KeyboardStatus")
        let defaults = try #require(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        let marks = try TestFiles.folder()
        defer { try? FileManager.default.removeItem(at: marks) }

        // A suite also reads the global domain, where this Simulator's list names ThumbFree: each step sets its own list.
        let others = ["en_US@sw=QWERTY;hw=Automatic", "emoji@sw=Emoji"]
        defaults.set(others, forKey: "AppleKeyboards")
        #expect(KeyboardStatus.current(defaults: defaults, marks: marks) == .notAdded)
        defaults.set(others + [Brand.keyboardBundleID], forKey: "AppleKeyboards")
        #expect(KeyboardStatus.current(defaults: defaults, marks: marks) == .added)
        #expect(KeyboardStatus.current(defaults: defaults, marks: nil) == .added) // no App Group: the list alone
        KeyboardMark.record(in: marks)
        #expect(KeyboardStatus.current(defaults: defaults, marks: marks) == .ready)
        defaults.set(others, forKey: "AppleKeyboards")
        #expect(KeyboardStatus.current(defaults: defaults, marks: marks) == .notAdded) // removed: the old mark proves nothing
    }

    // Only a list that can be read and lacks ThumbFree means not added. One that cannot be read (here not a list of
    // names) proves nothing, so the mark decides, as before the list came first.
    @Test func anUnreadableListLeavesItToTheMark() throws {
        let suite = TestFiles.defaultsSuite("KeyboardStatusUnreadable")
        let defaults = try #require(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        let marks = try TestFiles.folder()
        defer { try? FileManager.default.removeItem(at: marks) }
        defaults.set("unreadable", forKey: "AppleKeyboards") // hides the Simulator's own list in the global domain
        #expect(defaults.stringArray(forKey: "AppleKeyboards") == nil)
        #expect(KeyboardStatus.current(defaults: defaults, marks: marks) == .notAdded)
        KeyboardMark.record(in: marks)
        #expect(KeyboardStatus.current(defaults: defaults, marks: marks) == .ready)
    }

    // Settings' keyboard row says what finishes each state.
    @Test func theKeyboardRowSaysWhatIsLeft() {
        #expect(SetupRows.keyboardLine(.notAdded) == "Add it in Settings")
        #expect(SetupRows.keyboardLine(.added) == "Tap a text box and switch to ThumbFree")
        #expect(SetupRows.keyboardLine(.ready) == "Added, with Full Access")
        #expect(SetupRows.doneCount(SetupRows.Facts(mic: .granted, keyboard: .added, model: true)) == 2)
    }

    // Guideline 5.1.1(iv): a button before iOS's microphone question says Continue, never Allow (App Review, 2026-10-09).
    @Test func theMicrophoneRowLeadsToIOSWithContinue() {
        #expect(SetupRows.micFix(.undetermined) == "Continue")
        #expect(SetupRows.micFix(.denied) == "Open Settings")
        #expect(SetupRows.micFix(.granted) == nil)
    }
}
