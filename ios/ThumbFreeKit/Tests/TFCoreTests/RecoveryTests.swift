import Foundation
import Testing
@testable import TFCore

@Suite final class RecoveryTests {
    let store = HistoryStore(root: FileManager.default.temporaryDirectory.appendingPathComponent("RecoveryTests-\(UUID().uuidString)"))
    let shared = SharedStore(directory: FileManager.default.temporaryDirectory.appendingPathComponent("RecoveryShared-\(UUID().uuidString)"))

    // Each test gets its own temporary folders (see `store.root` and `shared.directory` above); remove them so runs
    // never pile up in shared temp space.
    deinit {
        try? FileManager.default.removeItem(at: store.root)
        try? FileManager.default.removeItem(at: shared.directory)
    }

    /// A take in `status` with a finished one-second WAV, or no audio at all.
    @discardableResult
    func saved(_ status: TakeStatus, audio: Bool = true, order: Int = 1) throws -> TakeRecord {
        let record = makeTake(order, status)
        try store.create(record)
        if audio {
            let writer = try WavWriter(url: store.audioURL(for: record.id))
            try writer.append([Float](repeating: 0.1, count: 16_000))
            _ = try writer.finish()
        }
        return record
    }

    @Test func everyLiveStatusEnds() throws {
        let rec = try saved(.recording, order: 1)
        let tra = try saved(.transcribing, order: 2)
        var sta = try saved(.staged, order: 3)
        sta.text = "Text."
        try store.update(sta)
        let ins = try saved(.inserting, order: 4)
        let done = try saved(.inserted, order: 5)

        let recovered = try Recovery.run(store, shared: shared)

        #expect(try [rec, tra, sta, ins, done].map { try store.record($0.id)?.status }
                == [.interrupted, .interrupted, .notInserted, .needsReview, .inserted])
        #expect(recovered.count == 4)
        #expect(try store.record(sta.id)?.text == "Text.")
    }

    // A take that died before its speech decision (recording, transcribing) holds text nobody confirmed: the write that
    // ends it clears it. Staged and inserting takes passed that decision: they keep the transcript, which is why it was
    // saved before insertion, and follow the Delivery table even without audio (the text may be in the field). The
    // audio stays either way.
    @Test func textIsClearedOnlyBeforeTheSpeechDecision() throws {
        let ends: [(TakeStatus, Bool, TakeStatus)] = [
            (.recording, true, .interrupted), (.recording, false, .failed),
            (.transcribing, true, .interrupted), (.transcribing, false, .failed),
            (.staged, true, .notInserted), (.staged, false, .notInserted),
            (.inserting, true, .needsReview), (.inserting, false, .needsReview),
        ]
        var takes: [TakeRecord] = []
        for (order, (status, audio, _)) in ends.enumerated() {
            var record = try saved(status, audio: audio, order: order + 1)
            record.partialText = "p"
            record.rawText = "r"
            record.text = "t"
            record.insertedText = "i"
            try store.update(record)
            takes.append(record)
        }

        try Recovery.run(store, shared: shared)

        for (record, (status, audio, end)) in zip(takes, ends) {
            let after = try #require(try store.record(record.id))
            #expect(after.status == end)
            #expect(after.error == (audio ? nil : TakeMessage.audioMissing.rawValue))
            let kept = status == .staged || status == .inserting
            #expect([after.partialText, after.rawText, after.text, after.insertedText]
                    == (kept ? ["p", "r", "t", nil] : [nil, nil, nil, nil]))
            #expect(FileManager.default.fileExists(atPath: store.audioURL(for: record.id).path) == audio)
        }
    }

    // Died mid-take: a second of audio on disk, but the header still says 0.
    @Test func theWavIsRepairedAndTheDurationSet() throws {
        let record = try saved(.recording, audio: false)
        let writer = try WavWriter(url: store.audioURL(for: record.id))
        try writer.append([Float](repeating: 0.1, count: 16_000))
        #expect(try WavFile.readMono16k(url: store.audioURL(for: record.id)).isEmpty)

        try Recovery.run(store, shared: shared)

        #expect(try WavFile.readMono16k(url: store.audioURL(for: record.id)).count == 16_000)
        #expect(try store.record(record.id)?.durationMs == 1_000)
    }

    // The Delivery table's "the app restarts": a staged take's item stays pending (nothing began); an inserting take's
    // item becomes unverified (it may already be in the field). Other items are left as they are.
    @Test func theOutboxFollowsTheDeliveryTable() throws {
        let staged = try saved(.staged, order: 1)
        let inserting = try saved(.inserting, order: 2)
        let typed = try saved(.inserted, order: 3)
        let items = [staged, inserting, typed].map { OutboxItem(takeID: $0.id, text: "Text.", target: nil) }
        var done = items[2]
        done.state = .typed
        try shared.write([items[0], items[1], done])

        try Recovery.run(store, shared: shared)

        #expect(try shared.outbox().map(\.state) == [.pending, .unverified, .typed])
        #expect(try store.record(inserting.id)?.status == .needsReview)
    }

    // Missing audio, or a file that lost its header before it reached the disk.
    @Test func missingAudioIsFailed() throws {
        let missing = try saved(.recording, audio: false, order: 1)
        let headerless = try saved(.recording, audio: false, order: 2)
        try Data().write(to: store.audioURL(for: headerless.id))

        try Recovery.run(store, shared: shared)

        for record in [missing, headerless] {
            let after = try store.record(record.id)
            #expect(after?.status == .failed && after?.error == "audioMissing")
        }
    }

    // The failure is thrown after the others are recovered; the bad take is left for the next start.
    @Test func oneBadTakeDoesNotStopTheRest() throws {
        let older = try saved(.recording, order: 1)
        let bad = try saved(.recording, order: 2) // newest: handled first
        let audio = store.audioURL(for: bad.id)
        try FileManager.default.setAttributes([.posixPermissions: 0o444], ofItemAtPath: audio.path) // repair cannot open it
        defer { try? FileManager.default.setAttributes([.posixPermissions: 0o644], ofItemAtPath: audio.path) }

        #expect(throws: (any Error).self) { try Recovery.run(store, shared: shared) }

        #expect(try store.record(older.id)?.status == .interrupted)
        #expect(try store.record(bad.id)?.status == .recording)
    }

    // A take folder with no take.json is left from a create that never finished. Everything else stays.
    @Test func onlyFoldersWithoutATakeJSONAreDeleted() throws {
        let takes = try TakeStatus.allCases.enumerated().map { try saved($0.element, order: $0.offset + 1) }
        let orphan = store.folder(for: UUID())
        try FileManager.default.createDirectory(at: orphan, withIntermediateDirectories: true)
        try Data(count: 44).write(to: orphan.appendingPathComponent("audio.wav"))
        let other = store.root.appendingPathComponent("notes")
        try FileManager.default.createDirectory(at: other, withIntermediateDirectories: true)

        try Recovery.run(store, shared: shared)

        #expect(!FileManager.default.fileExists(atPath: orphan.path))
        #expect(FileManager.default.fileExists(atPath: other.path))
        #expect(takes.allSatisfy { FileManager.default.fileExists(atPath: store.audioURL(for: $0.id).path) })
    }

    // Nothing is retried or typed after a restart: no take is live when recovery is done.
    @Test func noTakeIsLeftLive() throws {
        for (order, status) in TakeStatus.allCases.enumerated() {
            try saved(status, audio: true, order: 2 * order + 1)
            try saved(status, audio: false, order: 2 * order + 2)
        }
        try Recovery.run(store, shared: shared)
        #expect(try store.all().filter(\.status.isLive).isEmpty)
    }
}
