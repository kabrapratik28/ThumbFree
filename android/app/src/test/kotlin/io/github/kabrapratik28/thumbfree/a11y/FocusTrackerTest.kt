package io.github.kabrapratik28.thumbfree.a11y

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

class FocusTrackerTest {
    private val text = FocusTracker.Field("a", 1, 0x1, false, true)
    private val tracker = FocusTracker()

    @Test
    fun editableTextFieldShows() {
        tracker.focused(text, 0)

        assertThat(tracker.visible(0, false)).isTrue()
    }

    @Test
    fun passwordVariantsHide() {
        val fields = listOf(0x81, 0x91, 0xe1, 0x12).map { text.copy(inputType = it) } + text.copy(isPassword = true)

        for (field in fields) {
            val tracker = FocusTracker()
            tracker.focused(field, 0)

            assertWithMessage("$field").that(tracker.visible(0, false)).isFalse()
        }
    }

    @Test
    fun numberFieldIsNotEligible() {
        tracker.focused(text.copy(inputType = 0x2), 0)

        assertThat(tracker.visible(0, false)).isFalse()
    }

    @Test
    fun phoneFieldIsNotEligible() {
        tracker.focused(text.copy(inputType = 0x3), 0)

        assertThat(tracker.visible(0, false)).isFalse()
    }

    @Test
    fun datetimeFieldIsNotEligible() {
        tracker.focused(text.copy(inputType = 0x4), 0)

        assertThat(tracker.visible(0, false)).isFalse()
    }

    @Test
    fun plainTextStillEligible() {
        tracker.focused(text.copy(inputType = 0x1), 0)
        assertThat(tracker.visible(0, false)).isTrue()

        tracker.focused(text.copy(inputType = 0x21), 0)
        assertThat(tracker.visible(0, false)).isTrue()
    }

    @Test
    fun unknownClassEditableStaysEligible() {
        tracker.focused(text.copy(inputType = 0x0), 0)

        assertThat(tracker.visible(0, false)).isTrue()
    }

    @Test
    fun notEditableHides() {
        tracker.focused(text.copy(editable = false), 0)

        assertThat(tracker.visible(0, false)).isFalse()
    }

    @Test
    fun lostHidesAfter400ms() {
        tracker.focused(text, 0)
        tracker.lost(1_000)

        assertThat(tracker.visible(1_399, false)).isTrue()
        assertThat(tracker.visible(1_400, false)).isFalse()
    }

    @Test
    fun refocusKeepsVisible() {
        tracker.focused(text, 0)
        tracker.lost(1_000)
        tracker.focused(text.copy(windowId = 2), 1_200)

        assertThat(tracker.visible(1_500, false)).isTrue()
    }

    @Test
    fun sessionKeepsVisible() {
        tracker.lost(0)

        assertThat(tracker.visible(10_000, true)).isTrue()
    }

    @Test
    fun repeatedFocusIsIdempotent() {
        tracker.focused(text, 0)
        tracker.focused(text, 10)

        assertThat(tracker.current).isEqualTo(text)
    }

    // The grace period keeps a showing bubble through a field switch; leaving a password field never shows it.
    @Test
    fun leavingAPasswordFieldStaysHidden() {
        tracker.focused(text, 0)
        tracker.lost(1_000)
        tracker.focused(text.copy(inputType = 0x81), 1_100)
        tracker.lost(1_200)

        assertThat(tracker.visible(1_200, false)).isFalse()
    }
}
