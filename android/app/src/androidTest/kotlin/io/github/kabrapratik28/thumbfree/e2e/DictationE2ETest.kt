package io.github.kabrapratik28.thumbfree.e2e

import android.app.ActivityManager
import android.content.Context
import android.graphics.Rect
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.google.common.truth.Truth.assertThat
import io.github.kabrapratik28.thumbfree.R
import io.github.kabrapratik28.thumbfree.a11y.DictationAccessibilityService
import io.github.kabrapratik28.thumbfree.app.AppGraph
import io.github.kabrapratik28.thumbfree.audio.AudioSource
import io.github.kabrapratik28.thumbfree.audio.ForegroundHooks
import io.github.kabrapratik28.thumbfree.core.session.Event
import io.github.kabrapratik28.thumbfree.core.session.State
import io.github.kabrapratik28.thumbfree.data.Dictation
import io.github.kabrapratik28.thumbfree.data.Status
import io.github.kabrapratik28.thumbfree.testing.A11yRule
import io.github.kabrapratik28.thumbfree.testing.JFK_TEXT
import io.github.kabrapratik28.thumbfree.testing.StrictModeRule
import io.github.kabrapratik28.thumbfree.testing.TestModels
import io.github.kabrapratik28.thumbfree.testing.WavFileSource
import io.github.kabrapratik28.thumbfree.testing.automation
import io.github.kabrapratik28.thumbfree.testing.awaitKeyboard
import io.github.kabrapratik28.thumbfree.testing.focusTarget
import io.github.kabrapratik28.thumbfree.testing.jfkPcm
import io.github.kabrapratik28.thumbfree.testing.launchInsertTargets
import io.github.kabrapratik28.thumbfree.testing.normalizeTranscript
import io.github.kabrapratik28.thumbfree.testing.waitFor
import io.github.kabrapratik28.thumbfree.testing.webField
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.AfterClass
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The whole pipeline as the owner uses it: the bubble over another app's field, a take fed from jfk.wav at 4x through
 * AppGraph.audioSourceFactory, Parakeet in the :engine process, and one commit into InsertTargetsActivity.
 */
@RunWith(AndroidJUnit4::class)
class DictationE2ETest {
    @get:Rule(order = 0)
    val a11y = A11yRule()

    @get:Rule(order = 1)
    val strict = StrictModeRule()

