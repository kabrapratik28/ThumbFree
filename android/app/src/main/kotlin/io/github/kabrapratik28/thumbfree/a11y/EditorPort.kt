package io.github.kabrapratik28.thumbfree.a11y

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.PersistableBundle
import android.text.TextUtils
import android.util.Log
import android.view.inputmethod.SurroundingText
import android.view.accessibility.AccessibilityNodeInfo
import io.github.kabrapratik28.thumbfree.core.insert.Surrounding
import io.github.kabrapratik28.thumbfree.core.text.FieldKind
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The field a take types into, pinned at touch-down: its input session ([generation]) and the node that session's focus
 * event named ([windowId], [nodeKey]; -1 and empty when that event had not come yet).
 */
data class Pin(val packageName: String, val windowId: Int, val nodeKey: String, val generation: Int) {
    /**
     * The node that focus event came from, read again just before a write; null when the pin names no node or one that
     * could not be read. Outside equals and toString: a node's toString carries its text.
     */
    var node: AccessibilityNodeInfo? = null
        internal set
}

/** How Clean up's one write ended ([EditorPort.replaceBeforeCursor]). */
enum class Replaced {
    /** Written, and the read back shows the new words right before the cursor, after the same words as before. */
    DONE,

    /** Nothing written: the old words were not right before a cursor with nothing selected, or the field changed. */
    UNCHANGED,

    /** Written, but the read back did not show the new words in the old ones' place. */
    UNSURE,
}

/** What [Inserter] needs from the focused editor. A call that takes a [Pin] uses only that pin's own input connection. */
interface EditorPort {
    fun cachedPin(): Pin?                                // EditorSession.snapshot (no IPC) plus the node of its session's last focus event; null without a snapshot
    fun samePin(pin: Pin): Boolean                       // same generation, input started, and no focus event of that session named another node; no IPC
    fun stillFocused(pin: Pin): Boolean                  // blocking IPC: a text Pin.node, read again, is focused and no password field; else the field focused now is no password field
    fun isPasswordTarget(): Boolean
    fun inputType(): Int
    fun capsExpected(pin: Pin): Boolean?                 // getCursorCapsMode(TextUtils.CAP_MODE_SENTENCES) != 0; null without the pin's connection
    fun readSurrounding(pin: Pin, before: Int, after: Int): Surrounding?   // blocking IPC; call off the main thread
    fun readNodeText(): String?
    fun commit(pin: Pin, text: String): Boolean          // false when the pin's session ended or has no connection (nothing sent)
    suspend fun awaitSelectionChange(timeoutMs: Long): Boolean
    fun copyToClipboard(text: String): Boolean           // EXTRA_IS_SENSITIVE; false when the clip reads back different (a denied read counts as written)
    fun paste(): Boolean                                 // ACTION_PASTE on the focused node; false for a password node
    fun cursorEnd(pin: Pin, take: String): Int?          // blocking IPC: the cursor's index in the field, when nothing is selected and [take] is right before it
    fun replaceBeforeCursor(pin: Pin, old: String, new: String, end: Int, live: () -> Boolean): Replaced   // blocking IPC: [old], ending at [end] right before the cursor, becomes [new]; one write
}

/** The real port over DictationAccessibilityService.instance and its EditorSession. */
class AccessibilityEditorPort(private val context: Context) : EditorPort {
    private val service get() = DictationAccessibilityService.instance
    private val editor get() = service?.editor

    override fun cachedPin(): Pin? {
        val service = service ?: return null
        val snapshot = service.editor.snapshot ?: return null
        val focus = service.lastFocus?.takeIf { it.generation == snapshot.generation } // an older field's event names nothing
        return Pin(snapshot.packageName, focus?.windowId ?: -1, focus?.nodeKey.orEmpty(), snapshot.generation)
            .apply { node = focus?.node }
    }

    /**
     * A focus event comes up to 100 ms after the move it reports. Read again from its app just before the write, past
     * the node cache, the pinned text node narrows that to this one IPC. A pin with no text node to read checks only
     * that the field focused now is not a password field. That includes a focus event from the host view: Compose's
     * root node and Chromium's host node report isFocused false even while one of their fields has input focus.
     */
    override fun stillFocused(pin: Pin): Boolean {
        val node = pin.node?.takeIf { it.isEditable } ?: return !isPasswordTarget()
        return node.refresh() && node.isFocused && !FieldKind.isPassword(node.inputType, node.isPassword)
    }

