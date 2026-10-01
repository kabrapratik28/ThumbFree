package io.github.kabrapratik28.thumbfree.a11y

import android.text.InputType
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.SurroundingText
import com.google.common.truth.Truth.assertThat
import io.github.kabrapratik28.thumbfree.core.session.Code
import io.github.kabrapratik28.thumbfree.core.session.Outcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowAccessibilityInputConnection
import org.robolectric.shadows.ShadowAccessibilityRecord
import org.robolectric.util.ReflectionHelpers

/**
 * An editor can show several text nodes through one view and one input connection, and move focus between them
 * without a new input session, so the generation stays. The write checks the pin's node too.
 */
@RunWith(RobolectricTestRunner::class)
class VirtualNodePinTest {
    private val service = Robolectric.buildService(DictationAccessibilityService::class.java).get()
    private val editor = EditorSession(service).also { service.editor = it }
    private val port = AccessibilityEditorPort(service)
    private val host = View(service) // the one view whose text nodes are virtual

    @Before
    fun startInput() {
        DictationAccessibilityService.instance = service
        ReflectionHelpers.setField(service, "mInputMethod", editor) // the system's doing for flagInputMethodEditor
        startSession()
    }

    @After
    fun dropInstance() {
        DictationAccessibilityService.instance = null
        DictationAccessibilityService.listener = null
    }

    // A password node is not dictatable, so the bubble ignores its focus event. The pin must not.
    @Test
    fun anotherNodeOfTheConnectionIsNotWritten() {
        focus(node(1))
        val pin = port.cachedPin()!!
        focus(node(2, password = true))

        assertThat(port.samePin(pin)).isFalse()
        assertThat(port.commit(pin, "secret")).isFalse() // refused before it reaches the connection
    }

    // Focus moves while the surrounding-text read blocks, and the generation stays.
    @Test
    fun moveDuringTheReadIsTargetChanged() = runBlocking<Unit> {
        focus(node(1))
        val pin = port.cachedPin()!!
        remote().setSurroundingTextCallback { _, _, _ ->
            focus(node(2, password = true))
            SurroundingText("Hi", 2, 2, 0)
        }

        val result = Inserter(port, Dispatchers.IO, log = {}).insert("there", pin, true)

        // A write that reaches Robolectric's connection throws instead (it rejects commitText's null TextAttribute).
        assertThat(result).isEqualTo(InsertResult(Outcome.NOT_INSERTED, null, Code.TARGET_CHANGED))
    }

    // A pin taken before its session's first focus event names no node: after any focus event it is refused.
    @Test
    fun pinWithoutANodeIsNotWrittenAfterAFocusEvent() {
        focus(node(1))
        startSession() // a new session whose focus event has not come yet
        val pin = port.cachedPin()!!
        focus(node(2, password = true))

        assertThat(port.samePin(pin)).isFalse()
        assertThat(port.commit(pin, "secret")).isFalse()
    }

    // A focus event comes up to 100 ms after the move it reports. Just before the write the pinned node is read again,
    // past the node cache: it must still have input focus and not be a password field.
    @Test
    fun pinnedNodeIsReadAgainBeforeTheWrite() {
        focus(node(1))
        val pin = port.cachedPin()!!
        val node = pin.node!! // what refresh() reads again from the app, set by hand here
        assertThat(port.stillFocused(pin)).isTrue()

        node.isFocused = false
        assertThat(port.stillFocused(pin)).isFalse()
        node.isFocused = true
        node.isPassword = true
        assertThat(port.stillFocused(pin)).isFalse()
        node.isPassword = false
        shadowOf(node).setRefreshReturnValue(false) // the node is gone
        assertThat(port.stillFocused(pin)).isFalse()
    }

    // Compose's root node and Chromium's host node report isFocused false even while one of their fields has input
    // focus, and the session's last focus event can name that host (a field focused as it appears, a WebView that does
    // not move focus to the field). Reading it again would refuse every take, so only a text node is read again; a pin
    // on the host keeps the check that the field focused now is not a password field.
    @Test
    fun hostOfTheTextNodesIsNotReadAgain() {
        focus(node(View.NO_ID).apply { isEditable = false; isFocused = false })

        assertThat(port.stillFocused(port.cachedPin()!!)).isTrue()
    }

    // The same host focus event once reached the bubble as a field that is not editable, so the bubble hid while one of
    // the host's fields held the input session, and findFocus answering with the host kept it hidden. A node of the
    // session's app that is not editable stands for the session's field as EditorInfo describes it: a password session
    // stays hidden, and another app's node is reported as it is.
    @Test
    fun hostFocusReportsTheSessionsField() {
        val reported = mutableListOf<FocusTracker.Field?>()
        DictationAccessibilityService.listener = object : ServiceListener {
            override fun onServiceConnected(service: DictationAccessibilityService) = Unit
            override fun onServiceGone() = Unit
            override fun onFieldChanged(field: FocusTracker.Field?, key: DictationAccessibilityService.FocusKey?) {
                reported += field
            }
        }
        fun hostOf(app: String = APP) = node(View.NO_ID, app = app).apply { isEditable = false; isFocused = false }

        focus(hostOf())
        assertThat(FocusTracker.eligible(reported.last()!!)).isTrue()
        focus(hostOf("com.example.other"))
        assertThat(FocusTracker.eligible(reported.last()!!)).isFalse()
        startSession(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)
        focus(hostOf())
        assertThat(FocusTracker.eligible(reported.last()!!)).isFalse()
    }

