import Foundation
import Testing
import TFCore
import UIKit
import UniformTypeIdentifiers
@testable import ThumbFree

/// A text field the keyboard types into. Like iOS, an empty side of the cursor reads as nil.
@MainActor final class FakeProxy: NSObject, UITextDocumentProxy {
    var text = ""
    var dropsInserts = false
    /// Like iOS in many apps, only the last `window` characters before the cursor can be read (nil: all of them).
    var window: Int?
    /// Runs when insertText is called, before the text lands.
    var willInsert: () -> Void = {}
    var documentIdentifier = UUID()
    /// false: the field reports no `documentIdentifier` (between two fields, or an app that gives none), so
    /// `FieldTraits.documentID` reads nil.
    nonisolated(unsafe) var hasDocumentID = true // read by `responds(to:)`, which NSObject makes nonisolated
    var keyboardType: UIKeyboardType = .default
    var autocapitalizationType: UITextAutocapitalizationType = .sentences
    var documentContextBeforeInput: String? {
        let readable = window.map { String(text.suffix($0)) } ?? text
        return readable.isEmpty ? nil : readable
    }
    var documentContextAfterInput: String? { nil }
    var selectedText: String? { nil }
    var documentInputMode: UITextInputMode? { nil }
    var hasText: Bool { !text.isEmpty }
    func insertText(_ string: String) {
        willInsert()
        if !dropsInserts { text += string }
    }
    func deleteBackward() { if !text.isEmpty { text.removeLast() } }
    func adjustTextPosition(byCharacterOffset offset: Int) {}
    func setMarkedText(_ markedText: String, selectedRange: NSRange) {}
    func unmarkText() {}
    override func responds(to selector: Selector!) -> Bool {
        selector == NSSelectorFromString("documentIdentifier") ? hasDocumentID : super.responds(to: selector)
    }
}

@MainActor @Suite final class KeyboardClientTests {
    let root: URL
    let shared: SharedStore
    let proxy = FakeProxy()

    init() throws {
        root = try TestFiles.folder()
        shared = SharedStore(directory: root)
    }

    deinit { try? FileManager.default.removeItem(at: root) }

    func client(openResult: Bool = true, record: @escaping (URL) -> Void = { _ in }) -> KeyboardClient {
        KeyboardClient(shared: shared) { url, done in
            record(url)
            done(openResult)
        }
    }

    func kinds() throws -> [KeyboardCommand.Kind] { try shared.pendingCommands().map(\.kind) }

    /// The app's side: the take is waiting for the keyboard, with its text in the outbox. `pinnedAt`: when the keyboard
    /// sent the command whose target this is (by default just now, on screen).
    func appDelivers(_ take: UUID, target: InsertTarget?, pinnedAt: Date? = Date(), text: String = "hello world") throws {
        try shared.write([OutboxItem(takeID: take, text: text, target: target, pinnedAt: pinnedAt)])
        try shared.write(HostStatus(session: .ready, engine: .readyCPU, micOn: true, takeID: take, take: .delivering))
    }

    // Both carry the field: the press pins a take it starts; either can be the stop that re-pins a cold take.
    @Test func aPressAndItsReleaseCarryTheFieldAndNameTheSameTake() throws {
        let client = client()
        proxy.text = "Hi"
        client.pressDown(proxy)
        client.pressUp(proxy)
        client.pressUp(proxy) // the same lift reported twice (the keyboard going away, then the gesture's reset)
        let commands = try shared.pendingCommands()
        #expect(commands.map(\.kind) == [.press, .release])
        #expect(commands.map(\.target) == [KeyboardClient.target(of: proxy), KeyboardClient.target(of: proxy)])
        #expect(commands[0].takeID == commands[1].takeID)
    }

    // Nothing handled the press (it is still on disk): the keyboard opens ThumbFree with the take's link.
    @Test func aPressNoSessionTookOpensThumbFree() throws {
        var urls: [URL] = []
        let client = client(openResult: false) { urls.append($0) }
        client.pressDown(proxy)
        let press = try #require(try shared.pendingCommands().first)
        client.openIfUnhandled(press)
        #expect(urls == [DictateLink.url(take: press.takeID)].compactMap { $0 })
        #expect(client.state == .openFailed) // the open failed: "Open ThumbFree to start."
        #expect(try kinds() == [.press]) // the press stays for the app for up to 30 s
    }

