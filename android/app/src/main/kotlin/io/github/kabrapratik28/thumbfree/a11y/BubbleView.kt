package io.github.kabrapratik28.thumbfree.a11y

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.animation.PathInterpolator
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
import io.github.kabrapratik28.thumbfree.core.session.Grey
import io.github.kabrapratik28.thumbfree.core.session.SpeechWait
import kotlin.math.PI
import kotlin.math.sin

/**
 * Draws the bubble (circle, level ring, spinner) and an optional chip with message and action buttons; before the speech
 * model is usable, the chip is the panel that says why (BubbleUi.NotReady), with Open. A bubble that can't listen yet is
 * grey ([grey]), with a ring for how far and a badge for why, since a yellow bubble always listens. The listening ring
 * and the stop mark fade in and out (220 ms and 180 ms) and the panel comes and goes with a short fade, none of it with
 * the phone's animations off; the circle's size and place never change.
 */
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

    // The grey look: the production art with its colours taken out and lifted toward a light grey, the ring around it and
    // the badge on it.
    private val greyArt = art(R.drawable.bubble_idle).mutate().apply { colorFilter = GREYED }
    private val badges = Grey.Badge.entries.associateWith {
        art(
            when (it) {
                Grey.Badge.DOWNLOAD, Grey.Badge.NOT_STARTED -> R.drawable.badge_download
                Grey.Badge.WIFI, Grey.Badge.CONNECTION -> R.drawable.badge_wifi
                Grey.Badge.RETRYING -> R.drawable.badge_pause
                Grey.Badge.STOPPED, Grey.Badge.LOAD_FAILED -> R.drawable.badge_stopped
                Grey.Badge.MIC_OFF -> R.drawable.badge_mic_off
            },
        ).mutate() // tinted here only
    }
    private val greyRing = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val greyRingBox = RectF()
    private var greyRingRadius = 0f
    private var badgeRadius = 0f
    private val badgeFill = Paint(Paint.ANTI_ALIAS_FLAG)

    /**
     * Grey, with [Grey]'s ring and badge, while the bubble can't listen yet (the speech model, or the microphone); null
     * for the yellow bubble, which always listens. It shows in place of the idle bubble only: a take draws as ever.
     */
    var grey: Grey? = null
        set(value) {
            if (field == value) return
            field = value
            describeState()
            invalidate()
        }

    // When the listening ring last started to come or go, and when the stop mark did; 0 draws either as it is.
    private var ringAt = 0L
    private var stopAt = 0L

    /** Whether the phone's animations are on (its animator duration scale, read at each render). */
    private var motion = true

    // The panel's and its words' fades, timed by hand frame by frame like the ring, so they follow the phone's animator
    // setting the way the app's screens do (ui/Motion.kt) rather than a process's own animator scale.
    private var panelAt = 0L
    private var panelIn = true
    private var textAt = 0L
    private val panelFrame: Runnable = object : Runnable {
        override fun run() {
            val p = progress(panelAt, if (panelIn) ENTER_MS else EXIT_MS, SystemClock.uptimeMillis())
            if (panelIn) {
                val e = ENTER.getInterpolation(p)
                chip.alpha = e
                chip.scaleX = 0.96f + 0.04f * e
                chip.scaleY = chip.scaleX
                chip.translationY = 4 * dp * (1 - e)
            } else {
                chip.alpha = 1 - EXIT.getInterpolation(p)
                if (p >= 1f && noteFor(ui) == null) chip.visibility = GONE
            }
            if (p < 1f) chip.postOnAnimation(this)
        }
    }
    private val textFrame: Runnable = object : Runnable {
        override fun run() {
            val p = progress(textAt, FADE_MS, SystemClock.uptimeMillis())
            message.alpha = 0.4f + 0.6f * STANDARD.getInterpolation(p)
            if (p < 1f) message.postOnAnimation(this)
        }
    }

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

    private val message = TextView(context).apply { setTextColor(ink) }

    private val chip = LinearLayout(context).apply {
        // Touches on the message stay here: passed on to the bubble's touch listener, a tap would start a take.
        setOnTouchListener { _, _ -> true }
    }
    private val pill = rounded(GradientDrawable.RECTANGLE).apply { cornerRadius = 24 * dp }

    // The not-ready panel follows the phone's dark theme as it is when the panel shows, which the take's chips never did.
    private val panel = GradientDrawable().apply { cornerRadius = 20 * dp }
    private fun night() = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

    /**
     * The circle's touch target side in dp at least, whatever the style: the welcome's try gives its bubble a larger
     * target than the art. 0 keeps the style's.
     */
    var targetDp = 0
        set(value) {
            if (field == value) return
            field = value
            resize()
            requestLayout()
        }

    /** Whether the X button shows while a take can be cancelled; the welcome's try has none, so nothing moves there. */
    var cancelable = true

    /**
     * Whether the grey look moves (the ring's turn, the download arrow's drift): on the welcome's try, which shows it
     * for a few minutes; in other apps the floating bubble's grey stays still, so it never redraws frame by frame.
     */
    var greyMotion = true

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
            fitChip()
            invalidate()
        }

    /** Where the not-ready panel stands: beside the circle, or 8 dp above or below it. */
    enum class PanelPlace { BESIDE, ABOVE, BELOW }

    /**
     * Where the not-ready panel stands. BubbleWindow asks for above when the room beside the bubble, toward the middle of
     * the screen, is too narrow, and for below when there is no room above either. [panelInset] keeps a panel above or
     * below 12 dp from the screen's edge on the circle's side.
     */
    var panelPlace = PanelPlace.BESIDE
        set(value) {
            if (field == value) return
            field = value
            extras.layoutParams = beside()
            fitChip()
            requestLayout()
            invalidate()
        }
    var panelInset = 0
        set(value) {
            if (field == value) return
            field = value
            if (panelPlace != PanelPlace.BESIDE) extras.layoutParams = beside()
        }

    /** Where the circle's top is in the view: under the panel while it stands above, else at the top. */
    val circleTop: Int get() = if (panelPlace == PanelPlace.ABOVE && chip.visibility == VISIBLE) extras.measuredHeight + (8 * dp).toInt() else 0

    /** How wide and how high the not-ready panel is, as last measured; 0 while it doesn't show. */
    val panelWidth: Int get() = if (ui is BubbleUi.NotReady && chip.visibility == VISIBLE) chip.measuredWidth else 0
    val panelHeight: Int get() = if (ui is BubbleUi.NotReady && chip.visibility == VISIBLE) chip.measuredHeight else 0

    init {
        setWillNotDraw(false)
        resize()
        addView(extras, beside())
        render(ui)
    }

    /** Sizes the target, the art (centered in it), the grey disc and the level ring for [style]. */
    private fun resize() {
        val art = (style.size.artDp * dp).toInt()
        sizePx = (maxOf(style.touchDp, targetDp) * dp).toInt()
        center = sizePx / 2f
        radius = art * 100f / 256
        val ringRadius = art * 16f / 48 // 16 dp on the 48 dp art
        ringBox.set(center - ringRadius, center - ringRadius, center + ringRadius, center + ringRadius)
        ring.strokeWidth = art * 3f / 48
        val inset = (sizePx - art) / 2
        for (drawable in listOf(idleArt, recordingArt, stopArt, greyArt)) drawable.setBounds(inset, inset, inset + art, inset + art)
        // The grey ring stands out from the disc as far as the target lets it; the badge sits on it, down and toward the end.
        greyRing.strokeWidth = art * 3.5f / 60
        greyRingRadius = minOf(sizePx / 2f - greyRing.strokeWidth, art * 0.56f)
        greyRingBox.set(center - greyRingRadius, center - greyRingRadius, center + greyRingRadius, center + greyRingRadius)
        badgeRadius = art * 0.15f
        minimumWidth = sizePx
        minimumHeight = sizePx
    }

    fun render(ui: BubbleUi) {
        val before = this.ui
        this.ui = ui
        val now = SystemClock.uptimeMillis()
        if ((ui is BubbleUi.Recording) != (before is BubbleUi.Recording)) ringAt = now
        if (stopShown(ui) != stopShown(before)) stopAt = now
        visibility = if (ui == BubbleUi.Hidden) GONE else VISIBLE
        describeState()
        cancel.visibility = if (cancelable && (ui is BubbleUi.Processing || (ui is BubbleUi.Recording && ui.locked))) VISIBLE else GONE
        val note = noteFor(ui)
        // The panel is read aloud as it comes, which nothing else here may be: a take's chips show while the microphone
        // can still be open. Its caller redraws it at most every 10%, so the reading never becomes a running count.
        message.contentDescription = (ui as? BubbleUi.NotReady)?.let(::notReadyRead)
        message.accessibilityLiveRegion = if (ui is BubbleUi.NotReady) ACCESSIBILITY_LIVE_REGION_POLITE else ACCESSIBILITY_LIVE_REGION_NONE
        motion = Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
        chip.removeCallbacks(panelFrame)
        when {
            // The panel leaves with a short fade; anything else simply goes.
            note == null && before is BubbleUi.NotReady && chip.visibility == VISIBLE && motion -> fadePanel(arriving = false)
            note == null -> chip.visibility = GONE
            // The panel again with a new percentage or reason: its new words fade up, nothing else moves.
            ui is BubbleUi.NotReady && before is BubbleUi.NotReady && chip.visibility == VISIBLE -> {
                if (message.text.toString() != note) {
                    message.text = note
                    textAt = now
                    message.removeCallbacks(textFrame)
                    textFrame.run()
                }
                if (panelAt == 0L || !panelIn) settle() // what a leaving fade left
            }
            else -> {
                layoutChip(ui)
                message.removeCallbacks(textFrame)
                message.alpha = 1f
                message.text = note
                if (ui is BubbleUi.Chip) for (action in ui.actions.take(2)) chip.addView(button(action))
                if (ui is BubbleUi.NotReady) chip.addView(panelButton())
                chip.visibility = VISIBLE
                if (ui is BubbleUi.NotReady && motion) arrive() else settle()
            }
        }
        invalidate()
    }

    /**
     * The label TalkBack hears for the bubble as it is drawn: why it can't listen while grey (a download's percentage in
     * steps of 10); else one label before, during and after a take, so nothing new is read into the open microphone,
     * and while the words are worked out (the microphone closed), how far. Set only when it changes.
     */
    private fun describeState() {
        val ui = ui
        val grey = grey.takeIf { greyShown(ui) }
        val label = grey?.let(::greyLabel) ?: when (ui) {
            BubbleUi.Hidden, BubbleUi.Idle, is BubbleUi.Chip, BubbleUi.Arming, is BubbleUi.Recording -> context.getString(R.string.bubble_label)
            is BubbleUi.NotReady -> greyLabel(Grey.of(ui.wait, ui.percent)) // what the panel says, in the grey look's words
            is BubbleUi.Processing -> when {
                ui.loadingModel -> context.getString(R.string.bubble_loading_model)
                ui.total != null -> context.getString(R.string.bubble_transcribing_progress, ui.done, ui.total)
                else -> context.getString(R.string.bubble_transcribing)
            }
        }
        if (contentDescription?.toString() != label) contentDescription = label
    }

    private fun greyLabel(grey: Grey): String = when (grey.badge) {
        Grey.Badge.DOWNLOAD -> context.getString(R.string.bubble_grey_downloading, ((grey.progress ?: 0f) * 10).toInt() * 10)
        Grey.Badge.WIFI -> context.getString(R.string.bubble_grey_wifi)
        Grey.Badge.CONNECTION -> context.getString(R.string.bubble_grey_connection)
        Grey.Badge.NOT_STARTED -> context.getString(R.string.bubble_grey_not_started)
        Grey.Badge.RETRYING -> context.getString(R.string.bubble_grey_retrying)
        Grey.Badge.STOPPED -> context.getString(R.string.bubble_grey_stopped)
        Grey.Badge.LOAD_FAILED -> context.getString(R.string.bubble_grey_load_failed)
        Grey.Badge.MIC_OFF -> context.getString(R.string.bubble_grey_mic_off)
        null -> context.getString(R.string.bubble_grey_preparing)
    }

    /** Whether the grey look stands in for [ui]: the idle bubble, with or without its chip or panel. */
    private fun greyShown(ui: BubbleUi) = ui == BubbleUi.Idle || ui is BubbleUi.Chip || ui is BubbleUi.NotReady

    /** What the chip says for [ui], or null for no chip. The spinner's note says "Loading model", then "2 of 5". */
    private fun noteFor(ui: BubbleUi): String? = when {
        ui is BubbleUi.Chip -> context.getString(CodeMessages.of(ui.code))
        ui is BubbleUi.NotReady -> notReady(ui)
        ui is BubbleUi.Processing && ui.loadingModel -> context.getString(R.string.bubble_loading_model)
        ui is BubbleUi.Processing && ui.total != null -> context.getString(R.string.bubble_progress, ui.done, ui.total)
        else -> null
    }

    /**
     * Lays the chip out for [ui]: a take's chip is a pill, its message beside its buttons; the not-ready panel is a card,
     * 240 to 296 dp wide and at least 88 dp high, its message above Open, which is a 48 dp row.
     */
    private fun layoutChip(ui: BubbleUi) {
        chip.removeAllViews()
        val isPanel = ui is BubbleUi.NotReady
        chip.orientation = if (isPanel) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        chip.gravity = if (isPanel) Gravity.START else Gravity.CENTER_VERTICAL
        chip.background = if (isPanel) panel else pill
        val night = isPanel && night()
        if (isPanel) {
            panel.setColor(if (night) PANEL_DARK else Color.WHITE)
            panel.setStroke((2 * dp).toInt(), if (night) PANEL_LINE_DARK else PANEL_LINE)
        }
        val pad = (16 * dp).toInt()
        if (isPanel) chip.setPadding(pad, pad, pad, (12 * dp).toInt()) else chip.setPadding((12 * dp).toInt(), 0, (4 * dp).toInt(), 0)
        chip.minimumWidth = if (isPanel) (240 * dp).toInt() else 0
        chip.minimumHeight = if (isPanel) (88 * dp).toInt() else 0
        fitChip()
        message.setTextColor(if (night) PANEL_TEXT_DARK else ink)
        message.textSize = 14f // in sp, as the phone's font size is now: it may have changed since the bubble was made
        message.typeface = if (isPanel) Typeface.create(Typeface.DEFAULT, 600, false) else Typeface.DEFAULT
        message.setLineSpacing(0f, if (isPanel) 20f / 14 else 1f)
        message.maxWidth = ((if (isPanel) 296 - 32 else 200) * dp).toInt()
        // The buttons keep their natural width and the message wraps in what is left. The window is first measured
        // 320 dp wide, and a message measured first squeezed Insert here to one letter per line.
        message.layoutParams = if (isPanel) LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        else LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        chip.addView(message)
    }

    /**
     * The panel comes from the bubble's side: from 96% and 4 dp lower to its place, fading in, over 180 ms, once it is laid
     * out at its size, so it grows from the corner nearest the bubble.
     */
    private fun arrive() {
        chip.alpha = 0f
        chip.scaleX = 0.96f
        chip.scaleY = 0.96f
        chip.translationY = 4 * dp
        chip.post {
            if (ui !is BubbleUi.NotReady) return@post
            chip.pivotX = if (mirrored) chip.width.toFloat() else 0f
            chip.pivotY = chip.height / 2f
            fadePanel(arriving = true)
        }
    }

    /** Starts the panel's arrival (180 ms) or leaving (120 ms), frame by frame. */
    private fun fadePanel(arriving: Boolean) {
        panelIn = arriving
        panelAt = SystemClock.uptimeMillis()
        chip.removeCallbacks(panelFrame)
        panelFrame.run()
    }

    /** The panel stands 8 dp off the circle when beside it, on the side toward the middle of the screen; a chip touches it. */
    private fun fitChip() {
        val gap = if (ui is BubbleUi.NotReady && panelPlace == PanelPlace.BESIDE) (8 * dp).toInt() else 0
        (chip.layoutParams as? LinearLayout.LayoutParams)?.let {
            it.leftMargin = if (mirrored) 0 else gap
            it.rightMargin = if (mirrored) gap else 0
            chip.layoutParams = it
        }
    }

    /** A chip at rest, whatever the panel's arrival or leaving left. */
    private fun settle() {
        panelAt = 0L
        chip.alpha = 1f
        chip.scaleX = 1f
        chip.scaleY = 1f
        chip.translationY = 0f
    }

    /** Whether [ui] prints the stop mark on the key: a take a tap started, which the next tap stops. */
    private fun stopShown(ui: BubbleUi) = ui is BubbleUi.Recording && ui.locked

    /**
     * How far a fade that started at [at] is, 0 to 1, over [ms]: done at once with the phone's animations off, and for a
     * state drawn before any change.
     */
    private fun progress(at: Long, ms: Long, now: Long): Float =
        if (at == 0L || !motion) 1f else ((now - at).toFloat() / ms).coerceIn(0f, 1f)


    private fun notReady(ui: BubbleUi.NotReady): String = when (ui.wait) {
        SpeechWait.DOWNLOADING -> context.getString(R.string.bubble_speech_downloading, ui.percent)
        SpeechWait.WIFI -> context.getString(R.string.bubble_speech_wifi)
        SpeechWait.CONNECTION -> context.getString(R.string.bubble_speech_connection)
        SpeechWait.PREPARING -> context.getString(R.string.bubble_speech_preparing)
        SpeechWait.RETRYING -> context.getString(R.string.bubble_speech_paused)
        SpeechWait.PAUSED -> context.getString(R.string.bubble_speech_stopped)
        SpeechWait.NO_SPACE, SpeechWait.CHECK_FAILED, SpeechWait.NOT_STARTED -> context.getString(R.string.bubble_speech_attention)
    }

    /** "ThumbFree. Your speech model is still downloading, 42 percent." for TalkBack: whose panel, then what it says. */
    private fun notReadyRead(ui: BubbleUi.NotReady): String {
        val app = context.getString(R.string.app_name)
        return if (ui.wait == SpeechWait.DOWNLOADING) context.getString(R.string.bubble_speech_downloading_read, app, ui.percent)
        else context.getString(R.string.bubble_speech_read, app, notReady(ui))
    }

    /** The circle's touch target on screen, or null while hidden or before the first layout pass. */
    fun circleOnScreen(): Rect? = onScreen(this, circle())

    /** The X button on screen, or null while it is hidden or before the first layout pass. */
    fun cancelOnScreen(): Rect? = onScreen(cancel, Rect(0, 0, cancel.width, cancel.height))

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        // The panel above the circle: the circle's room under it, which no child holds. (Below, its top margin holds it.)
        val top = circleTop
        if (top > 0) setMeasuredDimension(measuredWidth, maxOf(measuredHeight, top + sizePx))
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        // A drag from the circle at the screen edge moves the bubble instead of starting the back gesture.
        systemGestureExclusionRects = listOf(circle())
    }

    override fun onDraw(canvas: Canvas) {
        val ui = ui
        val now = SystemClock.uptimeMillis()
        // The red ring: drawn in over 220 ms as listening starts, out over the same as it stops.
        val ringIn = progress(ringAt, RING_MS, now)
        val listening = if (ui is BubbleUi.Recording) ENTER.getInterpolation(ringIn) else 1 - STANDARD.getInterpolation(ringIn)
        val stopIn = progress(stopAt, STOP_MS, now)
        val stop = if (stopShown(ui)) ENTER.getInterpolation(stopIn) else 1 - STANDARD.getInterpolation(stopIn)
        // The idle bubble is see-through, at the style's opacity (80% unless the owner changed it); every other state
        // is solid, so the red ring and the spinner always show. Listening, it turns solid as its ring comes.
        val idle = ui == BubbleUi.Idle || ui is BubbleUi.Chip || ui is BubbleUi.NotReady || ui is BubbleUi.Recording
        val idleAlpha = (style.opacity * 255 + 50) / 100
        val alpha = (idleAlpha + (255 - idleAlpha) * listening).toInt()
        val saved = if (idle && alpha < 255) canvas.saveLayerAlpha(0f, 0f, width.toFloat(), height.toFloat(), alpha) else canvas.save()
        canvas.translate(if (mirrored) (width - sizePx).toFloat() else 0f, circleTop.toFloat())
        val grey = grey.takeIf { greyShown(ui) }
        when {
            ui == BubbleUi.Arming -> {
                fill.color = GREY
                canvas.drawCircle(center, center, radius, fill)
                canvas.drawCircle(center, center, radius, outline)
            }
            ui == BubbleUi.Hidden -> Unit
            grey != null -> drawGrey(grey, canvas, now)
            else -> {
                idleArt.draw(canvas)
                if (listening > 0f) drawWith(recordingArt, listening, canvas)
                if (ui is BubbleUi.Recording) {
                    ring.color = ink // on the yellow disc, between the key and the rim
                    ring.alpha = (255 * listening).toInt()
                    canvas.drawArc(ringBox, -90f, 360 * ui.level.coerceIn(0f, 1f), false, ring)
                    ring.alpha = 255
                }
                if (stop > 0f) drawWith(stopArt, stop, canvas) // the stop mark: the next tap stops
                if (ui is BubbleUi.Processing) {
                    ring.color = ink
                    canvas.drawArc(ringBox, now % 1_000 * 0.36f, 90f, false, ring)
                }
            }
        }
        canvas.restoreToCount(saved)
        // One turn per second while processing; the fades for as long as they last; the grey ring's slow turn and the
        // download badge's drift while they show.
        val greyMoves = grey != null && motion && greyMotion && (grey.turning || grey.badge == Grey.Badge.DOWNLOAD)
        if (ui is BubbleUi.Processing || ringIn < 1f || stopIn < 1f || greyMoves) postInvalidateOnAnimation()
    }

    /**
     * The grey bubble: the art greyed, its ring (the track, a thin neutral outline even with no progress to show, as for
     * the microphone; and the part done in the theme's ink, or a light grey on a dark phone, never yellow, which is for a
     * bubble that listens; full and turning once a turn every 2.4 s while the model is checked or loaded), and its badge
     * on the ring, down and toward the end, whose download arrow drifts down and back every 1.4 s. Still with animations
     * off, and in other apps ([greyMotion]).
     */
    private fun drawGrey(grey: Grey, canvas: Canvas, now: Long) {
        val night = night()
        val moving = motion && greyMotion
        greyArt.draw(canvas)
        greyRing.color = if (night) TRACK_DARK else TRACK
        canvas.drawArc(greyRingBox, 0f, 360f, false, greyRing)
        grey.progress?.let { progress ->
            greyRing.color = if (night) NEUTRAL_DARK else INK_FACE
            val start = if (grey.turning && moving) now % TURN_MS * 360f / TURN_MS - 90f else -90f
            // Turning, a 300 degree arc; still (animations off, or in other apps) full, never read as a part done.
            val sweep = if (grey.turning) (if (moving) 300f else 360f) else 360f * progress.coerceIn(0f, 1f)
            if (sweep > 0f) canvas.drawArc(greyRingBox, start, sweep, false, greyRing)
        }
        val badge = grey.badge?.let(badges::get) ?: return
        val at = greyRingRadius * 0.7071f // 45 degrees down the ring, toward the end
        val x = center + (if (layoutDirection == LAYOUT_DIRECTION_RTL) -at else at)
        val y = center + at
        badgeFill.color = if (night) PANEL_DARK else Color.WHITE
        canvas.drawCircle(x, y, badgeRadius + dp * 1.5f, badgeFill)
        badgeFill.color = if (night) NEUTRAL_DARK else INK
        canvas.drawCircle(x, y, badgeRadius, badgeFill)
        val drift = if (grey.badge == Grey.Badge.DOWNLOAD && moving) sin(now % DRIFT_MS * PI.toFloat() / DRIFT_MS) * dp * 1.5f else 0f
        val half = badgeRadius * 0.62f
        badge.setBounds((x - half).toInt(), (y - half + drift).toInt(), (x + half).toInt(), (y + half + drift).toInt())
        badge.setTint(if (night) INK else Color.WHITE)
        badge.draw(canvas)
    }

    private fun drawWith(art: android.graphics.drawable.Drawable, alpha: Float, canvas: Canvas) {
        art.alpha = (255 * alpha).toInt()
        art.draw(canvas)
        art.alpha = 255
    }

    // The X button and the chip sit beside the circle, toward the middle of the screen; the panel above or below it, from
    // its outer edge toward the middle.
    private fun beside() = LayoutParams(
        LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT,
        (if (panelPlace == PanelPlace.BESIDE) Gravity.CENTER_VERTICAL else Gravity.TOP) or if (mirrored) Gravity.RIGHT else Gravity.LEFT,
    ).apply {
        val side = if (panelPlace == PanelPlace.BESIDE) sizePx else panelInset
        if (mirrored) rightMargin = side else leftMargin = side
        if (panelPlace == PanelPlace.BELOW) topMargin = sizePx + (8 * dp).toInt()
    }

    private fun circle() = (if (mirrored) width - sizePx else 0).let { Rect(it, circleTop, it + sizePx, circleTop + sizePx) }

    private fun onScreen(view: View, bounds: Rect): Rect? {
        if (!view.isShown || !view.isLaidOut) return null
        val (x, y) = IntArray(2).also(view::getLocationOnScreen)
        return bounds.apply { offset(x, y) }
    }

    private fun art(id: Int) = context.getDrawable(id)!! // bounds set by resize()

    private fun rounded(shape: Int) =
        GradientDrawable().apply { this.shape = shape; setColor(Color.WHITE); setStroke(dp.toInt(), LINE) }

    /**
     * The panel's Open ThumbFree: a small filled button, full width under the message and 48 dp high, the app's main-action
     * colours (ink, or sunflower on a dark phone), so it reads as the thing to tap.
     */
    private fun panelButton() = button(ChipAction.OPEN_SPEECH).apply {
        val night = night()
        minHeight = (48 * dp).toInt()
        minimumHeight = (48 * dp).toInt()
        gravity = Gravity.CENTER
        background = GradientDrawable().apply {
            cornerRadius = 20 * dp
            setColor(if (night) SUNFLOWER else INK_FACE)
        }
        setTextColor(if (night) INK else Color.WHITE)
        typeface = Typeface.create(Typeface.DEFAULT, 600, false)
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = (12 * dp).toInt() }
    }

    private fun button(action: ChipAction) = Button(context, null, android.R.attr.borderlessButtonStyle).apply {
        text = if (action == ChipAction.OPEN_SPEECH) context.getString(R.string.bubble_open_app, context.getString(R.string.app_name))
        else context.getString(label(action))
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
        ChipAction.OPEN_SPEECH -> R.string.bubble_open_app
    }

    private companion object {
        const val GREY = 0xFF9AA0A6.toInt()
        const val LINE = 0xFFDADCE0.toInt()
        // The panel's 2 dp line: ink at 20% on light; on dark, the app's dark surface, text, line and button colours.
        const val PANEL_LINE = 0x331F1B3A
        const val PANEL_DARK = 0xFF1C1935.toInt()
        const val PANEL_TEXT_DARK = 0xFFEDE9F7.toInt()
        const val PANEL_LINE_DARK = 0xFF443F60.toInt()
        // The app's ink, its buttons' ink and sunflower (ui/Theme.kt); the grey ring's track on light and dark phones, and
        // on dark its part done and its badge, in the dark theme's quiet text colour (onSurfaceVariant).
        const val INK = 0xFF1F1B3A.toInt()
        const val INK_FACE = 0xFF39335F.toInt()
        const val SUNFLOWER = 0xFFFFC83D.toInt()
        const val TRACK = 0xFFE6DCC8.toInt()
        const val TRACK_DARK = 0xFF443F60.toInt()
        const val NEUTRAL_DARK = 0xFFC9C3DC.toInt()

        /** Greys the art: its colours out, then lifted toward a light grey with a touch of the ink's violet. */
        val GREYED = ColorMatrixColorFilter(
            ColorMatrix().apply { setSaturation(0f) }.apply {
                postConcat(
                    ColorMatrix(
                        floatArrayOf(
                            0.5f, 0f, 0f, 0f, 112f,
                            0f, 0.5f, 0f, 0f, 110f,
                            0f, 0f, 0.5f, 0f, 120f,
                            0f, 0f, 0f, 1f, 0f,
                        ),
                    ),
                )
            },
        )

        // The grey ring's turn while the model is checked or loaded, and the download badge's drift.
        const val TURN_MS = 2_400L
        const val DRIFT_MS = 1_400L

        // The app's motion (ui/Motion.kt): the ring and the stop mark, the panel's arrival and leaving, a text change.
        const val RING_MS = 220L
        const val STOP_MS = 180L
        const val ENTER_MS = 180L
        const val EXIT_MS = 120L
        const val FADE_MS = 160L
        val ENTER = PathInterpolator(0.05f, 0.70f, 0.10f, 1.00f)
        val STANDARD = PathInterpolator(0.20f, 0.00f, 0.00f, 1.00f)
        val EXIT = PathInterpolator(0.30f, 0.00f, 0.80f, 0.15f)
    }
}
