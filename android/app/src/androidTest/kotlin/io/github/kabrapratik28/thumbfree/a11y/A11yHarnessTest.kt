package io.github.kabrapratik28.thumbfree.a11y

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import com.google.common.truth.Truth.assertThat
import io.github.kabrapratik28.thumbfree.testing.A11yRule
import io.github.kabrapratik28.thumbfree.testing.SERVICE
import io.github.kabrapratik28.thumbfree.testing.enabledServices
import io.github.kabrapratik28.thumbfree.testing.launchInsertTargets
import io.github.kabrapratik28.thumbfree.testing.shell
import io.github.kabrapratik28.thumbfree.testing.waitFor
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

// The end-to-end suite drives the app with UiDevice while our service types into fields. If UiAutomation unbinds the
// service, or the rule drops someone else's service or keyboard, every later device test is meaningless.
@RunWith(AndroidJUnit4::class)
class A11yHarnessTest {
    @get:Rule
    val a11y = A11yRule()

    @Test
    fun serviceSurvivesUiAutomation() {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        launchInsertTargets(device)
        device.findObject(By.desc("empty")).click()

        assertThat(waitFor(5_000) { DictationAccessibilityService.instance != null }).isTrue()

        val end = SystemClock.uptimeMillis() + 3_000
        while (SystemClock.uptimeMillis() < end) {
            for (desc in listOf("second", "prefilled", "empty")) device.findObject(By.desc(desc)).click()
            device.waitForIdle()
        }

        assertThat(DictationAccessibilityService.instance).isNotNull()
        assertThat(enabledServices()).containsAtLeastElementsIn(a11y.servicesBefore.toSet() + SERVICE)
        assertThat(shell("settings get secure default_input_method").trim()).isEqualTo(a11y.keyboard)
    }
}
