package io.github.kabrapratik28.thumbfree.a11y

import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.view.WindowMetrics
import android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
import android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
import android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
import android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
import android.view.WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
import android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
import android.view.WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
import android.view.WindowManager.LayoutParams.WRAP_CONTENT
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.github.kabrapratik28.thumbfree.core.session.BubblePlacement
import io.github.kabrapratik28.thumbfree.core.session.BubbleUi
import io.github.kabrapratik28.thumbfree.core.session.ChipAction
import io.github.kabrapratik28.thumbfree.core.session.ChipAction.COPY
import io.github.kabrapratik28.thumbfree.core.session.ChipAction.DISMISS
import io.github.kabrapratik28.thumbfree.core.session.ChipAction.INSERT_HERE
import io.github.kabrapratik28.thumbfree.core.session.ChipAction.RETRY
import io.github.kabrapratik28.thumbfree.core.session.ChipAction.UNDO
import io.github.kabrapratik28.thumbfree.core.session.Code
import io.github.kabrapratik28.thumbfree.core.session.PreviewPlace
import io.github.kabrapratik28.thumbfree.core.session.PreviewUi
import java.util.concurrent.TimeUnit.MILLISECONDS
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

@RunWith(RobolectricTestRunner::class)
class BubbleWindowTest {
    private val context = RuntimeEnvironment.getApplication()
    private val windowManager = FakeWindowManager(context.getSystemService(WindowManager::class.java))
    private val chips = mutableListOf<ChipAction>()
    private val window = BubbleWindow(context, windowManager, onTouch = { false }, onChip = { chips += it })

    @Test
    fun paramsNotFocusable() {
        val params = window.params
        val required = FLAG_NOT_FOCUSABLE or FLAG_NOT_TOUCH_MODAL or FLAG_LAYOUT_NO_LIMITS

        assertThat(params.type).isEqualTo(TYPE_ACCESSIBILITY_OVERLAY)
        assertThat(params.flags and required).isEqualTo(required)
        assertThat(params.flags and FLAG_WATCH_OUTSIDE_TOUCH).isEqualTo(0)
        assertThat(params.width).isEqualTo(WRAP_CONTENT)
        assertThat(params.height).isEqualTo(WRAP_CONTENT)
        assertThat(params.gravity).isEqualTo(Gravity.TOP or Gravity.START)
        assertThat(params.format).isEqualTo(PixelFormat.TRANSLUCENT)
    }

    // BubblePlacement works in screen pixels. By default the window's y counts from below the status bar and camera
    // cutout: on the emulator a y of 1342 landed at 1484, on the keyboard.
    @Test
    fun paramsUseScreenCoordinates() {
        assertThat(window.params.fitInsetsTypes).isEqualTo(0)
        assertThat(window.params.layoutInDisplayCutoutMode).isEqualTo(LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS)
    }

    // The wiring calls keepScreenOn on every render, so only a change relays out the window.
    @Test
    fun keepScreenOnToggles() {
        window.show(0, 0)

        window.keepScreenOn(true)
        window.keepScreenOn(true)
        assertThat(window.params.flags and FLAG_KEEP_SCREEN_ON).isEqualTo(FLAG_KEEP_SCREEN_ON)
        assertThat(windowManager.updates).isEqualTo(1)

        window.keepScreenOn(false)
        assertThat(window.params.flags and FLAG_KEEP_SCREEN_ON).isEqualTo(0)
        assertThat(windowManager.updates).isEqualTo(2)
    }

    @Test
    fun addViewFailureReturnsFalse() {
        val failing = FakeWindowManager(context.getSystemService(WindowManager::class.java), failAdd = true)

        assertThat(BubbleWindow(context, failing, { false }, {}).show(0, 0)).isFalse()
    }

    @Test
    fun chipAutoDismisses() {
        window.render(BubbleUi.Chip(Code.NO_SPEECH, listOf(DISMISS)))

        idle(1_499)
        assertThat(chips).isEmpty()
        idle(1)
        assertThat(chips).containsExactly(DISMISS)
    }