    // hostBundleID (set by the controller from the arbiter) rides into the opened link; nil (untrusted, or the setting
    // off) carries no host at all.
    @Test func theOpenedLinkCarriesTheTrustedHostOrNone() throws {
        var urls: [URL] = []
        let client = client(openResult: false) { urls.append($0) }
        client.hostBundleID = "net.whatsapp.WhatsApp"
        client.pressDown(proxy)
        let press = try #require(try shared.pendingCommands().first)
        client.openIfUnhandled(press)
        #expect(DictateLink.host(from: try #require(urls.first)) == "net.whatsapp.WhatsApp")

        urls = []
        client.hostBundleID = nil
        client.pressDown(proxy)
        let secondPress = try #require(try shared.pendingCommands().last)
        client.openIfUnhandled(secondPress)
        #expect(DictateLink.host(from: try #require(urls.first)) == nil)
    }

    // No speech model: the mic opens ThumbFree where the model is offered, and writes no press or release.
    @Test func withNoModelTheMicOpensThumbFreeAndStartsNoTake() throws {
        try shared.write(HostStatus(engine: .noModel))
        var urls: [URL] = []
        let client = client { urls.append($0) }
        client.refresh(proxy)
        #expect(client.state == .needsModel)
        client.pressDown(proxy)
        client.pressUp(proxy)
        #expect(urls == [DictateLink.model].compactMap { $0 })
        #expect(urls.allSatisfy(DictateLink.isModel))
        #expect(!DictateLink.isModel(try #require(DictateLink.url(take: UUID()))))
        #expect(try kinds().isEmpty)
    }

    // A hold that opens ThumbFree lifts at the app switch. The link makes its own tap for the take, and a release now
    // could end the take the waiting press starts as a hold released before any audio.
    @Test func aKeyUpWhileThumbFreeOpensWritesNoRelease() throws {
        let client = client()
        client.pressDown(proxy)
        let press = try #require(try shared.pendingCommands().first)
        client.openIfUnhandled(press)
        #expect(client.state == .opening)
        client.pressUp(proxy)
        #expect(try kinds() == [.press])
    }

    // The stale-link rule can open ThumbFree for a take that is already dead. If a fresh take then delivers before this
    // keyboard presses again, refresh must adopt it as the active take, or opening sticks on "Opening ThumbFree" and
    // deliverIfDue (which needs take == activeTake) never offers the chip or types the text.
    @Test func refreshAdoptsAFreshLiveTakeAfterOpeningForADeadOne() throws {
        let client = client()
        client.pressDown(proxy)
        let press = try #require(try shared.pendingCommands().first)
        client.openIfUnhandled(press)
        #expect(client.state == .opening)
        let fresh = UUID()
        try appDelivers(fresh, target: KeyboardClient.target(of: proxy))
        client.refresh(proxy)
        #expect(client.opening == .no)
        #expect(proxy.text == "Hello world")
    }

    // The app deleted the press (it handled it): no link, even if the take is already over by then.
    @Test func aPressTheAppTookDoesNotOpenAndListensOnceAudioFlows() throws {
        var urls: [URL] = []
        let client = client { urls.append($0) }
        client.pressDown(proxy)
        let press = try #require(try shared.pendingCommands().first)
        try shared.remove(press)
        client.openIfUnhandled(press)
        #expect(urls.isEmpty)
        let take = press.takeID
        try shared.write(HostStatus(session: .starting, takeID: take, take: .recording))
        client.refresh(proxy)
        #expect(client.state == .starting)
        try shared.write(HostStatus(session: .ready, micOn: true, takeID: take, take: .recording))
        client.refresh(proxy)
        #expect(client.state == .listening)
    }

    // The take's time is the take's: it counts from the start the app's status carries, so every keyboard shows the same
    // time for the same take, one made later too (another app's, or this one made again after an app switch). A status
    // with no start (an older app) shows 0:00, and a clock set back never shows a time below it.
    @Test func theRecordingTimeCountsFromTheTakesStart() throws {
        let take = UUID(), start = Date(timeIntervalSinceNow: -20) // 20 s in when these keyboards first see it
        try shared.write(HostStatus(session: .ready, micOn: true, takeID: take, take: .recording, takeStartedAt: start))
        let first = client()
        first.refresh(proxy)
        #expect(first.recordingSeconds(now: start + 7.5) == 7)
        let later = client()
        later.refresh(proxy)
        #expect(later.recordingSeconds(now: start + 65.5) == 65)
        #expect(later.recordingSeconds(now: start - 3) == 0)
        try shared.write(HostStatus(session: .ready, micOn: true, takeID: take, take: .recording))
        later.refresh(proxy)
        #expect(later.recordingSeconds(now: start + 9) == 0)
    }

