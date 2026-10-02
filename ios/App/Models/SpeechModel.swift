import Foundation
import Observation
import TFCore

/// One speech model on this iPhone: ready, missing, or on its way. The model and the speech check
/// download into one folder, which the engine and `SpeechCheck` both load from. One download at a time per model.
@MainActor @Observable final class SpeechModel {
    enum Phase: Equatable {
        case missing
        /// `done == total`: every byte is in, and the files are being checked.
        case downloading(done: Int64, total: Int64)
        case failed(ModelDownloader.Failure)
        case ready
    }

    /// Why a download is not moving: a Wi-Fi only download with no Wi-Fi, or no connection at all.
    enum Waiting: Equatable { case wifi, connection }

    /// What the app downloads: the English model and the speech check.
    static let english = [ModelCatalog.v2, ModelCatalog.silero]
    /// The multilingual model (25 languages) and the speech check.
    static let multilingual = [ModelCatalog.v3, ModelCatalog.silero]
    /// "Download on Wi-Fi only" (on unless you turn it off in Settings).
    static let wifiOnlyKey = "TFWifiOnly"
    /// A started download's network, per model ("TFDownloadCellular.<id>": true when it may use mobile data), kept until
    /// it is ready, cancelled or deleted, so a relaunch goes on in the session that has its task.
    static let networkKey = "TFDownloadCellular"
    /// The models the engine has loaded since their download: a later load takes seconds, the first about half a minute.
    static let loadedKey = "TFModelLoaded"

    private(set) var phase: Phase
    let entries: [ModelEntry]
    /// Where the model goes: Application Support/Models/<id>.
    let folder: URL
    /// Runs once a download has put a checked model in `folder`.
    var onInstalled: (URL) -> Void = { _ in }
    /// Runs when Download or Try again asks for this model, before its download starts.
    var onDownload: () -> Void = {}
    /// This download may use mobile data: Wi-Fi only was off when it started, or you tapped Use mobile data.
    private(set) var cellular = false
    let network: NetworkWatch
    private let downloader: ModelDownloader
    private let defaults: UserDefaults
    /// The Mac's copy the Simulator uses instead of a download.
    private let cached: URL?
    private var work: Task<Void, Never>?
    /// Use mobile data stopped the download to start it again on any network.
    private var switching = false
    /// The latest download start's number: a progress report or an end from an older start (cancelled, or switched to
    /// mobile data) is dropped, so it never moves the download that replaced it.
    private(set) var starts = 0
    /// Screenshots (Debug builds) hold the reason a download waits (`hold(_:)`); nil otherwise.
    @ObservationIgnored private var heldWaiting: Waiting?

    init(entries: [ModelEntry] = SpeechModel.english, folder: URL, downloader: ModelDownloader = ModelDownloader(),
         ready: Bool, cached: URL? = nil, network: NetworkWatch = NetworkWatch(watching: false),
         defaults: UserDefaults = .standard) {
        self.entries = entries
        self.folder = folder
        self.downloader = downloader
        self.cached = cached
        self.network = network
        self.defaults = defaults
        phase = ready ? .ready : .missing
    }

    var id: String { entries[0].id }
    /// "English" or "Multilingual".
    var name: String { entries[0].displayName }
    var totalBytes: Int64 { entries.reduce(0) { $0 + $1.totalBytes } }
    /// The folder the engine loads: the downloaded model (on the Simulator, the Mac's copy first); nil until it is here.
    var usableFolder: URL? { phase == .ready ? cached ?? folder : nil }

    /// Waiting for Wi-Fi (a Wi-Fi only download and no Wi-Fi now), or for any connection. A background download waits
    /// without a word, so the model's row says it instead.
    var waiting: Waiting? {
        if let heldWaiting { return heldWaiting }
        guard case .downloading(let done, let total) = phase, done < total else { return nil }
        guard network.online else { return .connection } // Airplane Mode: Wi-Fi alone would not do
        return !cellular && !network.wifi ? .wifi : nil
    }

    /// Download on Wi-Fi only (Settings), which a new download follows: on unless you turned it off.
    var wifiOnly: Bool { defaults.object(forKey: Self.wifiOnlyKey) as? Bool ?? true }

    /// A download began and has not ended ready or cancelled: its staging folder is here, and it goes on at launch.
    var started: Bool { FileManager.default.fileExists(atPath: ModelDownloader.staging(for: folder).path) }

    /// The engine has loaded this model since its download, so the next load is quick. A new download or a Delete
    /// forgets it.
    var loadedBefore: Bool { defaults.stringArray(forKey: Self.loadedKey)?.contains(id) == true }

    /// The engine finished loading this model.
    func markLoaded() {
        guard !loadedBefore else { return }
        defaults.set((defaults.stringArray(forKey: Self.loadedKey) ?? []) + [id], forKey: Self.loadedKey)
    }

    private func forgetLoaded() {
        defaults.set(defaults.stringArray(forKey: Self.loadedKey)?.filter { $0 != id }, forKey: Self.loadedKey)
    }

    /// Download, or Try again: starts the download, or goes on with one that a network error or a restart stopped. It
    /// follows Download on Wi-Fi only.
    func download() {
        onDownload()
        start(cellular: !wifiOnly)
    }

