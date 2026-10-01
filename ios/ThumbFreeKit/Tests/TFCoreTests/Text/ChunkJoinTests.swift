import Testing
@testable import TFCore

@Suite struct ChunkJoinTests {
    @Test func trimsAndJoinsWithOneSpace() {
        // Empty chunks add nothing; trimming uses the Unicode White_Space set (U+00A0 and U+0085 go, U+001C stays).
        let chunks = [" So I was thinking.\n", "", "\u{A0}about  this\u{85}", "   ", "\u{1C}done. "]
        TextTest.expectSame(ChunkJoin.join(chunks), "So I was thinking. about  this \u{1C}done.")
    }
}
