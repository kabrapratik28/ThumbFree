import Foundation
import Testing
import TFCore
@testable import ThumbFree

// The app's side of Clean up: the answers file, deleted takes and the deadline (docs/contract.md, Clean up).
@MainActor @Suite final class CleanUpTests {
    let root: URL
    let shared: SharedStore

    init() throws {
        root = try TestFiles.folder()
        shared = SharedStore(directory: root)
    }

    deinit { try? FileManager.default.removeItem(at: root) }

    func command(_ take: UUID, sentAt: Date = Date()) -> KeyboardCommand {
        KeyboardCommand(takeID: take, kind: .clean, sentAt: sentAt, text: "um see you at six no seven")
    }

    // The review: a take deleted from History takes its tidied words out of the App Group too.
    @Test func deletedTakesLoseTheirTidiedWords() throws {
        let kept = UUID(), deleted = UUID()
        try shared.write([CleanupResult(requestID: UUID(), takeID: deleted, state: .done, text: "See you at 7."),
                          CleanupResult(requestID: UUID(), takeID: kept, state: .done, text: "Call me.")])
        let cleanUp = CleanUp(shared: shared)
        cleanUp.forget([deleted])
        #expect(try shared.cleanups().map(\.takeID) == [kept])
    }

    // The review: a request still running when its take is deleted writes nothing.
    @Test func aLateAnswerForADeletedTakeIsNotWritten() async throws {
        let cleanUp = CleanUp(shared: shared)
        cleanUp.fakeAnswer = "See you at 7." // answered after 1 s
        let take = UUID()
        cleanUp.run(command(take), defaultStyle: .clean)
        cleanUp.forget([take])
        try await Task.sleep(for: .seconds(1.5))
        #expect(try shared.cleanups().isEmpty)
    }

    // The review: a request that waited past its deadline (behind another, or on disk while the app was away) is
    // answered at once, as failed, without asking the model.
    @Test func aRequestPastItsDeadlineFailsAtOnce() async throws {
        let cleanUp = CleanUp(shared: shared)
        cleanUp.fakeAnswer = "See you at 7."
        let late = command(UUID(), sentAt: Date(timeIntervalSinceNow: -30))
        cleanUp.run(late, defaultStyle: .clean)
        try await waitUntil(.seconds(0.8)) { try shared.cleanups().contains { $0.requestID == late.id } }
        #expect(try shared.cleanups().map(\.state) == [.failed])
    }
}
