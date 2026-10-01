package io.github.kabrapratik28.thumbfree.a11y

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Looper
import android.os.SystemClock
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
import android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import com.google.common.truth.Truth.assertThat
import io.github.kabrapratik28.thumbfree.R
import io.github.kabrapratik28.thumbfree.core.session.BubbleStyle
import io.github.kabrapratik28.thumbfree.core.session.PreviewUi
import java.time.Duration
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE) // real text measurement, for the line box
class PreviewPanelTest {
    private val context = RuntimeEnvironment.getApplication()
    private val panel = PreviewPanel(context, context.getSystemService(WindowManager::class.java))
    private val style = BubbleStyle.RECOMMENDED
    private val width get() = panel.widthFor(style, 1080)
    private val words get() = panel.scroll.getChildAt(0) as TextView

    private fun colorsOf(text: CharSequence): List<Pair<String, Int>> {
        val spanned = text as Spanned
        return spanned.getSpans(0, text.length, ForegroundColorSpan::class.java)
            .map { text.substring(spanned.getSpanStart(it), spanned.getSpanEnd(it)) to it.foregroundColor }
    }

    private fun label() = (panel.view.getChildAt(0) as TextView).text.toString()

    /** Renders [ui] and lays it out as its window would. */
    private fun show(ui: PreviewUi) {
        val height = panel.render(ui, style, width)
        panel.view.layout(0, 0, width, height)
    }

    private fun said(words: Int, from: Int = 1) = (from until from + words).joinToString(" ") { "word$it" }

    private fun touch(action: Int) {
        val now = SystemClock.uptimeMillis()
        panel.scroll.dispatchTouchEvent(MotionEvent.obtain(now, now, action, 10f, 10f, 0))
    }

    @Test
    fun settledWordsInInkAndTheTailLighter() {
        val height = panel.render(PreviewUi.Live("And so, my fellow", " Americans"), style, width)

        assertThat(height).isGreaterThan(0)
        assertThat(label()).isEqualTo("Preview")
        assertThat(panel.text.toString()).isEqualTo("And so, my fellow Americans")
        assertThat(colorsOf(panel.text)).containsExactly(
            "And so, my fellow" to context.getColor(R.color.preview_ink),
            " Americans" to context.getColor(R.color.preview_tentative),
        )
    }

    @Test
    fun theBoxIsAtMostThreeLinesAndFollowsTheNewestWords() {
        show(PreviewUi.Live(said(12), ""))
        val short = panel.scroll.layoutParams.height
        assertThat(short).isEqualTo(words.measuredHeight) // under three lines: as tall as the words

        show(PreviewUi.Live(said(80), " tail"))

        assertThat(words.lineCount).isGreaterThan(PreviewPanel.MAX_LINES)
        // Three lines, and at the bottom the box's top is the top of the third line from the end.
        val box = panel.scroll.layoutParams.height
        assertThat(box).isEqualTo(words.height - words.layout.getLineTop(words.lineCount - PreviewPanel.MAX_LINES))
        assertThat(panel.scroll.scrollY).isEqualTo(words.layout.getLineTop(words.lineCount - PreviewPanel.MAX_LINES))
        assertThat(panel.scroll.canScrollVertically(1)).isFalse() // at the bottom: the newest words show
        assertThat(panel.scroll.canScrollVertically(-1)).isTrue()
        assertThat(panel.text.toString()).endsWith("word80 tail")
    }

    @Test
    fun onlyAboutTheLastTwoThousandCharactersAreKept() {
        val committed = said(3_000) // about 26,000 characters: a long take

        show(PreviewUi.Live(committed, " tail"))

        val shown = panel.text.toString()
        assertThat(shown.length).isAtMost(PreviewPanel.MAX_CHARS + 1)
        assertThat(shown).startsWith("…word") // cut at a word
        assertThat(shown).endsWith("word3000 tail")
        assertThat(colorsOf(panel.text).last()).isEqualTo(" tail" to context.getColor(R.color.preview_tentative))
    }

    @Test
    fun scrollingBackHoldsTheWordsUntilFiveSecondsAfterTheLastTouch() {
        show(PreviewUi.Live(said(80), ""))
        touch(MotionEvent.ACTION_DOWN)
        panel.scroll.scrollTo(0, 0) // the finger drags the words back to the start
        touch(MotionEvent.ACTION_UP)

        show(PreviewUi.Live(said(90), ""))
        assertThat(panel.text.toString()).endsWith("word80") // held while the user reads
        assertThat(panel.scroll.scrollY).isEqualTo(0)

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(PreviewPanel.RESUME_MS))
        panel.view.layout(0, 0, width, panel.render(PreviewUi.Live(said(90), ""), style, width))

