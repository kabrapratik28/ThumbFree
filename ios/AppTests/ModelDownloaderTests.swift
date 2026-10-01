import CryptoKit
import Foundation
import Synchronization
import Testing
import TFCore
@testable import ThumbFree

/// The downloader's waits, recorded instead of slept.
final class Waits: Sendable {
    private let list = Mutex<[Duration]>([])
    var all: [Duration] { list.withLock { $0 } }
    func add(_ wait: Duration) { list.withLock { $0.append(wait) } }
}

/// Every test serves its files from `StubServer` under a repo of its own; nothing reaches the network.
@Suite final class ModelDownloaderTests {
    let root: URL
    let folder: URL
    let repo = "test/\(UUID().uuidString)"
    let waits = Waits()
    let a = Data((0..<3_000).map { UInt8($0 % 251) })
    let b = Data("vocabulary".utf8)

    let transfers: ModelTransfers

    init() throws {
        root = try TestFiles.folder()
        folder = root.appendingPathComponent("model", isDirectory: true)
        transfers = ModelTransfers.foreground(root: root, protocols: [StubServer.self])
    }

    deinit {
        transfers.invalidate()
        try? FileManager.default.removeItem(at: root)
    }

    func entry(_ files: [(String, Data)]) -> ModelEntry {
        ModelEntry(id: "test", displayName: "Test", summary: "", repo: repo, revision: "abc", files: files.map { path, data in
            ModelFile(path: path, bytes: Int64(data.count), sha256: SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined())
        }, languageHint: "en")
    }

    func url(_ path: String) throws -> URL {
        try #require(URL(string: "https://huggingface.co/\(repo)/resolve/abc/\(path)"))
    }

    func downloader(free: Int64 = .max) -> ModelDownloader {
        let waits = waits, transfers = transfers
        return ModelDownloader(transfers: { _ in transfers }, sleep: { waits.add($0) }, freeBytes: { free })
    }

    var staging: URL { ModelDownloader.staging(for: folder) }

    /// A task on the session for `path`'s part from byte `offset`, as a relaunch finds one iOS carried on: the request and
    /// description ("<file bytes> <first byte> <part path from root>") `ModelTransfers` gives its tasks. Not resumed yet.
    func leftover(_ path: String, from offset: Int, of bytes: Int) throws -> URLSessionTask {
        var request = URLRequest(url: try url(path))
        if offset > 0 { request.setValue("bytes=\(offset)-", forHTTPHeaderField: "Range") }
        let task = try #require(transfers.session).downloadTask(with: request)
        task.taskDescription = "\(bytes) \(offset) \(staging.lastPathComponent)/\(path).part"
        return task
    }

    /// Waits (up to 10 s) until `task` has finished with nobody waiting for it: its body is taken or dropped by then.
    func waitUntilFinished(_ task: URLSessionTask) async throws {
        let end = ContinuousClock.now + .seconds(10)
        while task.state != .completed, ContinuousClock.now < end { try await Task.sleep(for: .milliseconds(20)) }
        #expect(task.state == .completed)
    }

    /// A receipt from before the pins: each file's size and modification date by its path alone, with no pin.
    func receiptByPath(_ model: ModelEntry) throws -> Data {
        var saved: [String: [Double]] = [:]
        for file in model.files {
            let attributes = try FileManager.default.attributesOfItem(atPath: folder.appendingPathComponent(file.path).path)
            let size = try #require(attributes[.size] as? Int64)
            let date = try #require(attributes[.modificationDate] as? Date)
            saved[file.path] = [Double(size), date.timeIntervalSinceReferenceDate]
        }
        return try JSONEncoder().encode(saved)
    }

    @Test func aModelIsFetchedCheckedAndMovedIntoPlace() async throws {
        let model = entry([("Encoder.mlmodelc/weights/weight.bin", a), ("vocab.json", b)])
        StubServer.serve(try url("Encoder.mlmodelc/weights/weight.bin"), [.file(a)])
        StubServer.serve(try url("vocab.json"), [.file(b)])
        let seen = Mutex<[Int64]>([])
        try await downloader().install([model], in: folder) { done, _ in seen.withLock { $0.append(done) } }
        #expect(try Data(contentsOf: folder.appendingPathComponent("Encoder.mlmodelc/weights/weight.bin")) == a)
        #expect(try Data(contentsOf: folder.appendingPathComponent("vocab.json")) == b)
        #expect(!FileManager.default.fileExists(atPath: staging.path))
        #expect(try folder.resourceValues(forKeys: [.isExcludedFromBackupKey]).isExcludedFromBackup == true)
        #expect(ModelDownloader.isInstalled([model], in: folder))
        #expect(seen.withLock { $0.first } == 0)
        #expect(seen.withLock { $0.last } == model.totalBytes) // all in: "Checking the file"
        #expect(try StubServer.requests(url("vocab.json")).first?.value(forHTTPHeaderField: "Accept-Encoding") == "identity")
    }

