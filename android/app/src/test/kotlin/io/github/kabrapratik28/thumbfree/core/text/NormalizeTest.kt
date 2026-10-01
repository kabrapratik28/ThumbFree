package io.github.kabrapratik28.thumbfree.core.text

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class NormalizeTest {
    @Test
    fun collapsesTriples() {
        // A14 to A18, adapted from MIT-licensed code; see THIRD_PARTY_NOTICES.md. D15 is our own. None of these
        // inputs has a filler, so normalize alone gives what the whole cleanup gives.
        val rows = mapOf(
            "w wh wh wh wh wh wh wh wh wh why" to "w wh why", // A14 test_filter_stutter_collapse
            "I I I I think so so so so" to "I think so", // A15 test_filter_stutter_short_words
            "Check data doc doc doc doc documentation." to "Check data doc documentation.", // A16 test_filter_stutter_longer_words
            "No NO no NO no" to "No", // A17 test_filter_stutter_mixed_case
            "no no is fine" to "no no is fine", // A18 test_filter_stutter_preserves_two_repetitions
            "The the the cat" to "The cat", // D15
        )
        assertThat(rows.mapValues { normalize(it.key) }).containsExactlyEntriesIn(rows)
    }
}
