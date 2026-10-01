package io.github.kabrapratik28.thumbfree.a11y

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.google.common.truth.Truth.assertThat
import io.github.kabrapratik28.thumbfree.core.session.Code
import io.github.kabrapratik28.thumbfree.core.session.Outcome
import io.github.kabrapratik28.thumbfree.testing.A11yRule
import io.github.kabrapratik28.thumbfree.testing.focusTarget
import io.github.kabrapratik28.thumbfree.testing.launchInsertTargets
import io.github.kabrapratik28.thumbfree.testing.webField
import io.github.kabrapratik28.thumbfree.testing.waitFor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AccessibilityEditorPortTest {
    @get:Rule
    val a11y = A11yRule()

    // Created after the rule has set the UiAutomation flags.
    private val device by lazy { UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()) }
    private val service get() = DictationAccessibilityService.instance!!
    private val port = AccessibilityEditorPort(InstrumentationRegistry.getInstrumentation().targetContext)
    private val logs = mutableListOf<String>()
    private val inserter = Inserter(port, Dispatchers.IO.limitedParallelism(1), log = { logs += it; Log.i("ThumbFree", it) })

    @Before
    fun launch() = launchInsertTargets(device)

    @Test
    fun commitLandsAndIsVerified() {
        val pin = pinField("second")

        val result = runBlocking { inserter.insert("hello there", pin, true) }

        assertThat(result).isEqualTo(InsertResult(Outcome.INSERTED, "Hello there", null))
        assertThat(device.findObject(By.desc("second")).text).isEqualTo("Hello there")
        assertThat(logs.single()).contains("evidence=surrounding")
    }

    @Test
    fun switchedFieldIsNotWritten() {
        val pin = pinField("prefilled")
        focusTarget(device, "second")
        assertThat(waitFor(5_000) { service.editor.generation > pin.generation }).isTrue()

        val result = runBlocking { inserter.insert("hello", pin, true) }

        assertThat(result).isEqualTo(InsertResult(Outcome.NOT_INSERTED, null, Code.TARGET_CHANGED))
        assertThat(device.findObject(By.desc("prefilled")).text).isEqualTo("Hello world")
        assertThat(device.findObject(By.desc("second")).text.orEmpty()).isEmpty()
    }

    // A take writes through the connection its session got at onStartInput. After a field change the app has
    // deactivated that connection, so a write through it reaches neither field.
    @Test
    fun staleConnectionWritesNowhere() {
        val pin = pinField("prefilled")
        val snapshot = service.editor.snapshot!!
        assertThat(snapshot.generation).isEqualTo(pin.generation)
        assertThat(snapshot.connection).isNotNull()
        focusTarget(device, "second")
        assertThat(waitFor(5_000) { (service.editor.snapshot?.generation ?: 0) > pin.generation }).isTrue()

        assertThat(port.commit(pin, "stale")).isFalse()
        snapshot.connection!!.commitText("stale", 1, null)
        SystemClock.sleep(1_000) // a commit that got through shows well within this

        assertThat(device.findObject(By.desc("prefilled")).text).isEqualTo("Hello world")
        assertThat(device.findObject(By.desc("second")).text.orEmpty()).isEmpty()
    }

    @Test
    fun passwordFieldIsRefused() {
        val pin = pinField("password")

        val result = runBlocking { inserter.insert("hello", pin, true) }

        assertThat(result).isEqualTo(InsertResult(Outcome.NOT_INSERTED, null, Code.PASSWORD_TARGET))
    }

    // Just before the write the pinned node is read again past the node cache, one IPC. Its median and worst time over
    // 20 reads are logged as still_focused_ms.
    @Test
    fun pinnedNodeIsReadAgainBeforeTheWrite() {
        val pin = pinField("second")
        val ms = (1..20).map {
            val start = System.nanoTime()
            assertThat(port.stillFocused(pin)).isTrue()
            (System.nanoTime() - start) / 1e6
        }.sorted()
        Log.i("ThumbFree", "still_focused_ms median=%.2f max=%.2f".format(ms[ms.size / 2], ms.last()))

        focusTarget(device, "prefilled")
        assertThat(waitFor(5_000) { service.editor.generation > pin.generation }).isTrue()
        assertThat(port.stillFocused(pin)).isFalse() // the pinned field lost focus
    }

    // A WebView's field is a virtual node of its host, and the session's last focus event can name the host, whose node
    // reports isFocused false. An automatic insert must still land in the field.
    @Test
    fun webFieldTakesAnAutomaticInsert() {
        val pin = pinWebField()

        val result = runBlocking { inserter.insert("hello there", pin, true) }

        assertThat(result.outcome).isEqualTo(Outcome.INSERTED)
        assertThat(waitFor(5_000) { webFieldText() == result.insertedText }).isTrue()
    }

    // The target app has focus, so the system denies our clipboard read-back; the clip must still count as written.
    @Test
    fun copyThenPasteLands() {
        pinField("second")

        assertThat(port.copyToClipboard("pasted words")).isTrue()
        assertThat(port.paste()).isTrue()

        assertThat(waitFor(5_000) { device.findObject(By.desc("second")).text == "pasted words" }).isTrue()
    }

    // Insert here checks for a password before it pastes, and focus can move in between: paste checks its own node.
    @Test
    fun pasteRefusesPasswordField() {
        pinField("password")

        assertThat(port.copyToClipboard("pasted words")).isTrue()
        assertThat(port.paste()).isFalse()

        assertThat(device.findObject(By.desc("password")).text.orEmpty()).isEmpty()
    }

    /**
     * Clicks the WebView's input once it has loaded and pins it as the touch handler does, once the session and its
     * focus event arrived. On the emulator that event names the host, and findFocus returns the host too (not
     * editable), so the field is found and read through UiAutomator. A second focus event of the session comes within
     * the 300 ms a finger takes to reach the bubble.
     */
    private fun pinWebField(): Pin {
        val input = checkNotNull(device.wait(Until.findObject(webField()), 10_000)) { "the web field did not load" }
        val before = service.editor.generation
        input.click()
        val pinned = waitFor(5_000) {
            service.editor.generation > before && port.cachedPin()?.nodeKey?.isNotEmpty() == true
        }
        assertThat(pinned).isTrue()
        SystemClock.sleep(300)
        return port.cachedPin()!!.also {
            Log.i("ThumbFree", "web_pin node_class=${it.node?.className} editable=${it.node?.isEditable}")
        }
    }

    private fun webFieldText() = device.findObject(webField())?.text

    /**
     * Focuses [desc] and pins it as the touch handler does at touch-down, once its session and focus event arrived: a
     * pin taken before that event names no node, and the event then refuses it (a finger reaching the bubble takes longer).
     */
    private fun pinField(desc: String): Pin {
        val before = service.editor.generation
        focusTarget(device, desc)
        assertThat(
            waitFor(5_000) {
                service.editor.generation > before && port.cachedPin()?.nodeKey?.isNotEmpty() == true &&
                    service.focusedEditable()?.contentDescription?.toString() == desc
            },
        ).isTrue()
        return port.cachedPin()!!
    }
}
