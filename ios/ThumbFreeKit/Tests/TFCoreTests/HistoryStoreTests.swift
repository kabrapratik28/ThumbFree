import Foundation
import Testing
@testable import TFCore

let oneDay: TimeInterval = 86_400
let fixedNow = Date(timeIntervalSince1970: 1_000 * oneDay) // a fixed clock

func makeTake(_ order: Int, _ status: TakeStatus = .inserted, startedAt: Date = fixedNow, starred: Bool = false) -> TakeRecord {
    TakeRecord(id: UUID(), order: order, startedAt: startedAt, modelID: "parakeet-tdt-0.6b-v2", status: status, starred: starred)
}

@Suite final class HistoryStoreTests {
    let store = HistoryStore(root: FileManager.default.temporaryDirectory.appendingPathComponent("HistoryStoreTests-\(UUID().uuidString)"))

    // Each test gets its own temporary folder (see `store.root` above); remove it so runs never pile up in shared temp space.
    deinit { try? FileManager.default.removeItem(at: store.root) }

    /// A take with a small audio file, as a finished take has.
    func saved(_ record: TakeRecord) throws -> TakeRecord {
        try store.create(record)
        try Data(count: 44).write(to: store.audioURL(for: record.id))
        return record
    }

    @Test func aCreatedTakeReadsBack() throws {
        var record = makeTake(1, .recording)
        record.partialText = "so we"
        try store.create(record)
        #expect(try store.record(record.id) == record)
        #expect(try store.all() == [record])
        #expect(FileManager.default.fileExists(atPath: store.root.appendingPathComponent("\(record.id.uuidString)/take.json").path))
        #expect(store.audioURL(for: record.id).lastPathComponent == "audio.wav")
    }

    @Test func updatesAreSavedAndAMissingTakeThrows() throws {
        var record = makeTake(1, .recording)
        try store.create(record)
        record.status = .staged
        record.rawText = "um so we go"
        record.text = "So we go."
        try store.update(record)
        #expect(try store.record(record.id) == record)
        // The host reads a failed save as "the text is not safe" and holds the text back.
        let gone = makeTake(2)
        #expect(throws: HistoryError.missing(gone.id)) { try store.update(gone) }
    }

    @Test func aTakeIsCreatedOnce() throws {
        let record = makeTake(1)
        try store.create(record)
        #expect(throws: HistoryError.exists(record.id)) { try store.create(record) }
    }

    // Newest first by order, not by the clock.
    @Test func allIsNewestFirstByOrder() throws {
        for (order, seconds) in [(1, 300.0), (3, 100.0), (2, 200.0)] {
            try store.create(makeTake(order, startedAt: Date(timeIntervalSince1970: seconds)))
        }
        #expect(try store.all().map(\.order) == [3, 2, 1])
    }

    @Test func nextOrderIsOnePastTheHighest() throws {
        #expect(try store.nextOrder() == 1)
        try store.create(makeTake(5))
        #expect(try store.nextOrder() == 6)
    }

    // A folder without a take.json, and names that are not take ids, are not takes.
    @Test func allSkipsFoldersThatAreNotTakes() throws {
        let record = try saved(makeTake(1))
        try FileManager.default.createDirectory(at: store.folder(for: UUID()), withIntermediateDirectories: true)
        try Data("x".utf8).write(to: store.root.appendingPathComponent("notes.txt"))
        #expect(try store.all() == [record])
    }

    @Test func deleteRemovesTheAudioThenTheFolder() throws {
        let record = try saved(makeTake(1))
        try store.delete(record.id)
        #expect(!FileManager.default.fileExists(atPath: store.folder(for: record.id).path))
        try store.delete(record.id) // a take that is already gone is fine
        #expect(try store.all().isEmpty)
    }

    // Audio that cannot be deleted keeps its take, so the user can still find it and delete it again.
    @Test func undeletableAudioKeepsItsTake() throws {
        let record = try saved(makeTake(1))
        let folder = store.folder(for: record.id)
        try FileManager.default.setAttributes([.posixPermissions: 0o555], ofItemAtPath: folder.path)
        defer { try? FileManager.default.setAttributes([.posixPermissions: 0o755], ofItemAtPath: folder.path) }
        #expect(throws: (any Error).self) { try store.delete(record.id) }
        #expect(try store.record(record.id) == record)
        #expect(FileManager.default.fileExists(atPath: store.audioURL(for: record.id).path))
    }

