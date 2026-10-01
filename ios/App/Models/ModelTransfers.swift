import Foundation
import os
import Synchronization
import TFCore

/// The URLSession model files come through: one download task per file, for the rest of its `.part` (a Range request
/// from the part's length). In the app it is a background session, so a download goes on rather than pausing: iOS keeps
/// a transfer going while ThumbFree is in the background, suspended or ended by the system, and relaunches the app to
/// hand over what finished. A background session takes one delegate for all its tasks, so each task's description says
/// which part it fills, from which byte, and how long the file is; a finished body is added to its part at once, since
/// iOS deletes the temporary file as soon as the callback returns. No resume data is kept: the part files are the only
/// record, so nothing saved can be fed back to a session that cannot read it.
final class ModelTransfers: NSObject, URLSessionDownloadDelegate, Sendable {
    /// A try worth another: a network error, a short body, 408, 429, 5xx, 416, a range that started elsewhere, or a body
    /// for a part that changed meanwhile.
    struct Retry: Error {
        let failure: ModelDownloader.Failure
        let after: Duration?
    }

    /// A try that ended without the whole file. `cause` is a `Retry` or what the download says; the HTTP status and the
    /// system error behind it are for the log.
    struct Ended: Error {
        let cause: Error
        let status: Int?
        let error: NSError?
        /// What the download says when this try is its last.
        var failure: Error { (cause as? Retry)?.failure ?? cause }
    }

    /// What a task's description holds: "<file bytes> <first byte> <part path from root>".
    private struct Job {
        let bytes: Int64
        let offset: Int64
        let path: String

        init(bytes: Int64, offset: Int64, path: String) {
            self.bytes = bytes
            self.offset = offset
            self.path = path
        }

        init?(_ description: String?) {
            guard let parts = description?.split(separator: " ", maxSplits: 2), parts.count == 3,
                  let bytes = Int64(parts[0]), let offset = Int64(parts[1]) else { return nil }
            self.init(bytes: bytes, offset: offset, path: String(parts[2]))
        }

        var description: String { "\(bytes) \(offset) \(path)" }
    }

    private struct Waiter {
        let continuation: CheckedContinuation<Void, Error>
        let progress: @Sendable (Int64) -> Void
    }

    private struct State {
        var session: URLSession?
        var waiters: [Int: Waiter] = [:]
        /// Why a finished task's body was not taken, until its completion call.
        var outcomes: [Int: Error] = [:]
        var reported: [Int: Int64] = [:]
        /// Results of tasks nobody waited for yet (one iOS carried on while ThumbFree was not running).
        var finished: [Int: Result<Void, Error>] = [:]
        /// Tasks this app cancelled because you asked (Cancel, Use mobile data). Any other cancel is iOS's, and is tried
        /// again like a broken connection.
        var cancelled: Set<Int> = []
    }

    private static let log = Logger(subsystem: Brand.bundleID, category: "download")

    static let backgroundID = "io.github.kabrapratik28.thumbfree.models"
    /// Wi-Fi only, the default.
    static let wifiOnly = ModelTransfers(configuration: configuration(cellular: false), root: AppEnvironment.modelsRoot)
    /// Any network: "Use mobile data".
    static let anyNetwork = ModelTransfers(configuration: configuration(cellular: true), root: AppEnvironment.modelsRoot)

    static func background(cellular: Bool) -> ModelTransfers { cellular ? anyNetwork : wifiOnly }

    /// The session iOS names when it relaunches the app for its events: asking makes it again, which takes them.
    static func background(identifier: String) -> ModelTransfers? {
        [wifiOnly, anyNetwork].first { $0.identifier == identifier }
    }

    /// Where the tasks' paths start: Application Support/Models in the app, a test's own folder in tests.
    let root: URL
    let identifier: String?
    private let state = Mutex(State())

    init(configuration: URLSessionConfiguration, root: URL) {
        self.root = root
        identifier = configuration.identifier
        super.init()
        let session = URLSession(configuration: configuration, delegate: self, delegateQueue: nil)
        state.withLock { $0.session = session }
    }

    /// A plain session: `-TFModelFixture`'s file URLs (a background session takes only HTTP and HTTPS) and tests, which
    /// never use a background session (it would ignore their stub).
    static func foreground(root: URL, protocols: [AnyClass]? = nil) -> ModelTransfers {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = protocols ?? configuration.protocolClasses
        configuration.timeoutIntervalForRequest = 60
        return ModelTransfers(configuration: configuration, root: root)
    }

    /// No cache (a 445 MB body must never land in the URL cache); iOS relaunches the app for the session's events.
    private static func configuration(cellular: Bool) -> URLSessionConfiguration {
        let configuration = URLSessionConfiguration.background(withIdentifier: backgroundID + (cellular ? ".any" : ".wifi"))
        configuration.sessionSendsLaunchEvents = true
        configuration.isDiscretionary = false
        configuration.allowsCellularAccess = cellular
        configuration.urlCache = nil
        configuration.requestCachePolicy = .reloadIgnoringLocalCacheData
        return configuration
    }