    // An app restart left half a file: the next download asks only for the rest.
    @Test func aPartGoesOnFromItsLength() async throws {
        let model = entry([("weight.bin", a)])
        try FileManager.default.createDirectory(at: staging, withIntermediateDirectories: true)
        try a.prefix(1_000).write(to: staging.appendingPathComponent("weight.bin.part"))
        StubServer.serve(try url("weight.bin"), [.file(a)])
        try await downloader().install([model], in: folder)
        let requests = try StubServer.requests(url("weight.bin"))
        #expect(requests.map { $0.value(forHTTPHeaderField: "Range") } == ["bytes=1000-"])
        #expect(try Data(contentsOf: folder.appendingPathComponent("weight.bin")) == a)
    }

    // A part the server cannot go on from (416), or one longer than the file, starts the file over cleanly.
    @Test func aStalePartStartsTheFileOver() async throws {
        let model = entry([("weight.bin", a), ("vocab.json", b)])
        try FileManager.default.createDirectory(at: staging, withIntermediateDirectories: true)
        try Data(repeating: 7, count: 500).write(to: staging.appendingPathComponent("weight.bin.part"))
        try Data(repeating: 7, count: 50).write(to: staging.appendingPathComponent("vocab.json.part"))
        StubServer.serve(try url("weight.bin"), [.status(416), .file(a)])
        StubServer.serve(try url("vocab.json"), [.file(b)])
        try await downloader().install([model], in: folder)
        #expect(try StubServer.requests(url("weight.bin")).map { $0.value(forHTTPHeaderField: "Range") } == ["bytes=500-", nil])
        #expect(try StubServer.requests(url("vocab.json")).map { $0.value(forHTTPHeaderField: "Range") } == [nil])
        #expect(ModelDownloader.isInstalled([model], in: folder))
    }

    // A connection that drops mid-file is tried again 2 s later. That try's bytes are gone (a download task keeps no
    // part of a failed body); on a phone the background session rides out short drops by itself.
    @Test func aBrokenConnectionIsTriedAgain() async throws {
        let model = entry([("weight.bin", a)])
        StubServer.serve(try url("weight.bin"), [.file(a, cut: 1_200), .file(a)])
        try await downloader().install([model], in: folder)
        #expect(try StubServer.requests(url("weight.bin")).map { $0.value(forHTTPHeaderField: "Range") } == [nil, nil])
        #expect(waits.all == [.seconds(2)])
        #expect(try Data(contentsOf: folder.appendingPathComponent("weight.bin")) == a)
    }

    @Test func serverErrorsAreTriedAgainAfter2And4And8Seconds() async throws {
        let model = entry([("weight.bin", a)])
        StubServer.serve(try url("weight.bin"), [.status(503), .status(500), .status(408), .file(a)])
        try await downloader().install([model], in: folder)
        #expect(waits.all == [.seconds(2), .seconds(4), .seconds(8)])
        #expect(ModelDownloader.isInstalled([model], in: folder))
    }

    @Test func afterThreeRetriesTheDownloadStopsAndSaysInterrupted() async throws {
        let model = entry([("weight.bin", a)])
        StubServer.serve(try url("weight.bin"), [.status(502)])
        await #expect(throws: ModelDownloader.Failure.interrupted) { try await self.downloader().install([model], in: self.folder) }
        #expect(try StubServer.requests(url("weight.bin")).count == 4) // one try and three retries
        #expect(!FileManager.default.fileExists(atPath: folder.path))
    }

    // Retry-After wins when it is longer than the backoff step, up to 60 s.
    @Test func retryAfterIsHonouredUpTo60Seconds() async throws {
        let model = entry([("weight.bin", a)])
        StubServer.serve(try url("weight.bin"), [.status(429, headers: ["Retry-After": "30"]),
                                                 .status(503, headers: ["Retry-After": "600"]), .file(a)])
        try await downloader().install([model], in: folder)
        #expect(waits.all == [.seconds(30), .seconds(60)])
    }