    /// Use mobile data: this download goes on over any network, keeping what came. Once, as on Android: the setting
    /// stays, and the next download follows it again.
    /// ponytail: the file on its way starts over on the new session; keep it with resume data if a phone shows it matters.
    func useMobileData() {
        guard waiting == .wifi, work != nil else { return }
        switching = true
        defaults.set(true, forKey: startedNetworkKey)
        work?.cancel()
    }

    /// Stops the download and deletes what it fetched, up to the moment the checked folder moves into place. It wins
    /// over a Use mobile data still on its way. With nothing running (a download that failed, which the welcome's switch
    /// leaves), what came and its network go at once, so no launch takes the download up again.
    func cancel() {
        switching = false
        guard let work else { return discard() }
        work.cancel()
    }

    /// Cancel with nothing running: what a download left is deleted, as a running one's end does after its Cancel.
    private func discard() {
        guard phase != .ready else { return }
        try? FileManager.default.removeItem(at: ModelDownloader.staging(for: folder))
        defaults.removeObject(forKey: startedNetworkKey)
        phase = .missing
    }

    /// Delete (Settings asks first): the model's folder goes, and with it anything a download left. A folder that cannot
    /// be removed leaves the model as its files are: still ready when they all stayed.
    func delete() {
        guard work == nil else { return }
        let files = FileManager.default
        try? files.removeItem(at: ModelDownloader.staging(for: folder))
        defaults.removeObject(forKey: startedNetworkKey)
        forgetLoaded()
        do {
            if files.fileExists(atPath: folder.path) { try files.removeItem(at: folder) }
        } catch {
            guard !ModelDownloader.isInstalled(entries, in: folder) else { return }
        }
        phase = .missing
    }

    /// At launch: a download that a restart cut off goes on by itself, over the network it was using (its task is in
    /// that network's session); one from before that was kept follows Download on Wi-Fi only.
    func resumeIfStarted() {
        guard phase == .missing, started else { return }
        guard let cellular = defaults.object(forKey: startedNetworkKey) as? Bool else { return download() }
        start(cellular: cellular)
    }

    private var startedNetworkKey: String { "\(Self.networkKey).\(id)" }

    private func start(cellular: Bool) {
        guard work == nil, phase != .ready else { return }
        starts += 1
        let token = starts
        forgetLoaded() // the next first load compiles this download
        self.cellular = cellular
        defaults.set(cellular, forKey: startedNetworkKey)
        phase = .downloading(done: 0, total: totalBytes)
        work = Task {
            let outcome: Phase
            do {
                try await downloader.install(entries, in: folder, cellular: cellular) { done, total in
                    Task { @MainActor in self.progress(done, total, of: token) } // the download's task holds self already
                }
                outcome = .ready
            } catch is CancellationError {
                outcome = .missing
            } catch let failure as ModelDownloader.Failure {
                outcome = .failed(failure)
            } catch {
                outcome = .failed(.interrupted) // a file operation failed; trying again goes on from what is on disk
            }
            guard token == starts else { return } // a newer start owns the download now
            work = nil
            let switched = switching // read and cleared on every end, so it never carries over to another download
            switching = false
            if outcome == .missing {
                if switched { return start(cellular: true) } // Use mobile data: again on any network, keeping what came
                // Cancel: what came is deleted, as on Android, so nothing goes on at the next launch.
                try? FileManager.default.removeItem(at: ModelDownloader.staging(for: folder))
            }
            if outcome == .ready || outcome == .missing { defaults.removeObject(forKey: startedNetworkKey) }
            phase = outcome
            if outcome == .ready { onInstalled(folder) }
        }
    }

    /// A progress report from download start number `token`: dropped once a newer start began, or after the end.
    func progress(_ done: Int64, _ total: Int64, of token: Int) {
        guard token == starts, case .downloading = phase else { return }
        phase = .downloading(done: done, total: total)
    }

    #if DEBUG
    /// Screenshots and UI tests (Debug builds): the download shows as `percent` done and stays there, with no transfer.
    func holdDownload(at percent: Int) {
        phase = .downloading(done: (totalBytes * Int64(min(max(percent, 0), 99)) + 99) / 100, total: totalBytes) // shows `percent`
    }

    /// Screenshots (Debug builds): the model shows held in `state`, with no transfer: waiting for Wi-Fi or a connection
    /// (at the held download's point, else 42%), checking the files, or failed (paused, no space, check failed), or
    /// missing.
    func hold(_ state: String) {
        let waits: Phase
        if case .downloading(let done, let total) = phase, done < total { waits = phase } else {
            waits = .downloading(done: totalBytes * 42 / 100, total: totalBytes)
        }
        switch state {
        case "wifi": (heldWaiting, phase) = (.wifi, waits)
        case "connection": (heldWaiting, phase) = (.connection, waits)
        case "checking": phase = .downloading(done: totalBytes, total: totalBytes)
        case "paused": phase = .failed(.interrupted)
        case "noSpace": phase = .failed(.noSpace(needed: totalBytes + ModelDownloader.margin))
        case "checkFailed": phase = .failed(.checkFailed)
        case "missing": phase = .missing
        default: break
        }
    }
    #endif
}