    /// The session, until `invalidate()` ends it.
    var session: URLSession? { state.withLock { $0.session } }

    /// Ends the session and its tasks (tests: each test's session goes with it). A later fetch throws a cancel.
    func invalidate() {
        state.withLock { state -> URLSession? in
            defer { state.session = nil }
            return state.session
        }?.invalidateAndCancel()
    }

    /// The rest of one file into `part` (inside `root`), `bytes` long in all. A task of ours already on the session for
    /// this part (one iOS carried on while ThumbFree was not running, in any state) is joined, never started twice.
    /// `progress` gets the bytes of the file in so far. A failed try throws `Ended`; your Cancel throws a cancel.
    func fetch(_ url: URL, to part: URL, bytes: Int64, progress: @escaping @Sendable (Int64) -> Void) async throws {
        guard let session else { throw CancellationError() }
        guard let path = path(of: part) else { throw ModelDownloader.Failure.checkFailed }
        // The tasks first, then the part's length: on a relaunch a task that finished while ThumbFree was not running may
        // still be adding its body (the session answers after the delegate calls it has queued).
        let tasks = await session.allTasks
        var offset = ModelDownloader.size(of: part) ?? 0
        if offset > bytes {
            try? FileManager.default.removeItem(at: part)
            offset = 0
        }
        guard offset < bytes else { return } // complete, not yet renamed
        let task: URLSessionTask
        let job: Job
        if let found = tasks.first(where: { Job($0.taskDescription)?.path == path }),
           let described = Job(found.taskDescription) {
            task = found
            job = described
        } else {
            var request = URLRequest(url: url)
            request.setValue("identity", forHTTPHeaderField: "Accept-Encoding") // a gzipped body breaks offsets and sizes
            if offset > 0 { request.setValue("bytes=\(offset)-", forHTTPHeaderField: "Range") }
            task = session.downloadTask(with: request)
            job = Job(bytes: bytes, offset: offset, path: path)
            task.taskDescription = job.description
        }
        do {
            try await withTaskCancellationHandler {
                try await withCheckedThrowingContinuation { continuation in
                    let done = state.withLock { state -> Result<Void, Error>? in
                        if let done = state.finished.removeValue(forKey: task.taskIdentifier) { return done }
                        state.waiters[task.taskIdentifier] = Waiter(continuation: continuation) { progress(job.offset + $0) }
                        return nil
                    }
                    if let done { return continuation.resume(with: done) } // it ended between the look and the wait
                    if task.state == .suspended { task.resume() }
                }
            } onCancel: {
                state.withLock { _ = $0.cancelled.insert(task.taskIdentifier) }
                task.cancel()
            }
        } catch let cancel as CancellationError {
            throw cancel
        } catch {
            throw Ended(cause: error, status: (task.response as? HTTPURLResponse)?.statusCode, error: task.error as NSError?)
        }
    }

    // MARK: The session's delegate (its own serial queue)

