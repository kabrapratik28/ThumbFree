import Testing
@testable import ThumbFree

/// The keyboard's pid-to-bundle table: the pid match is what keeps ThumbFree from opening the wrong app.
@Suite struct HostTableTests {
    @Test func theHostPidResolvesToItsBundleID() {
        var table = HostTable()
        table.record(pid: 501, bundleID: "net.whatsapp.WhatsApp")
        table.record(pid: 733, bundleID: "com.apple.MobileSMS")
        #expect(table.bundleID(forHostPid: 501) == "net.whatsapp.WhatsApp")
        #expect(table.bundleID(forHostPid: 733) == "com.apple.MobileSMS")
    }

    // A pid never harvested resolves to nothing: an unknown or untrusted host, so the link carries no host.
    @Test func anUnknownPidResolvesToNothing() {
        var table = HostTable()
        table.record(pid: 501, bundleID: "net.whatsapp.WhatsApp")
        #expect(table.bundleID(forHostPid: 999) == nil)
    }

    // A pid takes the newest bundle id seen for it (a stale reading is corrected on the next harvest).
    @Test func aPidTakesItsNewestBundleID() {
        var table = HostTable()
        table.record(pid: 501, bundleID: "com.apple.Spotlight")
        table.record(pid: 501, bundleID: "net.whatsapp.WhatsApp")
        #expect(table.bundleID(forHostPid: 501) == "net.whatsapp.WhatsApp")
    }

    // Only the last 32 pids stay (the table only feeds the log's pair count): the oldest goes first, and a pid seen again
    // counts as the newest.
    @Test func theTableKeepsTheLast32Pids() {
        var table = HostTable()
        for pid in 1...40 { table.record(pid: pid, bundleID: "app.\(pid)") }
        #expect(table.count == 32)
        #expect(table.bundleID(forHostPid: 8) == nil) // the oldest 8 went
        #expect(table.bundleID(forHostPid: 9) == "app.9")
        table.record(pid: 9, bundleID: "app.9") // seen again: now the newest
        table.record(pid: 41, bundleID: "app.41")
        #expect(table.count == 32)
        #expect(table.bundleID(forHostPid: 9) == "app.9")
        #expect(table.bundleID(forHostPid: 10) == nil) // the oldest went instead
    }

    @Test func emptyOrNonPositivePairsAreIgnored() {
        var table = HostTable()
        table.record(pid: 0, bundleID: "net.whatsapp.WhatsApp")
        table.record(pid: -1, bundleID: "net.whatsapp.WhatsApp")
        table.record(pid: 501, bundleID: "")
        #expect(table.count == 0)
    }

    // The trust decision: the only bundle id ever returned is the one harvested this tap, never a value already sitting
    // in the table for hostPid. This is what closes the reused-pid opening.
    @Test func aFreshMatchingPairTrustsItsBundleID() {
        var table = HostTable()
        #expect(table.trust((pid: 501, bundleID: "net.whatsapp.WhatsApp"), hostPid: 501) == "net.whatsapp.WhatsApp")
    }

    // A pid reused by a different app: the table still holds an earlier app's bundle id for hostPid, but this tap
    // harvested a different pid, so the decision never falls back to that entry.
    @Test func aDifferentPidWhileTheTableStillHoldsHostPidTrustsNothing() {
        var table = HostTable()
        _ = table.trust((pid: 501, bundleID: "net.whatsapp.WhatsApp"), hostPid: 501)
        #expect(table.trust((pid: 733, bundleID: "com.apple.MobileSMS"), hostPid: 501) == nil)
        #expect(table.bundleID(forHostPid: 501) == "net.whatsapp.WhatsApp") // untouched, but never consulted for the decision
    }

    @Test func aFailedHarvestTrustsNothing() {
        var table = HostTable()
        #expect(table.trust(nil, hostPid: 501) == nil)
    }

    // The pid this tap harvested matches hostPid, but the pair itself is rejected (an empty bundle id): the decision
    // must not fall back to the bundle id already recorded for that same pid from an earlier, valid tap.
    @Test func aRejectedPairForAPidAlreadyInTheTableTrustsNothing() {
        var table = HostTable()
        _ = table.trust((pid: 501, bundleID: "net.whatsapp.WhatsApp"), hostPid: 501)
        #expect(table.trust((pid: 501, bundleID: ""), hostPid: 501) == nil)
    }
}
