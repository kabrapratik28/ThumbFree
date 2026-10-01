package io.github.kabrapratik28.thumbfree.a11y

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SelectionWaitTest {
    private val service = Robolectric.buildService(DictationAccessibilityService::class.java).get()
    private val editor = EditorSession(service).also { service.editor = it }
    private val port = AccessibilityEditorPort(service)

    @After
    fun dropInstance() {
        DictationAccessibilityService.instance = null
    }

    // Waits can interleave on the insert dispatcher: the one that ends first must not disarm a newer one.
    @Test
    fun endingWaitLeavesANewerCallback() = runTest {
        DictationAccessibilityService.instance = service
        val waiting = async(start = CoroutineStart.UNDISPATCHED) { port.awaitSelectionChange(1_000) }   // armed on return
        val newer: () -> Unit = {}
        editor.onSelectionChanged = newer

        assertThat(waiting.await()).isFalse()
        assertThat(editor.onSelectionChanged).isSameInstanceAs(newer)
    }
}
