package io.github.kabrapratik28.thumbfree.a11y

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import io.github.kabrapratik28.thumbfree.testing.SERVICE
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ServiceConfigTest {
    // What the system parsed from accessibility_service.xml. More event types would wake us on every scroll and click.
    @Test
    fun inputMethodAndMinimalEvents() {
        val manager = InstrumentationRegistry.getInstrumentation().targetContext
            .getSystemService(AccessibilityManager::class.java)
        val ours = manager.installedAccessibilityServiceList
            .single { ComponentName.unflattenFromString(it.id) == ComponentName.unflattenFromString(SERVICE) }

        assertThat(ours.flags and AccessibilityServiceInfo.FLAG_INPUT_METHOD_EDITOR).isNotEqualTo(0)
        assertThat(ours.flags and AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS).isNotEqualTo(0)
        assertThat(ours.eventTypes).isEqualTo(
            AccessibilityEvent.TYPE_VIEW_FOCUSED or AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOWS_CHANGED,
        )
        assertThat(ours.notificationTimeout).isEqualTo(100L)
    }
}
