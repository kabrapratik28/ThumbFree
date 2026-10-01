import Foundation
import TFCore

/// "Go back to the app automatically": on by default, stored in the App Group so the
/// keyboard and the app read the same value. Off means the keyboard never reads the host and the link carries no host,
/// so the app always shows the swipe-back screen. It also remembers which apps ThumbFree has already returned to, so
/// the first-time "If iOS asks, tap Open" line shows once per app. A Debug launch argument (`-TFAutoReturn NO`)
/// overrides the flag for tests.
enum AutoReturn {
    static let key = "TFAutoReturn"
    private static let returnedKey = "TFReturnedApps"

    private static var groupDefaults: UserDefaults? { UserDefaults(suiteName: Brand.appGroupID) }

    /// True unless the user turned it off. A build without the `TF_AUTO_RETURN` compile flag is off for good: the whole
    /// feature (the keyboard's private lookup and the app's open) is a no-op, not just the private-API lines. Falls back to
    /// on when the App Group is unreachable (no Full Access), so the keyboard also checks Full Access before it reads the
    /// arbiter.
    static var enabled: Bool {
        #if TF_AUTO_RETURN
        #if DEBUG
        if let value = UserDefaults.standard.string(forKey: key) { return (value as NSString).boolValue }
        #endif
        guard let defaults = groupDefaults, defaults.object(forKey: key) != nil else { return true }
        return defaults.bool(forKey: key)
        #else
        return false
        #endif
    }

    static func setEnabled(_ on: Bool) { groupDefaults?.set(on, forKey: key) }

    static func hasReturned(to bundleID: String) -> Bool {
        (groupDefaults?.stringArray(forKey: returnedKey) ?? []).contains(bundleID)
    }

    static func markReturned(to bundleID: String) {
        guard let defaults = groupDefaults else { return }
        var apps = Set(defaults.stringArray(forKey: returnedKey) ?? [])
        apps.insert(bundleID)
        defaults.set(Array(apps), forKey: returnedKey)
    }
}
