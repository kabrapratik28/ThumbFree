import SwiftUI
import TFCore

/// How far the ThumbFree keyboard's setup has come, as the app can see it. iOS's list of enabled keyboards (the global
/// `AppleKeyboards` preference, which any app can read; `tools/sim-enable-keyboard.sh` writes the same key on the
/// Simulator) says it is added; the keyboard's mark (`KeyboardMark`, which it can write only with Full Access) says it
/// ran with Full Access. The list comes first: a keyboard removed from it is not added, whatever its old mark says. A
/// list that cannot be read proves nothing, so the mark decides then.
enum KeyboardStatus: String, Equatable {
    case notAdded
    /// In iOS's list, but not seen with Full Access yet: Full Access is off, or the keyboard has not come up since.
    case added
    case ready

    /// The App Group folder the keyboard leaves its mark in, found (and made) once: the reads each second only look.
    static let marksFolder = try? AppGroup.ipcDirectory()

    static func current(defaults: UserDefaults = .standard, marks: URL? = KeyboardStatus.marksFolder) -> KeyboardStatus {
        #if DEBUG
        // Screenshots (Debug builds): `-TFSetupKeyboard notAdded|added|ready` holds the status there.
        if let held = defaults.string(forKey: "TFSetupKeyboard").flatMap(KeyboardStatus.init(rawValue:)) { return held }
        #endif
        let list = defaults.stringArray(forKey: "AppleKeyboards")
        if let list, !list.contains(Brand.keyboardBundleID) { return .notAdded }
        if let marks, KeyboardMark.lastSeen(in: marks) != nil { return .ready }
        return list == nil ? .notAdded : .added
    }
}

extension View {
    /// Keeps `status` current while this view is on screen: when it appears, each time the app comes to the front (back
    /// from Settings by any route, also once ready, since the keyboard may have been removed meanwhile), and every second
    /// while the app is in front and the keyboard is not ready, so the keyboard's first appearance over this screen shows
    /// within a second.
    func followsKeyboard(_ status: Binding<KeyboardStatus>) -> some View { modifier(KeyboardFollower(status: status)) }
}

/// `followsKeyboard(_:)`'s reads: a new task whenever the app comes to the front or leaves it, or the keyboard gets ready
/// or stops being so.
private struct KeyboardFollower: ViewModifier {
    @Binding var status: KeyboardStatus
    @Environment(\.scenePhase) private var scenePhase

    func body(content: Content) -> some View {
        content.task(id: [scenePhase == .active, status == .ready]) {
            status = .current()
            while scenePhase == .active, status != .ready, !Task.isCancelled {
                try? await Task.sleep(for: .seconds(1))
                status = .current()
            }
        }
    }
}
