import Foundation

public enum TakeStatus: String, Codable, Sendable, CaseIterable {
    case recording, transcribing, staged, inserting
    case inserted, unverified, notInserted, noSpeech, cancelled, failed, interrupted, needsReview

    /// Recording, being transcribed, or its text on the way to the field. Never deleted; Recovery ends it after a crash.
    public var isLive: Bool { self == .recording || self == .transcribing || self == .staged || self == .inserting }
}

/// Any new field must be optional or decoded with a default: `all()` silently skips a take.json that fails to
/// decode, so a required field with no default would make every existing take disappear after an app update.
public struct TakeRecord: Codable, Sendable, Identifiable, Equatable {
    public let id: UUID
    /// Grows with every take: "newest" is by order, never by the clock.
    public let order: Int
    public let startedAt: Date
    public var durationMs: Int
    /// The model that made the current text.
    public var modelID: String
    public var status: TakeStatus
    /// Why the take ended, as a TakeMessage raw value (for example "audioMissing").
    public var error: String?
    public var rawText: String?
    public var text: String?
    public var partialText: String?
    public var chunksDone: Int
    public var insertedText: String?
    /// `text` came from Transcribe again and was never typed.
    public var retranscribed: Bool
    public var starred: Bool

    public init(id: UUID, order: Int, startedAt: Date, modelID: String, status: TakeStatus = .recording,
                durationMs: Int = 0, error: String? = nil, rawText: String? = nil, text: String? = nil,
                partialText: String? = nil, chunksDone: Int = 0, insertedText: String? = nil,
                retranscribed: Bool = false, starred: Bool = false) {
        self.id = id
        self.order = order
        self.startedAt = startedAt
        self.durationMs = durationMs
        self.modelID = modelID
        self.status = status
        self.error = error
        self.rawText = rawText
        self.text = text
        self.partialText = partialText
        self.chunksDone = chunksDone
        self.insertedText = insertedText
        self.retranscribed = retranscribed
        self.starred = starred
    }
}

public enum HistoryError: Error, Equatable {
    case exists(UUID)
    case missing(UUID)
}

/// History: one folder per take, `<root>/<uuid>/take.json` and `audio.wav`. No database, so no migrations; retention
/// deletes folders. take.json is written atomically. Not locked: the app's session host is its one caller.
public struct HistoryStore: Sendable {
    public let root: URL

    public init(root: URL) { self.root = root }

    public func folder(for id: UUID) -> URL { root.appendingPathComponent(id.uuidString, isDirectory: true) }
    public func audioURL(for id: UUID) -> URL { folder(for: id).appendingPathComponent("audio.wav") }
    private func recordURL(_ id: UUID) -> URL { folder(for: id).appendingPathComponent("take.json") }

    /// Makes the take's folder and take.json, before any audio. Throws `exists` when the take is already there.
    public func create(_ record: TakeRecord) throws {
        guard !FileManager.default.fileExists(atPath: recordURL(record.id).path) else { throw HistoryError.exists(record.id) }
        try ensureRootExists()
        try FileManager.default.createDirectory(at: folder(for: record.id), withIntermediateDirectories: true)
        try write(record)
    }

    /// Throws `missing` when the take is gone, so a lost take never reads as saved text.
    public func update(_ record: TakeRecord) throws {
        guard FileManager.default.fileExists(atPath: recordURL(record.id).path) else { throw HistoryError.missing(record.id) }
        try write(record)
    }

    public func record(_ id: UUID) throws -> TakeRecord? {
        guard FileManager.default.fileExists(atPath: recordURL(id).path) else { return nil }
        return try JSONDecoder().decode(TakeRecord.self, from: Data(contentsOf: recordURL(id)))
    }

    /// Every take, newest first by order. A folder whose take.json is missing or does not decode is skipped.
    public func all() throws -> [TakeRecord] {
        guard FileManager.default.fileExists(atPath: root.path) else { return [] }
        return try FileManager.default.contentsOfDirectory(atPath: root.path)
            .compactMap { UUID(uuidString: $0) }
            .compactMap { try? record($0) }
            .sorted { $0.order > $1.order }
    }

    /// The audio first, then the folder: a crash between the two leaves a take whose audio is missing, never audio that
    /// no take names. When the audio cannot be deleted this throws and the take stays, so the user can still find it.
    public func delete(_ id: UUID) throws {
        let files = FileManager.default
        if files.fileExists(atPath: audioURL(for: id).path) { try files.removeItem(at: audioURL(for: id)) }
        if files.fileExists(atPath: folder(for: id).path) { try files.removeItem(at: folder(for: id)) }
    }

    /// One past the highest order, or 1.
    public func nextOrder() throws -> Int { (try all().map(\.order).max() ?? 0) + 1 }

    /// Deletes what Retention picks. A take whose audio cannot be deleted stays for the next pass. Returns how many went.
    @discardableResult
    public func applyRetention(keepDays: Int?, keepCount: Int?, now: Date) throws -> Int {
        Retention.idsToDelete(try all(), keepDays: keepDays, keepCount: keepCount, now: now)
            .filter { (try? delete($0)) != nil }
            .count
    }

    private func write(_ record: TakeRecord) throws {
        try JSONEncoder().encode(record).write(to: recordURL(record.id), options: .atomic)
    }

    /// Recordings and text never leave the phone, not even in a backup. Set on every `create`,
    /// not just the first: `createDirectory` accepts an already-existing folder, so this stays cheap once the root
    /// exists, and the flag is never missed if something else made the root first (see
    /// `SharedStore.ensureDirectoryExists()`, the same pattern for the IPC directory).
    private func ensureRootExists() throws {
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        var url = root
        try url.setResourceValues(values)
    }
}
