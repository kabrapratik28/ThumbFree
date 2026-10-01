package io.github.kabrapratik28.thumbfree.e2e

import android.content.Context
import android.content.res.Configuration
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import com.google.common.truth.Truth.assertThat
import io.github.kabrapratik28.thumbfree.a11y.DictationAccessibilityService
import io.github.kabrapratik28.thumbfree.a11y.PreviewPanel
import io.github.kabrapratik28.thumbfree.app.AppGraph
import io.github.kabrapratik28.thumbfree.audio.AudioSource
import io.github.kabrapratik28.thumbfree.core.models.Catalog
import io.github.kabrapratik28.thumbfree.core.session.BubblePlacement
import io.github.kabrapratik28.thumbfree.core.session.PreviewPlace
import io.github.kabrapratik28.thumbfree.core.session.State
import io.github.kabrapratik28.thumbfree.data.Status
import io.github.kabrapratik28.thumbfree.engine.RemoteEngine
import io.github.kabrapratik28.thumbfree.testing.A11yRule
import io.github.kabrapratik28.thumbfree.testing.JFK_TEXT
import io.github.kabrapratik28.thumbfree.testing.StrictModeRule
import io.github.kabrapratik28.thumbfree.testing.WavFileSource
import io.github.kabrapratik28.thumbfree.testing.focusTarget
import io.github.kabrapratik28.thumbfree.testing.jfkPcm
import io.github.kabrapratik28.thumbfree.testing.launchInsertTargets
import io.github.kabrapratik28.thumbfree.testing.normalizeTranscript
import io.github.kabrapratik28.thumbfree.testing.waitFor
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.AfterClass
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The live preview as a user sees it. A JFK take at real time (the preview stream must keep up, so no 4x here)
 * with Show words while I speak off, then on: with it on the panel by the bubble shows JFK's words growing while the take
 * records, and the text typed at the stop is exactly the text typed with it off. Nothing is left behind: no per-take
 * state in the wiring and no open stream in :engine.
 */
@RunWith(AndroidJUnit4::class)
class PreviewE2ETest {
    @get:Rule(order = 0)
    val a11y = A11yRule()

    @get:Rule(order = 1)
    val strict = StrictModeRule()

