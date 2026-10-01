package io.github.kabrapratik28.thumbfree.ui

import com.google.common.truth.Truth.assertThat
import io.github.kabrapratik28.thumbfree.R
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** History's storage line ends with the size, which wraps as the line's last words when the line is full. */
@RunWith(RobolectricTestRunner::class)
class RetentionLineTest {
    // "Keeps takes for 7 days, up to 200 · under 1 MB" filled the line on a 1080 px phone and left "MB" alone on the
    // next one. A no-break space keeps the number and its unit together.
    @Test
    fun underOneMegabyteKeepsItsUnitOnTheLine() {
        val text = RuntimeEnvironment.getApplication().getString(R.string.ui_under_1_mb)

        assertThat(text).endsWith("1\u00A0MB")
    }
}
