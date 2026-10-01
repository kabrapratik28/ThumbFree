package io.github.kabrapratik28.thumbfree.a11y

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.InputMethod
import android.content.Intent
import android.graphics.Rect
import android.graphics.RectF
import android.os.Bundle
import android.text.InputType
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

interface ServiceListener {
    fun onServiceConnected(service: DictationAccessibilityService)
    fun onServiceGone()
    /** [field] is null when focus left; [key] says which focus the report is about (the live preview's geometry). */
    fun onFieldChanged(field: FocusTracker.Field?, key: DictationAccessibilityService.FocusKey? = null)
}

/** Reports the focused field to [listener] and owns the [editor] session that types text in. Callbacks run on main. */
class DictationAccessibilityService : AccessibilityService() {
    companion object {
        @Volatile var instance: DictationAccessibilityService? = null
        @Volatile var listener: ServiceListener? = null
    }

    /**
     * Where a TYPE_VIEW_FOCUSED event came from, and the editor generation it came in; [node] is null when it could not
     * be read. Not a data class: a node's toString carries its text.
     */
    class FocusedNode(val windowId: Int, val nodeKey: String, val generation: Int, val node: AccessibilityNodeInfo?)

    /**
     * Live preview: which focus a field report is about, as [FocusedNode] names it: the window, the node's key and the
     * editor generation. Two fields alike in every [FocusTracker.Field] value still differ here.
     */
    data class FocusKey(val windowId: Int, val nodeKey: String, val generation: Int)

    lateinit var editor: EditorSession                   // set in onCreateInputMethod()

    /** The bubble's window, made by the wiring (AndroidPorts) when the service connects. Main thread. */
    var bubble: BubbleWindow? = null

    /**
     * The last focus event in the connected editor's app, a password or number field's too, so a pin can be taken and
     * checked without IPC. One view can show several text nodes through one input connection and move focus between
     * them without a new input session: only this event tells. Another app's event (the keyboard's, say) cannot move
     * this app's connection, so it leaves this as it is.
     */
    @Volatile
    var lastFocus: FocusedNode? = null
        private set

    private var unreadable = 0 // numbers the keys of nodes that cannot be read; main thread only

    // flagInputMethodEditor makes the system call this just before onServiceConnected.
    override fun onCreateInputMethod(): InputMethod = EditorSession(this).also { editor = it }

    override fun onServiceConnected() {
        instance = this
        listener?.onServiceConnected(this)
    }

    // Disabled, or unbound by a UiAutomation without FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES.
    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        listener?.onServiceGone()
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_FOCUSED) {
            val node = event.source
            if (event.packageName?.toString() == editor.snapshot?.packageName) {
                // hashCode() comes from the node's view, virtual and window ids, so it tells apart fields that share a
                // resource id, and the text nodes of one view. A node that cannot be read gets a key of its own: its
                // event always refuses a pin taken before it.
                val key = node?.let(::keyOf) ?: "?${++unreadable}"
                lastFocus = FocusedNode(event.windowId, key, editor.generation, node)
            }
            listener?.onFieldChanged(fieldOf(node ?: return), focusOf(node))
        } else {
            // A keyboard showing or an app switch: report the focus again, so the listener can move or hide the bubble.
            val listener = listener ?: return
            val node = findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            listener.onFieldChanged(node?.let(::fieldOf), node?.let(::focusOf))
        }
    }

    private fun keyOf(node: AccessibilityNodeInfo) = "${node.viewIdResourceName}|${node.className}|${node.hashCode()}"

    private fun focusOf(node: AccessibilityNodeInfo) = FocusKey(node.windowId, keyOf(node), editor.generation)

    /**
     * The field [node] reports. A view that shows its text fields through one host (a WebView, Compose) can name the
     * host in its focus event, and findFocus can answer with the host: not editable, it would hide the bubble while one
     * of those fields holds the input session. So a node that is not editable, of the app holding the session, stands
     * for the session's field as its EditorInfo describes it, which keeps password and number fields hidden.
     */
    private fun fieldOf(node: AccessibilityNodeInfo): FocusTracker.Field {
        val pkg = node.packageName?.toString().orEmpty()
        val session = editor.snapshot?.takeIf { !node.isEditable && it.packageName == pkg }
            ?: return FocusTracker.Field(pkg, node.windowId, node.inputType, node.isPassword, node.isEditable)
        val editable = session.inputType != InputType.TYPE_NULL // as EditorSession reports it
        return FocusTracker.Field(pkg, node.windowId, session.inputType, node.isPassword, editable)
    }

    override fun onInterrupt() = Unit

    /** The focused editable node in the active window (findFocus(FOCUS_INPUT)), or null. */
    fun focusedEditable(): AccessibilityNodeInfo? =
        findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { it.isEditable }

    /**
     * The line the text cursor is on in the focused field, as wide as the field, in screen pixels: from the bounds of the
     * character before the cursor. The field's own bounds when it is empty or its app gives no character bounds; null
     * with no focused editable field. One binder call, and one more once the field has text.
     */
    fun cursorLine(): Rect? {
        val node = focusedEditable() ?: return null
        val field = Rect().also(node::getBoundsInScreen)
        val text = node.text
        val cursor = node.textSelectionEnd
        if (text.isNullOrEmpty() || cursor < 0) return field
        val args = Bundle().apply {
            putInt(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_START_INDEX, (cursor - 1).coerceIn(0, text.length - 1))
            putInt(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_LENGTH, 1)
        }
        if (!node.refreshWithExtraData(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY, args)) return field
        val char = node.extras.getParcelableArray(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY, RectF::class.java)
            ?.firstOrNull() ?: return field
        return Rect(field.left, char.top.toInt(), field.right, char.bottom.toInt())
    }

    /** Bounds of the input-method window from getWindows(), or null when no keyboard shows. */
    fun imeBounds(): Rect? =
        windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
            ?.let { window -> Rect().also(window::getBoundsInScreen) }
}
