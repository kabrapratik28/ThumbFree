package io.github.kabrapratik28.thumbfree.core.session

import com.google.common.truth.Truth.assertThat
import io.github.kabrapratik28.thumbfree.core.session.BubblePlacement.Box
import org.junit.Test

class PreviewPlacementTest {
    private val area = Box(0, 100, 1080, 2300) // below a 100 px status bar

    private fun place(bubble: Box, width: Int = 700, height: Int = 200, imeTop: Int? = null, line: Box? = null, area: Box = this.area) =
        PreviewPlacement.place(bubble, width, height, area, imeTop, line, gapPx = 20, marginPx = 20)

    /** The bubble's band across the screen, as BubbleWindow passes it: the circle at the right edge, 1000 to 1180. */
    private val band = Box(0, 1000, 1080, 1180)

    private fun top(height: Int = 200, line: Box? = null, area: Box = this.area, bubble: Box = band) =
        PreviewPlacement.top(area.right - area.left - 40, height, area, line, bubble, sidePx = 20, gapPx = 10, marginPx = 20)

    @Test
    fun aboveTheBubbleFlushWithItsEdgeTowardTheMiddle() {
        // Never beside the circle, where its X button and chips open.
        assertThat(place(Box(900, 1000, 1080, 1180))).isEqualTo(380 to 780) // right edge: right-aligned with it
        assertThat(place(Box(0, 1000, 180, 1180))).isEqualTo(0 to 780) // left edge: left-aligned
        assertThat(place(Box(500, 1000, 680, 1180))).isEqualTo(0 to 780) // mid-screen: kept on screen
    }

    @Test
    fun belowWhenThereIsNoRoomAbove() {
        assertThat(place(Box(900, 150, 1080, 330))).isEqualTo(380 to 350)
    }

    @Test
    fun staysOnScreenAndAboveTheKeyboard() {
        assertThat(place(Box(900, 1000, 1080, 1180), width = 1200)).isEqualTo(0 to 780) // wider than the room: from the left
        // A keyboard just under the circle leaves no room below it: above, as before.
        assertThat(place(Box(900, 1000, 1080, 1180), imeTop = 1200)).isEqualTo(380 to 780)
    }

    @Test
    fun keepsOffTheCursorLineOrHides() {
        // Above would cover the line at 850 to 900: below the circle instead.
        assertThat(place(Box(900, 1000, 1080, 1180), line = Box(0, 850, 1080, 900))).isEqualTo(380 to 1200)
        // Neither spot is clear (a keyboard at 1300 pushes "below" up onto the circle): no panel at all.
        assertThat(place(Box(900, 1000, 1080, 1180), imeTop = 1300, line = Box(0, 850, 1080, 900))).isNull()
    }

    @Test
    fun cursorsNearEveryEdgeAreNeverCovered() {
        val bubble = Box(900, 1000, 1080, 1180)
        for (line in listOf(
            Box(0, 100, 1080, 150), // at the top
            Box(0, 2250, 1080, 2300), // at the bottom
            Box(0, 960, 1080, 1010), // just above the circle
            Box(0, 1170, 1080, 1220), // just below it
            Box(0, 700, 100, 1500), // a tall narrow field at the left edge
            Box(980, 700, 1080, 1500), // one at the right edge, beside the circle
        )) {
            for (height in listOf(120, 200, 420)) { // one line, three lines, three lines at a large font
                val spot = place(bubble, height = height, line = line) ?: continue // hidden: safe
                val (x, y) = spot
                val covers = x < line.right && x + 700 > line.left && y < line.bottom + 20 && y + height > line.top - 20
                assertThat(covers).isFalse()
                assertThat(y).isAtLeast(area.top)
                assertThat(y + height).isAtMost(area.bottom)
            }
        }
    }

    @Test
    fun aSmallWindowOrACutoutKeepsThePanelInsideOrHidesIt() {
        val small = Box(0, 300, 720, 900) // a split-screen half under a cutout band
        val spot = place(Box(560, 700, 720, 860), width = 704, height = 200, area = small)
        if (spot != null) {
            assertThat(spot.first).isAtLeast(small.left)
            assertThat(spot.second).isAtLeast(small.top)
            assertThat(spot.second + 200).isAtMost(small.bottom)
        }
        // Too small for the panel above or below without covering the circle: hidden.
        assertThat(place(Box(560, 350, 720, 510), height = 300, area = Box(0, 300, 720, 700))).isNull()
    }

    @Test
    fun theTopStripSitsJustUnderTheStatusBarWithSideMargins() {
        assertThat(top()).isEqualTo(20 to 110)
    }

    @Test
    fun theTopStripGivesWayToACursorLineUnderIt() {
        assertThat(top(line = Box(0, 250, 1080, 300))).isNull() // a search box at the top: next to the bubble instead
        assertThat(top(line = Box(0, 800, 1080, 850))).isEqualTo(20 to 110) // a field further down: the strip stays
    }

    @Test
    fun theTopStripNeverCoversTheBubble() {
        // The owner dragged the bubble to the top edge: the strip would take the touches meant for it (a locked take's stop).
        assertThat(top(bubble = Box(0, 110, 1080, 290))).isNull()
        assertThat(top(bubble = Box(0, 320, 1080, 500))).isNull() // within the margin under the strip
        assertThat(top(bubble = Box(0, 400, 1080, 580))).isEqualTo(20 to 110) // clear of it
    }

    @Test
    fun theTopStripMustFitTheArea() {
        val split = Box(0, 100, 1080, 350) // a short split-screen half
        assertThat(top(height = 260, area = split, bubble = Box(0, 2000, 1080, 2180))).isNull() // 110 + 260 > 350
        assertThat(top(height = 120, area = split, bubble = Box(0, 2000, 1080, 2180))).isEqualTo(20 to 110) // it fits
        // Three lines at a 200% font: twice as tall, past the half's bottom.
        assertThat(top(height = 480, area = Box(0, 100, 1080, 500), bubble = Box(0, 2000, 1080, 2180))).isNull()
    }
}
