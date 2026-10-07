package io.github.kabrapratik28.thumbfree.core.text

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CleanupPromptTest {
    @Test
    fun cleanIsInstructionV2WithTheTakeLast() {
        val prompt = CleanupPrompt.build("  um so I I think we should go  ", CleanupStyle.CLEAN)
        assertThat(prompt).startsWith("You clean up text that someone dictated by voice.")
        assertThat(prompt).contains("When the speaker corrects themselves, keep only the final version.")
        assertThat(prompt).endsWith("Text: um so I I think we should go\nCleaned text:")
        assertThat(prompt).doesNotContain("Style:")
    }

    @Test
    fun eachOtherStyleAddsItsLineBeforeTheTake() {
        for (style in CleanupStyle.entries - CleanupStyle.CLEAN) {
            val prompt = CleanupPrompt.build("see you at seven", style)
            assertThat(prompt).contains("Style: " + CleanupPrompt.styleLine(style))
            assertThat(prompt.indexOf("Style:")).isLessThan(prompt.indexOf("Text: see you at seven"))
        }
    }
}