    @Test func theTextIsTypedOnceIntoTheSameField() throws {
        let client = client()
        client.pressDown(proxy)
        let press = try #require(try shared.pendingCommands().first)
        try appDelivers(press.takeID, target: press.target)
        client.refresh(proxy)
        #expect(proxy.text == "Hello world") // capital at the start of an empty field
        #expect(try kinds() == [.press, .insertionBegan, .insertionConfirmed])
        client.refresh(proxy) // a second notification types nothing more
        #expect(proxy.text == "Hello world")
        #expect(try kinds() == [.press, .insertionBegan, .insertionConfirmed])
        #expect(client.chip == nil)
    }

    @Test func anotherFieldIsHeldBackUntilInsertHere() throws {
        let client = client()
        client.pressDown(proxy)
        let press = try #require(try shared.pendingCommands().first)
        proxy.documentIdentifier = UUID() // the user moved to another field
        try appDelivers(press.takeID, target: press.target)
        client.refresh(proxy)
        #expect(proxy.text.isEmpty)
        #expect(try kinds() == [.press, .insertionHeldBack])
        #expect(client.chip?.canInsert == true)
        #expect(client.chip?.text == "Not typed in.")
        #expect(client.chip?.announcement == "Not typed in. Tap Insert here or Copy.") // VoiceOver hears what to tap
        client.insertHere(proxy)
        #expect(proxy.text == "Hello world")
        #expect(try kinds() == [.press, .insertionHeldBack, .insertionBegan, .insertionConfirmed])
    }

    // Two empty fields that report no document look the same (an empty context hashes alike): a take pressed in one chat's
    // empty box is never typed by itself into another chat's, only offered with Insert here.
    @Test func anotherEmptyFieldWithNoDocumentIsHeldBack() throws {
        let client = client()
        proxy.hasDocumentID = false
        client.pressDown(proxy)
        let press = try #require(try shared.pendingCommands().first)
        let other = FakeProxy() // the user moved to another empty field, also with no document
        other.hasDocumentID = false
        try appDelivers(press.takeID, target: press.target)
        client.refresh(other)
        #expect(other.text.isEmpty)
        #expect(try kinds() == [.press, .insertionHeldBack])
        #expect(client.chip?.canInsert == true)
        client.insertHere(other)
        #expect(other.text == "Hello world")
    }

    // A web page keeps one identifier for all its fields, even while the keyboard is away: back on screen, another empty
    // box on the page reads exactly like the one the take was pressed in, so a take pinned before is only offered.
    @Test func aTakePinnedBeforeTheKeyboardCameBackIsHeldBack() throws {
        let client = client()
        client.pressDown(proxy)
        let press = try #require(try shared.pendingCommands().first)
        client.appeared() // Done hid the keyboard; the user tapped the page's other empty box
        try appDelivers(press.takeID, target: press.target, pinnedAt: press.sentAt)
        client.refresh(proxy)
        #expect(proxy.text.isEmpty)
        #expect(try kinds() == [.press, .insertionHeldBack])
        #expect(client.chip?.canInsert == true)
    }

    // An outbox item from before the pin (no `pinnedAt`) is never typed by itself, even into the same field: only offered.
    @Test func anItemWithoutAPinIsHeldBack() throws {
        let client = client()
        client.pressDown(proxy)
        let press = try #require(try shared.pendingCommands().first)
        try appDelivers(press.takeID, target: press.target, pinnedAt: nil)
        client.refresh(proxy)
        #expect(proxy.text.isEmpty)
        #expect(try kinds() == [.press, .insertionHeldBack])
        #expect(client.chip?.canInsert == true)
    }

    // A warm take pressed once the keyboard is on screen is pinned by that press, after `appeared()`: it types by itself.
    // (The other typed tests pin at delivery time and never call `appeared()`, so they pass the time check trivially.)
    @Test func aTakePressedSinceTheKeyboardCameOnScreenIsTyped() throws {
        let client = client()
        client.appeared()
        client.pressDown(proxy)
        let press = try #require(try shared.pendingCommands().first)
        try appDelivers(press.takeID, target: press.target, pinnedAt: press.sentAt)
        client.refresh(proxy)
        #expect(proxy.text == "Hello world")
        #expect(try kinds() == [.press, .insertionBegan, .insertionConfirmed])
        #expect(client.chip == nil)
    }

