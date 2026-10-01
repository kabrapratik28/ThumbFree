import Foundation

/// The keyboard's in-memory map from a process id to a host app's bundle id (our own implementation).
/// The arbiter harvests `(processIdentifier, sourceBundleIdentifier)` pairs on every appearance, keystroke and caret move;
/// at the mic tap the host is `bundleID(forHostPid:)` for the pid of the app this keyboard serves. Trusting the pid, not the
/// last-seen bundle id, is what keeps ThumbFree from ever opening the wrong app: a stale reading resolves to nothing, not to
/// the wrong host. It lives only in memory (never the App Group, never UserDefaults), because pids are recycled, and it
/// keeps only the last `limit` pids: the trust decision reads only this tap's pair, so the table just feeds the log's count.
struct HostTable {
    static let limit = 32
    /// Newest last, one entry per pid.
    private var pairs: [(pid: Int, bundleID: String)] = []

    init() {}

    /// Records one harvested pair. A pid always takes the newest bundle id seen for it and counts as the newest pid; past
    /// `limit` pids the oldest goes. Returns whether the pair was accepted (false for a non-positive pid or an empty bundle
    /// id, which leaves the table unchanged).
    @discardableResult
    mutating func record(pid: Int, bundleID: String) -> Bool {
        guard pid > 0, !bundleID.isEmpty else { return false }
        pairs.removeAll { $0.pid == pid }
        pairs.append((pid, bundleID))
        if pairs.count > Self.limit { pairs.removeFirst(pairs.count - Self.limit) }
        return true
    }

    /// The trusted bundle id for the host the keyboard serves, or nil when the pid was never harvested (an unknown or
    /// untrusted host: the link then carries no host and the app shows the swipe-back screen).
    func bundleID(forHostPid pid: Int) -> String? { pairs.last { $0.pid == pid }?.bundleID }

    /// The trust decision for one tap: records `pair` (when there is one) and hands back its bundle id only if the pair
    /// was accepted and its pid is `hostPid`, the pid of the app this keyboard serves. `pair` is nil for a failed
    /// harvest (the private read came back with nothing). The decision only ever looks at `pair`, the one harvested this
    /// tap, never at a value already in the table for `hostPid`: that is what keeps a reused pid, or a rejected read for
    /// a pid the table still remembers from an earlier tap, from resolving to a stale app.
    mutating func trust(_ pair: (pid: Int, bundleID: String)?, hostPid: Int) -> String? {
        guard let pair, record(pid: pair.pid, bundleID: pair.bundleID) else { return nil }
        return pair.pid == hostPid ? pair.bundleID : nil
    }

    var count: Int { pairs.count }
}
