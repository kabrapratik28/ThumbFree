package io.github.kabrapratik28.thumbfree.a11y

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.InputMethod
import android.text.InputType
import android.view.inputmethod.EditorInfo
import java.util.concurrent.atomic.AtomicInteger

/**
 * The accessibility input-method session (Android 13+): the system connects it to whichever editor has input focus,
 * alongside the real keyboard. Its callbacks run on the main thread.
 */
class EditorSession(service: AccessibilityService) : InputMethod(service) {
    /** The connected editor as of its last onStartInput, with the input connection that belongs to it alone. */
    data class Snapshot(
        val packageName: String, val fieldId: Int, val inputType: Int, val generation: Int,
        val connection: InputMethod.AccessibilityInputConnection?,
    )

    /**
     * New on every onStartInput and onFinishInput and never reused in this process, so a target pinned at record time
     * can tell the editor changed, even after the service reconnected with a new session.
     */
    @Volatile
    var generation = 0
        private set

    /** The connected editor, or null when none is. One read and no IPC, so the controller can pin it at touch-down. */
    @Volatile
    var snapshot: Snapshot? = null
        private set

    val inputStarted: Boolean get() = currentInputStarted

    @Volatile
    var onSelectionChanged: (() -> Unit)? = null

    override fun onStartInput(attribute: EditorInfo, restarting: Boolean) {
        generation = generations.incrementAndGet()
        // The framework sets the started connection just before this call. Published with the generation in one write,
        // so a take that checks its generation writes through its own field's connection, never a newer field's.
        val editor = Snapshot(
            attribute.packageName.orEmpty(), attribute.fieldId, attribute.inputType, generation, currentInputConnection,
        )
        snapshot = editor
        // EditorInfo has no window id (-1 here) or password flag; the focus event that follows within
        // notificationTimeout brings both. Do not read the focused node here: the node cache still holds the
        // previous field until then.
        DictationAccessibilityService.listener?.onFieldChanged(
            FocusTracker.Field(
                editor.packageName, -1, editor.inputType, false,
                editable = editor.inputType != InputType.TYPE_NULL, // TYPE_NULL editors only take key events
            ),
            DictationAccessibilityService.FocusKey(-1, "", generation), // a new session: its node is not known yet
        )
    }

    override fun onFinishInput() {
        generation = generations.incrementAndGet()
        snapshot = null
        DictationAccessibilityService.listener?.onFieldChanged(null, null)
    }

    override fun onUpdateSelection(
        oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int, candidatesStart: Int, candidatesEnd: Int,
    ) {
        onSelectionChanged?.invoke()
    }

    private companion object {
        // Shared by every session: each service connection builds a new one, which must not count from 0 again.
        val generations = AtomicInteger()
    }
}