    @Test
    fun chipTimeoutsFollowTheirActions() {
        val cases = mapOf(
            listOf(COPY) to 300_000L, listOf(INSERT_HERE) to 300_000L, listOf(UNDO) to 5_000L,
            listOf(RETRY, DISMISS) to 8_000L,
        )
        for ((actions, ms) in cases) {
            chips.clear()
            window.render(BubbleUi.Chip(Code.HELD_BACK, actions))

            idle(ms - 1)
            assertWithMessage("$actions").that(chips).isEmpty()
            idle(1)
            assertWithMessage("$actions").that(chips).containsExactly(DISMISS)
        }
    }

    @Test
    fun laterRenderCancelsTheDismissal() {
        window.render(BubbleUi.Chip(Code.NO_SPEECH, listOf(DISMISS)))
        window.render(BubbleUi.Idle)

        idle(10_000)
        assertThat(chips).isEmpty()
    }

    // The system can drop the window while the service stays connected. Neither a relayout nor hide() may crash the
    // service, and the next show adds the window again.
    @Test
    fun droppedWindowCountsAsHidden() {
        window.show(0, 0)
        windowManager.dropped = true
        window.keepScreenOn(true)
        window.show(0, 0)
        assertThat(windowManager.adds).isEqualTo(2)

        windowManager.dropped = true
        window.hide()
        window.show(0, 0)
        assertThat(windowManager.adds).isEqualTo(3)
        // A device run's log shows whether this ever happens.
        assertThat(ShadowLog.getLogsForTag("ThumbFree").map { it.msg })
            .containsAtLeast("bubble updateViewLayout failed", "bubble removeView failed")
    }

    // A move that finds the window gone adds it again at once.
    @Test
    fun moveOfADroppedWindowAddsItAgain() {
        window.show(0, 0)
        windowManager.dropped = true

        assertThat(window.show(0, 5)).isTrue()
        assertThat(windowManager.adds).isEqualTo(2)
        assertThat(window.shown).isTrue()
    }

    // When the service goes, its chip's dismissal must not fire later on the next window's chip.
    @Test
    fun disposeStopsTheDismissalAndRemovesTheWindow() {
        window.show(0, 0)
        window.render(BubbleUi.Chip(Code.HELD_BACK, listOf(COPY)))

        window.dispose()
        idle(300_000)

        assertThat(chips).isEmpty()
        assertThat(windowManager.removes).isEqualTo(1)
    }

    // The service re-reports focus on every window event, including the bubble's own moves.
    @Test
    fun repeatedPlacementDoesNotRelayout() {
        window.show(10, 20)
        window.show(10, 20)
        window.move(10, 20)
        assertThat(windowManager.updates).isEqualTo(0)

        window.show(10, 21)
        assertThat(windowManager.updates).isEqualTo(1)
    }

    @Test
    fun repeatReportDuringDragDoesNotMove() {
        window.show(0, 0)
        val bubble = windowManager.view!!

        bubble.dispatchTouchEvent(touch(MotionEvent.ACTION_DOWN))
        window.move(40, 0) // the drag
        window.show(0, 0) // a focus re-report mid-drag
        assertThat(window.params.x).isEqualTo(40)

        bubble.dispatchTouchEvent(touch(MotionEvent.ACTION_UP))
        window.show(0, 0)
        assertThat(window.params.x).isEqualTo(0)

        // A window removed mid-touch never sees the UP, but events queued before it died still arrive. Later
        // placements still apply.
        bubble.dispatchTouchEvent(touch(MotionEvent.ACTION_DOWN))
        window.hide()
        bubble.dispatchTouchEvent(touch(MotionEvent.ACTION_MOVE))
        window.show(5, 0)
        window.show(7, 0)
        assertThat(window.params.x).isEqualTo(7)
    }

    @Test
    fun rightEdgeBubbleKeepsCancelAndChipOnScreen() = assertExtrasOnScreen(circleX = SCREEN_WIDTH - 48)

    @Test
    fun leftEdgeBubbleKeepsCancelAndChipOnScreen() = assertExtrasOnScreen(circleX = 0)

