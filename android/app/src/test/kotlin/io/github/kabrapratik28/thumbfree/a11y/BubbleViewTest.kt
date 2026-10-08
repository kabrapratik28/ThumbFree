package io.github.kabrapratik28.thumbfree.a11y

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.View.MeasureSpec
import android.view.accessibility.AccessibilityNodeInfo
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import com.google.common.collect.Range
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.github.kabrapratik28.thumbfree.core.session.BubbleStyle
import io.github.kabrapratik28.thumbfree.core.session.BubbleUi
import io.github.kabrapratik28.thumbfree.core.session.ChipAction
import io.github.kabrapratik28.thumbfree.core.session.Code
import io.github.kabrapratik28.thumbfree.core.session.Grey
import io.github.kabrapratik28.thumbfree.core.session.SpeechWait
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
class BubbleViewTest {
    private val context = RuntimeEnvironment.getApplication()
    private val chips = mutableListOf<ChipAction>()
    private val view = BubbleView(context) { chips += it }
    private val noTarget = BubbleUi.Chip(Code.NO_TARGET, listOf(ChipAction.COPY, ChipAction.INSERT_HERE))

    // One label before and during a take, set once, so TalkBack never reads a new one into the open microphone; while
    // the words are worked out (the microphone closed), what it does.
    @Test
    fun contentDescriptions() {
        view.render(BubbleUi.Idle)
        val label = view.contentDescription
        assertThat(label.toString()).isEqualTo("Dictation, double tap to start or stop")
        view.render(BubbleUi.Arming)
        view.render(BubbleUi.Recording(0f, locked = true, 0))
        view.render(BubbleUi.Recording(0.6f, locked = true, 100))
        assertThat(view.contentDescription).isSameInstanceAs(label)
        view.render(BubbleUi.Processing(false, 0, null))
        assertThat(view.contentDescription.toString()).isEqualTo("Transcribing")

        view.render(noTarget)
        assertThat(texts()).contains(context.getString(CodeMessages.of(Code.NO_TARGET)))
        assertThat(buttons().map { it.text.toString() }).containsExactly("Copy", "Insert here").inOrder()
    }

    // Before the speech model is usable a tap shows a panel instead of listening: how far its download is, or why it
    // waits, with Open ThumbFree. TalkBack reads it as it comes, saying whose it is; a take's chips never are read aloud.
    @Test
    fun notReadyPanelSaysWhyAndOpensTheApp() {
        view.render(BubbleUi.NotReady(SpeechWait.DOWNLOADING, 42))

        val panel = message(DOWNLOADING_42)
        assertThat(panel.contentDescription.toString()).isEqualTo("ThumbFree. Your speech model is still downloading, 42 percent.")
        assertThat(panel.accessibilityLiveRegion).isEqualTo(View.ACCESSIBILITY_LIVE_REGION_POLITE)
        // A tap can't start one now: the bubble says why, in the grey look's words, even before it is grey.
        assertThat(view.contentDescription.toString()).isEqualTo("Speech is downloading, 40 percent")
        val open = buttons().single { it.text.toString() == "Open ThumbFree" }
        // A small filled button in the app's ink, its words white: the thing to tap.
        assertThat((open.background as android.graphics.drawable.GradientDrawable).color!!.defaultColor).isEqualTo(INK_FACE)
        assertThat(open.currentTextColor).isEqualTo(Color.WHITE)
        open.performClick()
        assertThat(chips).containsExactly(ChipAction.OPEN_SPEECH)

        val says = mapOf(
            SpeechWait.WIFI to "Your speech model is waiting for Wi-Fi.", SpeechWait.CONNECTION to "Your speech model is waiting for a connection.",
            SpeechWait.PAUSED to "Your speech model's download stopped.", SpeechWait.PREPARING to "Your speech model is almost ready.",
            SpeechWait.RETRYING to "Your speech model's download is paused.",
            SpeechWait.NO_SPACE to "Your speech model needs attention.", SpeechWait.CHECK_FAILED to "Your speech model needs attention.",
            SpeechWait.NOT_STARTED to "Your speech model needs attention.",
        )
        for ((wait, text) in says) {
            view.render(BubbleUi.NotReady(wait, 0))
            assertThat(message(text).contentDescription.toString()).isEqualTo("ThumbFree. $text")
        }
        // The bubble says what its panel says: paused for a retry's pause, and stopped, as step 1 does, for a stop that
        // Try again picks up from.
        view.render(BubbleUi.NotReady(SpeechWait.RETRYING, 40))
        assertThat(view.contentDescription.toString()).isEqualTo("Speech download paused")
        view.render(BubbleUi.NotReady(SpeechWait.PAUSED, 40))
        assertThat(view.contentDescription.toString()).isEqualTo("Speech download stopped")

        view.render(noTarget)
        assertThat(message(context.getString(CodeMessages.of(Code.NO_TARGET))).accessibilityLiveRegion)
            .isEqualTo(View.ACCESSIBILITY_LIVE_REGION_NONE)
    }

