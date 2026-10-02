package io.github.kabrapratik28.thumbfree.a11y

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
import android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
import android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
import android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
import android.view.WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
import android.view.WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
import android.view.WindowManager.LayoutParams.WRAP_CONTENT
import io.github.kabrapratik28.thumbfree.core.session.BubblePlacement
import io.github.kabrapratik28.thumbfree.core.session.BubbleStyle
import io.github.kabrapratik28.thumbfree.core.session.BubbleUi
import io.github.kabrapratik28.thumbfree.core.session.ChipAction
import io.github.kabrapratik28.thumbfree.core.session.Grey
import io.github.kabrapratik28.thumbfree.core.session.PreviewPlace
import io.github.kabrapratik28.thumbfree.core.session.PreviewPlacement
import io.github.kabrapratik28.thumbfree.core.session.PreviewUi

/**
 * The bubble's overlay window; [windowManager] must be the accessibility service's. The window never takes focus, so
 * the field being dictated into keeps its keyboard. Main thread only.
 */
class BubbleWindow(
    private val context: Context,
    private val windowManager: WindowManager,
    private val onTouch: (MotionEvent) -> Boolean,
    private val onChip: (ChipAction) -> Unit,
) {
    val params = WindowManager.LayoutParams(
        WRAP_CONTENT, WRAP_CONTENT, TYPE_ACCESSIBILITY_OVERLAY,
        FLAG_NOT_FOCUSABLE or FLAG_NOT_TOUCH_MODAL or FLAG_LAYOUT_NO_LIMITS, PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        // x, y are screen pixels, like BubblePlacement's: no status bar, navigation bar or cutout inset shifts them.
        fitInsetsTypes = 0
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        // A chip or the not-ready panel widens the window toward the middle of the screen, which moves its left edge: the
        // system would slide the whole window there, bubble and all, from where it was. The bubble stays put instead, and
        // the panel comes in place. (Android 13 has no public way to ask this.)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) setCanPlayMoveAnimation(false)
    }

    private val view = BubbleView(context, onChip).apply {
        greyMotion = false // its grey look stays still in other apps: no redraw frame by frame
        setOnTouchListener { _, event ->
            if (!shown) return@setOnTouchListener true // an event queued before hide() must not re-arm the guard
            // A tap anywhere else puts the not-ready panel away; only its window asks for such touches.
            if (event.actionMasked == MotionEvent.ACTION_OUTSIDE) {
                if (panel) onChip(ChipAction.DISMISS)
                return@setOnTouchListener true
            }
            touching = event.actionMasked != MotionEvent.ACTION_UP && event.actionMasked != MotionEvent.ACTION_CANCEL
            onTouch(event)
        }
        // A chip, the panel or the X button coming or going changes the window's size: placed again from its spot (see
        // place), so the circle stays where it was.
        addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) {
                this@BubbleWindow.handler.post { spot?.let { (x, y) -> move(x, y) } }
            }
        }
    }
    private val handler = Handler(Looper.getMainLooper())
    private val dismiss = Runnable { onChip(ChipAction.DISMISS) }

    /** The live preview panel's window, by the circle while a take shows words (showPreview). */
    val preview = PreviewPanel(context, windowManager)
    private var topUnsafe = false // this take's top strip would cover the cursor's line: it shows by the bubble

    /** False while hidden, and once a relayout or a remove found that the system had dropped the window. */
    var shown = false
        private set

    /** Posted to main when a relayout or a remove finds the window dropped: a finger on it never sends its UP. */
    var onDropped: () -> Unit = {}
    private var touching = false
    private var panel = false // the not-ready panel shows
    private var spot: Pair<Int, Int>? = null // the circle's top-left as last placed

    /**
     * Adds the window with the circle's top-left corner at [x], [y] (screen pixels), or moves it there when it already
     * shows. While a finger is on the bubble a showing window stays put: the service re-reports focus on every window
     * event, including this window's own moves, and placing it then would pull the bubble from under a drag or a hold.
     * Drags use [move].
     */
    fun show(x: Int, y: Int): Boolean {
        if (shown) {
            if (!touching) move(x, y)
            if (shown) return true // a move that found the window gone falls through and adds it again
        }
        place(x, y)
        try {
            windowManager.addView(view, params)
        } catch (e: RuntimeException) {
            // BadTokenException while the service is not connected, for example. The caller tries again later.
            Log.w("ThumbFree", "bubble addView failed", e)
            return false
        }
        shown = true
        return true
    }

    fun move(x: Int, y: Int) {
        if (place(x, y)) relayout() // a repeated focus report places it on the same spot
    }

    fun hide() {
        preview.hide() // the panel goes with the bubble
        if (!shown) return
        shown = false
        touching = false // a removed window may never see the touch's UP or CANCEL
        try {
            windowManager.removeView(view)
        } catch (e: IllegalArgumentException) {
            // The system dropped the window already; it is hidden either way.
            Log.w("ThumbFree", "bubble removeView failed", e)
            handler.post { onDropped() }
        }
    }

    /**
     * The service is gone: stops the chip's dismissal timer, so it can never dismiss a later window's chip, and removes
     * the window.
     */
    fun dispose() {
        handler.removeCallbacks(dismiss)
        hide()
    }

    /**
     * A chip's dismissal timer keeps running while the window is hidden, and the next render cancels it. The not-ready
     * panel has no timer: what it says is how to get dictation working, so it stays until Open, a tap outside it (the
     * window watches outside touches only while it shows) or a second tap on the bubble. Its DISMISS goes to its owner,
     * AndroidPorts, since the panel is no take's.
     */
    fun render(ui: BubbleUi) {
        handler.removeCallbacks(dismiss)
        view.render(ui)
        if (ui is BubbleUi.Chip) handler.postDelayed(dismiss, chipMs(ui.actions))
        panel = ui is BubbleUi.NotReady
        val flags = if (panel) params.flags or FLAG_WATCH_OUTSIDE_TOUCH else params.flags and FLAG_WATCH_OUTSIDE_TOUCH.inv()
        if (flags != params.flags) {
            params.flags = flags
            relayout()
        }
    }

    /** The circle's touch target side in pixels, for the current [style]. */
    val sizePx: Int get() = view.sizePx

    /** The bubble's grey look while it can't listen yet (BubbleView.grey); null once it can. */
    var grey: Grey?
        get() = view.grey
        set(value) {
            view.grey = value
        }

    val style: BubbleStyle get() = view.style

    /** Draws the bubble at [style]'s size and idle opacity. The caller places it again: its spot depends on the size. */
    fun restyle(style: BubbleStyle) {
        view.style = style
    }

    /**
     * Live preview: shows [ui] in the panel [where] the owner chose, in [area] (screen pixels less the bars and
     * cutout), above a keyboard whose top is [imeTop] and clear of the text cursor's [line]: in a strip at the top of
     * the screen (PreviewPlacement.top), or above or below the circle (PreviewPlacement.place). A top strip that would
     * not fit, or would cover the cursor's line or the bubble, gives way to the bubble's spot for the rest of the take;
     * with no safe spot the panel hides. Hidden for Hidden, and while the bubble is hidden.
     */
    fun showPreview(ui: PreviewUi, area: BubblePlacement.Box, imeTop: Int?, line: BubblePlacement.Box?, where: PreviewPlace) {
        val spot = spot
        if (ui == PreviewUi.Hidden || !shown || spot == null) return hidePreview()
        val margin = (8 * context.resources.displayMetrics.density).toInt()
        val circle = BubblePlacement.Box(spot.first, spot.second, spot.first + sizePx, spot.second + sizePx)
        if (where == PreviewPlace.TOP && !topUnsafe) {
            val width = area.right - area.left - 2 * margin
            val height = preview.render(ui, style, width)
            // The bubble with its X and chips, which open beside the circle: the whole band across the screen it spans.
            val band = BubblePlacement.Box(area.left, circle.top, area.right, circle.top + maxOf(sizePx, view.height))
            val at = PreviewPlacement.top(width, height, area, line, band, sidePx = margin, gapPx = margin / 2, marginPx = margin)
            if (at != null) return preview.show(at.first, at.second, width)
            topUnsafe = true
        }
        val width = preview.widthFor(style, area.right - area.left)
        val height = preview.render(ui, style, width)
        val at = PreviewPlacement.place(circle, width, height, area, imeTop, line, gapPx = margin, marginPx = margin)
            ?: return preview.hide() // nowhere safe from the cursor's line: no panel until there is
        preview.show(at.first, at.second, width)
    }

    /** The circle as placed, in screen pixels (left, top, right, bottom), or null while hidden. For tests. */
    internal fun circle(): IntArray? = spot?.takeIf { shown }?.let { (x, y) -> intArrayOf(x, y, x + sizePx, y + sizePx) }

    /** Hides the live preview panel; the next take tries its chosen place again. */
    fun hidePreview() {
        topUnsafe = false
        preview.hide()
    }

    fun keepScreenOn(on: Boolean) {
        val flags = if (on) params.flags or FLAG_KEEP_SCREEN_ON else params.flags and FLAG_KEEP_SCREEN_ON.inv()
        if (flags == params.flags) return // called on every render; only a change needs a relayout
        params.flags = flags
        relayout()
    }

    fun haptic(feedbackConstant: Int) {
        view.performHapticFeedback(feedbackConstant)
    }

    /**
     * The circle's touch target in screen pixels, not the X button or chip beside it. Null while the bubble is not
     * showing, and until the first layout pass after [show].
     */
    fun boundsOnScreen(): Rect? = if (shown) view.circleOnScreen() else null

    /** The X button in screen pixels. Null while it or the bubble is hidden, and until the first layout pass after [show]. */
    fun cancelBoundsOnScreen(): Rect? = if (shown) view.cancelOnScreen() else null

    private fun relayout() {
        if (!shown) return
        try {
            windowManager.updateViewLayout(view, params)
        } catch (e: IllegalArgumentException) {
            // The system dropped the window while the service stayed connected: hidden, so the next show adds it again.
            Log.w("ThumbFree", "bubble updateViewLayout failed", e)
            shown = false
            touching = false
            handler.post { onDropped() }
        }
    }

    /**
     * Points [params] at the circle's top-left corner [x], [y]; false when nothing changed. In the right half of the
     * screen the window hangs from the right edge and the view mirrors, so the X button and chip open to the left of
     * the circle. The not-ready panel opens beside the circle toward the middle when there is room for it and 12 dp more,
     * else above the circle, or below it when the panel would reach within 12 dp of the screen's top, kept 12 dp from the
     * screen's edges. A bubble dropped mid-screen can have less room than a chip needs: the whole window then shifts in
     * just enough to keep it on screen, and goes back when the chip goes.
     */
    private fun place(x: Int, y: Int): Boolean {
        spot = x to y
        // ponytail: one binder call per move on API 33 (insets load lazily from 34); cache the width if drags stutter there
        val screenWidth = windowManager.currentWindowMetrics.bounds.width()
        val right = BubblePlacement.snapSide(x + view.sizePx / 2, screenWidth) == BubblePlacement.Side.RIGHT
        val edge = (12 * context.resources.displayMetrics.density).toInt()
        val outer = if (right) screenWidth - x - view.sizePx else x // the circle's distance from its own screen edge
        val panel = view.panelWidth
        val gap = (8 * context.resources.displayMetrics.density).toInt()
        view.mirrored = right
        view.panelInset = maxOf(0, edge - outer)
        view.panelPlace = when {
            panel == 0 || (if (right) x else screenWidth - x - view.sizePx) >= panel + edge + gap -> BubbleView.PanelPlace.BESIDE
            y - view.panelHeight - gap < edge -> BubbleView.PanelPlace.BELOW // above, it would leave the screen's top
            else -> BubbleView.PanelPlace.ABOVE
        }
        val gravity = Gravity.TOP or if (right) Gravity.END else Gravity.START
        val far = if (panel > 0) edge else 0 // the panel keeps 12 dp from the far edge too
        val offset = outer.coerceAtMost(screenWidth - maxOf(view.width, view.sizePx) - far)
        val top = y - view.circleTop
        if (gravity == params.gravity && offset == params.x && top == params.y) return false
        params.gravity = gravity
        params.x = offset
        params.y = top
        return true
    }

    private fun chipMs(actions: List<ChipAction>): Long = when {
        ChipAction.COPY in actions || ChipAction.INSERT_HERE in actions -> 300_000
        ChipAction.RETRY in actions -> 8_000
        ChipAction.UNDO in actions -> 5_000
        else -> 1_500
    }
}