    // The X button and the chip open toward the middle of the screen; the circle stays where it was placed.
    private fun assertExtrasOnScreen(circleX: Int) {
        window.show(circleX, 1342)
        val bubble = windowManager.view as BubbleView
        for (ui in listOf(BubbleUi.Recording(0f, locked = true, 0), BubbleUi.Chip(Code.HELD_BACK, listOf(COPY, INSERT_HERE)))) {
            window.render(ui)
            val any = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            bubble.measure(any, any)
            bubble.layout(0, 0, bubble.measuredWidth, bubble.measuredHeight)

            // What the window manager does with the params: under END gravity, x counts from the right edge.
            val fromRight = (window.params.gravity and Gravity.END) == Gravity.END
            val left = if (fromRight) SCREEN_WIDTH - window.params.x - bubble.width else window.params.x
            val circle = if (fromRight) left + bubble.width - bubble.sizePx else left
            val extra = (0 until bubble.childCount).map(bubble::getChildAt).single { it.visibility == View.VISIBLE }

            assertWithMessage("$ui circle").that(circle).isEqualTo(circleX)
            assertWithMessage("$ui left").that(left + extra.left).isAtLeast(0)
            assertWithMessage("$ui right").that(left + extra.right).isAtMost(SCREEN_WIDTH)
            val beside = left + extra.right <= circle || left + extra.left >= circle + bubble.sizePx
            assertWithMessage("$ui beside the circle").that(beside).isTrue()
        }
    }

