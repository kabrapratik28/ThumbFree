import Foundation
import Synchronization

/// Canned HTTP answers for the downloader's tests: a URLProtocol in the test session, so no test touches the network.
/// Replies are queued per URL and each request takes the next one; the last reply repeats. Tests use a repo of their
/// own (a UUID), so tests running in parallel never share a URL.
final class StubServer: URLProtocol, @unchecked Sendable {
    enum Reply: Sendable {
        /// The file from the request's Range start (206 with Content-Range) or whole (200). `cut`: the connection drops
        /// after that many bytes of the body.
        case file(Data, cut: Int? = nil)
        /// The first half of the file (a 200 for all of it), then nothing more until the request is cancelled.
        case stall(Data)
        /// An empty body with this status and these headers.
        case status(Int, headers: [String: String] = [:])
        /// The connection fails at once with this error, as URLSession says it (no network, mobile data turned off for the
        /// app, roaming off, iOS cancelling a task).
        case fail(URLError.Code)
    }

    private static let replies = Mutex<[URL: [Reply]]>([:])
    private static let seen = Mutex<[URL: [URLRequest]]>([:])

    static func serve(_ url: URL, _ queued: [Reply]) { replies.withLock { $0[url] = queued } }

    /// The requests made for `url`, oldest first.
    static func requests(_ url: URL) -> [URLRequest] { seen.withLock { $0[url] ?? [] } }

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func stopLoading() {}

    override func startLoading() {
        guard let url = request.url, let client else { return }
        Self.seen.withLock { $0[url, default: []].append(request) }
        let reply = Self.replies.withLock { replies -> Reply? in
            guard var queued = replies[url], let next = queued.first else { return nil }
            if queued.count > 1 { queued.removeFirst() }
            replies[url] = queued
            return next
        }
        switch reply {
        case .file(let data, let cut)?:
            let start = request.value(forHTTPHeaderField: "Range")
                .flatMap { Int($0.dropFirst("bytes=".count).dropLast()) } ?? 0
            var headers = ["Content-Length": "\(data.count - start)"]
            if start > 0 { headers["Content-Range"] = "bytes \(start)-\(data.count - 1)/\(data.count)" }
            respond(url, start > 0 ? 206 : 200, headers)
            let body = data.dropFirst(start)
            client.urlProtocol(self, didLoad: Data(body.prefix(cut ?? body.count)))
            if cut == nil {
                client.urlProtocolDidFinishLoading(self)
            } else {
                // A moment later, as on a real network: URLSession drops data it has not yet handed to the delegate
                // when the failure comes in the same instant.
                DispatchQueue.global().asyncAfter(deadline: .now() + .milliseconds(50)) {
                    self.client?.urlProtocol(self, didFailWithError: URLError(.networkConnectionLost))
                }
            }
        case .stall(let data)?:
            respond(url, 200, ["Content-Length": "\(data.count)"])
            client.urlProtocol(self, didLoad: data.prefix(data.count / 2))
        case .status(let code, let headers)?:
            respond(url, code, headers.merging(["Content-Length": "0"]) { old, _ in old })
            client.urlProtocolDidFinishLoading(self)
        case .fail(let code)?:
            client.urlProtocol(self, didFailWithError: URLError(code))
        case nil:
            respond(url, 404, ["Content-Length": "0"])
            client.urlProtocolDidFinishLoading(self)
        }
    }

    private func respond(_ url: URL, _ code: Int, _ headers: [String: String]) {
        guard let response = HTTPURLResponse(url: url, statusCode: code, httpVersion: "HTTP/1.1", headerFields: headers) else { return }
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
    }
}
