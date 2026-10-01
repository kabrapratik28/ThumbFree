package io.github.kabrapratik28.thumbfree.a11y

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.view.MotionEvent
import android.view.View
import android.view.View.MeasureSpec
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
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
class BubbleViewTest {
    private val context = RuntimeEnvironment.getApplication()
    private val chips = mutableListOf<ChipAction>()
    private val view = BubbleView(context) { chips += it }
    private val noTarget = BubbleUi.Chip(Code.NO_TARGET, listOf(ChipAction.COPY, ChipAction.INSERT_HERE))

    @Test
    fun contentDescriptions() {
        view.render(BubbleUi.Idle)
        assertThat(view.contentDescription.toString()).isEqualTo("Start dictation")
        view.render(BubbleUi.Recording(0f, locked = true, 0))
        assertThat(view.contentDescription.toString()).isEqualTo("Stop dictation")
        view.render(BubbleUi.Processing(false, 0, null))
        assertThat(view.contentDescription.toString()).isEqualTo("Transcribing")

        view.render(noTarget)
        assertThat(texts()).contains(context.getString(CodeMessages.of(Code.NO_TARGET)))
        assertThat(buttons().map { it.text.toString() }).containsExactly("Copy", "Insert here").inOrder()
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
            Triple(BubbleUi.Idle, "Start dictation", emptyList()),
            Triple(BubbleUi.Arming, "Stop dictation", emptyList()),
            Triple(BubbleUi.Recording(0.5f, locked = false, 0), "Stop dictation", emptyList()),
            Triple(BubbleUi.Recording(0.5f, locked = true, 0), "Stop dictation", emptyList()),
            Triple(BubbleUi.Processing(loadingModel = true, 0, null), "Loading model", listOf("Loading model")),
            Triple(BubbleUi.Processing(false, 2, 5), "Transcribing, 2 of 5", listOf("2 of 5")),
            Triple(BubbleUi.Processing(false, 0, null), "Transcribing", emptyList()),
            Triple(noTarget, "Start dictation", listOf(context.getString(CodeMessages.of(Code.NO_TARGET)), "Copy", "Insert here")),
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

    private fun draw(ui: BubbleUi): Bitmap {
        view.render(ui)
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

    private fun cancelButtons() = visible().filter { it.contentDescription?.toString() == "Cancel dictation" }

    private companion object {
        const val RING = 0xFFFF3B30.toInt()
        const val INK = 0xFF1F1B3A.toInt() // @color/brand_mark
    }
}
