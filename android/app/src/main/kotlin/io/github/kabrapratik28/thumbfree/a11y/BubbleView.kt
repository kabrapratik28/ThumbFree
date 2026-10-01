package io.github.kabrapratik28.thumbfree.a11y

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import io.github.kabrapratik28.thumbfree.R
import io.github.kabrapratik28.thumbfree.core.session.BubbleStyle
import io.github.kabrapratik28.thumbfree.core.session.BubbleUi
import io.github.kabrapratik28.thumbfree.core.session.ChipAction

/** Draws the bubble (circle, level ring, spinner) and an optional chip with message and action buttons. */
class BubbleView(context: Context, private val onChip: (ChipAction) -> Unit) : FrameLayout(context) {
    private val dp = resources.displayMetrics.density

    /** The circle's touch target, at the view's top left, or its top right when [mirrored]: the art, never under 48 dp. */
    var sizePx = 0
        private set

    private var center = 0f
    private var radius = 0f // the disc of the bubble art (drawable/bubble_idle.xml)
    private val ringBox = RectF()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp; color = LINE }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 3 * dp; strokeCap = Paint.Cap.ROUND
    }
    private val ink = context.getColor(R.color.brand_mark)
    private val idleArt = art(R.drawable.bubble_idle)
    private val recordingArt = art(R.drawable.bubble_recording) // the idle art plus the red listening ring
    private val stopArt = art(R.drawable.bubble_stop)
    private var ui: BubbleUi = BubbleUi.Idle

    /** The size and idle opacity (Settings > Bubble). A change lays the circle out again and redraws it. */
    var style = BubbleStyle.RECOMMENDED
        set(value) {
            if (field == value) return
            field = value
            resize()
            extras.layoutParams = beside()
            requestLayout()
            invalidate()
        }

    private val cancel = ImageButton(context).apply {
        setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
        background = InsetDrawable(rounded(GradientDrawable.OVAL), (8 * dp).toInt())
        scaleType = ImageView.ScaleType.FIT_CENTER
        (14 * dp).toInt().let { setPadding(it, it, it, it) }
        contentDescription = context.getString(R.string.bubble_cancel)
        setOnClickListener { onChip(ChipAction.CANCEL) }
        (48 * dp).toInt().let { layoutParams = LinearLayout.LayoutParams(it, it) } // a 48 dp target at every bubble size
    }

    private val message = TextView(context).apply {
        setTextColor(ink)
        textSize = 14f
        maxWidth = (200 * dp).toInt()
        // The buttons keep their natural width and the message wraps in what is left. The window is first measured
        // 320 dp wide, and a message measured first squeezed Insert here to one letter per line.
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
    }

    private val chip = LinearLayout(context).apply {
        gravity = Gravity.CENTER_VERTICAL
        background = rounded(GradientDrawable.RECTANGLE).apply { cornerRadius = 24 * dp }
        setPadding((12 * dp).toInt(), 0, (4 * dp).toInt(), 0)
        // Touches on the message stay here: passed on to the bubble's touch listener, a tap would start a take.
        setOnTouchListener { _, _ -> true }
    }

    // Beside the circle: the X button next to it, then the chip.
    private val extras = LinearLayout(context).apply {
        gravity = Gravity.CENTER_VERTICAL
        addView(cancel)
        addView(chip)
    }

    /** The circle sits at the right end with the X button and chip to its left, for a bubble in the right half. */
    var mirrored = false
        set(value) {
            if (field == value) return
            field = value
            extras.layoutParams = beside()
            extras.removeView(cancel)
            extras.addView(cancel, if (value) 1 else 0)
            invalidate()
        }

    init {
        setWillNotDraw(false)
        resize()
        addView(extras, beside())
        render(ui)
    }

    /** Sizes the target, the art (centered in it), the grey disc and the level ring for [style]. */
    private fun resize() {
        val art = (style.size.artDp * dp).toInt()
        sizePx = (style.touchDp * dp).toInt()
        center = sizePx / 2f
        radius = art * 100f / 256
        val ringRadius = art * 16f / 48 // 16 dp on the 48 dp art
        ringBox.set(center - ringRadius, center - ringRadius, center + ringRadius, center + ringRadius)
        ring.strokeWidth = art * 3f / 48
        val inset = (sizePx - art) / 2
        for (drawable in listOf(idleArt, recordingArt, stopArt)) drawable.setBounds(inset, inset, inset + art, inset + art)
        minimumWidth = sizePx
        minimumHeight = sizePx
    }

    fun render(ui: BubbleUi) {
        this.ui = ui
        visibility = if (ui == BubbleUi.Hidden) GONE else VISIBLE
        contentDescription = when (ui) {
            BubbleUi.Hidden, BubbleUi.Idle, is BubbleUi.Chip -> context.getString(R.string.bubble_start)
            BubbleUi.Arming, is BubbleUi.Recording -> context.getString(R.string.bubble_stop)
            is BubbleUi.Processing -> when {
                ui.loadingModel -> context.getString(R.string.bubble_loading_model)
                ui.total != null -> context.getString(R.string.bubble_transcribing_progress, ui.done, ui.total)
                else -> context.getString(R.string.bubble_transcribing)
            }
        }
        cancel.visibility = if (ui is BubbleUi.Processing || (ui is BubbleUi.Recording && ui.locked)) VISIBLE else GONE
        // The spinner says "Loading model" while the model loads and "2 of 5" once the chunk count is known.
        val note = when {
            ui is BubbleUi.Chip -> context.getString(CodeMessages.of(ui.code))
            ui is BubbleUi.Processing && ui.loadingModel -> context.getString(R.string.bubble_loading_model)
            ui is BubbleUi.Processing && ui.total != null -> context.getString(R.string.bubble_progress, ui.done, ui.total)
            else -> null
        }
        chip.visibility = if (note != null) VISIBLE else GONE
        if (note != null) {
            chip.removeAllViews()
            chip.addView(message.apply { text = note })
            if (ui is BubbleUi.Chip) for (action in ui.actions.take(2)) chip.addView(button(action))
        }
        invalidate()
    }

    /** The circle's touch target on screen, or null while hidden or before the first layout pass. */
    fun circleOnScreen(): Rect? = onScreen(this, circle())

    /** The X button on screen, or null while it is hidden or before the first layout pass. */
    fun cancelOnScreen(): Rect? = onScreen(cancel, Rect(0, 0, cancel.width, cancel.height))

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        // A drag from the circle at the screen edge moves the bubble instead of starting the back gesture.
        systemGestureExclusionRects = listOf(circle())
    }

    override fun onDraw(canvas: Canvas) {
        val ui = ui
        // The idle bubble is see-through, at the style's opacity (85% unless the owner changed it); every other state
        // is solid, so the red ring and the spinner always show.
        val idle = ui == BubbleUi.Idle || ui is BubbleUi.Chip
        val alpha = (style.opacity * 255 + 50) / 100
        val saved = if (idle) canvas.saveLayerAlpha(0f, 0f, width.toFloat(), height.toFloat(), alpha) else canvas.save()
        if (mirrored) canvas.translate((width - sizePx).toFloat(), 0f)
        when (ui) {
            BubbleUi.Idle, is BubbleUi.Chip -> idleArt.draw(canvas)
            BubbleUi.Arming -> {
                fill.color = GREY
                canvas.drawCircle(center, center, radius, fill)
                canvas.drawCircle(center, center, radius, outline)
            }
            is BubbleUi.Recording -> {
                recordingArt.draw(canvas)
                ring.color = ink // on the yellow disc, between the key and the rim
                canvas.drawArc(ringBox, -90f, 360 * ui.level.coerceIn(0f, 1f), false, ring)
                if (ui.locked) stopArt.draw(canvas) // the stop mark: the next tap stops
            }
            is BubbleUi.Processing -> {
                idleArt.draw(canvas)
                ring.color = ink
                canvas.drawArc(ringBox, SystemClock.uptimeMillis() % 1_000 * 0.36f, 90f, false, ring)
                postInvalidateOnAnimation() // one turn per second, for as long as this state lasts
            }
            BubbleUi.Hidden -> Unit
        }
        canvas.restoreToCount(saved)
    }

    // The X button and the chip sit beside the circle, toward the middle of the screen.
    private fun beside() = LayoutParams(
        LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT,
        Gravity.CENTER_VERTICAL or if (mirrored) Gravity.RIGHT else Gravity.LEFT,
    ).apply { if (mirrored) rightMargin = sizePx else leftMargin = sizePx }

    private fun circle() = (if (mirrored) width - sizePx else 0).let { Rect(it, 0, it + sizePx, sizePx) }

    private fun onScreen(view: View, bounds: Rect): Rect? {
        if (!view.isShown || !view.isLaidOut) return null
        val (x, y) = IntArray(2).also(view::getLocationOnScreen)
        return bounds.apply { offset(x, y) }
    }

    private fun art(id: Int) = context.getDrawable(id)!! // bounds set by resize()

    private fun rounded(shape: Int) =
        GradientDrawable().apply { this.shape = shape; setColor(Color.WHITE); setStroke(dp.toInt(), LINE) }

    private fun button(action: ChipAction) = Button(context, null, android.R.attr.borderlessButtonStyle).apply {
        setText(label(action))
        isAllCaps = false
        minWidth = 0
        minimumWidth = 0
        setOnClickListener { onChip(action) }
    }

    private fun label(action: ChipAction) = when (action) {
        ChipAction.COPY -> R.string.bubble_copy
        ChipAction.INSERT_HERE -> R.string.bubble_insert_here
        ChipAction.UNDO -> R.string.bubble_undo
        ChipAction.RETRY -> R.string.bubble_retry
        ChipAction.DISMISS -> R.string.bubble_dismiss
        ChipAction.CANCEL -> R.string.bubble_cancel
    }

    private companion object {
        const val GREY = 0xFF9AA0A6.toInt()
        const val LINE = 0xFFDADCE0.toInt()
    }
}