    func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask, didWriteData bytesWritten: Int64,
                    totalBytesWritten: Int64, totalBytesExpectedToWrite: Int64) {
        let id = downloadTask.taskIdentifier
        let progress = state.withLock { state -> (@Sendable (Int64) -> Void)? in
            guard totalBytesWritten - (state.reported[id] ?? 0) >= 1 << 20 || totalBytesWritten == totalBytesExpectedToWrite
            else { return nil }
            state.reported[id] = totalBytesWritten
            return state.waiters[id]?.progress
        }
        progress?(totalBytesWritten)
    }

    func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask, didFinishDownloadingTo location: URL) {
        let outcome = take(location, response: downloadTask.response, for: Job(downloadTask.taskDescription))
        if let outcome { state.withLock { $0.outcomes[downloadTask.taskIdentifier] = outcome } }
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        let id = task.taskIdentifier
        let (waiter, result) = state.withLock { state -> (Waiter?, Result<Void, Error>) in
            state.reported[id] = nil
            let ours = state.cancelled.remove(id) != nil
            let result: Result<Void, Error>
            if let outcome = state.outcomes.removeValue(forKey: id) {
                result = .failure(outcome)
            } else if let error = error as? URLError, error.code == .cancelled, ours {
                result = .failure(CancellationError()) // your Cancel: SpeechModel deletes what came
            } else if let error = error as? URLError, Self.offline.contains(error.code) {
                result = .failure(Retry(failure: .noInternet, after: nil))
            } else if error != nil {
                result = .failure(Retry(failure: .interrupted, after: nil)) // a network error: that try's bytes are gone
            } else {
                result = .success(())
            }
            guard let waiter = state.waiters.removeValue(forKey: id) else {
                state.finished[id] = result
                return (nil, result)
            }
            return (waiter, result)
        }
        if case .failure(let failure) = result, !(failure is CancellationError) {
            let code = (error as NSError?).map { "\($0.domain) \($0.code)" } ?? "no error"
            let status = (task.response as? HTTPURLResponse)?.statusCode ?? 0
            Self.log.notice("A model file's try failed: \(code, privacy: .public), HTTP \(status, privacy: .public)")
        }
        waiter?.continuation.resume(with: result)
    }

    /// No way online now: no connection, mobile data turned off for ThumbFree, or roaming turned off abroad.
    static let offline: Set<URLError.Code> = [.notConnectedToInternet, .dataNotAllowed, .internationalRoamingOff]

    func urlSessionDidFinishEvents(forBackgroundURLSession session: URLSession) {
        guard let identifier = session.configuration.identifier else { return }
        Task { @MainActor in AppDelegate.backgroundEventsDone(identifier) }
    }

    // MARK: Parts

    private func path(of part: URL) -> String? {
        let base = root.standardizedFileURL.path + "/"
        let path = part.standardizedFileURL.path
        return path.hasPrefix(base) ? String(path.dropFirst(base.count)) : nil
    }

    /// Adds a finished body to its part: the rest of the file (206 from the part's length), or all of it (200). Nil when
    /// the part is now whole; else why not.
    private func take(_ location: URL, response: URLResponse?, for job: Job?) -> Error? {
        guard let job else { return ModelDownloader.Failure.checkFailed }
        let part = root.appendingPathComponent(job.path)
        // No folder: a Cancel (or a reset) deleted the download while this body was on its way. It is not brought back.
        guard FileManager.default.fileExists(atPath: part.deletingLastPathComponent().path) else { return CancellationError() }
        // The part changed since this task began (a task from before a relaunch, or a second one for the part): its body
        // is stale and the part is fine, so the next try goes on from the part's length.
        guard (ModelDownloader.size(of: part) ?? 0) == job.offset else { return Retry(failure: .interrupted, after: nil) }
        let http = response as? HTTPURLResponse
        let got = ModelDownloader.size(of: location) ?? 0
        do {
            switch http?.statusCode ?? 200 { // a file URL (the UI tests' fixture) has no status
            case 206:
                guard let range = http?.value(forHTTPHeaderField: "Content-Range").flatMap(Self.contentRange),
                      range.start == job.offset else {
                    try? FileManager.default.removeItem(at: part) // the range starts elsewhere: start the file over
                    return Retry(failure: .interrupted, after: nil)
                }
                guard range.total == nil || range.total == job.bytes, job.offset + got <= job.bytes else {
                    try? FileManager.default.removeItem(at: part)
                    return ModelDownloader.Failure.checkFailed
                }
                try Self.append(location, to: part, at: job.offset)
                return job.offset + got == job.bytes ? nil : Retry(failure: .interrupted, after: nil) // the body ended early
            case 200:
                guard got <= job.bytes else { return ModelDownloader.Failure.checkFailed } // longer than the catalog says
                try Self.append(location, to: part, at: 0) // from zero: replaces the part
                return got == job.bytes ? nil : Retry(failure: .interrupted, after: nil)
            case 416:
                try? FileManager.default.removeItem(at: part)
                return Retry(failure: .interrupted, after: nil)
            case 408, 429, 500...599:
                return Retry(failure: .interrupted, after: Self.retryAfter(http))
            default:
                return ModelDownloader.Failure.interrupted
            }
        } catch {
            return ModelDownloader.Failure.interrupted // the disk is full: the next try checks the space
        }
    }

    /// `body` onto the end of `part`, whose length is `offset`; from zero, the body becomes the part. Each 4 MiB chunk is
    /// copied in its own autorelease pool: without it the chunks pile up until the delegate call returns (about the whole
    /// body in memory).
    private static func append(_ body: URL, to part: URL, at offset: Int64) throws {
        let files = FileManager.default
        guard offset > 0 else {
            try? files.removeItem(at: part)
            return try files.moveItem(at: body, to: part)
        }
        let output = try FileHandle(forWritingTo: part)
        defer { try? output.close() }
        try output.seekToEnd()
        let input = try FileHandle(forReadingFrom: body)
        defer { try? input.close() }
        while try autoreleasepool(invoking: {
            guard let chunk = try input.read(upToCount: 4 << 20), !chunk.isEmpty else { return false }
            try output.write(contentsOf: chunk)
            return true
        }) {}
    }

    /// "bytes <start>-<end>/<total>": the start, and the total unless it is "*".
    static func contentRange(_ value: String) -> (start: Int64, total: Int64?)? {
        guard value.hasPrefix("bytes "), let dash = value.firstIndex(of: "-"), let slash = value.firstIndex(of: "/"),
              let start = Int64(value[value.index(value.startIndex, offsetBy: 6)..<dash]) else { return nil }
        return (start, Int64(value[value.index(after: slash)...]))
    }

    /// Retry-After in seconds. ponytail: an HTTP date reads as none, so the backoff step applies; parse dates if a
    /// server sends them.
    static func retryAfter(_ response: HTTPURLResponse?) -> Duration? {
        response?.value(forHTTPHeaderField: "Retry-After").flatMap { Int($0.trimmingCharacters(in: .whitespaces)) }
            .map { .seconds($0) }
    }
}
