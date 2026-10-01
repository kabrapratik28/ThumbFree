import Foundation
import Testing
@testable import ThumbFree

/// The keyboard's mark, the app's only sign that the keyboard is added with Full Access.
@Suite final class KeyboardMarkTests {
    let folder: URL

    init() throws { folder = try TestFiles.folder() }

    deinit { try? FileManager.default.removeItem(at: folder) }

    @Test func theMarkSaysWhenTheKeyboardLastAppeared() throws {
        #expect(KeyboardMark.lastSeen(in: folder) == nil)
        KeyboardMark.record(in: folder)
        let seen = try #require(KeyboardMark.lastSeen(in: folder))
        #expect(abs(seen.timeIntervalSinceNow) < 60)
    }
}