        assertThat(panel.text.toString()).endsWith("word90")
        assertThat(panel.scroll.canScrollVertically(1)).isFalse() // back at the newest words
    }

    @Test
    fun scrollingBackToTheBottomFollowsAgainAtOnce() {
        show(PreviewUi.Live(said(80), ""))
        touch(MotionEvent.ACTION_DOWN)
        panel.scroll.scrollTo(0, 0)
        panel.scroll.scrollTo(0, words.height) // and down again to the bottom
        touch(MotionEvent.ACTION_UP)

        show(PreviewUi.Live(said(90), ""))

        assertThat(panel.text.toString()).endsWith("word90")
        assertThat(panel.scroll.canScrollVertically(1)).isFalse()
    }

    @Test
    fun aTouchOnlyScrollsThePanelAndTouchesOutsideGoToTheApp() {
        // Not focusable: the system sends every touch outside the window to the windows under it. Touchable: a finger
        // can scroll the words back.
        assertThat(panel.params.flags and FLAG_NOT_FOCUSABLE).isEqualTo(FLAG_NOT_FOCUSABLE)
        assertThat(panel.params.flags and FLAG_NOT_TOUCHABLE).isEqualTo(0)
        show(PreviewUi.Live("short", ""))
        touch(MotionEvent.ACTION_DOWN) // nothing to scroll: a tap never holds the words
        touch(MotionEvent.ACTION_UP)
        show(PreviewUi.Live("short and more", ""))
        assertThat(panel.text.toString()).isEqualTo("short and more")
    }

    @Test
    fun afterTheStopTheWordsDimUnderTranscribing() {
        panel.render(PreviewUi.Finishing("And so, my fellow Americans"), style, width)

        assertThat(label()).isEqualTo("Transcribing")
        assertThat(colorsOf(panel.text).map { it.second }.toSet()).containsExactly(context.getColor(R.color.preview_tentative))
    }

    @Test
    fun hiddenLaysOutNothing() {
        assertThat(panel.render(PreviewUi.Hidden, style, width)).isEqualTo(0)
    }

    @Test
    fun widthAndTypeFollowTheBubbleSize() {
        val small = panel.widthFor(BubbleStyle(BubbleStyle.Size.SMALL, 85), 2_000)
        val large = panel.widthFor(BubbleStyle(BubbleStyle.Size.EXTRA_LARGE, 85), 2_000)
        assertThat(large).isGreaterThan(small)
        assertThat(panel.widthFor(style, 300)).isLessThan(300) // never wider than the room it has
    }

    @Test
    fun everyTextColourKeepsNormalTextContrastOverAnyApp() = assertContrast()

    @Test
    @Config(qualifiers = "night")
    fun everyTextColourKeepsNormalTextContrastOverAnyAppInTheDark() = assertContrast()

    @Test
    fun thePanelIsSolidWhateverTheBubblesTransparency() {
        panel.render(PreviewUi.Live("And so", ""), BubbleStyle(BubbleStyle.Size.LARGE, BubbleStyle.MIN_OPACITY), width)

        val color = (panel.view.background as GradientDrawable).color!!.defaultColor
        assertThat(Color.alpha(color)).isEqualTo(255) // the app's text never shows through the words
    }

    /** A 4.5:1 contrast for every text colour (14 to 16 sp is normal text) on the panel, which is solid. */
    private fun assertContrast() {
        val surface = context.getColor(R.color.preview_surface)
        assertThat(Color.alpha(surface)).isEqualTo(255) // nothing of the app shows through
        for (color in listOf(R.color.preview_ink, R.color.preview_tentative, R.color.preview_label)) {
            assertThat(ColorUtils.calculateContrast(context.getColor(color), surface)).isAtLeast(4.5)
        }
    }

    @Test
    fun talkBackCanReadTheWordsButIsNeverMadeToSpeakThem() {
        panel.render(PreviewUi.Live("And so", ", my fellow"), style, width)

        // The microphone is open: no live region anywhere in the panel, so no update is announced into the take.
        val views = listOf(panel.view, panel.view.getChildAt(0), panel.scroll, words)
        assertThat(views.map { it.accessibilityLiveRegion }.toSet()).containsExactly(View.ACCESSIBILITY_LIVE_REGION_NONE)
        assertThat(views.map { it.importantForAccessibility }).doesNotContain(View.IMPORTANT_FOR_ACCESSIBILITY_NO)
        assertThat(words.text.toString()).isEqualTo("And so, my fellow")
    }
}
