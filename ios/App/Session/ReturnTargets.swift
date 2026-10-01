import Foundation

/// The apps ThumbFree can send you back to, and the URL that reopens each where you were. The keyboard puts the host's
/// bundle id in the dictate link; the app looks it up here and opens the scheme once audio is flowing. Every entry
/// starts marked `needsDeviceCheck`, because a build other than Debug opens a scheme only after a test iPhone confirms
/// it lands back on the same screen (never a new chat or a blank compose sheet); that check shows where the scheme
/// lands, not which app owns it. No dictation app is named here.
struct ReturnTarget: Equatable, Sendable {
    let bundleID: String
    let displayName: String
    let scheme: String            // opened as a URL; open() needs no LSApplicationQueriesSchemes declaration
    let needsDeviceCheck: Bool    // true until a test iPhone confirms it
}

enum ReturnTargets {
    /// The first list: schemes found elsewhere to reopen the same screen. Each "needs check" until a test iPhone (an
    /// iPhone 16 on iOS 26.5.2 so far) confirms it: open a chat or note with a draft, go home, open the URL from
    /// Safari, and the same screen and draft must come back. Add more only on a pass.
    static let all: [ReturnTarget] = [
        ReturnTarget(bundleID: "net.whatsapp.WhatsApp", displayName: "WhatsApp", scheme: "whatsapp-consumer://", needsDeviceCheck: true),
        // Messages and Notes: verified on a test iPhone 16, iOS 26.5.2, 2026-09-28. Both came back to the same
        // conversation or note.
        ReturnTarget(bundleID: "com.apple.MobileSMS", displayName: "Messages", scheme: "ichat://", needsDeviceCheck: false),
        ReturnTarget(bundleID: "com.apple.mobilenotes", displayName: "Notes", scheme: "mobilenotes://", needsDeviceCheck: false),
        ReturnTarget(bundleID: "com.apple.mobilemail", displayName: "Mail", scheme: "message://", needsDeviceCheck: true),
        ReturnTarget(bundleID: "com.tinyspeck.chatlyio", displayName: "Slack", scheme: "slack://open", needsDeviceCheck: true),
        ReturnTarget(bundleID: "com.openai.chat", displayName: "ChatGPT", scheme: "com.openai.chat://", needsDeviceCheck: true),
        ReturnTarget(bundleID: "com.anthropic.claude", displayName: "Claude", scheme: "claude://", needsDeviceCheck: true),
        // Signal: verified on a test iPhone 16, iOS 26.5.2, 2026-09-28 (back in the same chat, words typed there).
        ReturnTarget(bundleID: "org.whispersystems.signal", displayName: "Signal", scheme: "sgnl://", needsDeviceCheck: false),
    ]

    private static let byBundle = Dictionary(uniqueKeysWithValues: all.map { ($0.bundleID, $0) })

    static func target(forBundleID bundleID: String) -> ReturnTarget? { byBundle[bundleID] }

    /// The app's display name for a bundle id when it is in the table, so the round-trip screen can name it even when it
    /// falls back to the swipe-back instructions.
    static func displayName(forBundleID bundleID: String?) -> String? { bundleID.flatMap { byBundle[$0]?.displayName } }

    /// Whether a build may open `target`: a Debug build, the test build where the device check happens, opens every
    /// entry; any other build opens only an entry a test iPhone has confirmed and shows the swipe-back screen for the
    /// rest. A custom URL scheme is not bound to one app (another app can register the same one); the URL is only the
    /// fixed scheme and never carries text. Universal links, bound to the app's own domain, are the stronger option.
    static func mayOpen(_ target: ReturnTarget, debugBuild: Bool) -> Bool { debugBuild || !target.needsDeviceCheck }

    #if DEBUG
    static let isDebugBuild = true
    #else
    static let isDebugBuild = false
    #endif
}

/// The round-trip screen's state while a keyboard link has opened the app. `leaving`: we are opening the host now.
/// `swipeBack`: fall back to the swipe instructions (automatic return off, unknown or untrusted host, an entry this
/// build may not open, the open failed, or the app is still in front 1.5 s after the open). `appName` is the host's
/// display name when known; `firstReturn` (the first return to that app) shows the one-time "If iOS asks, tap Open"
/// line while leaving.
struct ReturnTrip: Equatable, Sendable {
    enum Phase: Sendable, Equatable { case leaving, swipeBack }
    var appName: String?
    var phase: Phase
    var firstReturn: Bool
}