    // A take deleted (or transcribed again) in History since the chip showed has no outbox item: Insert here types nothing.
    @Test func insertHereTypesNothingForATakeHistoryDropped() throws {
        let client = client()
        client.pressDown(proxy)
        let press = try #require(try shared.pendingCommands().first)
        proxy.documentIdentifier = UUID() // the user moved to another field
        try appDelivers(press.takeID, target: press.target)
        client.refresh(proxy)
        #expect(client.chip?.canInsert == true)
        try shared.write([OutboxItem]()) // History deleted the take
        client.insertHere(proxy)
        #expect(proxy.text.isEmpty)
        #expect(try kinds() == [.press, .insertionHeldBack])
        #expect(client.chip == nil)
    }

    // Insert here's pending-or-heldBack filter also rejects a typed item: the app already reported it landed, so
    // typing it again would double it.
    @Test func insertHereTypesNothingForATypedItem() throws {
        let client = client()
        client.pressDown(proxy)
        let press = try #require(try shared.pendingCommands().first)
        proxy.documentIdentifier = UUID() // the user moved to another field
        try appDelivers(press.takeID, target: press.target)
        client.refresh(proxy)
        #expect(client.chip?.canInsert == true)
        try shared.write([OutboxItem(takeID: press.takeID, text: "hello world", target: press.target, state: .typed)])
        client.insertHere(proxy)
        #expect(proxy.text.isEmpty)
    }

    // ...and an unverified one: the app is not sure it landed, so Insert here still leaves it to Copy instead.
    @Test func insertHereTypesNothingForAnUnverifiedItem() throws {
        let client = client()
        client.pressDown(proxy)
        let press = try #require(try shared.pendingCommands().first)
        proxy.documentIdentifier = UUID() // the user moved to another field
        try appDelivers(press.takeID, target: press.target)
        client.refresh(proxy)
        #expect(client.chip?.canInsert == true)
        try shared.write([OutboxItem(takeID: press.takeID, text: "hello world", target: press.target, state: .unverified)])
        client.insertHere(proxy)
        #expect(proxy.text.isEmpty)
    }

    // A take Delete or Transcribe again dropped since the chip showed has no outbox item left: refresh drops the
    // chip too (not just Insert here's reread).
    @Test func refreshDropsTheChipForATakeHistoryDropped() throws {
        let client = client()
        client.pressDown(proxy)
        let press = try #require(try shared.pendingCommands().first)
        proxy.documentIdentifier = UUID() // the user moved to another field
        try appDelivers(press.takeID, target: press.target)
        client.refresh(proxy)
        #expect(client.chip?.canInsert == true)
        try shared.write([OutboxItem]()) // History deleted the take
        client.refresh(proxy)
        #expect(client.chip == nil)
    }

    // Copy also checks the outbox before it copies: once the take's item is gone, it copies nothing, not the
    // chip's stale text.
    @Test func copyCopiesNothingForATakeHistoryDropped() throws {
        let client = client()
        client.pressDown(proxy)
        let press = try #require(try shared.pendingCommands().first)
        proxy.documentIdentifier = UUID() // the user moved to another field
        try appDelivers(press.takeID, target: press.target)
        client.refresh(proxy)
        #expect(client.chip?.canInsert == true)
        try shared.write([OutboxItem]()) // History deleted the take, but the chip has not refreshed yet
        UIPasteboard.general.setItems([[UTType.plainText.identifier: "sentinel"]], options: [.localOnly: true])
        client.copy()
        #expect(UIPasteboard.general.string == "sentinel") // the stale text never reached the pasteboard
    }

    @Test func aWriteThatDoesNotShowIsUnverifiedAndOnlyCopied() throws {
        let client = client()
        client.pressDown(proxy)
        let press = try #require(try shared.pendingCommands().first)
        proxy.dropsInserts = true
        var inserts = 0
        proxy.willInsert = { inserts += 1 }
        try appDelivers(press.takeID, target: press.target)
        client.refresh(proxy)
        #expect(try kinds() == [.press, .insertionBegan, .insertionUnverified])
        #expect(client.chip?.canInsert == false)
        #expect(client.chip?.text == "The text may not have arrived.")
        #expect(client.chip?.announcement == "The text may not have arrived.")
        client.insertHere(proxy) // it could type the text twice, so it does nothing
        #expect(inserts == 1)
        #expect(try kinds() == [.press, .insertionBegan, .insertionUnverified])
    }