    // Dropped mid-screen, a bubble can have less room on its side than a chip with two buttons needs: the window shifts
    // in just enough to keep the chip on screen, and the circle goes back to its spot when the chip goes.
    @Test
    fun midScreenBubbleShiftsForAWideChip() {
        val narrow = FakeWindowManager(context.getSystemService(WindowManager::class.java), width = 200)
        val window = BubbleWindow(context, narrow, { false }, {})
        window.show(76, 1342) // the circle's middle at 100 of 200: its X button and chip open to the left
        val bubble = narrow.view as BubbleView
        fun layOut() {
            bubble.measure(View.MeasureSpec.makeMeasureSpec(200, View.MeasureSpec.AT_MOST), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            bubble.layout(0, 0, bubble.measuredWidth, bubble.measuredHeight)
            idle(0) // the placement the width change posted
        }
        fun left() = if ((window.params.gravity and Gravity.END) == Gravity.END) 200 - window.params.x - bubble.width else window.params.x
        layOut()

        window.render(BubbleUi.Chip(Code.HELD_BACK, listOf(COPY, INSERT_HERE)))
        layOut()
        assertThat(bubble.width).isGreaterThan(76 + bubble.sizePx) // wider than the room left of the circle
        assertThat(left()).isEqualTo(0) // shifted just enough

        window.render(BubbleUi.Idle)
        layOut()
        assertThat(left()).isEqualTo(76)
    }

    private fun touch(action: Int) = MotionEvent.obtain(0, 0, action, 1f, 1f, 0)

    private fun idle(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(ms, MILLISECONDS)

    /** Records calls instead of adding a window; [failAdd] makes addView throw like a service without a token. */
    // Where the live preview panel shows: the top strip when chosen, by the bubble for the rest of the take when the
    // strip would cover the cursor's line, and nowhere when no spot is safe.
    private val area = BubblePlacement.Box(0, 100, SCREEN_WIDTH, 2300)
    private val words = PreviewUi.Live("And so, my fellow Americans", "")
    private val margin get() = (8 * context.resources.displayMetrics.density).toInt()

    @Test
    fun thePreviewShowsAsATopStripWhenChosen() {
        window.show(900, 1500)

        window.showPreview(words, area, null, BubblePlacement.Box(0, 1200, SCREEN_WIDTH, 1250), PreviewPlace.TOP)

        assertThat(window.preview.shown).isTrue()
        assertThat(window.preview.params.x).isEqualTo(margin)
        assertThat(window.preview.params.y).isEqualTo(area.top + margin / 2) // just under the status bar
        assertThat(window.preview.params.width).isEqualTo(SCREEN_WIDTH - 2 * margin)
    }

    @Test
    fun aTopStripOverTheCursorLineShowsByTheBubbleForTheRestOfTheTake() {
        window.show(900, 1500)
        val searchBox = BubblePlacement.Box(0, 120, SCREEN_WIDTH, 200)

        window.showPreview(words, area, null, searchBox, PreviewPlace.TOP)
        val byBubble = window.preview.params.y
        assertThat(byBubble).isLessThan(1500) // above the circle
        assertThat(byBubble).isGreaterThan(200) // not over the search box
        window.showPreview(words, area, null, null, PreviewPlace.TOP) // the line went: this take stays by the bubble
        assertThat(window.preview.params.y).isEqualTo(byBubble)

        window.hidePreview() // the take ended: the next one tries the top again
        window.showPreview(words, area, null, null, PreviewPlace.TOP)
        assertThat(window.preview.params.y).isEqualTo(area.top + margin / 2)
    }

    @Test
    fun aBubbleAtTheTopKeepsTheTopStripOffItSoItsStopStaysReachable() {
        window.show(900, 120) // dragged to the top edge

        window.showPreview(words, area, null, null, PreviewPlace.TOP)

        assertThat(window.preview.shown).isTrue()
        assertThat(overlapsCircle(900, 120)).isFalse() // by the bubble instead: below it, off the circle
        assertThat(window.preview.params.y).isAtLeast(120 + window.sizePx)
    }

    @Test
    @Config(fontScale = 2f)
    fun atTwiceTheFontSizeATopStripThatDoesNotFitGivesWay() {
        val longWords = PreviewUi.Live("And so, my fellow Americans, ask not what your country can do for you, ask what", "")
        window.show(900, 1500)
        window.showPreview(longWords, area, null, null, PreviewPlace.TOP)
        val strip = window.preview.view.measuredHeight // the strip at this font
        assertThat(window.preview.params.y).isEqualTo(area.top + margin / 2) // it fits the full area
        window.hidePreview()
        val short = area.copy(bottom = area.top + margin / 2 + strip - 1) // a split-screen half one pixel too short

        window.showPreview(longWords, short, null, null, PreviewPlace.TOP)

        val isStrip = window.preview.shown && window.preview.params.y == short.top + margin / 2 &&
            window.preview.params.width == SCREEN_WIDTH - 2 * margin
        assertThat(isStrip).isFalse() // it does not fit: by the bubble, or hidden
    }

    private fun overlapsCircle(x: Int, y: Int): Boolean {
        val p = window.preview.params
        val height = window.preview.view.measuredHeight
        return p.x < x + window.sizePx && p.x + p.width > x && p.y < y + window.sizePx && p.y + height > y
    }

    @Test
    fun withNoSafeSpotThePanelHides() {
        window.show(900, 1500)
        // A cursor line right above the circle, a keyboard right below it: neither spot is clear.
        val line = BubblePlacement.Box(0, 1500 - 400, SCREEN_WIDTH, 1499)

        window.showPreview(words, area, 1500 + window.sizePx + 4, line, PreviewPlace.BUBBLE)

        assertThat(window.preview.shown).isFalse()
    }

    private class FakeWindowManager(real: WindowManager, private val failAdd: Boolean = false, private val width: Int = SCREEN_WIDTH) :
        WindowManager by real {
        var updates = 0
        var adds = 0
        var removes = 0
        var view: View? = null
        var dropped = false // the system removed the window: the window manager no longer knows the view

        override fun addView(view: View, params: ViewGroup.LayoutParams) {
            if (failAdd) throw WindowManager.BadTokenException("no token")
            this.view = view
            adds++
            dropped = false
        }

        override fun updateViewLayout(view: View, params: ViewGroup.LayoutParams) {
            if (dropped) throw IllegalArgumentException("View not attached to window manager")
            updates++
        }

        override fun removeView(view: View) {
            if (dropped) throw IllegalArgumentException("View not attached to window manager")
            removes++
        }

        override fun getCurrentWindowMetrics() = WindowMetrics(Rect(0, 0, width, 2400), WindowInsets.CONSUMED, 1f)
    }

    private companion object {
        const val SCREEN_WIDTH = 1080
    }
}