    // Two text nodes alike in every FocusTracker.Field value report different focus keys; the same node again reports
    // the same key, and a new input session a new one. The preview's geometry is kept per key, so one field's is never
    // taken for the other's.
    @Test
    fun alikeFieldsReportDifferentFocusKeys() {
        val reported = mutableListOf<Pair<FocusTracker.Field?, DictationAccessibilityService.FocusKey?>>()
        DictationAccessibilityService.listener = object : ServiceListener {
            override fun onServiceConnected(service: DictationAccessibilityService) = Unit
            override fun onServiceGone() = Unit
            override fun onFieldChanged(field: FocusTracker.Field?, key: DictationAccessibilityService.FocusKey?) {
                reported += field to key
            }
        }

        focus(node(1))
        val (fieldA, keyA) = reported.last()
        focus(node(2))
        val (fieldB, keyB) = reported.last()
        focus(node(1))
        val keyAAgain = reported.last().second
        startSession()
        val keySession = reported.last().second

        assertThat(fieldB).isEqualTo(fieldA)
        assertThat(keyB).isNotEqualTo(keyA)
        assertThat(keyAAgain).isEqualTo(keyA)
        assertThat(keySession).isNotEqualTo(keyA)
    }

    // The move has happened in the app, but its focus event has not come yet: the write is refused all the same.
    @Test
    fun moveNotYetReportedIsTargetChanged() = runBlocking<Unit> {
        focus(node(1))
        val pin = port.cachedPin()!!
        remote().setSurroundingTextCallback { _, _, _ ->
            pin.node!!.isFocused = false // what the app answers now, while the event is still on its way
            SurroundingText("Hi", 2, 2, 0)
        }

        val result = Inserter(port, Dispatchers.IO, log = {}).insert("there", pin, true)

        assertThat(result).isEqualTo(InsertResult(Outcome.NOT_INSERTED, null, Code.TARGET_CHANGED))
    }

    // Every node that cannot be read used to have the key "?", so a pin taken just after one such event matched the
    // next one, whatever node it came from. Each gets a key of its own: a later unreadable event always refuses.
    @Test
    fun unreadableFocusEventsNeverMatch() {
        focus(null)
        val pin = port.cachedPin()!!
        focus(null) // another node, maybe a password one, that cannot be read either

        assertThat(port.samePin(pin)).isFalse()
        assertThat(port.commit(pin, "secret")).isFalse()
    }

    // What must keep working: the same node again, another app's event, and a session with no focus event since the pin.
    // A pin keeps its connection while the reads through it still answer.
    @Test
    fun unchangedNodeKeepsItsConnection() {
        focus(node(1))
        val pin = port.cachedPin()!!
        focus(node(1))
        focus(node(3, app = "com.example.keyboard")) // the keyboard's window: it cannot move this app's connection

        assertThat(port.samePin(pin)).isTrue()
        assertThat(port.readSurrounding(pin, 2, 0)).isNotNull()
        startSession()
        val unnamed = port.cachedPin()!!
        assertThat(port.samePin(unnamed)).isTrue()
        assertThat(port.readSurrounding(unnamed, 2, 0)).isNotNull()
    }

    private fun startSession(type: Int = InputType.TYPE_CLASS_TEXT) {
        shadowOf(service).startInput(EditorInfo().apply { packageName = APP; inputType = type })
        remote().setSurroundingText(SurroundingText("Hi", 2, 2, 0))
    }

    private fun node(id: Int, password: Boolean = false, app: String = APP) = AccessibilityNodeInfo(host, id).apply {
        packageName = app
        className = "android.widget.EditText"
        isEditable = true
        isFocused = true // the source of a focus event
        isPassword = password
        inputType = InputType.TYPE_CLASS_TEXT or if (password) InputType.TYPE_TEXT_VARIATION_PASSWORD else 0
    }

    /** A focus event in [APP] from [node]; a null node is one the service cannot read (getSource() returns null). */
    private fun focus(node: AccessibilityNodeInfo?) {
        val event = AccessibilityEvent(AccessibilityEvent.TYPE_VIEW_FOCUSED).apply { packageName = node?.packageName ?: APP }
        if (node != null) Shadow.extract<ShadowAccessibilityRecord>(event).setSourceNode(node)
        service.onAccessibilityEvent(event)
    }

    private fun remote() = Shadow.extract<ShadowAccessibilityInputConnection>(editor.currentInputConnection)

    private companion object {
        const val APP = "com.example"
    }
}
