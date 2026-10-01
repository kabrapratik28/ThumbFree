import Foundation

/// The keyboard's mark in the App Group. The keyboard leaves it each time it appears with Full Access (without Full
/// Access it cannot write there at all), so the app can tell that the keyboard can reach it (`KeyboardStatus`): iOS
/// gives the app no API for that. The mark is an empty file; only its date counts.
/// ponytail: a keyboard removed later, or Full Access turned off later, leaves the old mark, so the app keeps saying
/// "Added" until the next reset; checking the mark's age is the upgrade if that confuses people.
enum KeyboardMark {
    static let fileName = "keyboard-seen"

    static func record(in directory: URL) {
        try? Data().write(to: directory.appendingPathComponent(fileName), options: .atomic)
    }

    /// When the keyboard last appeared with Full Access; nil when it never has.
    static func lastSeen(in directory: URL) -> Date? {
        (try? FileManager.default.attributesOfItem(atPath: directory.appendingPathComponent(fileName).path))?[.modificationDate] as? Date
    }
}