    // No connection, mobile data turned off for ThumbFree, or roaming off: each says No internet, not Interrupted.
    @Test(arguments: [URLError.Code.notConnectedToInternet, .dataNotAllowed, .internationalRoamingOff])
    func noConnectionSaysNoInternet(_ code: URLError.Code) async throws {
        let model = entry([("weight.bin", a)])
        StubServer.serve(try url("weight.bin"), [.fail(code)])
        await #expect(throws: ModelDownloader.Failure.noInternet) { try await self.downloader().install([model], in: self.folder) }
        #expect(waits.all == [.seconds(2), .seconds(4), .seconds(8)])
    }

    // A cancel you did not ask for (iOS cancelled the task) is a broken connection: tried again after 2 s, and what came
    // before stays. Only your Cancel deletes the download (SpeechModelTests).
    @Test func aCancelFromIOSIsTriedAgainAndKeepsThePart() async throws {
        let model = entry([("weight.bin", a)])
        try FileManager.default.createDirectory(at: staging, withIntermediateDirectories: true)
        try a.prefix(1_000).write(to: staging.appendingPathComponent("weight.bin.part"))
        StubServer.serve(try url("weight.bin"), [.fail(.cancelled), .file(a)])
        try await downloader().install([model], in: folder)
        let requests = try StubServer.requests(url("weight.bin"))
        #expect(requests.map { $0.value(forHTTPHeaderField: "Range") } == ["bytes=1000-", "bytes=1000-"])
        #expect(waits.all == [.seconds(2)])
        #expect(try Data(contentsOf: folder.appendingPathComponent("weight.bin")) == a)
    }

    // A task of ours already on its way for a part (iOS carried it on while ThumbFree was not running) is joined, never
    // started twice: the download waits for it, and your Cancel ends it as yours.
    @Test func aTaskAlreadyOnItsWayIsJoined() async throws {
        let model = entry([("weight.bin", a)])
        try FileManager.default.createDirectory(at: staging, withIntermediateDirectories: true)
        StubServer.serve(try url("weight.bin"), [.stall(a)])
        let kept = try leftover("weight.bin", from: 0, of: a.count)
        kept.resume()
        let end = ContinuousClock.now + .seconds(10)
        while try StubServer.requests(url("weight.bin")).isEmpty, ContinuousClock.now < end { try await Task.sleep(for: .milliseconds(20)) }
        let downloader = downloader(), folder = folder
        let install = Task { try await downloader.install([model], in: folder) }
        install.cancel()
        await #expect(throws: CancellationError.self) { try await install.value }
        #expect(kept.state == .completed) // cancelled by the download that joined it
        #expect(try StubServer.requests(url("weight.bin")).count == 1)
    }

    // A task from before its part grew (its first byte is no longer the part's length) that finished while nothing
    // waited for it brings a stale body: it is dropped and the part is kept, so the download goes on from the part's
    // length instead of starting the file over.
    @Test func aStaleBodyKeepsThePart() async throws {
        let model = entry([("weight.bin", a)])
        try FileManager.default.createDirectory(at: staging, withIntermediateDirectories: true)
        let part = staging.appendingPathComponent("weight.bin.part")
        try a.prefix(1_000).write(to: part)
        StubServer.serve(try url("weight.bin"), [.file(a)])
        let kept = try leftover("weight.bin", from: 500, of: a.count)
        kept.resume()
        try await waitUntilFinished(kept)
        #expect(ModelDownloader.size(of: part) == 1_000)
        try await downloader().install([model], in: folder)
        #expect(try StubServer.requests(url("weight.bin")).map { $0.value(forHTTPHeaderField: "Range") } == ["bytes=500-", "bytes=1000-"])
        #expect(try Data(contentsOf: folder.appendingPathComponent("weight.bin")) == a)
    }

    // A task that finished while nothing waited for it (iOS carried it on while ThumbFree was not running) has already
    // filled its part: the download goes on without asking for the file again.
    @Test func aTaskThatFinishedWithNobodyWaitingIsNotFetchedAgain() async throws {
        let model = entry([("weight.bin", a)])
        try FileManager.default.createDirectory(at: staging, withIntermediateDirectories: true)
        StubServer.serve(try url("weight.bin"), [.file(a)])
        let kept = try leftover("weight.bin", from: 0, of: a.count)
        kept.resume()
        try await waitUntilFinished(kept)
        try await downloader().install([model], in: folder)
        #expect(try StubServer.requests(url("weight.bin")).count == 1)
        #expect(ModelDownloader.isInstalled([model], in: folder))
    }

