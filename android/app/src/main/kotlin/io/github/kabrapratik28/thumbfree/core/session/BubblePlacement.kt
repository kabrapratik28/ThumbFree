package io.github.kabrapratik28.thumbfree.core.session

import kotlin.math.roundToInt

/** Where the bubble window goes, in screen pixels. */
object BubblePlacement {
    enum class Side { LEFT, RIGHT }

    data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int)

    /**
     * Where the owner dropped the bubble, as fractions of the room it moves in (0 the left or top end, 1 the right or
     * bottom one), so the spot holds across rotations, screens and sizes.
     */
    data class Spot(val x: Float, val y: Float) {
        /** At the nearer side edge, at the same height (Snap to screen edge). */
        fun snapped() = copy(x = if (x < 0.5f) 0f else 1f)
    }

    /** Top-left corner for a sizePx bubble at [side]: gapPx above the IME top when [ime] is set, else noImeGapPx above the bottom inset. */
    fun place(screen: Box, ime: Box?, bottomInsetPx: Int, sizePx: Int, gapPx: Int, noImeGapPx: Int, side: Side): Pair<Int, Int> {
        val x = if (side == Side.LEFT) screen.left else screen.right - sizePx
        val y = if (ime != null) ime.top - gapPx - sizePx else screen.bottom - bottomInsetPx - noImeGapPx - sizePx
        // A floating keyboard can sit near the top; never place the bubble above the screen.
        return x to y.coerceAtLeast(screen.top)
    }

    fun snapSide(centerX: Int, screenWidth: Int): Side = if (centerX * 2 < screenWidth) Side.LEFT else Side.RIGHT

    /**
     * Top-left corner for a sizePx bubble at the owner's [spot] in [area] (the screen less its bars and cutout), kept above
     * a keyboard whose top is [imeTop].
     */
    fun at(spot: Spot, area: Box, sizePx: Int, imeTop: Int?): Pair<Int, Int> {
        val x = area.left + (spot.x * (area.right - area.left - sizePx)).roundToInt()
        val y = area.top + (spot.y * (area.bottom - area.top - sizePx)).roundToInt()
        return x to y.coerceAtMost((imeTop ?: area.bottom) - sizePx).coerceAtLeast(area.top)
    }

    /**
     * The spot of a sizePx bubble dropped with its top-left corner at [x], [y]: within [area] and above a keyboard whose
     * top is [imeTop], and at the nearer side edge when [snap].
     */
    fun spotOf(x: Int, y: Int, area: Box, sizePx: Int, imeTop: Int?, snap: Boolean): Spot {
        val (left, top) = at(Spot(0f, 0f), area, sizePx, null)
        val (right, bottom) = at(Spot(1f, 1f), area, sizePx, imeTop)
        fun fraction(value: Int, from: Int, to: Int, room: Int) = (value.coerceIn(from, maxOf(from, to)) - from).toFloat() / maxOf(room, 1)
        val spot = Spot(
            fraction(x, left, right, area.right - area.left - sizePx),
            fraction(y, top, bottom, area.bottom - area.top - sizePx),
        )
        return if (snap) spot.snapped() else spot
    }

    /**
     * The top-left y that keeps a sizePx bubble at [x], [y] marginPx clear of [line], the text cursor's line: just below it
     * when that stays above [bottom], else just above it when that stays below [top]. Below first: above a field sits what
     * came before it (the Try tab's "Ready" line, an app's header), and a chat box on the keyboard leaves no room below
     * anyway. [y] when the bubble doesn't cover the line, or neither fits.
     */
    fun clear(x: Int, y: Int, sizePx: Int, line: Box, top: Int, bottom: Int, marginPx: Int): Int {
        val covers = x < line.right && x + sizePx > line.left && y < line.bottom + marginPx && y + sizePx > line.top - marginPx
        if (!covers) return y
        val below = line.bottom + marginPx
        val above = line.top - marginPx - sizePx
        return when {
            below + sizePx <= bottom -> below
            above >= top -> above
            else -> y
        }
    }
}
