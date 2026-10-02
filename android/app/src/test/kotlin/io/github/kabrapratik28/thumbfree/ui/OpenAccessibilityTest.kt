package io.github.kabrapratik28.thumbfree.ui

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Where "Agree and open settings" goes (a setup row's Turn on shows that step first): Android's accessibility list,
 * scrolled to the service on Pixel, else App info. HomeScreenTest checks both from the real screen on the emulator.
 */
@RunWith(RobolectricTestRunner::class)
class OpenAccessibilityTest {
    private val service = ComponentName("io.github.kabrapratik28.thumbfree", "io.github.kabrapratik28.thumbfree.a11y.DictationAccessibilityService")

    // The list comes with the extras that make Pixel scroll to the service and highlight it, and nothing else opens.
    @Test
    fun opensTheListWithTheHighlightExtras() {
        val started = mutableListOf<Intent>()

        assertThat(openAccessibilitySettings(service) { started += it; true }).isTrue()
        val list = started.single()
        val key = service.flattenToString()
        assertThat(list.action).isEqualTo(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        assertThat(list.getStringExtra(":settings:fragment_args_key")).isEqualTo(key)
        assertThat(list.getBundleExtra(":settings:show_fragment_args")?.getString(":settings:fragment_args_key")).isEqualTo(key)
    }

    // A phone without the list gets the app's own page, App info, and false, so the caller says where the switch is.
    @Test
    fun withoutTheListItOpensAppInfo() {
        val started = mutableListOf<Intent>()

        val opened = openAccessibilitySettings(service) { started += it; it.action != Settings.ACTION_ACCESSIBILITY_SETTINGS }

        assertThat(opened).isFalse()
        assertThat(started.map { it.action })
            .containsExactly(Settings.ACTION_ACCESSIBILITY_SETTINGS, Settings.ACTION_APPLICATION_DETAILS_SETTINGS).inOrder()
        assertThat(started[1].data).isEqualTo(Uri.fromParts("package", service.packageName, null))
    }
}
