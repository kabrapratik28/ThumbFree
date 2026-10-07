import Foundation
import Testing
@testable import TFCore

@Suite final class CleanupIPCTests {
    let dir = FileManager.default.temporaryDirectory.appendingPathComponent("CleanupIPCTests-\(UUID().uuidString)")
    var store: SharedStore { SharedStore(directory: dir) }
    let take = UUID()

    deinit { try? FileManager.default.removeItem(at: dir) }

    // The keyboard's request carries the take as typed and the style (nil: the default the app keeps).
    @Test func aCleanCommandRoundTrips() throws {
        let command = KeyboardCommand(takeID: take, kind: .clean, text: "see you at five", style: .friendly)
        try store.append(command)
        let read = try #require(try store.pendingCommands().first)
        #expect(read == command)
        #expect(read.text == "see you at five")
        #expect(read.style == .friendly)
    }

    // Files from a build without Clean up still read: no text, no style, no availability.
    @Test func olderFilesStillDecode() throws {
        let command = #"{"id":"\#(UUID().uuidString)","takeID":"\#(take.uuidString)","kind":"press","sentAt":700000000}"#
        let decoded = try JSONDecoder().decode(KeyboardCommand.self, from: Data(command.utf8))
        #expect(decoded.text == nil && decoded.style == nil)
        let status = #"{"session":"ready","engine":"readyCPU","micOn":true,"take":"idle","level":0,"updatedAt":700000000}"#
        #expect(try JSONDecoder().decode(HostStatus.self, from: Data(status.utf8)).cleanup == nil)
    }

    @Test func theStatusCarriesAvailability() throws {
        var status = HostStatus(session: .ready)
        status.cleanup = .appleIntelligenceOff
        try store.write(status)
        #expect(try store.status()?.cleanup == .appleIntelligenceOff)
    }

    // The app keeps the last ten answers, newest last.
    @Test func answersKeepTheNewestTen() throws {
        let results = (0..<12).map { CleanupResult(requestID: UUID(), takeID: take, state: $0 % 2 == 0 ? .done : .paused,
                                                    text: $0 % 2 == 0 ? "Text \($0)." : nil,
                                                    resetAt: $0 % 2 == 0 ? nil : Date(timeIntervalSince1970: 1_800_000_000)) }
        try store.write(results)
        #expect(try store.cleanups() == Array(results.suffix(10)))
        #expect(try SharedStore(directory: dir.appendingPathComponent("none")).cleanups().isEmpty)
    }

    // The take machine never sees Clean up's commands: the app handles them on their own.
    @Test(arguments: [KeyboardCommand.Kind.clean, .cleanBegan, .cleanConfirmed, .cleanUnverified])
    func cleanCommandsAreNoTakeEvent(kind: KeyboardCommand.Kind) {
        #expect(KeyboardCommand(takeID: take, kind: kind).event == nil)
    }
}
