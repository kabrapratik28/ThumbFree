import Foundation

/// The files the keyboards and the app share in the App Group container. Keyboards only create command files, each
/// with a unique name (`commands/<sentAt nanoseconds, 20 digits>-<id>.json`), so several keyboard processes never
/// collide. The app only writes status.json and outbox.json, and deletes command files after handling them. Every
/// write is atomic (Foundation writes a temporary file and renames it over the old one), so a reader never sees half a
/// file.
public struct SharedStore: Sendable {
    public static let outboxLimit = 20

    public let directory: URL
    private var commandsDirectory: URL { directory.appendingPathComponent("commands", isDirectory: true) }
    private var statusURL: URL { directory.appendingPathComponent("status.json") }
    private var outboxURL: URL { directory.appendingPathComponent("outbox.json") }

    public init(directory: URL) { self.directory = directory }

    /// Keyboards only. The same command twice is harmless: it is the same file.
    public func append(_ command: KeyboardCommand) throws {
        try ensureDirectoryExists()
        try FileManager.default.createDirectory(at: commandsDirectory, withIntermediateDirectories: true)
        let nanoseconds = UInt64(max(0, min(9e18, (command.sentAt.timeIntervalSince1970 * 1e9).rounded(.down))))
        let name = String(format: "%020llu", nanoseconds) + "-\(command.id.uuidString).json"
        try JSONEncoder().encode(command).write(to: commandsDirectory.appendingPathComponent(name), options: .atomic)
    }

    /// App only: every command not yet removed, by sentAt, then id. A file that does not decode is skipped. A command
    /// the app handled but did not remove (it crashed) comes back: handling must be idempotent.
    public func pendingCommands() throws -> [KeyboardCommand] {
        try commandFiles()
            .compactMap { try? JSONDecoder().decode(KeyboardCommand.self, from: Data(contentsOf: commandsDirectory.appendingPathComponent($0))) }
            .sorted { ($0.sentAt, $0.id.uuidString) < ($1.sentAt, $1.id.uuidString) }
    }

    /// App only, after handling the command. Removing it twice is harmless: a concurrent remove of the same command
    /// can list the file and then lose the delete race, which throws "no such file", treated as success.
    public func remove(_ command: KeyboardCommand) throws {
        for name in try commandFiles() where name.hasSuffix("-\(command.id.uuidString).json") {
            do {
                try FileManager.default.removeItem(at: commandsDirectory.appendingPathComponent(name))
            } catch let error as CocoaError where error.code == .fileNoSuchFile {
                // Another remove already deleted it first.
            }
        }
    }

    /// App only.
    public func write(_ status: HostStatus) throws {
        try ensureDirectoryExists()
        try JSONEncoder().encode(status).write(to: statusURL, options: .atomic)
    }

    /// Nil before the app's first write.
    public func status() throws -> HostStatus? {
        guard FileManager.default.fileExists(atPath: statusURL.path) else { return nil }
        return try JSONDecoder().decode(HostStatus.self, from: Data(contentsOf: statusURL))
    }

    /// App only. Keeps the newest 20 (newest last).
    public func write(_ outbox: [OutboxItem]) throws {
        try ensureDirectoryExists()
        try JSONEncoder().encode(Array(outbox.suffix(Self.outboxLimit))).write(to: outboxURL, options: .atomic)
    }

    public func outbox() throws -> [OutboxItem] {
        guard FileManager.default.fileExists(atPath: outboxURL.path) else { return [] }
        return try JSONDecoder().decode([OutboxItem].self, from: Data(contentsOf: outboxURL))
    }

    /// Command file names. Other names (a temporary file in the middle of a write) are not commands.
    private func commandFiles() throws -> [String] {
        guard FileManager.default.fileExists(atPath: commandsDirectory.path) else { return [] }
        return try FileManager.default.contentsOfDirectory(atPath: commandsDirectory.path)
            .filter { $0.hasSuffix(".json") && !$0.hasPrefix(".") }
    }

    /// Recordings and commands never leave the phone, not even in a backup (`HistoryStore`
    /// enforces the same thing for take audio). A per-directory flag, set the first time this store creates its
    /// directory: every atomic write swaps in a new file, which would drop a per-file flag, but the directory itself
    /// is never replaced, and iOS applies the flag to everything under it.
    private func ensureDirectoryExists() throws {
        guard !FileManager.default.fileExists(atPath: directory.path) else { return }
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        var url = directory
        try url.setResourceValues(values)
    }
}