    override fun samePin(pin: Pin): Boolean =
        editor?.let { it.generation == pin.generation && it.inputStarted } == true && sameNode(pin)

    override fun isPasswordTarget(): Boolean =
        FieldKind.isPassword(inputType(), service?.focusedEditable()?.isPassword == true)

    override fun inputType(): Int = editor?.snapshot?.inputType ?: 0

    override fun capsExpected(pin: Pin): Boolean? =
        connection(pin)?.getCursorCapsMode(TextUtils.CAP_MODE_SENTENCES)?.let { it != 0 }

    override fun readSurrounding(pin: Pin, before: Int, after: Int): Surrounding? {
        val window = connection(pin)?.getSurroundingText(before, after, 0) ?: return null
        val text = window.text.toString()
        val start = minOf(window.selectionStart, window.selectionEnd)
        val end = maxOf(window.selectionStart, window.selectionEnd)
        if (start < 0 || end > text.length) return null      // another app's answer: bad indexes read as unreadable
        return Surrounding(text.substring(0, start), text.substring(end), window.offset)
    }

    override fun readNodeText(): String? {
        val node = service?.focusedEditable() ?: return null
        if (!node.refresh()) return null                     // the node cache can still hold the text from before the write
        return if (node.isShowingHintText) "" else node.text?.toString()
    }

    override fun commit(pin: Pin, text: String): Boolean {
        val connection = connection(pin) ?: return false
        connection.commitText(text, 1, null)
        return true
    }

    /**
     * The pinned field's own connection from one read of the snapshot, or null once that session ended. If focus moves
     * after this read, the app has deactivated the connection, so a write through it lands nowhere, not in the new field.
     * For a write, the node check is the last step before it, on the thread that sends it.
     */
    private fun connection(pin: Pin) =
        editor?.snapshot?.takeIf { it.generation == pin.generation && sameNode(pin) }?.connection

    /**
     * No focus event of [pin]'s session named another node. A view with several text nodes can move its one connection
     * to another of them, a password node too, and keep the session, so the generation alone cannot tell. A pin that
     * names no node holds only until its session's first focus event.
     */
    private fun sameNode(pin: Pin): Boolean {
        val focus = service?.lastFocus?.takeIf { it.generation == pin.generation } ?: return true
        return focus.windowId == pin.windowId && focus.nodeKey == pin.nodeKey
    }

    override suspend fun awaitSelectionChange(timeoutMs: Long): Boolean {
        val editor = editor ?: return false
        val changed = CompletableDeferred<Unit>()
        val callback: () -> Unit = { changed.complete(Unit) }
        // Armed after the commit: an update that arrives before this line only costs the full wait.
        editor.onSelectionChanged = callback
        try {
            return withTimeoutOrNull(timeoutMs) { changed.await() } != null
        } finally {
            if (editor.onSelectionChanged === callback) editor.onSelectionChanged = null   // leave a newer wait armed
        }
    }

    override fun copyToClipboard(text: String): Boolean {
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        val clip = ClipData.newPlainText(null, text)
        clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
        clipboard.setPrimaryClip(clip)
        // Android 10+ lets an app without window focus write the clipboard but not read it (the bubble never takes
        // focus), and the read then returns null. Only a clip that reads back different is a failure.
        val readBack = clipboard.primaryClip ?: return true
        return readBack.getItemAt(0).text?.toString() == text
    }

    /** Where Clean up's take ends in the field, read right after it was typed, so a later write knows it is the same words. */
    override fun cursorEnd(pin: Pin, take: String): Int? {
        val connection = connection(pin) ?: return null
        val window = connection.getSurroundingText(take.length, 0, 0) ?: return null
        val cursor = window.selectionStart
        if (cursor != window.selectionEnd || cursor !in take.length..window.text.length) return null
        if (!window.text.substring(0, cursor).toString().endsWith(take)) return null
        val origin = if (window.offset >= 0) window.offset else (nodeCursor()?.minus(cursor) ?: return null)
        return origin + cursor
    }

