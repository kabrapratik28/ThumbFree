import Foundation
import TFCore
import UIKit
import UniformTypeIdentifiers

/// The keyboard's side of a take (docs/contract.md: IPC and Delivery): it writes press
/// and release commands, opens ThumbFree when no session takes the press, and types the take's text once, only into
/// the field the press pinned. Everything else is offered with Insert here, Copy and Dismiss.
@MainActor @Observable final class KeyboardClient {
    /// A take's text the keyboard did not type by itself.
    struct Chip: Equatable {
        let item: OutboxItem
        /// Insert here only when nothing may have landed; after an unverified write it could type the text twice.
        let canInsert: Bool
        /// Short: the bar shares its width with Insert here, Copy, the dismiss button and the mic.
        var text: String { canInsert ? "Not typed in." : "The text may not have arrived." }
        /// What VoiceOver says when the chip comes up. The drawn line is short to fit the bar; this one also says what to tap.
        var announcement: String { canInsert ? "Not typed in. Tap Insert here or Copy." : text }
    }

    enum Opening { case no, opening, failed }

    static let confirmWait: Duration = .milliseconds(150)

    private(set) var status: HostStatus?
    private(set) var opening = Opening.no
    private(set) var chip: Chip?
    private(set) var pressing = false
    var fullAccess = true
    /// The trusted host app's bundle id for automatic return (the controller resolves it from the arbiter at the mic tap,
    /// only when the setting is on and it trusts the pid). nil means the link carries no host and the app shows swipe back.
    var hostBundleID: String?

    private let shared: SharedStore?
    private let open: (URL, @escaping @MainActor (Bool) -> Void) -> Void
    private var activeTake: UUID?
    private var delivered: Set<UUID> = []
    /// When this keyboard last came on screen (`appeared()`).
    private var shownAt = Date.distantPast

    /// `shared` is nil without the App Group (no Full Access). `open` opens a URL from the keyboard (AppOpener).
    init(shared: SharedStore?, open: @escaping (URL, @escaping @MainActor (Bool) -> Void) -> Void) {
        self.shared = shared
        self.open = open
    }

    var state: KeyState { KeyState.from(status, take: activeTake, opening: opening, fullAccess: fullAccess, now: Date()) }

    /// Mic key down: writes the press with this field (it pins a take the press starts, and re-pins the take that
    /// opened the app when this press stops it), and opens ThumbFree when no session takes it within 150 ms. With no
    /// speech model (the app's status says noModel), it only opens ThumbFree where the model is offered.
    func pressDown(_ proxy: UITextDocumentProxy) {
        pressing = true
        guard fullAccess, shared != nil else { return }
        status = try? shared?.status()
        chip = nil
        opening = .no
        // No speech model: a take could only fail. ThumbFree opens on its Try tab, which offers the download.
        if status?.engine == .noModel, status?.liveTake(now: Date()) == nil, let url = DictateLink.model {
            activeTake = nil
            open(url) { [weak self] opened in
                if !opened { self?.opening = .failed }
            }
            return
        }
        let press = KeyboardCommand(takeID: status?.liveTake(now: Date()) ?? UUID(), kind: .press, target: Self.target(of: proxy))
        activeTake = press.takeID
        guard write(press) else { return }
        Task {
            try? await Task.sleep(for: Self.confirmWait)
            openIfUnhandled(press)
        }
    }

    /// Mic key up (also when the keyboard goes away mid-press), with this field: a hold's release can be the stop of the
    /// take that opened the app, whose text then goes here. Once per press: the keyboard going away and the gesture's
    /// reset can both report the same lift.
    func pressUp(_ proxy: UITextDocumentProxy) {
        guard pressing else { return }
        pressing = false
        // While its link opens ThumbFree, the app makes its own tap for the take. A release now (the app switch hides the
        // keyboard) could end the take the waiting press starts as a hold released before any audio.
        guard let take = activeTake, opening != .opening else { return }
        write(KeyboardCommand(takeID: take, kind: .release, target: Self.target(of: proxy)))
    }

    /// The app's status changed (a Darwin notification), or the keyboard appeared.
    func refresh(_ proxy: UITextDocumentProxy) {
        status = try? shared?.status()
        if let status, status.takeID == activeTake, status.take != .idle { opening = .no }
        // The stale-link rule can open ThumbFree for a take that is already dead: adopt the fresh one the app now runs,
        // or opening sticks and deliverIfDue (which needs take == activeTake) never offers this take's chip or text.
        if opening == .opening, let live = status?.liveTake(now: Date()) { activeTake = live; opening = .no }
        // Delete or Transcribe again since the chip showed leaves no outbox item for its take: the chip goes too, not
        // just Insert here's reread.
        if let chip, !((try? shared?.outbox()) ?? []).contains(where: { $0.takeID == chip.item.takeID }) {
            self.chip = nil
        }
        deliverIfDue(proxy)
    }

    /// Whole seconds the take has been recording, from the start the app's status carries (`HostStatus.takeStartedAt`, on
    /// the wall clock both processes read): every keyboard, in any app, shows the same time for the same take. 0 without
    /// a start (an older app).
    func recordingSeconds(now: Date) -> Int {
        guard let start = status?.takeStartedAt else { return 0 }
        return max(0, Int(now.timeIntervalSince(start)))
    }

