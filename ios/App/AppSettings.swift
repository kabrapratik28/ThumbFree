import Foundation
import Observation
import TFCore

/// Session length and how long History keeps takes (Settings), kept in UserDefaults and handed to the session host
/// whenever they change.
@MainActor @Observable final class AppSettings {
    static let sessionMinutesKey = "TFSessionMinutes"
    static let keepDaysKey = "TFKeepDays"
    static let keepCountKey = "TFKeepCount"
    /// The microphone stays on this long after the last take.
    static let sessionChoices = [2, 5, 15, 60]
    static let defaultSessionMinutes = 5

    /// The host takes every change at once.
    var onChange: (AppSettings) -> Void = { _ in }
    private let defaults: UserDefaults

    init(defaults: UserDefaults = .standard) { self.defaults = defaults }

    var sessionMinutes: Int {
        get {
            access(keyPath: \.sessionMinutes)
            return (defaults.object(forKey: Self.sessionMinutesKey) as? Int).flatMap { Self.sessionChoices.contains($0) ? $0 : nil }
                ?? Self.defaultSessionMinutes
        }
        set {
            withMutation(keyPath: \.sessionMinutes) { defaults.set(newValue, forKey: Self.sessionMinutesKey) }
            onChange(self)
        }
    }

    /// Days a take is kept; nil: forever (the default). Stored as 0 for forever.
    var keepDays: Int? {
        get {
            access(keyPath: \.keepDays)
            return Self.limit(defaults.object(forKey: Self.keepDaysKey) as? Int, choices: Retention.dayChoices) ?? Retention.defaultDays
        }
        set {
            withMutation(keyPath: \.keepDays) { defaults.set(newValue ?? 0, forKey: Self.keepDaysKey) }
            onChange(self)
        }
    }

    /// The most takes kept; nil: no limit. 200 by default. Stored as 0 for no limit.
    var keepCount: Int? {
        get {
            access(keyPath: \.keepCount)
            return Self.limit(defaults.object(forKey: Self.keepCountKey) as? Int, choices: Retention.countChoices) ?? Retention.defaultCount
        }
        set {
            withMutation(keyPath: \.keepCount) { defaults.set(newValue ?? 0, forKey: Self.keepCountKey) }
            onChange(self)
        }
    }

    /// A stored rule: 0 is no limit; a value that is not a choice counts as never set.
    private static func limit(_ stored: Int?, choices: [Int?]) -> Int?? {
        guard let stored else { return nil }
        if stored == 0 { return .some(nil) }
        return choices.contains(stored) ? .some(stored) : nil
    }

    /// Gives the host the session length and the retention rule.
    func apply(to host: SessionHost) {
        host.idleTimeout = TimeInterval(sessionMinutes * 60)
        host.keepDays = keepDays
        host.keepCount = keepCount
    }

    /// The stricter rule's warning, in the Android app's words.
    static func deleteWarning(_ count: Int) -> String {
        count == 1 ? "The new rule deletes 1 take and its recording now. It can't be undone."
            : "The new rule deletes \(count) takes and their recordings now. It can't be undone."
    }
}
