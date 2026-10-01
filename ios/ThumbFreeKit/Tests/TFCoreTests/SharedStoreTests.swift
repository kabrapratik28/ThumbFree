import Foundation
import Testing
@testable import TFCore

@Suite final class SharedStoreTests {
    let dir = FileManager.default.temporaryDirectory.appendingPathComponent("SharedStoreTests-\(UUID().uuidString)")
    var store: SharedStore { SharedStore(directory: dir) }
    let take = UUID()
    let target = InsertTarget(documentID: UUID(), contextHash: InsertTarget.contextHash(before: "Hi ", after: nil))

    // Each test gets its own temporary folder (see `dir` above); remove it so runs never pile up in shared temp space.
    deinit { try? FileManager.default.removeItem(at: dir) }

    func command(_ seconds: Double, _ kind: KeyboardCommand.Kind = .press, id: UUID = UUID()) -> KeyboardCommand {
        KeyboardCommand(id: id, takeID: take, kind: kind, target: kind == .press ? target : nil,
                        sentAt: Date(timeIntervalSince1970: 1_790_000_000 + seconds))
    }

    // Keyboards write their files in any order; the app handles them by sentAt.
    @Test func commandsComeBackInSentAtOrder() throws {
        let late = command(3), early = command(1), middle = command(2)
        for command in [late, early, middle] { try store.append(command) }
        #expect(try store.pendingCommands() == [early, middle, late])
    }

    // Two commands sent at the same instant come back by id.
    @Test func theSameInstantSortsByID() throws {
        let a = command(1, id: UUID(uuidString: "00000000-0000-0000-0000-00000000000A") ?? UUID())
        let b = command(1, id: UUID(uuidString: "00000000-0000-0000-0000-00000000000B") ?? UUID())
        try store.append(b)
        try store.append(a)
        #expect(try store.pendingCommands() == [a, b])
    }

    // Two keyboard processes (one per host app) appending at the same instant never overwrite each other.
    @Test func twoKeyboardsAtTheSameInstantNeverCollide() async throws {
        let store = store
        let sentAt = Date(timeIntervalSince1970: 1_790_000_000)
        try await withThrowingTaskGroup(of: Void.self) { group in
            for _ in 0..<2 {
                group.addTask {
                    for _ in 0..<50 { try store.append(KeyboardCommand(id: UUID(), takeID: UUID(), kind: .ping, target: nil, sentAt: sentAt)) }
                }
            }
            try await group.waitForAll()
        }
        #expect(Set(try store.pendingCommands().map(\.id)).count == 100)
    }

    // A keyboard that writes the same command twice leaves one file.
    @Test func theSameCommandTwiceIsOneFile() throws {
        let press = command(1)
        try store.append(press)
        try store.append(press)
        #expect(try store.pendingCommands() == [press])
    }

    // The app removes a command after handling it. A crash before the remove replays it at the next read, and a
    // second remove is harmless.
    @Test func handledCommandsAreRemovedAndACrashReplaysThem() throws {
        let first = command(1), second = command(2, .release)
        try store.append(first)
        try store.append(second)
        try store.remove(first)
        #expect(try store.pendingCommands() == [second]) // handled but not removed yet: comes back
        #expect(try store.pendingCommands() == [second])
        try store.remove(second)
        try store.remove(second)
        #expect(try store.pendingCommands().isEmpty)
    }

    // Two concurrent removes of the same command: whichever loses the delete race sees "no such file", which is
    // harmless, not a thrown error.
    @Test func concurrentRemovesOfTheSameCommandNeverThrow() async throws {
        let store = store
        let press = command(1)
        try store.append(press)
        try await withThrowingTaskGroup(of: Void.self) { group in
            for _ in 0..<20 {
                group.addTask { try store.remove(press) }
            }
            try await group.waitForAll()
        }
        #expect(try store.pendingCommands().isEmpty)
    }

