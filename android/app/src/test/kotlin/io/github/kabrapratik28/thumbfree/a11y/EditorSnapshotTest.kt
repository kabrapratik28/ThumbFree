package io.github.kabrapratik28.thumbfree.a11y

import android.view.inputmethod.EditorInfo
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class EditorSnapshotTest {
    private val editor = EditorSession(Robolectric.buildService(DictationAccessibilityService::class.java).get())

    // The controller pins its target from this at touch-down: one read, no IPC, null when no editor is connected.
    @Test
    fun snapshotFollowsTheEditor() {
        assertThat(editor.snapshot).isNull()

        // Robolectric starts no input connection; the device test checks the real one.
        editor.onStartInput(editorInfo("com.example", 7, 0x21), false)
        val started = editor.generation
        assertThat(editor.snapshot).isEqualTo(EditorSession.Snapshot("com.example", 7, 0x21, started, null))

        editor.onFinishInput()
        val finished = editor.generation
        assertThat(finished).isGreaterThan(started)
        assertThat(editor.snapshot).isNull()

        editor.onStartInput(editorInfo("com.example", 8, 0x1), false)
        assertThat(editor.generation).isGreaterThan(finished)
        assertThat(editor.snapshot).isEqualTo(EditorSession.Snapshot("com.example", 8, 0x1, editor.generation, null))
    }

    // A service reconnect builds a new session. A pin from the old one must never match a field of the new one.
    @Test
    fun generationsNeverRepeatAcrossSessions() {
        editor.onStartInput(editorInfo("com.example", 7, 0x1), false)
        val pinned = editor.snapshot!!.generation
        val next = EditorSession(Robolectric.buildService(DictationAccessibilityService::class.java).get())

        val handedOut = (1..3).flatMap {
            next.onStartInput(editorInfo("com.example", 8, 0x1), false)
            val started = next.generation
            next.onFinishInput()
            listOf(started, next.generation)
        }

        assertThat(handedOut).doesNotContain(pinned)
    }

    private fun editorInfo(pkg: String, id: Int, type: Int) =
        EditorInfo().apply { packageName = pkg; fieldId = id; inputType = type }
}
