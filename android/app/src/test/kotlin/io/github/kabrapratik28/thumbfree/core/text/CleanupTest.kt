package io.github.kabrapratik28.thumbfree.core.text

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CleanupTest {
    @Test
    fun failsOpen() {
        // B1, adapted from MIT-licensed code; see THIRD_PARTY_NOTICES.md.
        val raw = "原始轉錄。"
        assertThat(cleanup(raw, null) { error("simulated optional cleanup failure") }).isEqualTo(raw)
    }

    @Test
    fun orderIsCustomWordsFillersNormalize() {
        // D16 with a stub custom-word step, so only the order is under test. The stub merges "a Zendesk" into
        // "Zendesk", which CustomWords' guard 4 refuses (CustomWordsTest.pipelineOrder).
        val raw = "um so I opened a Zendesk uh ticket ticket ticket"
        var seen = ""
        val customWords = { text: String -> seen = text; text.replace("a Zendesk", "Zendesk") }

        assertThat(cleanup(raw, "en", customWords)).isEqualTo("so I opened Zendesk ticket")
        assertThat(seen).isEqualTo(raw) // custom words run first, on the raw text
        assertThat(cleanup(raw, "en")).isEqualTo("so I opened a Zendesk ticket") // no custom words by default
    }
}
