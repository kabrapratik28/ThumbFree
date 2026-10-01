import CryptoKit
import Foundation
import Synchronization
import Testing
import TFCore
@testable import ThumbFree

@MainActor @Suite final class SpeechModelTests {
    let root: URL
    let folder: URL
    let repo = "test/\(UUID().uuidString)"
    let data = Data((0..<2_000).map { UInt8($0 % 199) })

    let transfers: ModelTransfers
    /// This test's own defaults: Wi-Fi only and a started download's network go here, never into the app's.
    let defaults: UserDefaults
    let suite = "SpeechModelTests-\(UUID().uuidString)"

    init() throws {
        root = try TestFiles.folder()
        folder = root.appendingPathComponent("model", isDirectory: true)
        transfers = ModelTransfers.foreground(root: root, protocols: [StubServer.self])
        defaults = try #require(UserDefaults(suiteName: suite))
    }

    deinit {
        transfers.invalidate()
        UserDefaults.standard.removePersistentDomain(forName: suite)
        try? FileManager.default.removeItem(at: root)
    }

    func file(_ path: String, _ data: Data) -> ModelFile {
        ModelFile(path: path, bytes: Int64(data.count), sha256: SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined())
    }

    var entry: ModelEntry {
        ModelEntry(id: "test", displayName: "Test", summary: "", repo: repo, revision: "abc", files: [file("weight.bin", data)],
                   languageHint: "en")
    }

