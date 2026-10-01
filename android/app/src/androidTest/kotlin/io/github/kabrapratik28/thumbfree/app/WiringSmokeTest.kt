package io.github.kabrapratik28.thumbfree.app

import android.graphics.Rect
import android.view.WindowInsets
import android.view.WindowManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.google.common.truth.Truth.assertThat
import io.github.kabrapratik28.thumbfree.a11y.DictationAccessibilityService
import io.github.kabrapratik28.thumbfree.testing.A11yRule
import io.github.kabrapratik28.thumbfree.testing.focusTarget
import io.github.kabrapratik28.thumbfree.testing.launchInsertTargets
import io.github.kabrapratik28.thumbfree.testing.shell
import io.github.kabrapratik28.thumbfree.testing.waitFor
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The real AppGraph, built by ThumbFreeApp in this process: the service connects to its ports, which place the bubble. */
@RunWith(AndroidJUnit4::class)
class WiringSmokeTest {
    @get:Rule
    val a11y = A11yRule()

    // Created after the rule has set the UiAutomation flags.
    private val device by lazy { UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()) }
    private val service get() = DictationAccessibilityService.instance!!
    private val density = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
    private val imeGap = (72 * density).toInt() // clears a chat app's input bar and its Send button

    @Before
    fun launch() = launchInsertTargets(device)

    /** The bubble's circle on screen, read on the main thread like every BubbleWindow call; null while it is hidden. */
    private fun bubble(): Rect? {
        var bounds: Rect? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync { bounds = service.bubble?.boundsOnScreen() }
        return bounds
    }

    @Test
    fun bubbleMovesAboveKeyboardThatAppearsLater() {
        focusTarget(device, "empty")
        check(waitFor(5_000) { service.imeBounds() != null }) { "no keyboard" } // back without one would close the activity
        device.pressBack() // The keyboard hides; the field keeps focus
        val metrics = service.getSystemService(WindowManager::class.java).currentWindowMetrics
        val bars = WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
        val inset = metrics.windowInsets.getInsetsIgnoringVisibility(bars)
        val lowest = metrics.bounds.bottom - inset.bottom - (96 * density).toInt()
        assertThat(waitFor(2_000) { service.imeBounds() == null && bubble()?.let { it.bottom <= lowest } == true }).isTrue()

        focusTarget(device, "empty") // the keyboard shows again, with no focus event of its own
        assertThat(waitFor(2_000) {
            val ime = service.imeBounds()
            ime != null && bubble()?.let { ime.top - it.bottom >= imeGap } == true
        }).isTrue()
    }

    @Test
    fun bubbleFollowsEligibleFocus() {
        // The password field hides the bubble, so its spot is forgotten, and the log is cleared: the bubble_shown line
        // checked below is this test's own, never one left over from an earlier test or run.
        focusTarget(device, "password")
        assertThat(waitFor(1_000) { bubble() == null }).isTrue()
        shell("logcat -c")

        focusTarget(device, "empty")
        assertThat(waitFor(2_000) {
            val bounds = bubble()
            val ime = service.imeBounds()
            bounds != null && (ime == null || ime.top - bounds.bottom >= imeGap)
        }).isTrue()
        // android/tools/fgs-gate.sh taps the middle of the last bubble_shown line, so it must match the circle on
        // screen.
        val circle = bubble()!!
        val line = "bubble_shown x=${circle.left} y=${circle.top} w=${circle.width()} h=${circle.height()}"
        assertThat(waitFor(1_000) { lastShownLine()?.endsWith(line) == true }).isTrue()

        focusTarget(device, "password")
        assertThat(waitFor(1_000) { bubble() == null }).isTrue()
    }

    private fun lastShownLine(): String? = shell("logcat -d -s ThumbFree").lines().lastOrNull { "bubble_shown" in it }
}