    /// The app deletes a press once it handles it. Still on disk after 150 ms: no session took it, so open ThumbFree with
    /// the take's link (the press stays for the app for up to 30 s). The status is no signal here: a short take can be
    /// over by then.
    func openIfUnhandled(_ press: KeyboardCommand) {
        guard activeTake == press.takeID, (try? shared?.pendingCommands())?.contains(where: { $0.id == press.id }) == true,
              let url = DictateLink.url(take: press.takeID, host: hostBundleID) else { return }
        opening = .opening
        open(url) { [weak self] opened in
            if !opened { self?.opening = .failed }
        }
    }

    /// Types the chip's take as the outbox has it now: a take deleted or transcribed again in History has no item left,
    /// so nothing stale is typed.
    func insertHere(_ proxy: UITextDocumentProxy) {
        guard let chip, chip.canInsert else { return }
        self.chip = nil
        guard let item = (try? shared?.outbox())?.last(where: { $0.takeID == chip.item.takeID }),
              [.pending, .heldBack].contains(item.state) else { return }
        insert(item, into: proxy)
    }

    /// Copies the take's text as the outbox has it now, for this device only. It runs in the keyboard: iOS refuses
    /// clipboard writes from a backgrounded app. A take deleted or transcribed again in History has no item left,
    /// so nothing stale is copied.
    func copy() {
        guard let chip else { return }
        self.chip = nil
        guard let item = (try? shared?.outbox())?.last(where: { $0.takeID == chip.item.takeID }) else { return }
        UIPasteboard.general.setItems([[UTType.plainText.identifier: item.text]], options: [.localOnly: true])
    }

    func dismiss() { chip = nil }

    /// The keyboard came on screen. A take pinned before may belong to another field that reads the same: a web page
    /// keeps one identifier for all its fields, even while the keyboard is away, so the page's empty boxes look alike.
    func appeared() { shownAt = Date() }

    // MARK: Delivery

    private func deliverIfDue(_ proxy: UITextDocumentProxy) {
        guard let status, status.take == .delivering, let take = status.takeID, take == activeTake,
              !delivered.contains(take), let item = (try? shared?.outbox())?.last(where: { $0.takeID == take }),
              item.state == .pending else { return }
        delivered.insert(take)
        // The host never stages blank text. Typing nothing is no delivery, and there is nothing to insert or copy.
        guard !item.text.allSatisfy(\.isWhitespace) else {
            write(.insertionHeldBack, take)
            return
        }
        // Typed by itself only into the field a command pinned since this keyboard came on screen, and that still reads the
        // same. Anything else could be another field that looks identical, such as a second empty box.
        guard let target = item.target, let pinnedAt = item.pinnedAt, pinnedAt >= shownAt,
              target == Self.target(of: proxy) else {
            write(.insertionHeldBack, take)
            chip = Chip(item: item, canInsert: true)
            return
        }
        insert(item, into: proxy)
    }

    /// One write. insertionBegan is on disk before insertText; the read-back then decides confirmed or unverified.
    /// Confirmed only when the text before the cursor changed and ends with the payload, as much of it as iOS shows
    /// (often only the end), and that is at least 16 characters or all of a shorter payload. A field that already ended
    /// with the same text is no proof.
    private func insert(_ item: OutboxItem, into proxy: UITextDocumentProxy) {
        guard write(.insertionBegan, item.takeID) else {
            chip = Chip(item: item, canInsert: true)
            return
        }
        let before = proxy.documentContextBeforeInput ?? "" // iOS reports an empty side as nil
        let payload = CursorFormatter.payload(text: item.text, before: before, after: proxy.documentContextAfterInput ?? "",
                                              capsExpected: FieldTraits.capsExpected(proxy.autocapitalizationType, before: before),
                                              field: FieldTraits.field(proxy.keyboardType), trailingSpace: false)
        proxy.insertText(payload)
        let now = proxy.documentContextBeforeInput ?? ""
        let seen = min(now.count, payload.count)
        let landed = now != before && seen >= min(payload.count, 16) && now.hasSuffix(String(payload.suffix(seen)))
        write(landed ? .insertionConfirmed : .insertionUnverified, item.takeID)
        if !landed { chip = Chip(item: item, canInsert: false) }
    }

    @discardableResult
    private func write(_ kind: KeyboardCommand.Kind, _ take: UUID) -> Bool {
        write(KeyboardCommand(takeID: take, kind: kind))
    }

    @discardableResult
    private func write(_ command: KeyboardCommand) -> Bool {
        guard let shared, (try? shared.append(command)) != nil else { return false }
        DarwinObserver.post(DarwinName.command)
        return true
    }

    /// The field at the cursor, or nil when it reports no identifier (between two fields, while iOS resets the keyboard's
    /// document, or an app that gives none): the context hash alone cannot tell such fields apart (two empty boxes hash
    /// alike), so no take is pinned to them.
    static func target(of proxy: UITextDocumentProxy) -> InsertTarget? {
        guard let documentID = FieldTraits.documentID(of: proxy) else { return nil }
        return InsertTarget(documentID: documentID,
                            contextHash: InsertTarget.contextHash(before: proxy.documentContextBeforeInput, after: proxy.documentContextAfterInput))
    }
}