    // A bubble that can't listen yet is grey, the art's colours taken out, with its ring and its badge: TalkBack hears
    // why, a download's percentage in steps of 10, and the label changes only as that does. A take draws as ever.
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun aBubbleThatCantListenIsGreyAndSaysWhy() {
        val c = view.sizePx / 2
        val disc = c + view.sizePx / 4 // the disc below the key
        val yellow = draw(BubbleUi.Idle).getPixel(c, disc)
        view.grey = Grey(Grey.Badge.DOWNLOAD, 0.42f)
        val grey = snapshot().getPixel(c, disc)
        assertThat(Color.blue(yellow)).isLessThan(100)
        // No colour left: a light grey with a touch of the ink's violet.
        val channels = listOf(Color.red(grey), Color.green(grey), Color.blue(grey))
        assertThat(channels.max() - channels.min()).isAtMost(24)
        assertThat(channels.min()).isAtLeast(160)
        assertThat(view.contentDescription.toString()).isEqualTo("Speech is downloading, 40 percent")

        val label = view.contentDescription
        view.grey = Grey(Grey.Badge.DOWNLOAD, 0.47f)
        assertThat(view.contentDescription).isSameInstanceAs(label) // the same ten percent: not set again
        view.grey = Grey(Grey.Badge.DOWNLOAD, 0.5f)
        assertThat(view.contentDescription.toString()).isEqualTo("Speech is downloading, 50 percent")

        val says = mapOf(
            Grey(Grey.Badge.WIFI, 0f) to "Speech is waiting for Wi-Fi", Grey(Grey.Badge.STOPPED, 0f) to "Speech download stopped",
            Grey(Grey.Badge.CONNECTION, 0f) to "Speech is waiting for a connection", Grey(Grey.Badge.NOT_STARTED, 0f) to "Speech is not downloaded",
            Grey(Grey.Badge.LOAD_FAILED, 1f) to "Speech couldn't be prepared", Grey(Grey.Badge.RETRYING, 0.4f) to "Speech download paused",
            Grey.PREPARING to "Speech is almost ready", Grey.MIC_OFF to "Microphone is off",
        )
        for ((look, text) in says) {
            view.grey = look
            assertThat(view.contentDescription.toString()).isEqualTo(text)
        }

        view.render(BubbleUi.Recording(0f, locked = true, 0))
        assertThat(view.contentDescription.toString()).isEqualTo("Dictation, double tap to start or stop")
        view.grey = null
        view.render(BubbleUi.Idle)
        assertThat(view.contentDescription.toString()).isEqualTo("Dictation, double tap to start or stop")
    }

