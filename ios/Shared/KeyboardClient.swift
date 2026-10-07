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
        forgetTake() // a new take: the last one can no longer be tidied
        guard fullAccess, shared != nil else { return }
        status = try? shared?.status()
        chip = nil
        opening = .no
        // No speech model: a take could only fail. ThumbFree opens on Home, which offers the download.
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
        readCleanup(proxy)
        followCursor(proxy)
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
        if landed, let documentID = FieldTraits.documentID(of: proxy) {
            typedTake = TypedTake(takeID: item.takeID, typed: payload, documentID: documentID)
            takeAtCursor = true
        }
    }

    // MARK: Clean up

    /// The take this keyboard typed last, which the sparkle may tidy: the text as it went in (`typed`), the field, and
    /// after a tidy the text it replaced (`original`, which Undo puts back).
    struct TypedTake: Equatable {
        let takeID: UUID
        var typed: String
        let documentID: UUID
        var original: String?
    }

    /// What the round button beside the mic shows: tidy, a request on its way (its spinner), or Undo.
    enum Sparkle: Equatable { case offer, working, undo }

    static let cleanupTimeout: TimeInterval = 25
    static let messageSeconds: TimeInterval = 4

    private(set) var typedTake: TypedTake?
    /// The typed take sits right before the cursor, in its field (`CleanupReplace.matches`), as of the last look.
    private(set) var takeAtCursor = false
    /// The style menu is open (a hold on the sparkle).
    private(set) var choosingStyle = false
    private var cleanRequest: (id: UUID, sentAt: Date)?
    private var cleanMessage: (text: String, until: Date)?
    /// Apple's rate limit: until when, and whether iOS said (else a moment, 5 s).
    private var pausedUntil: (date: Date, known: Bool)?
    /// The controller lays the keyboard out again when the style menu opens or closes: it makes the keyboard taller.
    var onStyleMenu: (() -> Void)?

    /// The round button, if any: only with Full Access, while ThumbFree's session is live (a status under 5 s old)
    /// and Clean up can run there, with no take going on, and the take this keyboard typed right before the cursor.
    func sparkle(now: Date) -> Sparkle? {
        guard fullAccess, let typedTake, takeAtCursor, !choosingStyle, let status, status.isFresh(now: now),
              status.session == .ready, status.cleanup == .ready, status.take == .idle else { return nil }
        if cleanRequest != nil { return .working }
        return typedTake.original == nil ? .offer : .undo
    }

    /// What the bar's status place says for Clean up, if anything: the wait, Apple's pause (counting down when iOS
    /// gave its end), or a short message after a try that left the words as they were.
    func cleanLine(now: Date) -> String? {
        if cleanRequest != nil { return CleanupWords.working }
        if let pausedUntil, pausedUntil.date > now {
            guard pausedUntil.known else { return CleanupWords.paused }
            return CleanupWords.paused(ready: KeyState.clock(Int(pausedUntil.date.timeIntervalSince(now).rounded(.up))))
        }
        if let cleanMessage, cleanMessage.until > now { return cleanMessage.text }
        return nil
    }

    /// The sparkle's tap (`style` nil: the default Settings keeps) or a style from the menu: asks the app, if the take
    /// is still right before the cursor in its field. During Apple's pause it only says so again.
    func tidy(style: CleanupStyle?, proxy: UITextDocumentProxy) {
        closeStyleMenu()
        guard let take = typedTake, take.original == nil, cleanRequest == nil else { return }
        if let pausedUntil, pausedUntil.date > Date() { return }
        guard atCursor(take, proxy) else { return leave(CleanupWords.changed) }
        let text = take.typed.trimmingCharacters(in: .whitespacesAndNewlines)
        let command = KeyboardCommand(takeID: take.takeID, kind: .clean, text: text, style: style)
        guard write(command) else { return say(CleanupWords.failed) }
        cleanMessage = nil
        cleanRequest = (command.id, command.sentAt)
    }

    /// Undo: the take as it was typed, back in place of the tidied words.
    func undoTidy(_ proxy: UITextDocumentProxy) {
        guard var take = typedTake, let original = take.original, cleanRequest == nil else { return }
        guard atCursor(take, proxy), replace(take.typed, with: original, take: take.takeID, proxy: proxy) else {
            return leave(CleanupWords.changed)
        }
        take.typed = original
        take.original = nil
        typedTake = take
    }

    func openStyleMenu() {
        guard sparkle(now: Date()) == .offer else { return }
        choosingStyle = true
        onStyleMenu?()
    }

    func closeStyleMenu() {
        guard choosingStyle else { return }
        choosingStyle = false
        onStyleMenu?()
    }

    /// A key typed, a new take or another field: the last take can no longer be tidied or undone.
    func forgetTake() {
        typedTake = nil
        takeAtCursor = false
        cleanRequest = nil
        closeStyleMenu()
    }

    /// Whether the take still sits right before the cursor in its field; another field forgets it.
    func followCursor(_ proxy: UITextDocumentProxy) {
        guard let take = typedTake else { return }
        guard FieldTraits.documentID(of: proxy) == take.documentID else { return forgetTake() }
        takeAtCursor = CleanupReplace.matches(before: proxy.documentContextBeforeInput ?? "", typed: take.typed)
    }

    /// The app's answer to this keyboard's request, or the request's timeout.
    private func readCleanup(_ proxy: UITextDocumentProxy) {
        guard let request = cleanRequest else { return }
        guard let result = (try? shared?.cleanups())?.last(where: { $0.requestID == request.id }) else {
            if Date().timeIntervalSince(request.sentAt) > Self.cleanupTimeout {
                cleanRequest = nil
                say(CleanupWords.failed)
            }
            return
        }
        cleanRequest = nil
        switch result.state {
        case .done:
            guard var take = typedTake, let cleaned = result.text else { return say(CleanupWords.failed) }
            guard atCursor(take, proxy) else { return leave(CleanupWords.changed) }
            let before = proxy.documentContextBeforeInput ?? ""
            let head = CleanupReplace.beforeTake(before: before, typed: take.typed)
            let caps = head.flatMap { FieldTraits.capsExpected(proxy.autocapitalizationType, before: $0) }
            var payload = CursorFormatter.payload(text: cleaned, before: head, after: proxy.documentContextAfterInput ?? "",
                                                  capsExpected: caps, field: FieldTraits.field(proxy.keyboardType),
                                                  trailingSpace: false)
            // iOS showed only the end of the take: keep the space it began with, which the formatter could not see.
            if head == nil { payload = String(take.typed.prefix(while: \.isWhitespace)) + payload }
            guard replace(take.typed, with: payload, take: take.takeID, proxy: proxy) else { return say(CleanupWords.failed) }
            take.original = take.typed
            take.typed = payload
            typedTake = take
        case .paused:
            pausedUntil = (result.resetAt ?? Date().addingTimeInterval(5), result.resetAt != nil)
        case .failed, .unavailable:
            say(CleanupWords.failed)
        }
    }

    /// The pin: the take's own field, with the take right before the cursor.
    private func atCursor(_ take: TypedTake, _ proxy: UITextDocumentProxy) -> Bool {
        FieldTraits.documentID(of: proxy) == take.documentID
            && CleanupReplace.matches(before: proxy.documentContextBeforeInput ?? "", typed: take.typed)
    }

    /// One replacement, as an insertion: the record on disk first, the take deleted one character at a time, the new
    /// text inserted, then read back. False only when the record could not be written (nothing changed then).
    private func replace(_ old: String, with new: String, take: UUID, proxy: UITextDocumentProxy) -> Bool {
        guard write(.cleanBegan, take) else { return false }
        for _ in 0..<old.count { proxy.deleteBackward() }
        proxy.insertText(new)
        let landed = CleanupReplace.matches(before: proxy.documentContextBeforeInput ?? "", typed: new)
        write(landed ? .cleanConfirmed : .cleanUnverified, take)
        takeAtCursor = landed
        return true
    }

    /// A short message in the status place; the take stays tidy-able.
    private func say(_ text: String) {
        cleanMessage = (text, Date().addingTimeInterval(Self.messageSeconds))
    }

    /// The text changed under the take: say so and forget it, so the sparkle goes.
    private func leave(_ text: String) {
        forgetTake()
        say(text)
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