    // Created after the rule has set the UiAutomation flags.
    private val device by lazy { UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()) }
    private val app = InstrumentationRegistry.getInstrumentation().targetContext
    private val service get() = DictationAccessibilityService.instance!!
    private var source = WavFileSource(jfkPcm())
    private lateinit var savedFactory: (Context) -> AudioSource

    @Before
    fun setUp() {
        savedFactory = AppGraph.audioSourceFactory
        AppGraph.audioSourceFactory = { source }
        launchInsertTargets(device)
    }

    @After
    fun tearDown() {
        try {
            checkIdleAfterTest()
        } finally {
            AppGraph.audioSourceFactory = savedFactory
        }
    }

    @Test
    fun tapTapInsertsJfk() {
        focusTarget(device, "empty")
        val id = dictate()

        assertJfkArrives("empty")
        val row = awaitRow(id) { it.status == Status.INSERTED }
        assertThat(row?.status).isEqualTo(Status.INSERTED)
        assertThat(normalizeTranscript(row!!.insertedText!!)).isEqualTo(JFK_TEXT)
        assertThat(device.findObject(By.desc("empty")).isFocused).isTrue() // the bubble never took it
    }

    // VOCAB: Settings.customWords correct the text typed in and the row's text; raw_text keeps what the model wrote.
    @Test
    fun customWordsCorrectTheInsertedText() {
        val saved = AppGraph.settings.customWords
        AppGraph.settings.customWords = listOf("Fellow Americans", "COUNTRY") // two words, and one
        try {
            focusTarget(device, "empty")
            val id = dictate()

            val text = device.awaitText("empty") { it.contains("Fellow Americans") && normalizeTranscript(it) == JFK_TEXT }
            assertThat(normalizeTranscript(text)).isEqualTo(JFK_TEXT)
            assertThat(text).contains("Fellow Americans")
            assertThat(Regex("COUNTRY").findAll(text).count()).isEqualTo(2)
            val row = awaitRow(id) { it.status == Status.INSERTED }!!
            assertThat(row.text).contains("Fellow Americans")
            assertThat(normalizeTranscript(row.rawText!!)).isEqualTo(JFK_TEXT)
            assertThat(row.rawText).doesNotContain("Fellow Americans")
            assertThat(row.rawText).doesNotContain("COUNTRY")
        } finally {
            AppGraph.settings.customWords = saved
        }
    }

    @Test
    fun holdToTalkInserts() {
        focusTarget(device, "empty")

        holdBubble(3_300) // JFK takes 2.75 s at 4x

        assertJfkArrives("empty")
    }

    @Test
    fun midSentenceSpacing() {
        focusTarget(device, "prefilled")
        setCursor("prefilled", 5) // after "Hello"

        dictate()

        val expected = "hello $JFK_TEXT world"
        val text = device.awaitText("prefilled") { normalizeTranscript(it) == expected }
        assertThat(normalizeTranscript(text)).isEqualTo(expected)
        assertThat(text).contains("Hello And so")
    }

    @Test
    fun passwordFieldShowsNoBubble() {
        focusTarget(device, "empty")
        settledBubble() // it shows for a text field, so its absence below is the password field's doing
        focusTarget(device, "password")

        SystemClock.sleep(1_000)

        assertThat(bubbleBounds()).isNull()
    }

    @Test
    fun silentTapNeverInserts() {
        source = WavFileSource(ShortArray(4_000), speedup = 1)
        focusTarget(device, "empty")
        val calls = AppGraph.queue.engineCalls
        val circle = settledBubble()

        device.click(circle.centerX(), circle.centerY())
        val id = awaitRecording()
        SystemClock.sleep(300)
        device.click(circle.centerX(), circle.centerY()) // no second settle: its 300 ms would take the take past 1 s
        SystemClock.sleep(3_000)

        assertThat(device.fieldText("empty")).isEmpty()
        assertThat(AppGraph.queue.engineCalls).isEqualTo(calls)
        val row = AppGraph.history.get(id)
        if (row != null) { // no row is fine too: a silent take under 1 s is deleted
            assertThat(row.status).isEqualTo(Status.NO_SPEECH)
            assertThat(row.text).isNull()
        }
    }

    @Test
    fun switchFieldsOffersInsertHere() {
        focusTarget(device, "empty")
        device.tapBubble() // a locked take
        val id = awaitRecording()
        focusTarget(device, "second") // while JFK plays
        assertThat(source.awaitEnd(10_000)).isTrue()
        device.tapBubble()

        assertThat(device.wait(Until.hasObject(By.text(app.getString(R.string.code_target_changed))), 30_000)).isTrue()
        assertThat(device.fieldText("empty")).isEmpty()
        assertThat(device.fieldText("second")).isEmpty()
        device.findObject(By.text(app.getString(R.string.bubble_insert_here))).click()
        assertJfkArrives("second")
        assertThat(awaitRow(id) { it.status == Status.INSERTED }?.status).isEqualTo(Status.INSERTED)
    }

    @Test
    fun cancelKeepsAudioAndUndoInserts() {
        focusTarget(device, "empty")
        device.tapBubble() // a locked take
        val id = awaitRecording()
        assertThat(source.awaitEnd(10_000)).isTrue()
        val x = AtomicReference<Rect>()
        check(waitFor(2_000) { x.set(onMain { service.bubble?.cancelBoundsOnScreen() }); x.get() != null }) { "no X button" }

        device.click(x.get().centerX(), x.get().centerY())

        val row = awaitRow(id) { it.status == Status.CANCELLED }
        assertThat(row?.status).isEqualTo(Status.CANCELLED)
        assertThat(File(app.filesDir, row!!.audioFile).exists()).isTrue()
        // The Undo chip goes after 5 s.
        checkNotNull(device.wait(Until.findObject(By.text(app.getString(R.string.bubble_undo))), 2_000)) { "no Undo" }.click()
        assertJfkArrives("empty")
        assertThat(awaitRow(id) { it.status == Status.INSERTED }?.status).isEqualTo(Status.INSERTED)
    }

    // The target is pinned at touch-down, so a focus change right after it never gets the text.
    @Test
    fun switchRightAfterTouchDownNeverWritesToSecondField() {
        focusTarget(device, "empty")
        val circle = settledBubble()
        val downAt = SystemClock.uptimeMillis()

        inject(downAt, MotionEvent.ACTION_DOWN, circle)
        focusNode("second") // a second finger would make a multi-touch gesture, so focus moves through accessibility
        assertThat(SystemClock.uptimeMillis() - downAt).isLessThan(300L) // landed before the touch could count as a hold
        val id = awaitRecording()
        assertThat(source.awaitEnd(10_000)).isTrue()
        inject(downAt, MotionEvent.ACTION_UP, circle) // a hold, so the release stops the take

        assertThat(device.wait(Until.hasObject(By.text(app.getString(R.string.code_target_changed))), 30_000)).isTrue()
        assertThat(device.fieldText("empty")).isEmpty()
        assertThat(device.fieldText("second")).isEmpty()
        val row = awaitRow(id) { it.status == Status.NOT_INSERTED }
        assertThat(row?.status).isEqualTo(Status.NOT_INSERTED)
        assertThat(row!!.error).isEqualTo("TARGET_CHANGED")
    }

    // The WebView names its host in the session's focus event, and findFocus answers with the host too, which is
    // not editable. The bubble must still show over its field, and a take through it must land there.
    @Test
    fun webFieldTakesATakeThroughTheBubble() {
        checkNotNull(device.wait(Until.findObject(webField()), 10_000)) { "the web field did not load" }.click()
        awaitKeyboard()

        val id = dictate()

        val text = awaitWebText { normalizeTranscript(it) == JFK_TEXT }
        assertThat(normalizeTranscript(text)).isEqualTo(JFK_TEXT)
        assertThat(awaitRow(id) { it.status == Status.INSERTED }?.status).isEqualTo(Status.INSERTED)
    }

    @Test
    fun foregroundServiceRunsOnlyWhileTheMicIsOpen() {
        focusTarget(device, "empty")
        device.tapBubble()
        assertThat(source.awaitEnd(10_000)).isTrue()
        assertThat(ForegroundHooks.isForeground).isTrue()

        device.tapBubble()

        assertThat(waitFor(2_000) { !ForegroundHooks.isForeground }).isTrue()
        // Read through our service: a UiDevice query first waits for 500 ms without accessibility events.
        assertThat(service.focusedEditable()!!.apply { refresh() }.text?.toString().orEmpty()).isEmpty()
        assertJfkArrives("empty")
    }

    @Test
    fun noModelShowsMessage() {
        val model = File(app.filesDir, "models/${TestModels.PARAKEET_Q8}") // where AppGraph's ModelStore looks
        val aside = File(model.path + ".aside")
        if (aside.exists()) check(aside.renameTo(model)) // a process death inside the try skips finally
        check(model.renameTo(aside)) { "no model: run android/tools/push-test-model.sh" }
        try {
            focusTarget(device, "empty")
            dictate()

            assertThat(device.wait(Until.hasObject(By.text(app.getString(R.string.code_no_model))), 10_000)).isTrue()
            assertThat(DictationAccessibilityService.instance).isNotNull()
        } finally {
            check(aside.renameTo(model)) { "the model could not be put back" }
        }
    }

    companion object {
        @AfterClass
        @JvmStatic
        fun afterClass() = releaseEngine()
    }

    /** Tap, let the whole source play, tap: a locked take. Returns its session id. */
    private fun dictate(): String {
        device.tapBubble()
        val id = awaitRecording()
        assertThat(source.awaitEnd(10_000)).isTrue()
        device.tapBubble()
        return id
    }

    /** Polls the web field until [done] holds or 30 s pass; returns the last text read, for the assertion. */
    private fun awaitWebText(done: (String) -> Boolean): String {
        var text = ""
        waitFor(30_000) { done(device.findObject(webField())?.text.orEmpty().also { text = it }) }
        return text
    }

    private fun assertJfkArrives(desc: String) {
        assertThat(normalizeTranscript(device.awaitText(desc) { normalizeTranscript(it) == JFK_TEXT })).isEqualTo(JFK_TEXT)
    }

    private fun focusNode(desc: String) = check(node(desc).performAction(AccessibilityNodeInfo.ACTION_FOCUS)) { "$desc refused focus" }

    /** Puts the cursor of [desc] at [index], then waits for onUpdateSelection or 500 ms. */
    private fun setCursor(desc: String, index: Int) {
        val moved = CountDownLatch(1)
        service.editor.onSelectionChanged = { moved.countDown() }
        try {
            val args = Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, index)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, index)
            }
            check(node(desc).performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, args)) { "$desc refused the cursor" }
            moved.await(500, TimeUnit.MILLISECONDS)
        } finally {
            service.editor.onSelectionChanged = null
        }
    }

    /** The node whose content description is [desc], under the active window's root. */
    private fun node(desc: String): AccessibilityNodeInfo {
        fun find(node: AccessibilityNodeInfo): AccessibilityNodeInfo? =
            if (node.contentDescription?.toString() == desc) node
            else (0 until node.childCount).firstNotNullOfOrNull { node.getChild(it)?.let(::find) }
        return checkNotNull(service.rootInActiveWindow?.let(::find)) { "no node $desc" }
    }
}