    @Test func fileNamesAreSentAtNanosecondsThenTheID() throws {
        let press = command(0)
        try store.append(press)
        #expect(try FileManager.default.contentsOfDirectory(atPath: dir.appendingPathComponent("commands").path)
                == ["01790000000000000000-\(press.id.uuidString).json"])
    }

    // One bad file never blocks the commands after it.
    @Test func aFileThatDoesNotDecodeIsSkipped() throws {
        let press = command(2)
        try store.append(press)
        try Data("{".utf8).write(to: dir.appendingPathComponent("commands/01790000000100000000-\(UUID().uuidString).json"))
        #expect(try store.pendingCommands() == [press])
    }

    @Test func statusRoundTripsAndIsNilBeforeTheFirstWrite() throws {
        #expect(try store.status() == nil)
        let status = HostStatus(session: .ready, engine: .readyNeuralEngine, micOn: true, takeID: take, take: .recording,
                                takeStartedAt: Date(timeIntervalSince1970: 1_789_999_993.25), level: 0.4,
                                expiresAt: Date(timeIntervalSince1970: 1_790_000_600),
                                updatedAt: Date(timeIntervalSince1970: 1_790_000_000.5), message: "No speech heard.")
        try store.write(status)
        #expect(try store.status() == status)
    }

    // A keyboard can meet a status.json an older app wrote, before the take's start was in it: it still reads, with no start.
    @Test func aStatusFromBeforeTheTakesStartStillReads() throws {
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let older = #"{"session":"ready","engine":"readyCPU","micOn":true,"takeID":"\#(take.uuidString)","take":"recording","level":0,"updatedAt":811692800}"#
        try Data(older.utf8).write(to: dir.appendingPathComponent("status.json"))
        let status = try #require(try store.status())
        #expect(status.takeID == take && status.take == .recording && status.micOn)
        #expect(status.takeStartedAt == nil)
    }

    @Test func theOutboxKeepsTheNewest20() throws {
        #expect(try store.outbox().isEmpty)
        let items = (0..<25).map { OutboxItem(takeID: UUID(), text: "take \($0)", target: target, createdAt: Date(timeIntervalSince1970: Double($0))) }
        try store.write(items)
        #expect(try store.outbox() == Array(items.suffix(20))) // newest last
    }

    // An outbox.json from before `pinnedAt` (written without the key) still reads, with no pin: the keyboard holds such a
    // take back instead of typing it by itself.
    @Test func anOutboxFromBeforeThePinStillReads() throws {
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let older = #"[{"takeID":"\#(take.uuidString)","text":"Hi.","target":{"documentID":"\#(target.documentID.uuidString)","contextHash":"\#(target.contextHash)"},"state":"pending","createdAt":811692800}]"#
        try Data(older.utf8).write(to: dir.appendingPathComponent("outbox.json"))
        let item = try #require(try store.outbox().first)
        #expect(item.takeID == take && item.text == "Hi." && item.target == target && item.state == .pending)
        #expect(item.pinnedAt == nil)
    }

    // Writes go to a temporary file that is renamed over the old one: nothing else is left in the folder.
    @Test func writesLeaveNoTemporaryFiles() throws {
        let press = command(1)
        try store.append(press)
        try store.write(HostStatus())
        try store.write([OutboxItem(takeID: take, text: "Hi.", target: nil)])
        #expect(try FileManager.default.contentsOfDirectory(atPath: dir.path).sorted() == ["commands", "outbox.json", "status.json"])
        #expect(try FileManager.default.contentsOfDirectory(atPath: dir.appendingPathComponent("commands").path).count == 1)
    }

    // A reader in the other process never sees half a file.
    @Test func readersNeverSeeHalfAFile() async throws {
        let store = store
        let big = HostStatus(message: String(repeating: "a", count: 200_000))
        try store.write(big)
        try await withThrowingTaskGroup(of: Void.self) { group in
            group.addTask {
                for level in 0..<500 {
                    var status = big
                    status.level = Float(level)
                    try store.write(status)
                }
            }
            group.addTask {
                for _ in 0..<500 {
                    let length = try store.status()?.message?.count
                    #expect(length == 200_000)
                }
            }
            try await group.waitForAll()
        }
    }

    // The field is recognized by a hash of the 32 characters on each side of the cursor, never by the text.
    @Test func theContextHashCoversOnly32CharactersEachSide() {
        #expect(InsertTarget.contextHash(before: "ab", after: "cd") == "1bd95cf6379b94fd3b6ceb1390b70b822c76442c4bfb8273b941e09d8dfd9b56")
        #expect(InsertTarget.contextHash(before: nil, after: nil) == "6e340b9cffb37a989ca544e6bb780a2c78901d3fb33738768511a30617afa01d")
        let near = String(repeating: "x", count: 32)
        #expect(InsertTarget.contextHash(before: "far away " + near, after: near + " far away")
                == InsertTarget.contextHash(before: near, after: near))
        #expect(InsertTarget.contextHash(before: "ab", after: "cd") != InsertTarget.contextHash(before: "abc", after: "d"))
    }

    @Test func darwinNamesStartWithTheBundleID() {
        #expect(DarwinName.command == Brand.bundleID + ".command")
        #expect(DarwinName.status == Brand.bundleID + ".status")
    }

    // Recordings and commands never leave the phone, not even in a backup.
    @Test func theDirectoryIsExcludedFromBackup() throws {
        try store.write(HostStatus())
        #expect(try dir.resourceValues(forKeys: [.isExcludedFromBackupKey]).isExcludedFromBackup == true)
    }
}
