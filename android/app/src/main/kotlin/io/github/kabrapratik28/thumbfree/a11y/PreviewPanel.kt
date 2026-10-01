package io.github.kabrapratik28.thumbfree.a11y

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
import android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
import android.view.WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
import android.view.WindowManager.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import io.github.kabrapratik28.thumbfree.R
import io.github.kabrapratik28.thumbfree.core.session.BubbleStyle
import io.github.kabrapratik28.thumbfree.core.session.PreviewUi

/**
 * The live preview panel's overlay window by the bubble. While a take records it shows the words the stream heard, the
 * settled ones in the ink and the tentative tail lighter, under a small "Preview" label; after the stop, the same words
 * in the lighter colour under "Transcribing" until the take's text is typed. The words scroll in a box at most
 * [MAX_LINES] lines tall that follows the newest words, and it keeps only about the last [MAX_CHARS] characters, so a
 * take of any length stays cheap (the typed text is never cut). A finger can scroll back to reread: the panel then
 * holds its words until the user scrolls back to the bottom, or [RESUME_MS] after the last touch, and then jumps to the
 * newest. Its width and type follow the bubble's size. It is solid, as the bubble is while it listens: nothing of the
 * app shows through its words. It never takes focus, and touches outside it go to the app under it.
 *
 * TalkBack can read and scroll it but is never made to speak it (no live region): the microphone is open, so TalkBack's
 * voice would go into the take and its text, and hearing your own words a second or two late disrupts speech. TalkBack
 * reads the typed text as usual. Main thread only; [windowManager] must be the accessibility service's.
 */
class PreviewPanel(private val context: Context, private val windowManager: WindowManager) {
    private val dp = context.resources.displayMetrics.density
    private val ink = context.getColor(R.color.preview_ink)
    private val light = context.getColor(R.color.preview_tentative)
    private val surface = context.getColor(R.color.preview_surface)
    private val main = Handler(Looper.getMainLooper()) // not View.getHandler, which is null while detached

    val params = WindowManager.LayoutParams(
        WRAP_CONTENT, WRAP_CONTENT, TYPE_ACCESSIBILITY_OVERLAY,
        // Touchable, for scrolling back; not focusable, which also sends every touch outside it to the windows under it.
        FLAG_NOT_FOCUSABLE or FLAG_LAYOUT_NO_LIMITS, PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        fitInsetsTypes = 0 // x, y are screen pixels, like the bubble's
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
    }

    private val label = TextView(context).apply {
        setTextColor(context.getColor(R.color.preview_label))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
    }

