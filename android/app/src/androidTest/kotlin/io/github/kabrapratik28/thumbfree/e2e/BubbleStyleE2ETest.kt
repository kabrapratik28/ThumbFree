package io.github.kabrapratik28.thumbfree.e2e

import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.os.SystemClock
import android.view.MotionEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.github.kabrapratik28.thumbfree.a11y.DictationAccessibilityService
import io.github.kabrapratik28.thumbfree.app.AppGraph
import io.github.kabrapratik28.thumbfree.audio.AudioSource
import io.github.kabrapratik28.thumbfree.core.session.BubblePlacement
import io.github.kabrapratik28.thumbfree.core.session.BubbleStyle
import io.github.kabrapratik28.thumbfree.testing.A11yRule
import io.github.kabrapratik28.thumbfree.testing.WavFileSource
import io.github.kabrapratik28.thumbfree.testing.automation
import io.github.kabrapratik28.thumbfree.testing.focusTarget
import io.github.kabrapratik28.thumbfree.testing.jfkPcm
import io.github.kabrapratik28.thumbfree.testing.launchInsertTargets
import io.github.kabrapratik28.thumbfree.testing.waitFor
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The floating bubble follows Settings > Bubble at once, with no restart: over another app's field its window takes each
 * size (the touch target never under 48 dp), a tap on it still starts and stops a take, and a drag leaves it where it is
 * let go at every size; on screen the idle bubble is as see-through as set while a take draws it solid. A dropped bubble
 * comes back to its spot, keeps off the field's line, and with Snap to screen edge goes to the nearer side.
 */
@RunWith(AndroidJUnit4::class)
class BubbleStyleE2ETest {
    @get:Rule val a11y = A11yRule()

