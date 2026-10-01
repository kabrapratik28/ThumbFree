package io.github.kabrapratik28.thumbfree.a11y

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.google.common.truth.Truth.assertThat
import io.github.kabrapratik28.thumbfree.testing.A11yRule
import io.github.kabrapratik28.thumbfree.testing.focusTarget
import io.github.kabrapratik28.thumbfree.testing.launchInsertTargets
import io.github.kabrapratik28.thumbfree.testing.waitFor
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EditorSessionTest {
    @get:Rule
    val a11y = A11yRule()

    // Created after the rule has set the UiAutomation flags.
    private val device by lazy { UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()) }
    private val service get() = DictationAccessibilityService.instance!!

    // AppGraph's ports, set at process start. Left null, no later class in the same run gets a bubble.
    private val appListener = DictationAccessibilityService.listener

    @Before
    fun launch() = launchInsertTargets(device)

    @After
    fun restoreListener() {
        DictationAccessibilityService.listener = appListener
    }

    @Test
    fun startInputBumpsGeneration() {
        focusTarget(device, "empty")
        val editor = service.editor
        assertThat(waitFor(5_000) { editor.inputStarted }).isTrue()
        device.waitForIdle()
        val g1 = editor.generation

        focusTarget(device, "second")

        assertThat(waitFor(5_000) { editor.generation > g1 }).isTrue()
    }

    @Test
    fun focusedEditableFindsTheField() {
        focusTarget(device, "prefilled")
        waitFor(5_000) { service.focusedEditable()?.contentDescription?.toString() == "prefilled" }

        assertThat(service.focusedEditable()!!.text.toString()).isEqualTo("Hello world")
    }

    @Test
    fun passwordFieldIsNotEligible() {
        val fields = CopyOnWriteArrayList<FocusTracker.Field?>()
        DictationAccessibilityService.listener = object : ServiceListener {
            override fun onServiceConnected(service: DictationAccessibilityService) = Unit
            override fun onServiceGone() = Unit
            override fun onFieldChanged(field: FocusTracker.Field?, key: DictationAccessibilityService.FocusKey?) {
                fields += field
            }
        }

        focusTarget(device, "password")
        SystemClock.sleep(1_000) // focus events arrive up to notificationTimeout (100 ms) late; let them all land

        assertThat(fields).isNotEmpty()
        val last = fields.last()
        assertThat(last).isNotNull()
        val tracker = FocusTracker().apply { focused(last!!, 0) }
        assertThat(tracker.visible(0, false)).isFalse()
    }
}
