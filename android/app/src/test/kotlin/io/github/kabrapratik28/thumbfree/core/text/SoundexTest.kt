package io.github.kabrapratik28.thumbfree.core.text

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SoundexTest {
    @Test
    fun matchesNatural() {
        // Codes from the natural 0.5.0 crate. The first letter never merges with the next
        // one, so pfister is p123 where American Soundex gives P236.
        val codes = mapOf(
            "chargebee" to "c621", "chargeb" to "c621", "robert" to "r163", "rupert" to "r163",
            "kotlin" to "k345", "cotlin" to "c345", "sandy" to "s530", "hand" to "h530", "handi" to "h530",
            "tymczak" to "t522", "ashcraft" to "a261", "zendesk" to "z532", "pfister" to "p123",
        )
        assertThat(codes.mapValues { Soundex.code(it.key) }).containsExactlyEntriesIn(codes)
    }
}
