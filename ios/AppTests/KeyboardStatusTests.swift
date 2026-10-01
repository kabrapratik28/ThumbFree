import Foundation
import Testing
import TFCore
@testable import ThumbFree

/// The keyboard's three setup states: not in iOS's list, in it but not seen with Full Access, and ready once its mark
/// is there. A suite and a folder of this test's own stand in for the global preferences and the App Group.
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
        #expect(KeyboardStatus.current(defaults: defaults, marks: marks) == .ready) // the mark proves it was added
    }

    // The setup rows say what finishes each state: under the row's title in Settings, where no text box is near, and
    // on its own line in the Try tab's card, over its box.
    @Test func theKeyboardRowSaysWhatIsLeft() {
        #expect(SetupRows.keyboardLine(.notAdded, compact: false) == "Add it in Settings")
        #expect(SetupRows.keyboardLine(.notAdded, compact: true) == "Add the keyboard in Settings")
        #expect(SetupRows.keyboardLine(.added, compact: true) == "Tap the box below and switch to ThumbFree")
        #expect(SetupRows.keyboardLine(.added, compact: false) == "Tap a text box and switch to ThumbFree")
        #expect(SetupRows.keyboardLine(.ready, compact: false) == "Added, with Full Access")
        #expect(SetupRows.doneCount(SetupRows.Facts(mic: .granted, keyboard: .added, model: true)) == 2)
    }
}
