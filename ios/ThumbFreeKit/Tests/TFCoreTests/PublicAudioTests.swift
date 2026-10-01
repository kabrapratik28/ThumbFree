import Foundation
import Testing
@testable import TFCore

enum TestAudio {
    static let dir = URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent().deletingLastPathComponent()
        .deletingLastPathComponent().deletingLastPathComponent()
        .appendingPathComponent("testdata/public")
    static func url(_ name: String) -> URL { dir.appendingPathComponent(name) }
    static func references() throws -> [String: String] {
        let text = try String(contentsOf: url("refs.tsv"), encoding: .utf8)
        var refs: [String: String] = [:]
        for line in text.split(separator: "\n") {
            let parts = line.split(separator: "\t", maxSplits: 1)
            if parts.count == 2 { refs[String(parts[0])] = String(parts[1]) }
        }
        return refs
    }
}

@Test func publicClipsAre16kMonoAndHaveReferences() throws {
    let refs = try TestAudio.references()
    #expect(refs.count >= 13)
    for name in refs.keys {
        let samples = try WavFile.readMono16k(url: TestAudio.url(name))
        #expect(samples.count > 16_000, "\(name) is shorter than 1 s")
        #expect(samples.allSatisfy { $0 >= -1 && $0 <= 1 })
    }
}

@Test func jfkIsElevenSeconds() throws {
    let samples = try WavFile.readMono16k(url: TestAudio.url("jfk.wav"))
    #expect(abs(Double(samples.count) / 16_000 - 11.0) < 0.1)
}
