package io.github.kabrapratik28.thumbfree.e2e

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.google.common.truth.Truth.assertThat
import io.github.kabrapratik28.thumbfree.a11y.PreviewPanel
import io.github.kabrapratik28.thumbfree.app.AppGraph
import io.github.kabrapratik28.thumbfree.audio.AudioSource
import io.github.kabrapratik28.thumbfree.data.Status
import io.github.kabrapratik28.thumbfree.e2e.PreviewE2ETest.Companion.panel
import io.github.kabrapratik28.thumbfree.e2e.PreviewE2ETest.Companion.stopAtTheEnd
import io.github.kabrapratik28.thumbfree.e2e.PreviewE2ETest.Companion.streamSessionGone
import io.github.kabrapratik28.thumbfree.testing.A11yRule
import io.github.kabrapratik28.thumbfree.testing.EngineMemory
import io.github.kabrapratik28.thumbfree.testing.StrictModeRule
import io.github.kabrapratik28.thumbfree.testing.WavFileSource
import io.github.kabrapratik28.thumbfree.testing.focusTarget
import io.github.kabrapratik28.thumbfree.testing.jfkPcm
import io.github.kabrapratik28.thumbfree.testing.launchInsertTargets
import io.github.kabrapratik28.thumbfree.testing.normalizeTranscript
import io.github.kabrapratik28.thumbfree.testing.waitFor
import java.io.File
import org.junit.After
import org.junit.AfterClass
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A long take with the live preview, almost 4 minutes of speech (JFK 20 times, each followed by 0.6 s of silence).
 * With the preview off (at 4x) no stream session ever exists; with it on (at real time) the panel keeps updating to the
 * end and keeps only its last 2,000 characters, :engine's memory stays flat after the first minute and under its 1.2 GB
 * bound, scrolling back holds the words until 5 s after the touch, or until the words are back at the bottom, and the
 * typed text and row are the same as with it off. Each stop lands on the audio's exact end (the source holds its next
 * read until the stop is in), so both takes' WAVs are the same at either speed. With `-e preview_shots 1` it saves
 * files/preview-long-mid.png and files/preview-long-scrolled.png.
 */
@RunWith(AndroidJUnit4::class)
class LongPreviewE2ETest {
    @get:Rule(order = 0)
    val a11y = A11yRule()

    @get:Rule(order = 1)
    val strict = StrictModeRule()

