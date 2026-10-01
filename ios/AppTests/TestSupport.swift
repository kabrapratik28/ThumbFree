import Foundation
import Testing
@testable import ThumbFree

/// Files for the app's tests: bundled clips, fresh temporary folders and small WAVs. A suite that makes a folder is a
/// `final class` that removes it in `deinit`: tests delete what they create, and never glob-delete shared temp folders.
enum TestFiles {
    private final class Token {}

    /// A file copied into the test bundle (see `project.yml`).
    static func url(_ name: String) throws -> URL {
        try #require(Bundle(for: Token.self).url(forResource: name, withExtension: nil))
    }

    /// A `UserDefaults` suite for one test, cleared now and again by the test's `defer`, so a crashed run cannot leave
    /// anything that breaks the next. Named for this checkout's folder (this file is AppTests/TestSupport.swift) and
    /// the test rather than a fresh UUID: clearing a suite leaves its empty plist behind, so a rerun reuses the one
    /// file.
    static func defaultsSuite(_ prefix: String, test: String = #function) -> String {
        let checkout = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent().lastPathComponent
        let name = "\(prefix)-\(checkout)-\(test.prefix { $0 != "(" })"
        UserDefaults(suiteName: name)?.removePersistentDomain(forName: name)
        return name
    }

    static func folder() throws -> URL {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("ThumbFreeTests-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        return url
    }

    /// A 16 kHz mono 16-bit WAV holding `samples`, in `folder`.
    static func wav(_ samples: [Float], in folder: URL) throws -> URL {
        var data = Data()
        func put<T: FixedWidthInteger>(_ value: T) { withUnsafeBytes(of: value.littleEndian) { data.append(contentsOf: $0) } }
        let bytes = UInt32(samples.count * 2)
        data.append(contentsOf: Array("RIFF".utf8)); put(36 + bytes); data.append(contentsOf: Array("WAVE".utf8))
        data.append(contentsOf: Array("fmt ".utf8)); put(UInt32(16)); put(UInt16(1)); put(UInt16(1))
        put(UInt32(16_000)); put(UInt32(32_000)); put(UInt16(2)); put(UInt16(16))
        data.append(contentsOf: Array("data".utf8)); put(bytes)
        for sample in samples { put(Int16(max(-1, min(1, sample)) * 32_767)) }
        let url = folder.appendingPathComponent("clip-\(UUID().uuidString).wav")
        try data.write(to: url)
        return url
    }
}

/// Polls `condition` on the main actor every 20 ms until it holds, or records an issue after `timeout`.
@MainActor func waitUntil(_ timeout: Duration = .seconds(10), sourceLocation: SourceLocation = #_sourceLocation,
                          _ condition: () throws -> Bool) async throws {
    let clock = ContinuousClock()
    let end = clock.now + timeout
    while try !condition() {
        guard clock.now < end else {
            Issue.record("timed out", sourceLocation: sourceLocation)
            return
        }
        try await Task.sleep(for: .milliseconds(20))
    }
}

/// A microphone that never delivers audio. `starts` counts its starts (once it is 1, the start has returned), `stops` its
/// stops.
@MainActor final class MuteSource: AudioSource {
    private(set) var starts = 0
    private(set) var stops = 0
    func start(_ onSamples: @escaping @Sendable ([Float]) -> Void) async throws { starts += 1 }
    func stop() { stops += 1 }
}

/// An engine for host tests: counts its calls and, while held, keeps each call waiting until `release()`: a slow
/// engine, on cue.
@MainActor final class TestEngine {
    private(set) var calls = 0
    private var held: Bool
    private var waiting: [CheckedContinuation<Void, Never>] = []
    private let text: String

    init(text: String, held: Bool = false) {
        self.text = text
        self.held = held
    }

    var source: EngineSource { .custom { [self] _ in await run() } }

    func release() {
        held = false
        waiting.forEach { $0.resume() }
        waiting = []
    }

    private func run() async -> String {
        calls += 1
        if held { await withCheckedContinuation { waiting.append($0) } }
        return text
    }
}