    // Created after the rule has set the UiAutomation flags.
    private val device by lazy { UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()) }
    private val app = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var savedStyle: BubbleStyle
    private lateinit var savedFactory: (Context) -> AudioSource
    private var savedSpot: BubblePlacement.Spot? = null
    private var savedSnap = false

    @Before
    fun setUp() {
        savedStyle = AppGraph.settings.bubbleStyle
        savedSpot = AppGraph.settings.bubbleSpot
        savedSnap = AppGraph.settings.bubbleSnap
        onMain { AppGraph.settings.bubbleSpot = null; AppGraph.settings.bubbleSnap = false } // each test starts beside the keyboard
        savedFactory = AppGraph.audioSourceFactory
        AppGraph.audioSourceFactory = { WavFileSource(jfkPcm()) }
        launchInsertTargets(device)
    }

    @After
    fun tearDown() {
        try {
            checkIdleAfterTest()
        } finally {
            onMain { AppGraph.settings.bubbleSpot = savedSpot; AppGraph.settings.bubbleSnap = savedSnap }
            restyle(savedStyle)
            AppGraph.audioSourceFactory = savedFactory
        }
    }

    @Test
    fun bubbleFollowsTheStyleLive() {
        focusTarget(device, "empty")
        val dp = app.resources.displayMetrics.density
        val width = device.displayWidth
        for (size in BubbleStyle.Size.entries) {
            val style = BubbleStyle(size, 50)
            restyle(style)
            val side = (maxOf(size.artDp, BubbleStyle.MIN_TOUCH_DP) * dp).toInt()

            assertWithMessage("$size").that(waitFor(5_000) { bubbleBounds()?.width() == side }).isTrue()
            assertWithMessage("$size").that(settledBubble().height()).isEqualTo(side)
            assertThat(onMain { DictationAccessibilityService.instance!!.bubble!!.style }).isEqualTo(style)

            // The tap geometry at this size: a tap on the circle starts a take, a second one stops it.
            device.tapBubble()
            awaitRecording()
            device.tapBubble()
            checkIdleAfterTest()

            // A drag across the middle: the bubble stays where it is let go, still this size.
            val to = if (settledBubble().centerX() > width / 2) width / 4 else width * 3 / 4
            dragTo(to)
            val dropped = waitFor(5_000) { bubbleBounds()?.let { abs(it.centerX() - to) <= 2 && it.width() == side } == true }
            assertWithMessage("$size drag to $to: ${bubbleBounds()}").that(dropped).isTrue()
            checkIdleAfterTest()
        }
    }

    // On screen, over the same spot of the app behind: the idle circle's middle pixel at 65% lies half way between 30%
    // and 100%, whatever the color behind, so the opacity is applied as set, at once. A take draws the circle solid at
    // any opacity: a pixel of its red ring at 30% matches that pixel at 100%.
    @Test
    fun idleOpacityFollowsLiveAndATakeIsSolid() {
        focusTarget(device, "empty")
        val (solid, half, faint) = listOf(100, 65, 30).map { restyle(BubbleStyle(BubbleStyle.Size.MEDIUM, it)); pixel() }
        val channel = (0..2).maxBy { abs(solid[it] - faint[it]) }
        assertWithMessage("${solid.toList()} vs ${faint.toList()}").that(abs(solid[channel] - faint[channel])).isAtLeast(40)
        val share = (half[channel] - faint[channel]).toFloat() / (solid[channel] - faint[channel])
        assertThat(share).isWithin(0.1f).of(0.5f)

        // The red ring of drawable/bubble_recording.xml: radius 111 of 256, clear of the level ring and the stop mark.
        val ring = -(111f / 256 * BubbleStyle.Size.MEDIUM.artDp * app.resources.displayMetrics.density).roundToInt()
        val (taking30, taking100) = listOf(30, 100).map { opacity ->
            restyle(BubbleStyle(BubbleStyle.Size.MEDIUM, opacity))
            device.tapBubble()
            awaitRecording()
            pixel(ring).also {
                device.tapBubble()
                checkIdleAfterTest()
            }
        }
        for (c in 0..2) assertWithMessage("${taking30.toList()} vs ${taking100.toList()}").that(abs(taking30[c] - taking100[c])).isAtMost(3)
    }

    // Dropped mid-screen, between the fields and the keyboard, the bubble stays there. It still shows only while a text
    // field has focus (a password field and the home screen get none), and it comes back at its spot on the next field,
    // and after the app was left.
    @Test
    fun dropInTheMiddleStaysAndIsRemembered() {
        focusTarget(device, "empty")
        val to = middle()
        dragTo(to.first, to.second)
        assertWithMessage("to $to: ${bubbleBounds()}").that(waitFor(5_000) { bubbleBounds()?.let { near(it, to) } == true }).isTrue()
        val spot = AppGraph.settings.bubbleSpot
        assertThat(spot).isNotNull()

        focusTarget(device, "password")
        assertThat(waitFor(5_000) { bubbleBounds() == null }).isTrue()
        focusTarget(device, "second")
        assertWithMessage("next field: ${bubbleBounds()}").that(waitFor(5_000) { bubbleBounds()?.let { near(it, to) } == true }).isTrue()

        device.pressHome()
        assertThat(waitFor(5_000) { bubbleBounds() == null }).isTrue()
        SystemClock.sleep(1_000) // and it stays away
        assertThat(bubbleBounds()).isNull()
        launchInsertTargets(device)
        focusTarget(device, "empty")

        assertWithMessage("back at $to: ${bubbleBounds()}").that(waitFor(5_000) { bubbleBounds()?.let { near(it, to) } == true }).isTrue()
        assertThat(AppGraph.settings.bubbleSpot).isEqualTo(spot)
    }

    // Dropped on the field being typed in, the bubble moves just clear of the cursor's line (the whole field while it is
    // empty), so it never covers what is typed.
    @Test
    fun dropOnTheFieldMovesOffItsLine() {
        focusTarget(device, "empty")
        val field = checkNotNull(device.findObject(By.desc("empty"))).visibleBounds
        val margin = (8 * app.resources.displayMetrics.density).toInt()

        dragTo(field.centerX(), field.centerY())

        assertWithMessage("$field").that(
            waitFor(5_000) { bubbleBounds()?.let { it.top == field.bottom + margin || it.bottom == field.top - margin } == true },
        ).isTrue()
    }

    // With Snap to screen edge a drop goes to the nearer side, at the height it was let go.
    @Test
    fun snapSendsADropToTheNearerEdge() {
        onMain { AppGraph.settings.bubbleSnap = true }
        focusTarget(device, "empty")
        val (_, y) = middle()

        dragTo(device.displayWidth * 2 / 5, y)

        val snapped = waitFor(5_000) { bubbleBounds()?.let { it.left == 0 && abs(it.centerY() - y) <= 2 } == true }
        assertWithMessage("at $y: ${bubbleBounds()}").that(snapped).isTrue()
    }

    /** Mid-screen: the middle of the width, half way between the last text field and the keyboard. */
    private fun middle(): Pair<Int, Int> {
        settledBubble() // the keyboard is up
        val fields = checkNotNull(device.findObject(By.desc("second"))).visibleBounds.bottom
        val keyboard = checkNotNull(onMain { DictationAccessibilityService.instance?.imeBounds() }).top
        return device.displayWidth / 2 to (fields + keyboard) / 2
    }

    private fun near(circle: Rect, to: Pair<Int, Int>) = abs(circle.centerX() - to.first) <= 2 && abs(circle.centerY() - to.second) <= 2

    // What Settings does on a change: save it, then tell the ports, which restyle the showing bubble.
    private fun restyle(style: BubbleStyle) = onMain {
        AppGraph.settings.bubbleStyle = style
        AppGraph.ports.bubbleSettingsChanged()
    }

    /** The red, green and blue on screen at the circle's middle, [dy] pixels down, once the circle holds still. */
    private fun pixel(dy: Int = 0): IntArray {
        val circle = settledBubble()
        SystemClock.sleep(200) // a frame or two past a change, which only redraws
        val shot = automation().takeScreenshot() // with the rule's flags: a plain uiAutomation would turn the service off
        val color = shot.getPixel(circle.centerX(), circle.centerY() + dy)
        shot.recycle()
        return intArrayOf(Color.red(color), Color.green(color), Color.blue(color))
    }

    /** DOWN on the circle, a quick move (a drag, not a hold) of its middle to [x], [y] in four steps, and UP there. */
    private fun dragTo(x: Int, y: Int? = null) {
        val circle = settledBubble()
        val dx = x - circle.centerX()
        val dy = (y ?: circle.centerY()) - circle.centerY()
        val downAt = SystemClock.uptimeMillis()
        inject(downAt, MotionEvent.ACTION_DOWN, circle)
        for (step in 1..4) inject(downAt, MotionEvent.ACTION_MOVE, Rect(circle).apply { offset(dx * step / 4, dy * step / 4) })
        inject(downAt, MotionEvent.ACTION_UP, Rect(circle).apply { offset(dx, dy) })
    }
}
