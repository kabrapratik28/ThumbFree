package io.github.kabrapratik28.thumbfree.core.text

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ChunkJoinTest {
    @Test
    fun trimsAndJoinsWithOneSpace() {
        // Chunk texts are trimmed and joined with one space. Empty chunks add nothing, and trimming uses Rust's
        // whitespace set (U+00A0 and U+0085 go, U+001C stays).
        val chunks = listOf(" So I was thinking.\n", "", "\u00A0about  this\u0085", "   ", "\u001Cdone. ")
        assertThat(joinChunks(chunks)).isEqualTo("So I was thinking. about  this \u001Cdone.")
    }
}
