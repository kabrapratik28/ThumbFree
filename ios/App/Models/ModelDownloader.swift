import Foundation
import os
import TFCore

/// Downloads a model's files into a staging folder next to its folder, checks every file with `ModelVerifier`, then
/// moves the staging folder into place with one rename. Bytes go to `<file>.part`, and the next
/// try asks only for the rest with a Range request, so an app restart never starts a finished part over. Each file comes
/// through `ModelTransfers`, which in the app is a background session: the download goes on while ThumbFree is in the
/// background or suspended. Network errors and HTTP 408, 429 and 5xx are tried again after 2, 4 and 8 s, or after the
/// server's Retry-After when longer (at most 60 s). Every write goes through `ModelFile.url(in:)`.
struct ModelDownloader: Sendable {
    /// Why a download stopped, as the app words it.
    enum Failure: Error, Equatable, Sendable {
        /// The iPhone has no connection.
        case noInternet
        /// `needed` bytes must be free: what is left to fetch plus the 1 GiB margin.
        case noSpace(needed: Int64)
        /// The connection broke or the server kept failing, three retries in a row. Trying again goes on from there.
        case interrupted
        /// A file did not match its size or SHA-256. It was deleted, so trying again fetches it fresh.
        case checkFailed
    }

    static let margin: Int64 = 1 << 30
    static let backoff: [Duration] = [.seconds(2), .seconds(4), .seconds(8)]
    static let longestWait: Duration = .seconds(60)
    private static let log = Logger(subsystem: Brand.bundleID, category: "download")

    /// The session a download uses: Wi-Fi only unless `cellular` ("Use mobile data"). Tests and `-TFModelFixture` give
    /// one plain session for both.
    var transfers: @Sendable (_ cellular: Bool) -> ModelTransfers = { ModelTransfers.background(cellular: $0) }
    /// Where a file comes from: Hugging Face at the entry's pinned revision. `-TFModelFixture` points it at a file.
    var source: @Sendable (ModelEntry, ModelFile) -> URL? = ModelDownloader.huggingFace
    var sleep: @Sendable (Duration) async throws -> Void = { try await Task.sleep(for: $0) }
    var freeBytes: @Sendable () -> Int64 = { SessionHost.freeBytes() }

    /// Every file of `entries` into `folder`, checked. Files already in the staging folder are kept (a download a
    /// restart cut off, or the good files of a copy the launch check turned down), and parts go on from their length.
    /// `cellular`: this download may use mobile data. `progress` gets the bytes on disk out of the total; when they are
    /// equal, the files are being checked. A download that stops logs one line: why, the HTTP status and the error behind
    /// it (domain and code; never a URL or text). Each failed try has its own line, from `ModelTransfers`; your Cancel
    /// logs nothing.
    @concurrent func install(_ entries: [ModelEntry], in folder: URL, cellular: Bool = false,
                             progress: @escaping @Sendable (_ done: Int64, _ total: Int64) -> Void = { _, _ in }) async throws {
        do {
            try await installFiles(entries, in: folder, cellular: cellular, progress: progress)
        } catch {
            let ended = error as? ModelTransfers.Ended
            let failure = ended?.failure ?? error
            if !(failure is CancellationError) {
                let status = ended?.status.map { "HTTP \($0)" } ?? "no HTTP status"
                let behind = ended?.error.map { Self.name($0) } ?? "no system error"
                Self.log.error("Download stopped: \(Self.name(failure), privacy: .public), \(status, privacy: .public), \(behind, privacy: .public)")
            }
            throw failure
        }
    }

    /// An error for the log: the app's failure by name, anything else by domain and code. Never a URL or user text.
    private static func name(_ error: Error) -> String {
        if let failure = error as? Failure { return "\(failure)" }
        let error = error as NSError
        return "\(error.domain) \(error.code)"
    }

    @concurrent private func installFiles(_ entries: [ModelEntry], in folder: URL, cellular: Bool,
                                          progress: @escaping @Sendable (_ done: Int64, _ total: Int64) -> Void) async throws {
        let files = FileManager.default
        let staging = Self.staging(for: folder)
        if !files.fileExists(atPath: staging.path), files.fileExists(atPath: folder.path) {
            try files.moveItem(at: folder, to: staging)
        }
        var jobs: [(file: ModelFile, url: URL, source: URL)] = []
        for entry in entries {
            for file in entry.files {
                guard let url = file.url(in: staging), let source = source(entry, file) else { throw Failure.checkFailed }
                jobs.append((file, url, source))
            }
        }
        let total = jobs.reduce(0) { $0 + $1.file.bytes }
        var done = jobs.reduce(0) { $0 + Self.bytesOnDisk($1.url, of: $1.file) }
        let needed = total - done + Self.margin
        guard freeBytes() >= needed else { throw Failure.noSpace(needed: needed) }
        try files.createDirectory(at: staging, withIntermediateDirectories: true)
        var values = URLResourceValues()
        values.isExcludedFromBackup = true // the rename keeps it, so the model folder is never in a backup
        var excluded = staging
        try excluded.setResourceValues(values)
        progress(done, total)
        let transfers = transfers(cellular)
        // Smallest first: the big weight file comes last, so a download you leave in the background has only that file
        // on its way, and nothing new has to start while ThumbFree is in the background (iOS may hold such starts back).
        for job in jobs.sorted(by: { $0.file.bytes < $1.file.bytes }) where Self.size(of: job.url) != job.file.bytes {
            try files.createDirectory(at: job.url.deletingLastPathComponent(), withIntermediateDirectories: true)
            let part = Self.part(job.url)
            let others = done - (Self.size(of: part) ?? 0)
            try await fetch(job.source, to: part, bytes: job.file.bytes, with: transfers) { progress(others + $0, total) }
            try? files.removeItem(at: job.url) // a short copy from a turned-down folder
            try files.moveItem(at: part, to: job.url)
            done = others + job.file.bytes
        }
        progress(total, total)
        // The whole files on disk, right before the rename: a check at arrival would miss later damage.
        for entry in entries {
            let results = ModelVerifier.check(folder: staging, entry: entry)
            let bad = entry.files.filter { results[$0.path] != .ok }
            guard bad.isEmpty else {
                for file in bad { if let url = file.url(in: staging) { try? files.removeItem(at: url) } }
                throw Failure.checkFailed
            }
            try Task.checkCancellation() // Cancel still works while the files are checked
        }
        try Self.writeReceipt(entries, in: staging)
        try? files.removeItem(at: folder)
        try files.moveItem(at: staging, to: folder)
    }