    private val device by lazy { UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()) }
    private var source = jfkSource()
    private lateinit var savedFactory: (Context) -> AudioSource
    private var savedPreview = false

    @Before
    fun setUp() {
        savedFactory = AppGraph.audioSourceFactory
        savedPreview = AppGraph.settings.livePreview
        AppGraph.audioSourceFactory = { source }
        launchInsertTargets(device)
    }

    @After
    fun tearDown() {
        try {
            checkIdleAfterTest()
        } finally {
            AppGraph.audioSourceFactory = savedFactory
            AppGraph.settings.livePreview = savedPreview
        }
    }

    @Test
    fun previewShowsTheWordsWhileSpeakingAndTheTypedTextIsTheSame() {
        AppGraph.settings.livePreview = false
        focusTarget(device, "empty")
        val offId = dictate { assertThat(panelText()).isNull() } // no panel with the preview off
        val off = device.awaitText("empty") { normalizeTranscript(it) == JFK_TEXT }
        val offRow = awaitRow(offId) { it.status == Status.INSERTED }!!

        AppGraph.settings.livePreview = true
        source = jfkSource()
        focusTarget(device, "second")
        val seen = linkedSetOf<String>()
        var shot = false
        val onId = dictate {
            panelText()?.let { if (it.isNotBlank()) seen += it }
            // With -e preview_shots 1: one screenshot of the panel mid-take, for review (files/preview-shot.png).
            if (!shot && shots && seen.size >= 4) {
                val files = InstrumentationRegistry.getInstrumentation().targetContext.filesDir
                device.takeScreenshot(java.io.File(files, "preview-shot.png"))
                shot = true
            }
        }
        val on = device.awaitText("second") { normalizeTranscript(it) == JFK_TEXT }
        val onRow = awaitRow(onId) { it.status == Status.INSERTED }!!
        Log.i("ThumbFree", "preview_e2e texts=${seen.size}") // numbers only

        // While recording the panel showed JFK's words, growing: at least three different texts. The last shows the newest
        // lines (the panel keeps three, cut at a line start with an ellipsis), far into the speech.
        assertThat(seen.size).isAtLeast(3)
        val last = normalizeTranscript(seen.last())
        assertThat(JFK_TEXT).contains(last)
        assertThat(last.split(" ").size).isAtLeast(12)
        assertThat(JFK_TEXT.indexOf(last) + last.length).isAtLeast(JFK_TEXT.length / 2)
        // The typed text is exactly the same with the preview on and off: the field and the row.
        assertThat(on).isEqualTo(off)
        assertThat(onRow.insertedText).isEqualTo(offRow.insertedText)
        assertThat(onRow.text).isEqualTo(offRow.text)
        // Once the text is typed the panel goes, and nothing of the take is left.
        assertThat(waitFor(5_000) { panelText() == null }).isTrue()
        assertThat(onMain { AppGraph.ports.previewTake }).isNull() // the wiring keeps only the pin, for a chip's Undo
        assertThat(waitFor(5_000) { streamSessionGone() }).isTrue() // the stream's session went with the take
    }

    @Test
    fun cancelHidesThePanelAndEndsTheStream() {
        AppGraph.settings.livePreview = true
        focusTarget(device, "empty")
        device.tapBubble()
        awaitRecording()
        assertThat(waitFor(8_000) { !panelText().isNullOrBlank() }).isTrue()

        onMain { AppGraph.controller.onEvent(io.github.kabrapratik28.thumbfree.core.session.Event.Cancel) }

        assertThat(waitFor(5_000) { panelText() == null }).isTrue()
        assertThat(waitFor(5_000) { onMain { AppGraph.controller.state } == State.Idle }).isTrue()
        assertThat(onMain { AppGraph.ports.previewTake }).isNull()
        assertThat(waitFor(5_000) { streamSessionGone() }).isTrue()
        assertThat(device.fieldText("empty")).isEmpty() // a preview never types
    }

    // A model without the preview flag (Canary) never starts the preview, switch on or not: no panel and no stream
    // session at any point, and its text is typed as usual.
    @Test
    fun aCanaryTakeWithThePreviewOnStartsNoStream() {
        val saved = AppGraph.settings.selectedModelId
        try {
            AppGraph.settings.selectedModelId = Catalog.CANARY_180M_FLASH_Q8.id
            AppGraph.settings.livePreview = true
            focusTarget(device, "empty")
            var streamed = false
            dictate { streamed = streamed || !streamSessionGone() || panelText() != null }
            val text = device.awaitText("empty", 60_000) { normalizeTranscript(it).contains("ask not what your country") }
            assertThat(streamed).isFalse()
            assertThat(normalizeTranscript(text)).contains("ask not what your country")
        } finally {
            AppGraph.settings.selectedModelId = saved
        }
    }

    // The panel takes touches in its own area, so its words can be scrolled back, and a tap there does nothing: it
    // never stops the take or starts another.
    @Test
    fun aTapOnThePanelNeitherStopsNorStartsATake() {
        AppGraph.settings.livePreview = true
        focusTarget(device, "empty")
        device.tapBubble()
        val id = awaitRecording()
        assertThat(waitFor(8_000) { !panelText().isNullOrBlank() }).isTrue()
        val (x, y) = onMain {
            val box = panel()!!.view
            val at = IntArray(2).also { box.getLocationOnScreen(it) }
            (at[0] + box.width / 2) to (at[1] + box.height / 2)
        }

        device.click(x, y)
        SystemClock.sleep(700)

        assertThat((onMain { AppGraph.controller.state } as? State.Recording)?.id).isEqualTo(id) // still the same take
        while (!source.awaitEnd(100)) Unit
        stopAtTheEnd(device, source)
        assertThat(normalizeTranscript(device.awaitText("empty") { normalizeTranscript(it) == JFK_TEXT })).isEqualTo(JFK_TEXT)
        assertThat(waitFor(5_000) { onMain { AppGraph.controller.state } == State.Idle }).isTrue() // and no new take
    }

    // The panel's geometry comes from a worker. With the target app's answers stuck, the take still stops, its audio
    // still arrives and its text is still typed: the main thread never waits for them.
    @Test
    fun aStuckCursorQueryNeverHoldsUpTheTake() {
        val stuck = CountDownLatch(1)
        val saved = onMain { AppGraph.ports.geometrySource }
        try {
            onMain { AppGraph.ports.geometrySource = { stuck.await(); null } }
            AppGraph.settings.livePreview = true
            focusTarget(device, "empty")
            var shown = false
            dictate { shown = shown || panelText() != null }
            val text = device.awaitText("empty") { normalizeTranscript(it) == JFK_TEXT }
            assertThat(normalizeTranscript(text)).isEqualTo(JFK_TEXT)
            assertThat(shown).isFalse() // no geometry, no panel: it never guesses where the cursor is
        } finally {
            stuck.countDown()
            onMain { AppGraph.ports.geometrySource = saved }
        }
    }

    // The panel's geometry belongs to one focus (its window, node and editor generation), not to a field that only
    // looks the same: "empty" and "second" are both plain text fields in one window. With A's cursor query held, focus
    // moves to B; A's answer, when it comes, is dropped, B's query goes out, and the panel stays hidden, words and all,
    // until B's answer arrives.
    @Test
    fun aLateCursorAnswerForOneFieldIsNeverUsedForAnother() {
        val saved = onMain { AppGraph.ports.geometrySource }
        val askedA = CountDownLatch(1)
        val releaseA = CountDownLatch(1)
        val askedB = CountDownLatch(1)
        val releaseB = CountDownLatch(1)
        val calls = AtomicInteger()
        try {
            onMain {
                AppGraph.ports.geometrySource = {
                    when (calls.incrementAndGet()) {
                        1 -> saved().also { askedA.countDown(); releaseA.await() } // read while A has focus, then held
                        2 -> { askedB.countDown(); releaseB.await(); saved() }
                        else -> saved()
                    }
                }
            }
            AppGraph.settings.livePreview = true
            focusTarget(device, "empty") // A
            device.tapBubble() // a locked take
            awaitRecording()
            assertThat(askedA.await(8, TimeUnit.SECONDS)).isTrue()
            focusTarget(device, "second") // B: the same app, window and input type
            assertThat(waitFor(8_000) { onMain { AppGraph.ports.previewHasWords } }).isTrue()

            releaseA.countDown()
            assertThat(askedB.await(8, TimeUnit.SECONDS)).isTrue() // A's answer dropped, B's asked for
            var shown = false
            val until = SystemClock.uptimeMillis() + 2_000
            while (SystemClock.uptimeMillis() < until) {
                shown = shown || panelText() != null
                SystemClock.sleep(50)
            }
            assertThat(shown).isFalse() // words to show, but no answer for B yet
            releaseB.countDown()
            assertThat(waitFor(8_000) { panelText() != null }).isTrue() // B's answer: the panel shows
        } finally {
            releaseA.countDown()
            releaseB.countDown()
            onMain {
                AppGraph.ports.geometrySource = saved
                AppGraph.controller.onEvent(io.github.kabrapratik28.thumbfree.core.session.Event.Cancel) // the take goes
            }
            waitFor(5_000) { onMain { AppGraph.controller.state } == State.Idle } // tearDown checks it went
        }
    }

    // With the bubble dragged to the top edge and "Top of the screen" chosen, the strip gives way to the bubble's spot,
    // never over the circle, so a locked take's stop tap still reaches the bubble.
    @Test
    fun withTheBubbleAtTheTopTheTopStripNeverTakesItsStopTap() {
        val savedSpot = AppGraph.settings.bubbleSpot
        val savedPlace = AppGraph.settings.livePreviewPlace
        try {
            AppGraph.settings.livePreview = true
            AppGraph.settings.livePreviewPlace = PreviewPlace.TOP
            AppGraph.settings.bubbleSpot = BubblePlacement.Spot(1f, 0f) // the top right corner
            onMain { AppGraph.ports.bubbleSettingsChanged() }
            focusTarget(device, "second")
            device.tapBubble() // a locked take
            val id = awaitRecording()
            var overlapped = false
            while (!source.awaitEnd(100)) {
                overlapped = overlapped || onMain {
                    val circle = DictationAccessibilityService.instance?.bubble?.circle() ?: return@onMain false
                    val panel = panel() ?: return@onMain false
                    val box = IntArray(2).also { panel.view.getLocationOnScreen(it) }
                    box[0] < circle[2] && box[0] + panel.view.width > circle[0] &&
                        box[1] < circle[3] && box[1] + panel.view.height > circle[1]
                }
            }
            stopAtTheEnd(device, source) // the tap on the bubble at the top stops the take
            val text = device.awaitText("second") { normalizeTranscript(it) == JFK_TEXT }
            assertThat(normalizeTranscript(text)).isEqualTo(JFK_TEXT)
            assertThat(overlapped).isFalse()
            assertThat(awaitRow(id) { it.status == Status.INSERTED }).isNotNull()
        } finally {
            AppGraph.settings.bubbleSpot = savedSpot
            AppGraph.settings.livePreviewPlace = savedPlace
            onMain { AppGraph.ports.bubbleSettingsChanged() }
        }
    }

    // With -e preview_shots 1, each place's panel mid-take, the same screen and the same words (when the panel first
    // reaches "can do for you"), to files/preview-place-{bubble,top}-{light,dark}.png. The mode is the emulator's. The
    // field is the fourth, "second", so the top strip is clear of its cursor line.
    @Test
    fun screenshotsOfBothPlaces() {
        assumeTrue("pass -e preview_shots 1", shots)
        val saved = AppGraph.settings.livePreviewPlace
        val night = context().resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        try {
            AppGraph.settings.livePreview = true
            for (place in PreviewPlace.entries) {
                AppGraph.settings.livePreviewPlace = place
                source = jfkSource()
                focusTarget(device, "second")
                var shot = false
                dictate {
                    if (!shot && normalizeTranscript(panelText().orEmpty()).endsWith("can do for you")) {
                        SystemClock.sleep(300) // the window's move lands a frame after its new words
                        val name = "preview-place-${place.name.lowercase()}-${if (night) "dark" else "light"}.png"
                        device.takeScreenshot(java.io.File(context().filesDir, name))
                        shot = true
                    }
                }
                device.awaitText("second") { normalizeTranscript(it) == JFK_TEXT }
                assertThat(shot).isTrue()
                awaitRecordingEnded()
                device.findObject(By.desc("second"))?.text = "" // the next take types into the same empty field
            }
        } finally {
            AppGraph.settings.livePreviewPlace = saved
        }
    }

    private fun awaitRecordingEnded() = assertThat(waitFor(10_000) { onMain { AppGraph.controller.state } == State.Idle }).isTrue()

    private fun context(): Context = InstrumentationRegistry.getInstrumentation().targetContext

    /** Tap, let the whole source play (running [during] while it records), tap: a locked take. Returns its id. */
    private fun dictate(during: () -> Unit): String {
        device.tapBubble()
        val id = awaitRecording()
        while (!source.awaitEnd(100)) during()
        stopAtTheEnd(device, source)
        return id
    }

    private val shots = androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("preview_shots") == "1"

    companion object {
        @AfterClass
        @JvmStatic
        fun afterClass() = releaseEngine()

        /** :engine has no preview stream session (it lives only while a take streams), or no model or process at all. */
        fun streamSessionGone() =
            !runBlocking { (AppGraph.engine as RemoteEngine).info() }.contains("stream_session=1")

        /** The preview panel while it shows, else null. Main thread. */
        fun panel(): PreviewPanel? = DictationAccessibilityService.instance?.bubble?.preview?.takeIf { it.shown }

        /**
         * JFK and 0.6 s of silence at real time, held at its end: with [stopAtTheEnd] a take's WAV is the same every time
         * (the stop's timing otherwise changes the trailing silence, and with it the text's final period).
         */
        fun jfkSource() = WavFileSource(jfkPcm().copyOf(176_000 + 9_600), speedup = 1, holdAtEnd = true)

        /**
         * Taps the stop while [source] (held at its end) waits, then lets it go on: the audio ends in 0.6 s of silence, so
         * the take ends with no tail and the WAV is the audio and one read of zeros, at any speed.
         */
        fun stopAtTheEnd(device: UiDevice, source: WavFileSource) {
            device.tapBubble()
            check(waitFor(5_000) { onMain { AppGraph.controller.state } is State.Stopping }) { "no stop" }
            source.resume()
        }
    }

    /** The preview panel's words while it shows, else null. */
    private fun panelText(): String? = onMain { panel()?.text?.toString() }
}
