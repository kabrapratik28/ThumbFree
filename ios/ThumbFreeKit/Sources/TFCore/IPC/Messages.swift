import CryptoKit
import Foundation

/// The take's phase as the keyboard is shown it (TakeState.phase).
public enum TakePhase: String, Codable, Sendable { case idle, recording, stopping, transcribing, delivering }

/// The text field a take started in, pinned at the press. Never holds the field's text.
public struct InsertTarget: Codable, Sendable, Equatable {
    /// `textDocumentProxy.documentIdentifier` at the press.
    public let documentID: UUID
    /// `contextHash(before:after:)` of the text around the cursor at the press.
    public let contextHash: String

    public init(documentID: UUID, contextHash: String) {
        self.documentID = documentID
        self.contextHash = contextHash
    }

    /// Lowercase hex SHA-256 of the 32 characters before the cursor, a zero byte, and the 32 characters after it.
    /// Unreadable context (nil) counts as empty.
    public static func contextHash(before: String?, after: String?) -> String {
        let bytes = Data((before ?? "").suffix(32).utf8) + [0] + Data((after ?? "").prefix(32).utf8)
        return SHA256.hash(data: bytes).map { String(format: "%02x", $0) }.joined()
    }
}

/// One command from a keyboard. Keyboards only create command files (one keyboard process per host app); the app
/// handles them by `sentAt`, then deletes them.
public struct KeyboardCommand: Codable, Sendable, Equatable {
    public enum Kind: String, Codable, Sendable {
        /// Key down and key up (the reducer applies the 300 ms tap-or-hold rule from `sentAt`), and the X button.
        case press, release, cancel
        /// Written durably before insertText.
        case insertionBegan
        /// insertText ran and the read-back shows the text.
        case insertionConfirmed
        /// insertText ran but the read-back could not confirm it.
        case insertionUnverified
        /// Not inserted: another field (documentID or contextHash differ), or no text proxy.
        case insertionHeldBack
        /// Asks the app to answer by writing status.json.
        case ping
        /// Clean up: asks the app to tidy `text`, the take as the keyboard typed it, in `style` (nil: the app's
        /// default). The app answers in cleanups.json under this command's id.
        case clean
        /// Clean up's replacement: written durably before the keyboard deletes the take; then whether the read-back
        /// shows the answer. Records only (the app logs them): nothing is retried.
        case cleanBegan, cleanConfirmed, cleanUnverified
    }

    public let id: UUID
    public let takeID: UUID
    public let kind: Kind
    /// Set on press, and for a cold take (the take whose press opened the app) also on the stop command.
    public let target: InsertTarget?
    public let sentAt: Date
    /// Clean up's request: the take's text as typed, and the style; nil for every other command.
    public let text: String?
    public let style: CleanupStyle?

    public init(id: UUID = UUID(), takeID: UUID, kind: Kind, target: InsertTarget? = nil, sentAt: Date = Date(),
                text: String? = nil, style: CleanupStyle? = nil) {
        self.id = id
        self.takeID = takeID
        self.kind = kind
        self.target = target
        self.sentAt = sentAt
        self.text = text
        self.style = style
    }
}

/// Whether Clean up can run now, as the app tells the keyboards: only `ready` shows the sparkle. `off`: Settings'
/// switch; the others are Apple Intelligence's states (`SystemLanguageModel.availability`, `supportsLocale()`).
public enum CleanupAvailability: String, Codable, Sendable {
    case ready, off, appleIntelligenceOff, notEligible, notReady, unsupportedLanguage
}

/// The app's answer to one clean command (`requestID` is the command's id). `done` carries the text that passed the
/// checks; `paused` is Apple's rate limit, with when it ends if iOS says (iOS 27); `failed` keeps the words as they
/// are; `unavailable` means Apple Intelligence could not run. The app is the only writer of cleanups.json.
public struct CleanupResult: Codable, Sendable, Equatable {
    public enum State: String, Codable, Sendable { case done, failed, paused, unavailable }

    public let requestID: UUID
    public let takeID: UUID
    public let state: State
    public let text: String?
    public let resetAt: Date?
    public let createdAt: Date

    public init(requestID: UUID, takeID: UUID, state: State, text: String? = nil, resetAt: Date? = nil,
                createdAt: Date = Date()) {
        self.requestID = requestID
        self.takeID = takeID
        self.state = state
        self.text = text
        self.resetAt = resetAt
        self.createdAt = createdAt
    }
}

public enum SessionPhase: String, Codable, Sendable { case off, starting, ready, ending }

/// `noModel`: no speech model on this iPhone yet (never downloaded, or it failed the launch check); keyboards offer to
/// get it instead of starting a take.
public enum EnginePhase: String, Codable, Sendable { case unloaded, loading, warming, readyNeuralEngine, readyCPU, failed, noModel }

/// What the app tells the keyboards. The app is the only writer of status.json.
public struct HostStatus: Codable, Sendable, Equatable {
    public var session: SessionPhase
    public var engine: EnginePhase
    public var micOn: Bool
    public var takeID: UUID?
    public var take: TakePhase
    /// When the live take began: at the press in a live session, else when the mic started (`SessionHost`'s arming clock),
    /// on the wall clock both processes read, as `updatedAt`. Every keyboard counts the take's time from it. nil with no
    /// take, and in a status from an app older than this field (it still decodes).
    public var takeStartedAt: Date?
    public var level: Float
    public var expiresAt: Date?
    public var updatedAt: Date
    /// User-facing outcome text, for example "No speech heard." (TakeMessage.text). Never transcript text.
    public var message: String?
    /// Whether Clean up can run; nil from an app without it, which shows no sparkle.
    public var cleanup: CleanupAvailability?

    public init(session: SessionPhase = .off, engine: EnginePhase = .unloaded, micOn: Bool = false, takeID: UUID? = nil,
                take: TakePhase = .idle, takeStartedAt: Date? = nil, level: Float = 0, expiresAt: Date? = nil,
                updatedAt: Date = Date(), message: String? = nil,
                cleanup: CleanupAvailability? = nil) {
        self.session = session
        self.engine = engine
        self.micOn = micOn
        self.takeID = takeID
        self.take = take
        self.takeStartedAt = takeStartedAt
        self.level = level
        self.expiresAt = expiresAt
        self.updatedAt = updatedAt
        self.message = message
        self.cleanup = cleanup
    }
}

/// A take's text on its way to a keyboard. The app is the only writer of outbox.json. The states follow the
/// Delivery table (DeliveryTable).
public struct OutboxItem: Codable, Sendable, Equatable {
    public enum State: String, Codable, Sendable { case pending, typed, unverified, heldBack }

    public let takeID: UUID
    public let text: String
    public let target: InsertTarget?
    /// The `sentAt` of the keyboard command whose target this is (the press, or a cold take's stop). A keyboard types the
    /// text by itself only if that command came since it last came on screen: a web page keeps one identifier for all
    /// its fields, even while the keyboard is away, so a field that reads the same afterwards may be another one.
    public let pinnedAt: Date?
    public var state: State
    public let createdAt: Date

    public init(takeID: UUID, text: String, target: InsertTarget?, pinnedAt: Date? = nil, state: State = .pending,
                createdAt: Date = Date()) {
        self.takeID = takeID
        self.text = text
        self.target = target
        self.pinnedAt = pinnedAt
        self.state = state
        self.createdAt = createdAt
    }
}

/// Darwin notifications carry no data: they only say "read the files again".
public enum DarwinName {
    public static let command = "io.github.kabrapratik28.thumbfree.command"
    public static let status = "io.github.kabrapratik28.thumbfree.status"
}