// Shared with LongTakeE2ETest.

internal fun <T> onMain(block: () -> T): T {
    val result = AtomicReference<T>()
    InstrumentationRegistry.getInstrumentation().runOnMainSync { result.set(block()) }
    return result.get()
}

/** The bubble's circle on screen, read on the main thread like every BubbleWindow call; null while it is hidden. */
internal fun bubbleBounds(): Rect? = onMain { DictationAccessibilityService.instance?.bubble?.boundsOnScreen() }

/**
 * The circle once it has held still for 300 ms: the bubble follows the keyboard as it slides in, through one or two
 * spots on the way, and a tap on a spot it has just left would miss.
 */
internal fun settledBubble(): Rect {
    var last: Rect? = null
    var since = 0L
    val settled = waitFor(5_000) {
        val now = bubbleBounds()
        if (now != last) {
            last = now
            since = SystemClock.uptimeMillis()
        }
        now != null && SystemClock.uptimeMillis() - since >= 300
    }
    check(settled) { "no bubble" }
    return last!!
}

internal fun UiDevice.tapBubble() {
    val circle = settledBubble()
    click(circle.centerX(), circle.centerY())
}

/**
 * Holds the circle for [ms]: DOWN, then UP [ms] later. Not UiDevice.swipe with ms / 5 steps: each step is a synchronous
 * move that waits about 34 ms for the next frame here, so a 3,300 ms hold lasted 22.7 s.
 */
