package io.github.kabrapratik28.thumbfree.build

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test

/**
 * A yellow bubble always works, so no screen may draw the bubble as a picture: its production art is drawn only by the
 * real bubble, a11y.BubbleView, which the floating bubble and the welcome's try both are, and in Settings, by the Bubble
 * section's labelled preview of its size and transparency and by About, as the app's logo. HomeScreenTest's
 * noBubbleButTheTrysOwn checks every welcome and Home state on a device.
 */
class BubbleArtGuardTest {
    // Unit tests run with the module directory (android/app/) as the working directory.
    private val main = File("src/main")

    @Test
    fun onlyTheRealBubbleDrawsTheBubbleArt() {
        val art = Regex("""\bR\.drawable\.bubble_(idle|recording|stop)\b|@drawable/bubble_(idle|recording|stop)\b""")
        val sources = main.walk().filter { it.extension in setOf("kt", "java", "xml") }.toList()

        val using = sources.filter { art.containsMatchIn(it.readText()) }.map { it.relativeTo(main).invariantSeparatorsPath }

        assertThat(sources.size).isAtLeast(10)
        assertThat(using).containsExactly(
            "kotlin/io/github/kabrapratik28/thumbfree/a11y/BubbleView.kt", "kotlin/io/github/kabrapratik28/thumbfree/ui/BubbleSettings.kt",
            "kotlin/io/github/kabrapratik28/thumbfree/ui/SettingsScreen.kt",
        )
    }
}