    func url(_ path: String = "weight.bin") throws -> URL { try #require(URL(string: "https://huggingface.co/\(repo)/resolve/abc/\(path)")) }

    func model(ready: Bool = false, entries: [ModelEntry]? = nil, network: NetworkWatch = NetworkWatch(watching: false)) -> SpeechModel {
        let transfers = transfers
        return SpeechModel(entries: entries ?? [entry], folder: folder,
                           downloader: ModelDownloader(transfers: { _ in transfers }, sleep: { _ in }), ready: ready,
                           network: network, defaults: defaults)
    }

    /// Where a started download's network is kept for a relaunch.
    var networkKey: String { "\(SpeechModel.networkKey).test" }

    @Test func aDownloadEndsReadyAndHandsOverTheFolder() async throws {
        StubServer.serve(try url(), [.file(data)])
        let model = model()
        var installed: [URL] = []
        model.onInstalled = { installed.append($0) }
        #expect(model.phase == .missing)
        model.download()
        #expect(model.phase == .downloading(done: 0, total: 2_000))
        try await waitUntil { model.phase == .ready }
        #expect(installed == [folder])
        #expect(defaults.object(forKey: networkKey) == nil) // nothing left to go on with at a relaunch
    }

    // A failure says why; Try again starts over from what is on disk.
    @Test func aFailureSaysWhyAndTryAgainGoesOn() async throws {
        StubServer.serve(try url(), [.status(404), .file(data)])
        let model = model()
        model.download()
        try await waitUntil { model.phase == .failed(.interrupted) }
        model.download()
        try await waitUntil { model.phase == .ready }
    }

    // A download that a restart cut off (its staging folder is there) goes on by itself at launch.
    @Test func aDownloadARestartCutOffGoesOnAtLaunch() async throws {
        let staging = ModelDownloader.staging(for: folder)
        try FileManager.default.createDirectory(at: staging, withIntermediateDirectories: true)
        try data.prefix(500).write(to: staging.appendingPathComponent("weight.bin.part"))
        StubServer.serve(try url(), [.file(data)])
        let model = model()
        model.resumeIfStarted()
        try await waitUntil { model.phase == .ready }
        #expect(try StubServer.requests(url()).map { $0.value(forHTTPHeaderField: "Range") } == ["bytes=500-"])
    }

    // Cancel stops the download and deletes what came, so nothing resumes at the next launch; Download starts over.
    @Test func cancelStopsTheDownloadAndDeletesIt() async throws {
        StubServer.serve(try url(), [.stall(data), .file(data)])
        let model = model()
        model.download()
        try await waitUntil { (try? StubServer.requests(self.url()).count) == 1 }
        model.cancel()
        try await waitUntil { model.phase == .missing }
        #expect(!FileManager.default.fileExists(atPath: ModelDownloader.staging(for: folder).path))
        model.download()
        try await waitUntil { model.phase == .ready }
        #expect(try StubServer.requests(url()).map { $0.value(forHTTPHeaderField: "Range") } == [nil, nil])
    }

    @Test func aReadyModelIsNeverFetchedAgain() throws {
        let model = model(ready: true)
        model.download()
        model.resumeIfStarted()
        #expect(model.phase == .ready)
        #expect(try StubServer.requests(url()).isEmpty)
    }

    @Test func nothingStartedMeansNothingResumes() throws {
        let model = model()
        model.resumeIfStarted()
        #expect(model.phase == .missing)
    }

    // Download on Wi-Fi only (the default): with no Wi-Fi the download says it waits, and Use mobile data starts it
    // again on the session that may use mobile data, once. With no connection at all it waits for a connection.
    @Test func aWifiOnlyDownloadWaitsAndUseMobileDataGoesOn() async throws {
        StubServer.serve(try url(), [.stall(data), .file(data)])
        let network = NetworkWatch(watching: false)
        network.wifi = false
        let used = Mutex<[Bool]>([])
        let transfers = transfers
        let model = SpeechModel(entries: [entry], folder: folder,
                                downloader: ModelDownloader(transfers: { cellular in
                                    used.withLock { $0.append(cellular) }
                                    return transfers
                                }, sleep: { _ in }), ready: false, network: network, defaults: defaults)
        model.download()
        #expect(model.waiting == .wifi)
        network.online = false // Airplane Mode: no connection at all, which Wi-Fi alone would not fix
        #expect(model.waiting == .connection)
        network.online = true
        try await waitUntil { (try? StubServer.requests(self.url()).count) == 1 }
        model.useMobileData()
        try await waitUntil { model.phase == .ready }
        #expect(used.withLock { $0 } == [false, true])
        #expect(model.waiting == nil)
    }

    // With Download on Wi-Fi only off, a download may use mobile data, and only no connection at all makes it wait.
    @Test func withWifiOnlyOffADownloadWaitsOnlyForAConnection() async throws {
        defaults.set(false, forKey: SpeechModel.wifiOnlyKey)
        StubServer.serve(try url(), [.stall(data)])
        let network = NetworkWatch(watching: false)
        network.wifi = false
        let transfers = transfers
        let model = SpeechModel(entries: [entry], folder: folder, downloader: ModelDownloader(transfers: { _ in transfers }),
                                ready: false, network: network, defaults: defaults)
        model.download()
        #expect(model.cellular)
        #expect(model.waiting == nil)
        network.online = false
        #expect(model.waiting == .connection)
        try await waitUntil { (try? StubServer.requests(self.url()).count) == 1 }
        model.cancel()
        try await waitUntil { model.phase == .missing }
    }

    // Delete removes the model's folder; Download fetches it again.
    @Test func deleteRemovesTheModel() throws {
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        try data.write(to: folder.appendingPathComponent("weight.bin"))
        let model = model(ready: true)
        #expect(model.usableFolder == folder)
        defaults.set(true, forKey: networkKey)
        model.delete()
        #expect(defaults.object(forKey: networkKey) == nil)
        #expect(model.phase == .missing)
        #expect(model.usableFolder == nil)
        #expect(!FileManager.default.fileExists(atPath: folder.path))
    }

    // A Delete that cannot remove the model's folder keeps the model ready: nothing still on disk is reported gone.
    @Test func aDeleteThatFailsKeepsTheModelReady() async throws {
        StubServer.serve(try url(), [.file(data)])
        let model = model()
        model.download()
        try await waitUntil { model.phase == .ready }
        try FileManager.default.setAttributes([.posixPermissions: 0o555], ofItemAtPath: folder.path) // its files cannot go
        defer { try? FileManager.default.setAttributes([.posixPermissions: 0o755], ofItemAtPath: folder.path) }
        model.delete()
        #expect(model.phase == .ready)
        #expect(model.usableFolder == folder)
    }

    // A relaunch goes on over the network the download was using, not the Wi-Fi only switch: after Use mobile data, the
    // session that may use mobile data, where its task is.
    @Test func aRelaunchAfterUseMobileDataGoesOnOverAnyNetwork() async throws {
        StubServer.serve(try url(), [.stall(data)])
        let network = NetworkWatch(watching: false)
        network.wifi = false
        let first = model(network: network)
        first.download()
        try await waitUntil { (try? StubServer.requests(self.url()).count) == 1 }
        first.useMobileData()
        try await waitUntil { first.cellular }
        #expect(defaults.object(forKey: networkKey) as? Bool == true)
        // The relaunched app: a new model, with its own session, over the same folder and defaults.
        let used = Mutex<[Bool]>([])
        let again = ModelTransfers.foreground(root: root, protocols: [StubServer.self])
        defer { again.invalidate() }
        let relaunched = SpeechModel(entries: [entry], folder: folder, downloader: ModelDownloader(transfers: { cellular in
            used.withLock { $0.append(cellular) }
            return again
        }, sleep: { _ in }), ready: false, network: network, defaults: defaults)
        relaunched.resumeIfStarted()
        try await waitUntil { !used.withLock { $0.isEmpty } }
        #expect(used.withLock { $0 } == [true])
        #expect(relaunched.waiting == nil) // not "Waiting for Wi-Fi"
        first.cancel()
        relaunched.cancel()
        try await waitUntil { first.phase == .missing && relaunched.phase == .missing }
        #expect(defaults.object(forKey: networkKey) == nil)
    }

    // Use mobile data keeps the files already finished over Wi-Fi: only the file on its way is fetched again.
    @Test func useMobileDataKeepsTheFilesAlreadyFinished() async throws {
        let vocab = Data("vocabulary".utf8)
        let entry = ModelEntry(id: "test", displayName: "Test", summary: "", repo: repo, revision: "abc",
                               files: [file("weight.bin", data), file("vocab.json", vocab)], languageHint: "en")
        StubServer.serve(try url("vocab.json"), [.file(vocab)])
        StubServer.serve(try url(), [.stall(data), .file(data)])
        let network = NetworkWatch(watching: false)
        network.wifi = false
        let model = model(entries: [entry], network: network)
        model.download()
        try await waitUntil { (try? StubServer.requests(self.url()).count) == 1 } // the small file is in, the big one on its way
        model.useMobileData()
        try await waitUntil { model.phase == .ready }
        #expect(try StubServer.requests(url("vocab.json")).count == 1)
        #expect(try StubServer.requests(url()).map { $0.value(forHTTPHeaderField: "Range") } == [nil, nil])
    }

    // Use mobile data, then Cancel before the first cancellation lands: the download stays cancelled, with no new request
    // and nothing left on disk.
    @Test func cancelRightAfterUseMobileDataStaysCancelled() async throws {
        StubServer.serve(try url(), [.stall(data), .file(data)])
        let network = NetworkWatch(watching: false)
        network.wifi = false
        let model = model(network: network)
        model.download()
        try await waitUntil { (try? StubServer.requests(self.url()).count) == 1 }
        model.useMobileData()
        model.cancel()
        try await waitUntil(.seconds(3)) { model.phase == .missing }
        try await Task.sleep(for: .milliseconds(300)) // room for a restart, which must not come
        #expect(model.phase == .missing)
        #expect(try StubServer.requests(url()).count == 1)
        #expect(!FileManager.default.fileExists(atPath: ModelDownloader.staging(for: folder).path))
    }

    // The words the welcome flow and the Try tab show, from the Android app.
    @Test func theStatusSpeaksPlainEnglish() {
        #expect(ModelStatusView.size(465_476_672) == "465 MB")
        #expect(ModelStatusView.size(1_539_218_496) == "1.5 GB")
        #expect(SpeechModel.english.reduce(0) { $0 + $1.totalBytes } == 465_476_672)
        let lines: [(SpeechModel.Phase, EnginePhase)] = [
            (.missing, .noModel), (.downloading(done: 196_000_000, total: 465_476_672), .noModel),
            (.downloading(done: 465_476_672, total: 465_476_672), .noModel), (.failed(.noInternet), .noModel),
            (.failed(.noSpace(needed: 1_539_218_496)), .noModel), (.failed(.interrupted), .noModel),
            (.failed(.checkFailed), .noModel), (.ready, .loading), (.ready, .readyNeuralEngine), (.ready, .failed),
        ]
        let shown = lines.map { phase, engine in
            [ModelStatusView.title(phase, engine: engine, total: 465_476_672), ModelStatusView.detail(phase, engine: engine) ?? ""]
        }
        #expect(shown == [
            ["Not downloaded · 465 MB", ""],
            ["Downloading · 42%", "196 MB of 465 MB"],
            ["Checking the file", ""],
            ["No internet connection", "Check your connection, then try again."],
            ["Not enough space", "It needs about 1.5 GB free. Make some room, then try again."],
            ["Download interrupted", "Try again. Files already downloaded are kept."],
            ["File check failed", "The file didn't match. Try again to download it fresh."],
            ["Getting ready for this iPhone", "The first time takes about half a minute. After that, ThumbFree starts in a moment."],
            ["The model is ready", ""],
            ["Could not load the model.", ""],
        ])
        #expect(ModelStatusView.title(.downloading(done: 1, total: 2), engine: .noModel, total: 2, waiting: .wifi) == "Waiting for Wi-Fi")
        #expect(ModelStatusView.title(.downloading(done: 1, total: 2), engine: .noModel, total: 2, waiting: .connection)
                == "Waiting for a connection")
        for line in shown.joined() { #expect(!line.contains("\u{2014}") && !line.contains("\u{2013}")) }
        #expect(lines.map { ModelStatusView.tone($0.0, engine: $0.1) }
                == [.plain, .plain, .busy, .problem, .problem, .problem, .problem, .busy, .done, .problem])
    }
}
