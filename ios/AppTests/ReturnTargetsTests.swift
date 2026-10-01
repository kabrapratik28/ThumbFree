import Foundation
import Testing
@testable import ThumbFree

/// The return-target table: the first list of apps, each with a scheme and marked "needs device check".
@Suite struct ReturnTargetsTests {
    @Test func theFirstListIsThere() {
        #expect(ReturnTargets.target(forBundleID: "net.whatsapp.WhatsApp")?.scheme == "whatsapp-consumer://")
        #expect(ReturnTargets.target(forBundleID: "com.apple.MobileSMS")?.scheme == "ichat://") // never sms:
        #expect(ReturnTargets.target(forBundleID: "com.apple.mobilenotes")?.scheme == "mobilenotes://")
        #expect(ReturnTargets.target(forBundleID: "com.apple.mobilemail")?.scheme == "message://")
        #expect(ReturnTargets.target(forBundleID: "com.tinyspeck.chatlyio")?.scheme == "slack://open")
        #expect(ReturnTargets.target(forBundleID: "com.openai.chat")?.scheme == "com.openai.chat://")
        #expect(ReturnTargets.target(forBundleID: "com.anthropic.claude")?.scheme == "claude://")
        #expect(ReturnTargets.target(forBundleID: "org.whispersystems.signal")?.scheme == "sgnl://")
        #expect(ReturnTargets.all.count == 8)
    }

    /// Only entries a test iPhone confirmed are trusted outside a Debug build: Messages, Notes and Signal so far.
    @Test func onlyDeviceVerifiedEntriesAreTrusted() {
        let verified = Set(ReturnTargets.all.filter { !$0.needsDeviceCheck }.map(\.displayName))
        #expect(verified == ["Messages", "Notes", "Signal"])
    }

    // A URL scheme is not tied to one app, so an entry still waiting for its device check opens only in a Debug build (the
    // test build, where that check happens); any other build swipes back instead. A checked entry opens in both.
    @Test func anEntryWaitingForItsDeviceCheckOpensOnlyInADebugBuild() throws {
        let unchecked = try #require(ReturnTargets.target(forBundleID: "net.whatsapp.WhatsApp"))
        #expect(ReturnTargets.mayOpen(unchecked, debugBuild: true))
        #expect(!ReturnTargets.mayOpen(unchecked, debugBuild: false))
        let checked = ReturnTarget(bundleID: unchecked.bundleID, displayName: unchecked.displayName, scheme: unchecked.scheme,
                                   needsDeviceCheck: false)
        #expect(ReturnTargets.mayOpen(checked, debugBuild: true))
        #expect(ReturnTargets.mayOpen(checked, debugBuild: false))
    }

    @Test func anUnknownHostHasNoTargetAndNoName() {
        #expect(ReturnTargets.target(forBundleID: "com.apple.mobilesafari") == nil)
        #expect(ReturnTargets.displayName(forBundleID: "com.apple.mobilesafari") == nil)
        #expect(ReturnTargets.displayName(forBundleID: nil) == nil)
        #expect(ReturnTargets.displayName(forBundleID: "net.whatsapp.WhatsApp") == "WhatsApp")
    }
}