    // The panel is a card beside the bubble: 240 to 296 dp wide, at least 88 dp high, its message above Open ThumbFree,
    // which is a 48 dp row. A take's chip stays a pill, its message beside its buttons.
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun notReadyPanelIsACardBesideTheBubble() {
        val dp = context.resources.displayMetrics.density
        view.render(BubbleUi.NotReady(SpeechWait.DOWNLOADING, 42))
        layOut()

        val panel = message(DOWNLOADING_42).parent as android.widget.LinearLayout
        assertThat(panel.orientation).isEqualTo(android.widget.LinearLayout.VERTICAL)
        assertThat(panel.width).isIn(Range.closed((240 * dp).toInt(), (296 * dp).toInt()))
        assertThat(panel.height).isAtLeast((88 * dp).toInt())
        assertThat(buttons().single().height).isAtLeast((48 * dp).toInt())

        view.render(BubbleUi.NotReady(SpeechWait.DOWNLOADING, 51)) // in place: the same card, new words
        assertThat(message("Your speech model is still downloading (51%).").parent).isSameInstanceAs(panel)
        view.render(noTarget)
        assertThat((message(context.getString(CodeMessages.of(Code.NO_TARGET))).parent as android.widget.LinearLayout).orientation)
            .isEqualTo(android.widget.LinearLayout.HORIZONTAL)
    }

    // The panel takes the phone's dark theme as it is when the panel shows, not as it was when the bubble was made.
    @Test
    fun notReadyPanelFollowsTheDarkThemeWhenItShows() {
        view.render(BubbleUi.NotReady(SpeechWait.DOWNLOADING, 42))
        assertThat(message(DOWNLOADING_42).currentTextColor).isEqualTo(INK)
        view.render(BubbleUi.Idle)

        RuntimeEnvironment.setQualifiers("+night")
        view.render(BubbleUi.NotReady(SpeechWait.DOWNLOADING, 42))
        assertThat(message(DOWNLOADING_42).currentTextColor).isEqualTo(0xFFEDE9F7.toInt())
    }

    // Grey, the bubble has no yellow anywhere, its ring and its badge included, on a light phone or a dark one: yellow is
    // for a bubble that listens.
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun aGreyBubbleHasNoYellowLightOrDark() {
        fun yellow(bitmap: Bitmap) = (0 until bitmap.width).sumOf { x ->
            (0 until bitmap.height).count { y ->
                val c = bitmap.getPixel(x, y)
                Color.alpha(c) > 128 && Color.red(c) > 200 && Color.green(c) > 150 && Color.blue(c) < 120
            }
        }
        assertThat(yellow(draw(BubbleUi.Idle))).isGreaterThan(100) // the yellow bubble itself
        for (qualifiers in listOf("", "+night")) {
            if (qualifiers.isNotEmpty()) RuntimeEnvironment.setQualifiers(qualifiers)
            for (look in listOf(
                Grey(Grey.Badge.DOWNLOAD, 1f), Grey(Grey.Badge.STOPPED, 0.5f), Grey(Grey.Badge.RETRYING, 0.5f), Grey.PREPARING, Grey.MIC_OFF,
            )) {
                view.grey = look
                assertWithMessage("$look ${qualifiers.ifEmpty { "light" }}").that(yellow(snapshot())).isEqualTo(0)
            }
        }
    }

    // A retry's pause draws the pause badge, as the last step's picture does, not a stop's mark; a model that wouldn't
    // load shares the stop mark. The same ring each time, so only the badge can differ.
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun aRetrysPauseDrawsThePauseNotTheStopMark() {
        fun look(badge: Grey.Badge): Bitmap {
            view.grey = Grey(badge, 0.5f)
            return snapshot()
        }
        val stopped = look(Grey.Badge.STOPPED)
        assertThat(look(Grey.Badge.RETRYING).sameAs(stopped)).isFalse()
        assertThat(look(Grey.Badge.LOAD_FAILED).sameAs(stopped)).isTrue()
    }