    private val device by lazy { UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()) }
    private val pcm = jfkPcm().let { jfk ->
        ShortArray(REPEATS * (jfk.size + 9_600)).also { pcm -> repeat(REPEATS) { jfk.copyInto(pcm, it * (jfk.size + 9_600)) } }
    }
    private var source = WavFileSource(pcm, holdAtEnd = true)
    private lateinit var savedFactory: (Context) -> AudioSource
    private var savedPreview = false
    private val shots = InstrumentationRegistry.getArguments().getString("preview_shots") == "1"

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
    fun aThreeMinuteTakeKeepsThePreviewUpdatingFlatAndScrollable() {
        // Off: no preview state and no stream session at any point of the take.
        AppGraph.settings.livePreview = false
        focusTarget(device, "empty")
        device.tapBubble()
        val offId = awaitRecording()
        var offStreamed = false
        while (!source.awaitEnd(500)) {
            offStreamed = offStreamed || !streamSessionGone() || onMain { AppGraph.ports.previewTake } != null
        }
        stopAtTheEnd(device, source)
        val off = device.awaitText("empty", 90_000) { phrases(it) == REPEATS }
        val offRow = awaitRow(offId) { it.status == Status.INSERTED }!!
        assertThat(offStreamed).isFalse()

        // On, at real time.
        AppGraph.settings.livePreview = true
        source = WavFileSource(pcm, speedup = 1, holdAtEnd = true)
        focusTarget(device, "second")
        device.tapBubble()
        val onId = awaitRecording()
        val start = SystemClock.elapsedRealtime()
        val changes = mutableListOf<Long>() // seconds into the take at which the panel's words changed
        var last = ""
        var hwmMinute = 0L // :engine's peak RSS after the first minute: the stream and two chunk transcribes have run
        val pss = mutableListOf<Long>()
        var midShot = !shots
        var held = false
        var resumed = false
        var bottomResumed = false
        var scrolledShot = !shots
        fun seconds() = (SystemClock.elapsedRealtime() - start) / 1_000
        while (!source.awaitEnd(500)) {
            val now = onMain { panel()?.text?.toString() }.orEmpty()
            if (now.isNotBlank() && now != last) changes += seconds()
            if (now.isNotBlank()) last = now
            if (hwmMinute == 0L && seconds() >= 60) hwmMinute = EngineMemory.hwmKb()
            if (seconds() % 10 == 0L) pss += EngineMemory.pssKb()
            if (!midShot && seconds() >= 20) midShot = true.also { shot("preview-long-mid.png") }
            // At 1:40, a finger scrolls the words back: they hold for 5 s after the touch, then follow again.
            if (!held && seconds() >= 100) {
                scrollBack()
                val words = onMain { panel()!!.text.toString() }
                assertThat(onMain { panel()!!.scroll.canScrollVertically(1) }).isTrue() // off the bottom
                SystemClock.sleep(3_000)
                assertThat(onMain { panel()!!.text.toString() }).isEqualTo(words) // held while the user reads
                held = true
                // 5 s after the last touch or scroll (a fling scrolls on a little after the finger lifts).
                resumed = waitFor(6_000) { following(words) }
            }
            // At 2:30, back and down to the bottom again by hand: the newest words at once, not 5 s later.
            if (!bottomResumed && seconds() >= 150) {
                scrollBack()
                val words = onMain { panel()!!.text.toString() }
                repeat(20) { if (onMain { panel()!!.scroll.canScrollVertically(1) }) scrollForward() }
                // Well before the 5 s: the words that came meanwhile at once, or the next ones.
                bottomResumed = waitFor(4_000) { following(words) }
            }
            if (!scrolledShot && seconds() >= 170) {
                scrollBack()
                scrolledShot = true.also { shot("preview-long-scrolled.png") }
            }
        }
        val shownAtEnd = onMain { panel() != null }
        val buffer = onMain { panel()?.text?.toString() }.orEmpty() // near the end: past the panel's cap
        stopAtTheEnd(device, source)
        val on = device.awaitText("second", 90_000) { phrases(it) == REPEATS }
        val onRow = awaitRow(onId) { it.status == Status.INSERTED }!!
        val hwm = EngineMemory.hwmKb()
        Log.i("ThumbFree", "preview_long changes=${changes.size} hwm_1min_kb=$hwmMinute hwm_kb=$hwm pss_min_kb=${pss.min()} " +
            "pss_max_kb=${pss.max()}") // numbers only

        assertThat(held).isTrue()
        assertThat(resumed).isTrue()
        assertThat(bottomResumed).isTrue()
        // Updating throughout: a change in every 20 s of the take, the 10 s held for the scroll-back excepted.
        for (window in 0 until 220 step 20) {
            if (window in 100 until 120) continue
            assertThat(changes.any { it in window until window + 20 }).isTrue()
        }
        assertThat(shownAtEnd).isTrue() // the stream kept up to the end: it never gave up
        // The panel's own buffer stays bounded: the take's text is over 2,100 characters, the panel keeps the last 2,000.
        assertThat(buffer.length).isAtMost(PreviewPanel.MAX_CHARS + 1)
        assertThat(buffer).startsWith("…")
        assertThat(on.length).isGreaterThan(PreviewPanel.MAX_CHARS) // and the typed text is whole
        // Memory: flat after the first minute (the peak no higher by more than a little), and under the 1.2 GB bound.
        assertThat(hwmMinute).isGreaterThan(0L)
        assertThat(hwm - hwmMinute).isLessThan(25_000L)
        assertThat(hwm).isLessThan(1_200_000L)
        // The typed text and the row are the same as with the preview off.
        assertThat(on).isEqualTo(off)
        assertThat(onRow.insertedText).isEqualTo(offRow.insertedText)
        assertThat(onRow.text).isEqualTo(offRow.text)
        assertThat(waitFor(5_000) { onMain { panel() } == null }).isTrue()
        assertThat(waitFor(5_000) { streamSessionGone() }).isTrue()
    }

    /** The panel shows newer words than [words], at the bottom. */
    private fun following(words: String) = onMain { panel()!!.text.toString() != words && !panel()!!.scroll.canScrollVertically(1) }

    /** A finger drags the words down across the box: they scroll back toward the start. */
    private fun scrollBack() = drag(down = true)

    /** A finger drags the words up: they scroll toward the newest. */
    private fun scrollForward() = drag(down = false)

    private fun drag(down: Boolean) {
        val (x, top, bottom) = onMain {
            val box = panel()!!.scroll
            val at = IntArray(2).also { box.getLocationOnScreen(it) }
            Triple(at[0] + box.width / 2, at[1] + box.height / 6, at[1] + box.height * 5 / 6)
        }
        if (down) device.swipe(x, top, x, bottom, 10) else device.swipe(x, bottom, x, top, 10)
    }

    private fun shot(name: String) {
        SystemClock.sleep(300) // the panel's window lands a frame after its words
        device.takeScreenshot(File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, name))
    }

    private fun phrases(text: String) = Regex("ask not what your country").findAll(normalizeTranscript(text)).count()

    companion object {
        const val REPEATS = 20 // about 2,160 characters of text: past the panel's 2,000

        @AfterClass
        @JvmStatic
        fun afterClass() = releaseEngine()
    }
}