    private val words: TextView = TextView(context).apply {
        setTextColor(ink)
        // Grown by new words while it follows them: stay at the bottom.
        addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> if (following) scroll.scrollTo(0, height) }
    }

    /**
     * The words' box, a public view for tests. Scrolled off the bottom (by a finger, or TalkBack), it holds its words;
     * back at the bottom, or RESUME_MS after the last touch or scroll, it follows the newest words again.
     */
    @SuppressLint("ClickableViewAccessibility") // it only notes the touch; the ScrollView scrolls and TalkBack scrolls too
    val scroll: ScrollView = ScrollView(context).apply {
        overScrollMode = View.OVER_SCROLL_NEVER
        isVerticalFadingEdgeEnabled = true // words cut at the box's edge fade out
        setFadingEdgeLength((8 * dp).toInt())
        addView(words)
        setOnScrollChangeListener { _, _, _, _, _ ->
            following = !canScrollVertically(1)
            if (following) follow() else rearm()
        }
        setOnTouchListener { _, _ ->
            rearm()
            false
        }
    }

    private val background = GradientDrawable().apply {
        cornerRadius = 16 * dp
        setColor(surface)
        setStroke(dp.toInt(), context.getColor(R.color.preview_outline))
    }

    /** The panel's content, a public view for tests. */
    val view = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding((12 * dp).toInt(), (6 * dp).toInt(), (12 * dp).toInt(), (8 * dp).toInt())
        background = this@PreviewPanel.background
        addView(label)
        addView(scroll)
    }

    /** False while hidden, and once a relayout found the window dropped. */
    var shown = false
        private set

    /** The words in the panel now, for tests. */
    val text: CharSequence get() = words.text

    private var following = true // at the bottom, with the newest words; false while the user reads back
    private var held: CharSequence? = null // newer words, kept back while the user reads back
    private val resume = Runnable {
        following = true
        follow()
    }

    /** Lays out [ui] for a panel [width] pixels wide at [style], and returns its height (0 for Hidden). */
    fun render(ui: PreviewUi, style: BubbleStyle, width: Int): Int {
        words.setTextSize(TypedValue.COMPLEX_UNIT_SP, textSp(style))
        when (ui) {
            PreviewUi.Hidden -> return 0
            is PreviewUi.Live -> {
                label.setText(R.string.live_preview_label)
                put(colored(ui.committed, ui.tentative))
            }
            is PreviewUi.Finishing -> {
                label.setText(R.string.bubble_transcribing)
                put(colored("", ui.text))
            }
        }
        // The box is as tall as the words, up to their last MAX_LINES lines: at the bottom, its top is a line's top.
        words.measure(
            View.MeasureSpec.makeMeasureSpec(width - view.paddingLeft - view.paddingRight, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.UNSPECIFIED,
        )
        val lines = words.lineCount
        val box = words.measuredHeight - if (lines <= MAX_LINES) 0 else words.layout.getLineTop(lines - MAX_LINES)
        if (scroll.layoutParams.height != box) scroll.layoutParams = scroll.layoutParams.apply { height = box }
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.UNSPECIFIED)
        return view.measuredHeight
    }

    /** The panel's width for [style] in an [available] pixel wide area: 240 to 288 dp by bubble size, never wider. */
    fun widthFor(style: BubbleStyle, available: Int): Int {
        val want = when (style.size) {
            BubbleStyle.Size.SMALL, BubbleStyle.Size.MEDIUM -> 240
            BubbleStyle.Size.LARGE -> 264
            BubbleStyle.Size.EXTRA_LARGE -> 288
        }
        return minOf((want * dp).toInt(), available - (16 * dp).toInt())
    }

    /** Shows the panel at [x], [y] (screen pixels) and [width], or moves it there. */
    fun show(x: Int, y: Int, width: Int) {
        val changed = params.x != x || params.y != y || params.width != width
        params.x = x
        params.y = y
        params.width = width
        if (shown) {
            if (changed) relayout()
            return
        }
        try {
            windowManager.addView(view, params)
            shown = true
        } catch (e: RuntimeException) {
            Log.w("ThumbFree", "preview addView failed", e) // the next update tries again
        }
    }

    /** Hides the panel; the next take's words start at the bottom. */
    fun hide() {
        main.removeCallbacks(resume)
        following = true
        held = null
        if (!shown) return
        shown = false
        try {
            windowManager.removeView(view)
        } catch (e: IllegalArgumentException) {
            Log.w("ThumbFree", "preview removeView failed", e) // dropped by the system: hidden either way
        }
    }

    private fun relayout() {
        try {
            windowManager.updateViewLayout(view, params)
        } catch (e: IllegalArgumentException) {
            shown = false // dropped by the system: the next show adds it again
        }
    }

    /** Puts [text] in the box and follows it to the bottom, or holds it while the user reads back. */
    private fun put(text: CharSequence) {
        if (!following) {
            held = text
            return
        }
        held = null
        words.text = text
    }

    /** Back to the newest words, at the bottom. */
    private fun follow() {
        held?.let { put(it) }
        scroll.scrollTo(0, words.height)
    }

    /** The user moved the words: follow them again RESUME_MS after the last touch or scroll. */
    private fun rearm() {
        main.removeCallbacks(resume)
        main.postDelayed(resume, RESUME_MS)
    }

    /**
     * [committed] then [tentative] in their colours, only about the last MAX_CHARS characters of them, cut at a word, with
     * an ellipsis where it was cut.
     */
    private fun colored(committed: String, tentative: String): CharSequence {
        val full = committed + tentative
        var cut = 0
        if (full.length > MAX_CHARS) {
            cut = full.length - MAX_CHARS
            full.indexOf(' ', cut).takeIf { it >= 0 }?.let { cut = it + 1 }
        }
        val out = SpannableStringBuilder()
        if (cut > 0) out.append(ELLIPSIS)
        val settled = out.length + maxOf(0, committed.length - cut)
        out.append(full, cut, full.length)
        out.setSpan(ForegroundColorSpan(ink), 0, settled, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        out.setSpan(ForegroundColorSpan(light), settled, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return out
    }

    private fun textSp(style: BubbleStyle) = when (style.size) {
        BubbleStyle.Size.SMALL, BubbleStyle.Size.MEDIUM -> 14f
        BubbleStyle.Size.LARGE -> 15f
        BubbleStyle.Size.EXTRA_LARGE -> 16f
    }

    companion object {
        const val MAX_LINES = 3
        const val MAX_CHARS = 2_000
        const val RESUME_MS = 5_000L
        private const val ELLIPSIS = "…"
    }
}