    /// One file into `part`, from its length on. A network error, or HTTP 408, 429 or 5xx, is tried again after the next
    /// backoff step, or the server's Retry-After when longer (at most 60 s). A try that added bytes starts the count again.
    private func fetch(_ url: URL, to part: URL, bytes: Int64, with transfers: ModelTransfers,
                       progress: @escaping @Sendable (Int64) -> Void) async throws {
        var retries = 0
        while true {
            let before = Self.size(of: part) ?? 0
            do {
                return try await transfers.fetch(url, to: part, bytes: bytes, progress: progress)
            } catch let ended as ModelTransfers.Ended {
                guard let retry = ended.cause as? ModelTransfers.Retry else { throw ended }
                if (Self.size(of: part) ?? 0) > before { retries = 0 }
                guard retries < Self.backoff.count else { throw ended }
                try await sleep(max(Self.backoff[retries], min(retry.after ?? .zero, Self.longestWait)))
                retries += 1
            }
        }
    }

    /// The launch check: every file in `folder` still has the size and modification date it had when it passed the
    /// SHA-256 check, as the receipt written before the rename says (each file is verified once per size and date).
    /// The receipt names each file by its path and pinned SHA-256, so a new pin of a file with the same path and
    /// size (the v2 and v3 Encoder weights are both 445,187,200 bytes) is hashed again. No receipt, or any file changed,
    /// means not installed. A receipt from before the pins (by path alone) that still matches every file's size
    /// and date has those files hashed against their pins once, here, with nothing downloaded: they pass into a receipt
    /// of the new kind, so a model installed then stays ready; files that fail lose the old receipt.
    /// ponytail: a file damaged with no new size or date shows up only as a failed load (the setup row's Try again).
    /// ponytail: that one-time hash (about 465 MB) runs on the main thread at the first launch after the update, well under
    /// a second on a recent iPhone; move it into the download's "Checking the file" if a phone shows it.
    static func isInstalled(_ entries: [ModelEntry], in folder: URL) -> Bool {
        let url = folder.appendingPathComponent(receiptName)
        guard let data = try? Data(contentsOf: url),
              let saved = try? JSONDecoder().decode([String: [Double]].self, from: data) else { return false }
        if saved == receipt(entries, in: folder) { return true }
        guard saved == receipt(entries, in: folder, pinned: false) else { return false }
        let passed = entries.allSatisfy { ModelVerifier.check(folder: folder, entry: $0).values.allSatisfy { $0 == .ok } }
        if passed { try? writeReceipt(entries, in: folder) } else { try? FileManager.default.removeItem(at: url) }
        return passed
    }

    static let receiptName = "verified.json"

    /// Each file's size and modification date by "<catalog path> <pinned SHA-256>" (`pinned` false: by path alone, as
    /// older receipts have it); nil when a file is missing, has another size, or its path would leave the folder.
    private static func receipt(_ entries: [ModelEntry], in folder: URL, pinned: Bool = true) -> [String: [Double]]? {
        var receipt: [String: [Double]] = [:]
        for file in entries.flatMap(\.files) {
            guard let url = file.url(in: folder), let attributes = try? FileManager.default.attributesOfItem(atPath: url.path),
                  let size = attributes[.size] as? Int64, size == file.bytes,
                  let date = attributes[.modificationDate] as? Date else { return nil }
            receipt[pinned ? "\(file.path) \(file.sha256)" : file.path] = [Double(size), date.timeIntervalSinceReferenceDate]
        }
        return receipt
    }

    private static func writeReceipt(_ entries: [ModelEntry], in folder: URL) throws {
        guard let receipt = receipt(entries, in: folder) else { throw Failure.checkFailed }
        try JSONEncoder().encode(receipt).write(to: folder.appendingPathComponent(receiptName), options: .atomic)
    }

    /// `<folder>.download`, next to the folder: where a download collects its files.
    static func staging(for folder: URL) -> URL {
        folder.deletingLastPathComponent().appendingPathComponent(folder.lastPathComponent + ".download", isDirectory: true)
    }

    /// `https://huggingface.co/<repo>/resolve/<revision>/<path>`: the pinned revision, no mirrors.
    static func huggingFace(_ entry: ModelEntry, _ file: ModelFile) -> URL? {
        URL(string: "https://huggingface.co/\(entry.repo)/resolve/\(entry.revision)/\(file.path)")
    }

    static func size(of url: URL) -> Int64? {
        (try? FileManager.default.attributesOfItem(atPath: url.path))?[.size] as? Int64
    }

    static func part(_ url: URL) -> URL { url.deletingLastPathComponent().appendingPathComponent(url.lastPathComponent + ".part") }

    private static func bytesOnDisk(_ url: URL, of file: ModelFile) -> Int64 {
        size(of: url) == file.bytes ? file.bytes : min(size(of: part(url)) ?? 0, file.bytes)
    }
}
