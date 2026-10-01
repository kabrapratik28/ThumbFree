#if TF_AUTO_RETURN
import os
import TFCore
import UIKit

/// Our own implementation of a known host-identification method (the keyboard arbiter's pid and bundle id pairs), gated
/// on the `TF_AUTO_RETURN` compilation condition so a build without it drops every private-API line. The private
/// reads live in `HostArbiterShim.m`: it resolves every class and property by name at runtime, never by linking against
/// it, and reads inside `@try`, because a private getter can raise an Objective-C exception even when the object
/// answers to the name, and Swift cannot catch one. The load-time swizzle that turns the arbiter on lives in
/// `HostArbiterActivation.m` (an Objective-C constructor, the only place that runs before the keyboard's own code).
/// This file decides what to trust and keeps the pid-to-bundle table in memory.
@MainActor final class HostArbiter {
    private var table = HostTable()
    private static let log = Logger(subsystem: Brand.bundleID, category: "hostReturn")

    /// Registers the keyboard with the system arbiter if it has no connection, then harvests. Called from
    /// `viewWillAppear`: a third-party keyboard loses its registration when it leaves the screen.
    func connectAndHarvest() {
        guard let client = Self.client() else { return }
        if TFArbiterValue(client, "connection") == nil { TFArbiterCall(client, "startConnection") }
        _ = harvest()
    }

    /// Reads the current client record and stores `(processIdentifier, sourceBundleIdentifier)`. Returns the pid it
    /// just recorded, or nil when the read failed or the pair was rejected (an invalid pid or an empty bundle id).
    /// Called on every appearance, keystroke and caret move.
    @discardableResult
    func harvest() -> Int? {
        guard let pair = Self.currentPair(), table.record(pid: pair.pid, bundleID: pair.bundleID) else { return nil }
        return pair.pid
    }

    /// The trusted host for the app this keyboard serves, but only when this same tap's own harvest reads that pid. The
    /// decision lives in `HostTable.trust`, which only ever answers with the bundle id read this tap, never a value
    /// already in the table: a recycled pid, or a failed or rejected read, resolves to nothing, never a stale app.
    /// `hostPid` is `_hostProcessIdentifier` on the controller. Logs the outcome with no user text, with the pid the
    /// arbiter reported at this tap (`seen`), so the device test can tell a lagging arbiter (another pid) from an empty
    /// one (none).
    func trustedHost(hostPid: Int) -> String? {
        let pair = Self.currentPair()
        let host = table.trust(pair, hostPid: hostPid)
        let pairs = table.count
        let seen = pair.map { String($0.pid) } ?? "none"
        Self.log.notice("""
            host \(host != nil ? "trusted" : "untrusted", privacy: .public) pairs=\(pairs, privacy: .public) \
            hostPid=\(hostPid, privacy: .public) seen=\(seen, privacy: .public) bundle=\(host ?? "none", privacy: .private)
            """)
        return host
    }

    /// The pid of the app this keyboard serves, read from `_hostProcessIdentifier` on the input view controller.
    static func hostPid(of controller: UIInputViewController) -> Int? { int(controller, "_hostProcessIdentifier") }

    // MARK: the private reads, all through the shim

    /// This tap's `(processIdentifier, sourceBundleIdentifier)` pair, or nil when the client, its state, or either
    /// field could not be read.
    private static func currentPair() -> (pid: Int, bundleID: String)? {
        guard let client = client(),
              let state = TFArbiterValue(client, "currentClientState"),
              let pid = int(state, "processIdentifier"),
              let bundle = TFArbiterValue(state, "sourceBundleIdentifier") as? String else { return nil }
        return (pid, bundle)
    }

    private static func client() -> Any? {
        #if targetEnvironment(simulator)
        return nil // the private arbiter is device-only; every read guards to nil here so nothing downstream can differ
        #else
        return TFArbiterClient()
        #endif
    }

    private static func int(_ object: Any, _ key: String) -> Int? { (TFArbiterValue(object, key) as? NSNumber)?.intValue }
}
#endif