    // The receipt names each file's pinned SHA-256: a new pin of a file with the same path and size (the v2 and v3
    // Encoder weights are both 445,187,200 bytes) is not trusted by the launch check. The next download hashes the file
    // on disk against the new pin, which fails, so it is deleted and the try after fetches it.
    @Test func aNewPinOfASameSizeFileIsCheckedAgain() async throws {
        let old = entry([("weight.bin", a)])
        StubServer.serve(try url("weight.bin"), [.file(a)])
        try await downloader().install([old], in: folder)
        #expect(ModelDownloader.isInstalled([old], in: folder))
        var changed = a
        changed[0] ^= 0xFF
        let new = entry([("weight.bin", changed)]) // the same path and size, another SHA-256
        #expect(!ModelDownloader.isInstalled([new], in: folder))
        StubServer.serve(try url("weight.bin"), [.file(changed)])
        await #expect(throws: ModelDownloader.Failure.checkFailed) { try await self.downloader().install([new], in: self.folder) }
        #expect(try StubServer.requests(url("weight.bin")).count == 1) // hashed, not fetched
        try await downloader().install([new], in: folder)
        #expect(try Data(contentsOf: folder.appendingPathComponent("weight.bin")) == changed)
        #expect(ModelDownloader.isInstalled([new], in: folder))
    }

    // A model installed before receipts named each file's pin (by path alone) never reads as missing: the
    // launch check hashes its files against the pins once, fetching nothing, and writes a receipt of the new kind. Files
    // that fail the hash lose the old receipt, so no later launch hashes them again.
    @Test func aReceiptFromBeforeThePinsIsCheckedAgainstThemOnce() throws {
        let model = entry([("weight.bin", a), ("vocab.json", b)])
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        try a.write(to: folder.appendingPathComponent("weight.bin"))
        try b.write(to: folder.appendingPathComponent("vocab.json"))
        let receipt = folder.appendingPathComponent(ModelDownloader.receiptName)
        try receiptByPath(model).write(to: receipt)
        #expect(ModelDownloader.isInstalled([model], in: folder))
        let saved = try JSONDecoder().decode([String: [Double]].self, from: Data(contentsOf: receipt))
        #expect(Set(saved.keys) == Set(model.files.map { "\($0.path) \($0.sha256)" }))
        var damaged = a
        damaged[0] ^= 0xFF
        try damaged.write(to: folder.appendingPathComponent("weight.bin")) // the same size: only the hash can tell
        try receiptByPath(model).write(to: receipt)
        #expect(!ModelDownloader.isInstalled([model], in: folder))
        #expect(!FileManager.default.fileExists(atPath: receipt.path))
    }

    // A file of the right size with the wrong bytes: deleted, so the next try fetches only it, fresh.
    @Test func aFileWhoseHashDoesNotMatchIsDeletedAndFetchedAgain() async throws {
        let model = entry([("weight.bin", a), ("vocab.json", b)])
        var damaged = a
        damaged[10] ^= 0xFF
        StubServer.serve(try url("weight.bin"), [.file(damaged), .file(a)])
        StubServer.serve(try url("vocab.json"), [.file(b)])
        await #expect(throws: ModelDownloader.Failure.checkFailed) { try await self.downloader().install([model], in: self.folder) }
        #expect(!FileManager.default.fileExists(atPath: folder.path))
        #expect(!FileManager.default.fileExists(atPath: staging.appendingPathComponent("weight.bin").path))
        #expect(FileManager.default.fileExists(atPath: staging.appendingPathComponent("vocab.json").path))
        try await downloader().install([model], in: folder)
        #expect(try StubServer.requests(url("weight.bin")).count == 2)
        #expect(try StubServer.requests(url("vocab.json")).count == 1)
        #expect(ModelDownloader.isInstalled([model], in: folder))
    }

    @Test func aResponseOfTheWrongSizeIsRejected() async throws {
        let model = entry([("weight.bin", a)])
        StubServer.serve(try url("weight.bin"), [.file(a + Data([1, 2, 3]))])
        await #expect(throws: ModelDownloader.Failure.checkFailed) { try await self.downloader().install([model], in: self.folder) }
        #expect(!FileManager.default.fileExists(atPath: folder.path))
    }