    // Recordings never leave the phone, not even in a backup.
    @Test func historyIsExcludedFromBackup() throws {
        try store.create(makeTake(1))
        #expect(try store.root.resourceValues(forKeys: [.isExcludedFromBackupKey]).isExcludedFromBackup == true)
    }

    // A take whose audio cannot be deleted stays for the next pass; the pass goes on past it and never throws for it.
    @Test func retentionSkipsATakeItCannotDelete() throws {
        let takes = try (1...152).map { try saved(makeTake($0)) }
        let stuck = store.folder(for: takes[1].id)
        try FileManager.default.setAttributes([.posixPermissions: 0o555], ofItemAtPath: stuck.path)
        defer { try? FileManager.default.setAttributes([.posixPermissions: 0o755], ofItemAtPath: stuck.path) }
        #expect(try store.applyRetention(keepDays: nil, keepCount: 150, now: fixedNow) == 1)
        #expect(try store.record(takes[1].id) != nil)
        #expect(try store.record(takes[0].id) == nil)
    }
}

// Retention is pure: the takes it would delete, newest first.
@Suite struct RetentionTests {
    @Test func theDefaultIsForeverAnd200() {
        #expect(Retention.defaultDays == nil && Retention.defaultCount == 200)
        #expect(Retention.dayChoices == [7, 30, 90, nil] && Retention.countChoices == [50, 200, 1_000, nil])
    }

    // Starred takes are never removed and do not count.
    @Test func keepsTheNewestUnstarred() {
        var takes = (1...152).map { makeTake($0) }
        takes[0].starred = true
        #expect(Retention.idsToDelete(takes, keepDays: nil, keepCount: 150, now: fixedNow) == [takes[1].id])
    }

    // A take recording, transcribing, staged or being typed is never deleted, however old, and does not count.
    @Test func neverDeletesALiveTake() {
        let live = [TakeStatus.recording, .transcribing, .staged, .inserting].enumerated().map {
            makeTake($0.offset + 1, $0.element, startedAt: fixedNow - 400 * oneDay)
        }
        let ended = (5...7).map { makeTake($0, startedAt: fixedNow - Double(8 - $0)) }
        #expect(Retention.idsToDelete(live + ended, keepDays: 7, keepCount: 1, now: fixedNow) == [ended[1].id, ended[0].id])
    }

    // "Newest" is by order: a take made after the clock jumped back is still the newest.
    @Test func aClockSetBackNeverMakesANewTakeLookOld() {
        let takes = (1...150).map { makeTake($0, startedAt: Date(timeIntervalSince1970: 1_000 + Double($0))) }
        let newest = makeTake(151, startedAt: Date(timeIntervalSince1970: 1))
        #expect(Retention.idsToDelete(takes + [newest], keepDays: nil, keepCount: 150, now: fixedNow) == [takes[0].id])
    }

    // A take goes once it started more than keepDays before fixedNow.
    @Test func byAge() {
        let old = makeTake(1, startedAt: fixedNow - 7 * oneDay - 1)
        let edge = makeTake(2, .noSpeech, startedAt: fixedNow - 7 * oneDay)
        let recent = makeTake(3, .failed, startedAt: fixedNow - oneDay)
        #expect(Retention.idsToDelete([old, edge, recent], keepDays: 7, keepCount: nil, now: fixedNow) == [old.id])
    }

    // Both rules apply, so the stricter one decides.
    @Test func bothRulesApply() {
        let takes = (1...5).map { makeTake($0, startedAt: fixedNow - Double(6 - $0) * oneDay - 1) } // take 1 is 5 days old
        #expect(Retention.idsToDelete(takes, keepDays: 3, keepCount: 4, now: fixedNow).count == 3) // the days remove more
        #expect(Retention.idsToDelete(takes, keepDays: 3, keepCount: 1, now: fixedNow).count == 4) // the count removes more
        #expect(Retention.idsToDelete(takes, keepDays: nil, keepCount: nil, now: fixedNow).isEmpty) // forever, no limit
    }

    @Test func liveStatuses() {
        #expect(TakeStatus.allCases.filter(\.isLive) == [.recording, .transcribing, .staged, .inserting])
    }
}
