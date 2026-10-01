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

    private(set) var phase: Phase
    let entries: [ModelEntry]
    /// Where the model goes: Application Support/Models/<id>.
    let folder: URL
    /// Runs once a download has put a checked model in `folder`.
    var onInstalled: (URL) -> Void = { _ in }
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
        guard case .downloading(let done, let total) = phase, done < total else { return nil }
        guard network.online else { return .connection } // Airplane Mode: Wi-Fi alone would not do
        return !cellular && !network.wifi ? .wifi : nil
    }

    /// Download on Wi-Fi only (Settings), which a new download follows: on unless you turned it off.
    var wifiOnly: Bool { defaults.object(forKey: Self.wifiOnlyKey) as? Bool ?? true }

    /// Download, or Try again: starts the download, or goes on with one that a network error or a restart stopped. It
    /// follows Download on Wi-Fi only.
    func download() { start(cellular: !wifiOnly) }

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
    /// over a Use mobile data still on its way.
    func cancel() {
        switching = false
        work?.cancel()
    }

    /// Delete (Settings asks first): the model's folder goes, and with it anything a download left. A folder that cannot
    /// be removed leaves the model as its files are: still ready when they all stayed.
    func delete() {
        guard work == nil else { return }
        let files = FileManager.default
        try? files.removeItem(at: ModelDownloader.staging(for: folder))
        defaults.removeObject(forKey: startedNetworkKey)
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
        guard phase == .missing, FileManager.default.fileExists(atPath: ModelDownloader.staging(for: folder).path) else { return }
        guard let cellular = defaults.object(forKey: startedNetworkKey) as? Bool else { return download() }
        start(cellular: cellular)
    }

    private var startedNetworkKey: String { "\(Self.networkKey).\(id)" }

    private func start(cellular: Bool) {
        guard work == nil, phase != .ready else { return }
        self.cellular = cellular
        defaults.set(cellular, forKey: startedNetworkKey)
        phase = .downloading(done: 0, total: totalBytes)
        work = Task {
            let outcome: Phase
            do {
                try await downloader.install(entries, in: folder, cellular: cellular) { done, total in
                    Task { @MainActor in self.progress(done, total) } // the download's task holds self already
                }
                outcome = .ready
            } catch is CancellationError {
                outcome = .missing
            } catch let failure as ModelDownloader.Failure {
                outcome = .failed(failure)
            } catch {
                outcome = .failed(.interrupted) // a file operation failed; trying again goes on from what is on disk
            }
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

    private func progress(_ done: Int64, _ total: Int64) {
        guard case .downloading = phase else { return } // a report that arrived after the end
        phase = .downloading(done: done, total: total)
    }
}