    // `ModelFile.url(in:)` is the only way to a file: a path that would leave the folder stops the download before any
    // request, and nothing is written outside.
    @Test func aPathThatWouldEscapeIsNeverFetchedOrWritten() async throws {
        let model = entry([("../escaped.bin", a)])
        StubServer.serve(try url("../escaped.bin"), [.file(a)])
        await #expect(throws: ModelDownloader.Failure.checkFailed) { try await self.downloader().install([model], in: self.folder) }
        #expect(try StubServer.requests(url("../escaped.bin")).isEmpty)
        #expect(!FileManager.default.fileExists(atPath: root.appendingPathComponent("escaped.bin").path))
        #expect(!FileManager.default.fileExists(atPath: root.appendingPathComponent("escaped.bin.part").path))
    }

    // What is left plus 1 GiB must be free before any request.
    @Test func notEnoughSpaceStopsBeforeAnyRequest() async throws {
        let model = entry([("weight.bin", a)])
        StubServer.serve(try url("weight.bin"), [.file(a)])
        let needed = model.totalBytes + ModelDownloader.margin
        await #expect(throws: ModelDownloader.Failure.noSpace(needed: needed)) {
            try await self.downloader(free: needed - 1).install([model], in: self.folder)
        }
        #expect(try StubServer.requests(url("weight.bin")).isEmpty)
    }

    // A copy that failed the launch check (a file missing) keeps its good files: only the missing one is fetched.
    @Test func aTurnedDownCopyKeepsItsGoodFiles() async throws {
        let model = entry([("weight.bin", a), ("vocab.json", b)])
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        try a.write(to: folder.appendingPathComponent("weight.bin"))
        #expect(!ModelDownloader.isInstalled([model], in: folder))
        StubServer.serve(try url("weight.bin"), [.file(a)])
        StubServer.serve(try url("vocab.json"), [.file(b)])
        try await downloader().install([model], in: folder)
        #expect(try StubServer.requests(url("weight.bin")).isEmpty)
        #expect(ModelDownloader.isInstalled([model], in: folder))
    }

    // The launch check trusts a folder only while every file keeps the size and date it had when it was checked.
    @Test func theLaunchCheckComparesEachFilesSizeAndDate() async throws {
        let model = entry([("weight.bin", a), ("vocab.json", b)])
        StubServer.serve(try url("weight.bin"), [.file(a)])
        StubServer.serve(try url("vocab.json"), [.file(b)])
        try await downloader().install([model], in: folder)
        #expect(ModelDownloader.isInstalled([model], in: folder))
        let vocab = folder.appendingPathComponent("vocab.json")
        try FileManager.default.setAttributes([.modificationDate: Date(timeIntervalSince1970: 0)], ofItemAtPath: vocab.path)
        #expect(!ModelDownloader.isInstalled([model], in: folder)) // the same size, another date
        try FileManager.default.removeItem(at: folder.appendingPathComponent(ModelDownloader.receiptName))
        #expect(!ModelDownloader.isInstalled([model], in: folder)) // no receipt
    }

    // The real thing, off by default: `TEST_RUNNER_TF_NETWORK_TESTS=1 tools/test-app.sh ...` fetches the speech check
    // (about 1 MB) from Hugging Face with download tasks, then cuts a file to a 400,000-byte part and fetches the rest
    // with a Range request.
    @Test(.enabled(if: ProcessInfo.processInfo.environment["TF_NETWORK_TESTS"] != nil))
    func theSpeechCheckDownloadsFromHuggingFaceAndResumes() async throws {
        let real = ModelTransfers.foreground(root: root)
        defer { real.invalidate() }
        let downloader = ModelDownloader(transfers: { _ in real })
        try await downloader.install([ModelCatalog.silero], in: folder)
        #expect(ModelVerifier.check(folder: folder, entry: ModelCatalog.silero).values.allSatisfy { $0 == .ok })
        let weights = "silero-vad-unified-256ms-v6.2.1.mlmodelc/weights/weight.bin"
        try FileManager.default.moveItem(at: folder, to: staging)
        let file = staging.appendingPathComponent(weights)
        try Data(contentsOf: file).prefix(400_000).write(to: ModelDownloader.part(file))
        try FileManager.default.removeItem(at: file)
        try await downloader.install([ModelCatalog.silero], in: folder)
        #expect(ModelVerifier.check(folder: folder, entry: ModelCatalog.silero).values.allSatisfy { $0 == .ok })
    }
}