    // Every grey look has its ring, a thin neutral outline even with no progress to show: the microphone off too.
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun everyGreyLookHasARing() {
        val c = view.sizePx / 2
        view.grey = Grey(Grey.Badge.DOWNLOAD, 0f)
        val empty = snapshot()
        val ringTop = (0 until c).first { Color.alpha(empty.getPixel(c, it)) > 128 } // the first drawn pixel above the art
        view.grey = Grey.MIC_OFF
        val micOff = snapshot()
        assertThat(micOff.getPixel(c, ringTop)).isEqualTo(empty.getPixel(c, ringTop))
        view.grey = null
        assertThat(Color.alpha(draw(BubbleUi.Idle).getPixel(c, ringTop))).isLessThan(128) // the yellow bubble has none
    }

    // In other apps the floating bubble's grey look stays still: drawn again later, the same pixels, so it never asks
    // for a redraw frame by frame. The welcome's try lets its download arrow drift.
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "xxhdpi") // the arrow drifts 1.5 dp: 4.5 px here
    fun theFloatingBubblesGreyStaysStill() {
        fun twoMoments(): Pair<Bitmap, Bitmap> {
            view.grey = Grey(Grey.Badge.DOWNLOAD, 0.42f)
            ShadowSystemClock.advanceBy(Duration.ofMillis(1_400 - SystemClock.uptimeMillis() % 1_400)) // the drift's start
            val first = snapshot()
            ShadowSystemClock.advanceBy(Duration.ofMillis(700)) // its furthest
            return first to snapshot()
        }
        view.greyMotion = false
        val (a, b) = twoMoments()
        assertThat(a.sameAs(b)).isTrue()
        view.grey = null
        view.greyMotion = true
        val (c, d) = twoMoments()
        assertThat(c.sameAs(d)).isFalse()
    }

    // A preparing ring that can't turn (in other apps, or with animations off) is drawn full: a still 300 degree arc
    // would read as 83% done. Along a ray through its missing part, it draws as the full ring of a finished download.
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "xxhdpi")
    fun aStillPreparingRingIsFull() {
        val c = view.sizePx / 2
        fun ray(bitmap: Bitmap) = (0 until c).map { r ->
            // 240 degrees from three o'clock, clockwise: eleven o'clock, inside the turning arc's 60 degree gap.
            val a = Math.toRadians(240.0)
            bitmap.getPixel((c + r * Math.cos(a)).toInt(), (c + r * Math.sin(a)).toInt())
        }
        view.greyMotion = false
        view.grey = Grey(Grey.Badge.DOWNLOAD, 1f)
        val full = ray(snapshot())
        view.grey = Grey.PREPARING
        assertThat(ray(snapshot())).isEqualTo(full)
    }

    // The panel's words take the phone's font size as it is when the panel shows, as its Open button does: at 200% they
    // grow, though the bubble was made at 100%.
    @Test
    fun notReadyPanelFollowsTheFontSizeWhenItShows() {
        view.render(BubbleUi.NotReady(SpeechWait.DOWNLOADING, 42))
        val size = message(DOWNLOADING_42).textSize
        view.render(BubbleUi.Idle)

        RuntimeEnvironment.setFontScale(2f)
        view.render(BubbleUi.NotReady(SpeechWait.DOWNLOADING, 42))
        assertThat(message(DOWNLOADING_42).textSize).isGreaterThan(size * 1.5f)
    }

    // The welcome's try gives its bubble a 72 dp target around the same art, and no X, so nothing moves beside it.
    @Test
    fun tryBubbleHasALargerTargetAndNoX() {
        val dp = context.resources.displayMetrics.density
        view.style = BubbleStyle.RECOMMENDED
        view.targetDp = 72
        view.cancelable = false
        assertThat(view.sizePx).isEqualTo((72 * dp).toInt())
        view.render(BubbleUi.Recording(0f, locked = true, 0))
        assertThat(cancelButtons()).isEmpty()
    }

    @Test
    fun chipButtonsDispatch() {
        view.render(noTarget)

        buttons().single { it.text.toString() == "Insert here" }.performClick()

        assertThat(chips).containsExactly(ChipAction.INSERT_HERE)
    }

    @Test
    fun hiddenIsGone() {
        view.render(BubbleUi.Hidden)
        assertThat(view.visibility).isEqualTo(View.GONE)

        view.render(BubbleUi.Idle)
        assertThat(view.visibility).isEqualTo(View.VISIBLE)
    }

    @Test
    fun cancelButtonWhileLockedOrProcessing() {
        for (ui in listOf(BubbleUi.Recording(0f, locked = true, 0), BubbleUi.Processing(false, 0, null))) {
            chips.clear()
            view.render(ui)

            cancelButtons().single().performClick()

            assertWithMessage("$ui").that(chips).containsExactly(ChipAction.CANCEL)
        }

        view.render(BubbleUi.Recording(0f, locked = false, 0))
        assertThat(cancelButtons()).isEmpty()
    }

    // BubbleWindow sends the view's touches to the gesture classifier; a tap on the chip text must not start a take.
    @Test
    fun chipMessageKeepsItsTouches() {
        var touches = 0
        view.setOnTouchListener { _, _ -> touches++; true }
        view.render(noTarget)
        layOut()

        val message = visible().single { it is TextView && it !is Button }
        val (x, y) = message.offsetIn(view)
        view.dispatchTouchEvent(down(x + 1f, y + message.height / 2f))
        assertThat(touches).isEqualTo(0)

        view.dispatchTouchEvent(down(1f, 1f))
        assertThat(touches).isEqualTo(1)
    }

    // The bubble's looks as TalkBack hears them, and the text beside the circle.
    @Test
    fun everyStateDescribesItself() {
        val cases = listOf(
            Triple(BubbleUi.Idle, "Dictation, double tap to start or stop", emptyList()),
            Triple(BubbleUi.Arming, "Dictation, double tap to start or stop", emptyList()),
            Triple(BubbleUi.Recording(0.5f, locked = false, 0), "Dictation, double tap to start or stop", emptyList()),
            Triple(BubbleUi.Recording(0.5f, locked = true, 0), "Dictation, double tap to start or stop", emptyList()),
            Triple(BubbleUi.Processing(loadingModel = true, 0, null), "Loading model", listOf("Loading model")),
            Triple(BubbleUi.Processing(false, 2, 5), "Transcribing, 2 of 5", listOf("2 of 5")),
            Triple(BubbleUi.Processing(false, 0, null), "Transcribing", emptyList()),
            Triple(noTarget, "Dictation, double tap to start or stop", listOf(context.getString(CodeMessages.of(Code.NO_TARGET)), "Copy", "Insert here")),
        )
        for ((ui, description, text) in cases) {
            view.render(ui)

            assertWithMessage("$ui").that(view.contentDescription.toString()).isEqualTo(description)
            assertWithMessage("$ui").that(texts()).isEqualTo(text)
        }
    }

    // ViewRootImpl first measures a WRAP_CONTENT window at 320 dp (config_prefDialogWidth on phones). Both buttons keep
    // their natural width there; a squeezed Insert here wrapped one letter per line and made the chip 200 dp tall.
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "w411dp-xxhdpi")
    fun twoButtonChipFitsA320dpWindow() {
        view.render(BubbleUi.Chip(Code.HELD_BACK, listOf(ChipAction.COPY, ChipAction.INSERT_HERE)))
        val any = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        val width320 = (320 * context.resources.displayMetrics.density).toInt()
        view.measure(MeasureSpec.makeMeasureSpec(width320, MeasureSpec.AT_MOST), any)

        for ((button, width) in buttons().map { it to it.measuredWidth }) {
            button.measure(any, any)
            assertWithMessage(button.text.toString()).that(width).isAtLeast(button.measuredWidth)
        }
    }

    // The idle bubble art at its opacity (85% here); recording adds the red ring and a level ring inside it; a LOCKED
    // recording prints a stop mark on the key; mirrored, the circle is drawn at the right end. At the medium size,
    // which the offsets below are measured on.
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun drawsTheBubbleArtTheRingsTheStopMarkAndTheMirror() {
        view.style = BubbleStyle(BubbleStyle.Size.MEDIUM, 85)
        val c = view.sizePx / 2
        val dp = context.resources.displayMetrics.density
        val ring = view.sizePx * 111 / 256 // the listening ring's radius in drawable/bubble_recording.xml
        val key = c - (4 * dp).toInt() // on the key's face, just above the bubble's center
        val locked = BubbleUi.Recording(0f, locked = true, 0)

        val idle = draw(BubbleUi.Idle).getPixel(c, c + (17 * dp).toInt()) // the yellow disc below the key
        assertThat(Color.alpha(idle)).isAtLeast(215)
        assertThat(Color.alpha(idle)).isAtMost(219)
        assertThat(Color.blue(idle)).isLessThan(100)
        val lockedArt = draw(locked)
        assertThat(lockedArt.getPixel(c, key)).isEqualTo(Color.WHITE)
        assertThat(lockedArt.getPixel(c, c + (6 * dp).toInt())).isNotEqualTo(Color.WHITE) // the mark stays on the key
        val unlocked = draw(BubbleUi.Recording(1f, locked = false, 0))
        assertThat(unlocked.getPixel(c, key)).isNotEqualTo(Color.WHITE)
        assertThat(unlocked.getPixel(c + ring, c)).isEqualTo(RING)
        assertThat(unlocked.getPixel(c + (16 * dp).toInt(), c)).isEqualTo(INK) // the level ring, full at level 1

        view.mirrored = true
        val mirrored = draw(locked)
        assertThat(mirrored.getPixel(mirrored.width - c, key)).isEqualTo(Color.WHITE)
        assertThat(mirrored.getPixel(mirrored.width - c + ring, c)).isEqualTo(RING)
    }

    // Each size draws the art at its size; the touch target never goes below 48 dp, so a small bubble keeps a 48 dp target
    // with its art centered in it.
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun sizeFollowsTheStyleWithA48dpFloor() {
        val dp = context.resources.displayMetrics.density
        val expected = mapOf(
            BubbleStyle.Size.SMALL to 48, BubbleStyle.Size.MEDIUM to 48, BubbleStyle.Size.LARGE to 60, BubbleStyle.Size.EXTRA_LARGE to 72,
        )
        for ((size, touchDp) in expected) {
            view.style = BubbleStyle(size, 85)
            assertWithMessage("$size").that(view.sizePx).isEqualTo((touchDp * dp).toInt())
            layOut()
            assertWithMessage("$size gesture exclusion").that(view.systemGestureExclusionRects)
                .containsExactly(Rect(0, 0, view.sizePx, view.sizePx))
        }

        // Small: the 40 dp art sits in the middle of its 48 dp target, so a corner of the target is empty.
        view.style = BubbleStyle(BubbleStyle.Size.SMALL, 100)
        val small = draw(BubbleUi.Idle)
        assertThat(Color.alpha(small.getPixel((2 * dp).toInt(), (2 * dp).toInt()))).isEqualTo(0)
        val c = view.sizePx / 2
        assertThat(Color.blue(small.getPixel(c, c + (14 * dp).toInt()))).isLessThan(100) // the yellow disc, scaled
    }

    // The red ring and the stop mark fade in as listening starts, and out as it stops, rather than snapping.
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun theRingAndTheStopMarkFadeInAndOut() {
        view.style = BubbleStyle(BubbleStyle.Size.MEDIUM, 85)
        val c = view.sizePx / 2
        val dp = context.resources.displayMetrics.density
        val ring = view.sizePx * 111 / 256
        val key = c - (4 * dp).toInt()
        val locked = BubbleUi.Recording(0f, locked = true, 0)

        val starting = draw(locked, settle = false)
        assertThat(starting.getPixel(c + ring, c)).isNotEqualTo(RING)
        assertThat(starting.getPixel(c, key)).isNotEqualTo(Color.WHITE)
        ShadowSystemClock.advanceBy(Duration.ofMillis(110))
        val halfway = snapshot()
        assertThat(Color.red(halfway.getPixel(c + ring, c))).isGreaterThan(Color.red(starting.getPixel(c + ring, c)))
        val listening = draw(locked)
        assertThat(listening.getPixel(c + ring, c)).isEqualTo(RING)
        assertThat(listening.getPixel(c, key)).isEqualTo(Color.WHITE)

        val stopping = draw(BubbleUi.Idle, settle = false)
        assertThat(stopping.getPixel(c + ring, c)).isEqualTo(RING) // still all there as it starts to go
        assertThat(draw(BubbleUi.Idle).getPixel(c + ring, c)).isNotEqualTo(RING)
    }

    // The idle bubble is drawn at the style's opacity; recording is always fully opaque so the red ring shows.
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun idleFollowsTheOpacityAndRecordingIsSolid() {
        val dp = context.resources.displayMetrics.density
        view.style = BubbleStyle(BubbleStyle.Size.MEDIUM, 40)
        val c = view.sizePx / 2
        val disc = c + (17 * dp).toInt()

        assertThat(Color.alpha(draw(BubbleUi.Idle).getPixel(c, disc))).isIn(Range.closed(100, 104)) // 40% of 255
        assertThat(Color.alpha(draw(BubbleUi.Recording(0f, locked = false, 0)).getPixel(c, disc))).isEqualTo(255)
    }

    // A drag from the circle at the screen edge moves the bubble instead of starting the back gesture.
    @Test
    fun circleIsExcludedFromSystemGestures() {
        view.render(BubbleUi.Recording(0f, locked = true, 0))
        layOut()
        assertThat(view.systemGestureExclusionRects).containsExactly(Rect(0, 0, view.sizePx, view.sizePx))

        view.mirrored = true
        layOut()
        assertThat(view.systemGestureExclusionRects)
            .containsExactly(Rect(view.width - view.sizePx, 0, view.width, view.sizePx))
    }

    private fun layOut() {
        val any = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        view.measure(any, any)
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
    }

    /** The view drawn in [ui]; [settle] lets its fades finish first, as they do within 220 ms. */
    private fun draw(ui: BubbleUi, settle: Boolean = true): Bitmap {
        view.render(ui)
        if (settle) ShadowSystemClock.advanceBy(Duration.ofMillis(300))
        return snapshot()
    }

    private fun snapshot(): Bitmap {
        layOut()
        return Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
    }

    private fun View.offsetIn(root: View): Pair<Int, Int> =
        if (this === root) 0 to 0 else (parent as View).offsetIn(root).let { (x, y) -> x + left to y + top }

    private fun down(x: Float, y: Float) = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, x, y, 0)

    private fun visible(root: View = view): List<View> =
        if (root.visibility != View.VISIBLE) emptyList()
        else listOf(root) + (root as? ViewGroup)?.let { g -> (0 until g.childCount).flatMap { visible(g.getChildAt(it)) } }.orEmpty()

    private fun texts() = visible().filterIsInstance<TextView>().map { it.text.toString() }

    private fun buttons() = visible().filterIsInstance<Button>()

    /** The chip's or panel's message that says [text]. */
    private fun message(text: String) = visible().filterIsInstance<TextView>().single { it !is Button && it.text.toString() == text }

    private fun cancelButtons() = visible().filter { it.contentDescription?.toString() == "Cancel dictation" }

    private fun sparkles() = visible().filter { it.contentDescription?.toString() in setOf("Clean up", "Cancel clean up", "Undo clean up") }

    // Clean up's sparkle (issue #1): beside the idle circle, toward the middle of the screen, the circle's size and the idle
    // bubble's transparency at every size, and level with it.
    @Test
    fun theSparkleIsTheBubblesSizeAndTransparencyBesideIt() {
        for (size in BubbleStyle.Size.entries) for (mirrored in listOf(false, true)) {
            view.style = BubbleStyle(size, 60)
            view.mirrored = mirrored
            view.sparkle = Sparkle.OFFER
            view.render(BubbleUi.Idle)
            layOut()
            val sparkle = sparkles().single()
            val (x, y) = sparkle.offsetIn(view)
            assertWithMessage("$size").that(sparkle.width).isEqualTo(view.sizePx)
            assertWithMessage("$size").that(sparkle.height).isEqualTo(view.sizePx)
            assertWithMessage("$size").that(sparkle.alpha).isEqualTo((60 * 255 + 50) / 100 / 255f)
            assertWithMessage("$size").that(y).isEqualTo(0)
            assertWithMessage("$size").that(view.width).isEqualTo(2 * view.sizePx)
            assertWithMessage("$size mirrored=$mirrored").that(x).isEqualTo(if (mirrored) 0 else view.sizePx)
        }
    }

    // Only the idle bubble has it: never during a take, with a chip, or grey.
    @Test
    fun theSparkleShowsOnTheIdleBubbleOnly() {
        view.sparkle = Sparkle.OFFER
        view.render(BubbleUi.Idle)
        assertThat(sparkles()).hasSize(1)
        for (ui in listOf(BubbleUi.Arming, BubbleUi.Recording(0f, locked = true, 0), BubbleUi.Processing(false, 0, null), noTarget)) {
            view.render(ui)
            assertWithMessage("$ui").that(sparkles()).isEmpty()
        }
        view.render(BubbleUi.Idle)
        view.grey = Grey.MIC_OFF
        assertThat(sparkles()).isEmpty()
        view.grey = null
        assertThat(sparkles()).hasSize(1)
        view.sparkle = null
        assertThat(sparkles()).isEmpty()
    }

    // The first time, a pill beside the sparkle says what a tap and a hold do; it shows only with the offer.
    @Test
    fun theFirstTimePillStandsBesideTheSparkle() {
        view.sparkle = Sparkle.OFFER
        view.render(BubbleUi.Idle)
        assertThat(texts()).doesNotContain("Tap to tidy · Hold for styles")
        view.sparkleHint = true
        layOut()
        val pill = visible().filterIsInstance<TextView>().single { it.text.toString() == "Tap to tidy · Hold for styles" }
        assertThat(pill.offsetIn(view).first).isEqualTo(2 * view.sizePx) // after the circle and the sparkle
        // A tap on the pill is the sparkle's, never a take's.
        val taps = mutableListOf<Boolean>()
        view.onSparkle = { taps += it }
        pill.performClick()
        assertThat(taps).containsExactly(false)
        view.sparkle = Sparkle.UNDO
        assertThat(texts()).doesNotContain("Tap to tidy · Hold for styles")
    }

    // A tap tidies with the default style and a hold picks one; TalkBack hears what it does, and names the hold.
    @Test
    fun theSparkleTapsHoldsAndSaysWhatItDoes() {
        val taps = mutableListOf<Boolean>()
        view.onSparkle = { taps += it }
        view.sparkle = Sparkle.OFFER
        view.render(BubbleUi.Idle)
        val sparkle = sparkles().single()
        sparkle.performClick()
        sparkle.performLongClick()
        assertThat(taps).containsExactly(false, true).inOrder()
        val info = sparkle.createAccessibilityNodeInfo()
        assertThat(sparkle.contentDescription.toString()).isEqualTo("Clean up")
        assertThat(info.actionList.single { it.id == AccessibilityNodeInfo.ACTION_LONG_CLICK }.label.toString()).isEqualTo("Choose a style")

        view.sparkle = Sparkle.WORKING
        assertThat(sparkle.contentDescription.toString()).isEqualTo("Cancel clean up")
        view.sparkle = Sparkle.UNDO
        assertThat(sparkle.contentDescription.toString()).isEqualTo("Undo clean up")
    }

    private companion object {
        const val DOWNLOADING_42 = "Your speech model is still downloading (42%)."
        const val RING = 0xFFFF3B30.toInt()
        const val INK = 0xFF1F1B3A.toInt() // @color/brand_mark
        const val INK_FACE = 0xFF39335F.toInt() // the buttons' ink (ui/Theme.kt)
    }
}