internal fun holdBubble(ms: Long) {
    val circle = settledBubble()
    val downAt = SystemClock.uptimeMillis()
    inject(downAt, MotionEvent.ACTION_DOWN, circle)
    SystemClock.sleep(ms)
    inject(downAt, MotionEvent.ACTION_UP, circle)
}

internal fun inject(downAt: Long, action: Int, at: Rect) {
    val event = MotionEvent.obtain(downAt, SystemClock.uptimeMillis(), action, at.exactCenterX(), at.exactCenterY(), 0)
    event.source = InputDevice.SOURCE_TOUCHSCREEN
    check(automation().injectInputEvent(event, true)) { "the touch was not injected" }
    event.recycle()
}

/** The field's text; fails when the field is not there, so an emptiness check cannot pass on a missing field. */
internal fun UiDevice.fieldText(desc: String): String =
    checkNotNull(wait(Until.findObject(By.desc(desc)), 5_000)) { "no field $desc" }.text.orEmpty()

/** Polls the field [desc] until [done] holds or [timeoutMs] passes; returns the last text read, for the assertion. */
internal fun UiDevice.awaitText(desc: String, timeoutMs: Long = 30_000, done: (String) -> Boolean): String {
    var text = ""
    waitFor(timeoutMs) { done(fieldText(desc).also { text = it }) }
    return text
}

/** Waits until a take records and returns its session id, so row checks read this take's row, never an older one. */
internal fun awaitRecording(): String {
    var id: String? = null
    check(waitFor(5_000) { (onMain { AppGraph.controller.state } as? State.Recording)?.id.also { id = it } != null }) { "no take" }
    return id!!
}

/** A row turns INSERTED only after the insert's verify read, a moment after the text shows: poll, never read once. */
internal fun awaitRow(id: String, timeoutMs: Long = 10_000, done: (Dictation) -> Boolean): Dictation? {
    var row: Dictation? = null
    waitFor(timeoutMs) { AppGraph.history.get(id).also { row = it }?.let(done) == true }
    return row
}

/**
 * Ends :engine, which the queue's RemoteEngine keeps bound with the model loaded for the rest of the run. RemoteEngine
 * allows one instance per process, and EngineServiceTest's own instance expects :engine to end when it unloads. The
 * queue's binding goes with the process (binderDied), and its next take loads the model again.
 */
internal fun releaseEngine() {
    val app = InstrumentationRegistry.getInstrumentation().targetContext
    fun enginePid() = app.getSystemService(ActivityManager::class.java).runningAppProcesses.orEmpty()
        .firstOrNull { it.processName == "${app.packageName}:engine" }?.pid
    enginePid()?.let(Process::killProcess)
    check(waitFor(5_000) { enginePid() == null }) { ":engine did not end" }
}

/** A take left live by a failed test would take the next test's taps: it is cancelled, and this test fails. */
internal fun checkIdleAfterTest() {
    val idle = waitFor(30_000) { onMain { AppGraph.controller.state } == State.Idle }
    if (!idle) onMain { AppGraph.controller.onEvent(Event.Cancel) }
    check(idle) { "a take was still live at the end of the test" }
}