    /**
     * Clean up's one write, after the user's tap: [old] must end at [end], right before a cursor with nothing selected, in
     * [pin]'s own field: the same words the take typed, not the same text elsewhere. It is selected, the selection is read
     * back to cover exactly [old], the field is checked again (its node, no password field, [live]: the offer still
     * stands), and [new] is committed over it, never deleted first, so a write that fails loses no words. Then one read
     * back: the words that were before [old], then [new], right before the cursor. An app that ignored the selection shows
     * both, which reads as UNSURE. A selection the user makes in the moment between the last check and the write would
     * still be replaced: the accessibility connection has no atomic replace.
     */
    override fun replaceBeforeCursor(pin: Pin, old: String, new: String, end: Int, live: () -> Boolean): Replaced {
        fun refuse(why: String): Replaced {
            Log.i("ThumbFree", "cleanup_replace refused=$why") // a code, never text
            return Replaced.UNCHANGED
        }
        val connection = connection(pin) ?: return refuse("connection")
        val window = connection.getSurroundingText(old.length + REPLACE_CONTEXT, 0, 0) ?: return refuse("read")
        val text = window.text.toString()
        val cursor = window.selectionStart
        // A selection, or another app's bad indexes: nothing is written.
        if (cursor != window.selectionEnd || cursor !in old.length..text.length) return refuse("selection")
        val before = text.substring(0, cursor)
        if (!before.endsWith(old)) return refuse("text")
        if (!sameNode(pin)) return refuse("node")
        val kept = before.dropLast(old.length)
        // Where the window starts in the field. Some apps (Compose's fields, for one) give no offset: the cursor's index
        // in the focused node's text tells it then.
        val origin = if (window.offset >= 0) window.offset else (nodeCursor()?.minus(cursor) ?: return refuse("offset"))
        val start = origin + kept.length
        if (start + old.length != end) return refuse("moved") // the same text, but not the take's own words
        connection.setSelection(start, end)
        // A web page applies a selection a moment later, so the reads ask again for up to 300 ms. Focus may move meanwhile.
        val covers = settled { !sameNode(pin) || connection.getSurroundingText(0, 0, 0)?.let { it.selected() == old } == true }
        if (!sameNode(pin)) return refuse("target") // another field's now: nothing more goes to this connection
        if (!covers) {
            connection.setSelection(end, end) // the cursor back where it was
            return refuse("covers")
        }
        if (this.connection(pin) !== connection || !sameNode(pin) || isPasswordTarget()) return refuse("target")
        if (!live()) {
            connection.setSelection(end, end)
            return refuse("cleared")
        }
        connection.commitText(new, 1, null)
        val landed = settled {
            connection.getSurroundingText(kept.length + new.length, 0, 0)?.let { it.selectionStart == it.selectionEnd && it.beforeCursor() == kept + new } == true
        }
        return if (landed) Replaced.DONE else Replaced.UNSURE
    }

    /** [check] now, or again every 50 ms for up to 300 ms; blocking, off the main thread like every port call. */
    private fun settled(check: () -> Boolean): Boolean {
        repeat(7) { tries ->
            if (check()) return true
            if (tries < 6) Thread.sleep(50)
        }
        return false
    }

    private fun SurroundingText.selected(): String? =
        if (selectionStart in 0..selectionEnd && selectionEnd <= text.length) text.substring(selectionStart, selectionEnd).toString() else null

    private fun SurroundingText.beforeCursor(): String? =
        if (selectionStart in 0..text.length) text.substring(0, selectionStart).toString() else null

    /** The cursor's index in the focused field's text, read again from its node; null for a selection or no node. */
    private fun nodeCursor(): Int? {
        val node = service?.focusedEditable() ?: return null
        if (!node.refresh()) return null
        return node.textSelectionStart.takeIf { it >= 0 && it == node.textSelectionEnd }
    }

    // Checked on the node that gets the paste: focus can reach a password field after Insert here's own check.
    override fun paste(): Boolean {
        val node = service?.focusedEditable() ?: return false
        return !FieldKind.isPassword(node.inputType, node.isPassword) && node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
    }

    private companion object {
        /** How many characters before the old words the read back compares, to catch an app that ignored the selection. */
        const val REPLACE_CONTEXT = 32
    }
}
