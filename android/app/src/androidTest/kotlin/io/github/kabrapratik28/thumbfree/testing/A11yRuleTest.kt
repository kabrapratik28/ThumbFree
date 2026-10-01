package io.github.kabrapratik28.thumbfree.testing

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import io.github.kabrapratik28.thumbfree.a11y.DictationAccessibilityService
import org.junit.Test
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runners.model.Statement

/**
 * A11yRule puts the accessibility service list back exactly as it found it: an empty list stays empty and an unset one
 * stays unset, so the service is never left listed after a run.
 */
@RunWith(AndroidJUnit4::class)
class A11yRuleTest {
    @Test
    fun anEmptyServiceListComesBackEmpty() = roundTrip("")

    @Test
    fun anUnsetServiceListComesBackUnset() = roundTrip(null)

    /** Sets the list to [value], runs a test under the rule, then checks the list reads as [value] again. */
    private fun roundTrip(value: String?) {
        val before = secure(KEY)
        try {
            putSecure(KEY, value)
            assertThat(secure(KEY)).isEqualTo(value)
            A11yRule().apply(
                object : Statement() {
                    override fun evaluate() = assertThat(DictationAccessibilityService.instance).isNotNull()
                },
                Description.EMPTY,
            ).evaluate()

            assertThat(secure(KEY)).isEqualTo(value)
        } finally {
            putSecure(KEY, before)
        }
    }

    private companion object {
        const val KEY = "enabled_accessibility_services"
    }
}
