package io.github.kabrapratik28.thumbfree.core.text

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CleanupPromptTest {
    @Test
    fun cleanIsInstructionV2WithTheTakeLast() {
        val prompt = CleanupPrompt.build("  um so I I think we should go  ", CleanupStyle.CLEAN)
        assertThat(prompt).startsWith("You clean up text that someone dictated by voice.")
        assertThat(prompt).contains("When the speaker corrects themselves (\"five no six\", \"Monday no Tuesday\"), keep only what they said last.")
        assertThat(prompt).endsWith("Text: um so I I think we should go\nCleaned text:")
        assertThat(prompt).doesNotContain("Style:")
    }

    @Test
    fun eachOtherStyleIsOneRewriteWithItsOwnLabel() {
        val labels = mapOf(
            CleanupStyle.SHORTER to "Shorter version:", CleanupStyle.FRIENDLY to "Friendly version:",
            CleanupStyle.PROFESSIONAL to "Professional version:", CleanupStyle.SIMPLE to "Simple version:",
        )
        for ((style, label) in labels) {
            val prompt = CleanupPrompt.build("see you at seven", style)
            assertThat(prompt).startsWith("You rewrite text that someone dictated by voice.")
            assertThat(prompt).contains("Reply with one rewritten version only.")
            assertThat(prompt).doesNotContain("Cleaned text:") // Clean's examples led to two versions
            assertThat(prompt).endsWith("Text: see you at seven\n$label")
        }
        assertThat(CleanupPrompt.labels).containsAtLeastElementsIn(labels.values + "Cleaned text:")
    }
}
