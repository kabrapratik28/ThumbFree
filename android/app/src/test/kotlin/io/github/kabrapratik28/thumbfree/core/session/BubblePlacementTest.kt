package io.github.kabrapratik28.thumbfree.core.session

import com.google.common.truth.Truth.assertThat
import io.github.kabrapratik28.thumbfree.core.session.BubblePlacement.Box
import io.github.kabrapratik28.thumbfree.core.session.BubblePlacement.Side
import io.github.kabrapratik28.thumbfree.core.session.BubblePlacement.Spot
import org.junit.Test

class BubblePlacementTest {
    private val screen = Box(0, 0, 1080, 2400)

    private fun place(ime: Box?, inset: Int, side: Side) = BubblePlacement.place(screen, ime, inset, 126, 32, 252, side)

    @Test
    fun aboveImeAtRightEdge() {
        assertThat(place(Box(0, 1500, 1080, 2400), 0, Side.RIGHT)).isEqualTo(954 to 1342)
    }

    @Test
    fun noImeUsesInset() {
        assertThat(place(null, 132, Side.RIGHT)).isEqualTo(954 to 1890)
    }

    @Test
    fun leftSide() {
        assertThat(place(null, 132, Side.LEFT)).isEqualTo(0 to 1890)
    }

    @Test
    fun snapSide() {
        assertThat(BubblePlacement.snapSide(300, 1080)).isEqualTo(Side.LEFT)
        assertThat(BubblePlacement.snapSide(700, 1080)).isEqualTo(Side.RIGHT)
    }

    // A floating keyboard near the top must not push the bubble off the screen.
    @Test
    fun staysOnScreen() {
        assertThat(place(Box(0, 100, 1080, 900), 0, Side.RIGHT)).isEqualTo(954 to 0)
    }

    // The screen less the status and navigation bars.
    private val area = Box(0, 100, 1080, 2300)

    // The owner's spot is fractions of the room the bubble moves in: the middle stays the middle on any screen, and a
    // keyboard pushes a low spot up, never above the screen.
    @Test
    fun spotMapsToTheScreenAboveTheKeyboard() {
        assertThat(BubblePlacement.at(Spot(0.5f, 0.5f), area, 126, null)).isEqualTo(477 to 1137)
        assertThat(BubblePlacement.at(Spot(0f, 0f), area, 126, null)).isEqualTo(0 to 100)
        assertThat(BubblePlacement.at(Spot(1f, 1f), area, 126, null)).isEqualTo(954 to 2174)
        assertThat(BubblePlacement.at(Spot(0.5f, 1f), area, 126, 1500)).isEqualTo(477 to 1374)
        assertThat(BubblePlacement.at(Spot(0.5f, 1f), area, 126, 150)).isEqualTo(477 to 100)
        assertThat(BubblePlacement.at(Spot(0.5f, 0.5f), Box(100, 0, 2300, 1080), 126, null)).isEqualTo(1137 to 477) // rotated
    }

    // A drop is kept on screen and above the keyboard; with snap it goes to the nearer side, at the height it was dropped.
    @Test
    fun dropBecomesASpot() {
        assertThat(BubblePlacement.spotOf(477, 1137, area, 126, null, snap = false)).isEqualTo(Spot(0.5f, 0.5f))
        assertThat(BubblePlacement.spotOf(-50, 2500, area, 126, null, snap = false)).isEqualTo(Spot(0f, 1f))
        val onKeyboard = BubblePlacement.spotOf(477, 1700, area, 126, 1500, snap = false)
        assertThat(BubblePlacement.at(onKeyboard, area, 126, null)).isEqualTo(477 to 1374)
        assertThat(BubblePlacement.spotOf(400, 1137, area, 126, null, snap = true)).isEqualTo(Spot(0f, 0.5f))
        assertThat(BubblePlacement.spotOf(500, 1137, area, 126, null, snap = true)).isEqualTo(Spot(1f, 0.5f))
        assertThat(Spot(0.3f, 0.2f).snapped()).isEqualTo(Spot(0f, 0.2f))
    }

    // Over the line the text cursor is on, the bubble moves just below it when there is room, else just above it; it
    // stays when neither fits. Below first, even when above is nearer: above a field is what came before it (the Try
    // tab's "Ready" line), and a chat box on the keyboard has no room below, so it still goes up.
    @Test
    fun spotClearsTheCursorLine() {
        val line = Box(40, 1000, 1040, 1060)
        fun clear(y: Int, top: Int = 100, bottom: Int = 1500) = BubblePlacement.clear(477, y, 126, line, top, bottom, 21)
        assertThat(clear(950)).isEqualTo(1081) // mostly above the line, still below it
        assertThat(clear(1040)).isEqualTo(1081)
        assertThat(clear(950, bottom = 1150)).isEqualTo(853) // no room below (a keyboard): up
        assertThat(clear(600)).isEqualTo(600) // clear already
        assertThat(clear(950, top = 900, bottom = 1150)).isEqualTo(950) // no room either way
        assertThat(BubblePlacement.clear(0, 1000, 30, line, 100, 1500, 21)).isEqualTo(1000) // beside the field
    }
}