    // insertionBegan is on disk before insertText: if the keyboard dies mid-write, the app knows the text may be there.
    @Test func insertionBeganIsOnDiskBeforeTheTextIsTyped() throws {
        let client = client()
        client.pressDown(proxy)
        let press = try #require(try shared.pendingCommands().first)
        try appDelivers(press.takeID, target: press.target)
        var onDisk: [KeyboardCommand.Kind] = []
        proxy.willInsert = { [shared] in onDisk = (try? shared.pendingCommands().map(\.kind)) ?? [] }
        client.refresh(proxy)
        #expect(onDisk == [.press, .insertionBegan])
    }

    // iOS may show only the end of the text before the cursor: a long take is confirmed from the part it shows.
    @Test func aLongTextReadBackThroughAShortWindowIsConfirmed() throws {
        let client = client()
        proxy.window = 20
        client.pressDown(proxy)
        let press = try #require(try shared.pendingCommands().first)
        try appDelivers(press.takeID, target: press.target, text: AppEnvironment.fakeText)
        client.refresh(proxy)
        #expect(proxy.text.hasSuffix("ask not what your country can do for you"))
        #expect(try kinds() == [.press, .insertionBegan, .insertionConfirmed])
        #expect(client.chip == nil)
    }

    @Test func aDroppedLongTextReadBackThroughAShortWindowIsUnverified() throws {
        let client = client()
        proxy.window = 20
        proxy.dropsInserts = true
        client.pressDown(proxy)
        let press = try #require(try shared.pendingCommands().first)
        try appDelivers(press.takeID, target: press.target, text: AppEnvironment.fakeText)
        client.refresh(proxy)
        #expect(try kinds() == [.press, .insertionBegan, .insertionUnverified])
        #expect(client.chip?.canInsert == false)
    }

    // The same sentence dictated again: a dropped write into a field that already ends with it is not typed.
    @Test func aDroppedWriteIntoAFieldThatEndsWithTheSameTextIsUnverified() throws {
        let client = client()
        proxy.text = "Thanks. See you soon."
        proxy.dropsInserts = true
        client.pressDown(proxy)
        let press = try #require(try shared.pendingCommands().first)
        try appDelivers(press.takeID, target: press.target, text: "see you soon.") // typed as " See you soon."
        client.refresh(proxy)
        #expect(try kinds() == [.press, .insertionBegan, .insertionUnverified])
        #expect(client.chip?.canInsert == false)
    }

    // The host never stages blank text. If an item's text were blank anyway, typing nothing is not a delivery: it is
    // held back, with nothing to insert or copy.
    @Test func aBlankTextIsHeldBackNotTyped() throws {
        let client = client()
        client.pressDown(proxy)
        let press = try #require(try shared.pendingCommands().first)
        try appDelivers(press.takeID, target: press.target, text: " \n")
        client.refresh(proxy)
        #expect(proxy.text.isEmpty)
        #expect(try kinds() == [.press, .insertionHeldBack])
        #expect(client.chip == nil)
    }

    @Test func theStatusLineFollowsTheApp() {
        let take = UUID(), now = Date()
        func state(_ status: HostStatus?, take mine: UUID? = take, opening: KeyboardClient.Opening = .no, fullAccess: Bool = true) -> KeyState {
            KeyState.from(status, take: mine, opening: opening, fullAccess: fullAccess, now: now)
        }
        #expect(state(nil, fullAccess: false) == .needsFullAccess)
        #expect(state(nil, take: nil) == .startDictation)
        #expect(state(nil, opening: .opening) == .opening)
        #expect(state(nil, opening: .failed) == .openFailed)
        #expect(state(HostStatus(session: .ready, micOn: true, expiresAt: now + 60), take: nil) == .ready)
        #expect(state(HostStatus(session: .ready, micOn: true, expiresAt: now - 1), take: nil) == .startDictation)
        #expect(state(HostStatus(session: .starting, takeID: take, take: .recording), opening: .opening) == .opening)
        #expect(state(HostStatus(session: .ready, micOn: true, takeID: take, take: .recording)) == .listening)
        // Any live take shows, not only this keyboard's: a tap acts on it (pressDown names it), also in a keyboard made
        // after its press, which knows no take (the cold take's app switch can recreate the keyboard).
        #expect(state(HostStatus(session: .ready, micOn: true, takeID: UUID(), take: .recording)) == .listening)
        #expect(state(HostStatus(session: .starting, takeID: UUID(), take: .recording), take: nil) == .starting)
        #expect(state(HostStatus(session: .starting, takeID: UUID(), take: .recording, updatedAt: now - 6), take: nil) == .startDictation) // from an app that went away
        #expect(state(HostStatus(takeID: take, take: .transcribing)) == .transcribing)
        #expect(state(HostStatus(message: "No speech heard."), take: nil) == .message("No speech heard."))
    }
}
