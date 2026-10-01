package io.github.kabrapratik28.thumbfree.core.session

import io.github.kabrapratik28.thumbfree.core.session.BubblePlacement.Box

/** Where the live preview shows (Settings > Bubble > Where to show). */
enum class PreviewPlace { BUBBLE, TOP }

/** Where the live preview panel goes, in screen pixels; null where no spot is safe, and then the panel hides. */
object PreviewPlacement {
    /**
     * The top-left of a [width] by [height] panel for the bubble whose circle is [bubble]: above it, or below it when there
     * is no room, flush with its edge toward the middle of the screen. Never beside it, where the bubble's X button and
     * chips open. Inside [area] and above a keyboard whose top is [imeTop]; of the two spots, the first that keeps
     * [marginPx] clear of [line] (the line the text cursor is on) and stays off the circle, else null.
     */
    fun place(bubble: Box, width: Int, height: Int, area: Box, imeTop: Int?, line: Box?, gapPx: Int, marginPx: Int): Pair<Int, Int>? {
        val bottom = minOf(area.bottom, imeTop ?: area.bottom)
        val right = bubble.left + bubble.right > area.left + area.right // the bubble is in the right half
        val x = (if (right) bubble.right - width else bubble.left).coerceIn(area.left, maxOf(area.left, area.right - width))
        val fit = { y: Int -> y.coerceIn(area.top, maxOf(area.top, bottom - height)) }
        return listOf(bubble.top - gapPx - height, bubble.bottom + gapPx).map(fit)
            .firstOrNull { y -> !covers(x, y, width, height, bubble, 0) && (line == null || !covers(x, y, width, height, line, marginPx)) }
            ?.let { x to it }
    }

    /**
     * The top-left of the top strip, [width] wide (the caller's: [area] less [sidePx] each side) and [height] tall: just
     * under the status bar ([area]'s top plus [gapPx]), [sidePx] in from the left. Null, and the take shows it next to
     * the bubble instead, when it would not fit inside [area], or would come within [marginPx] of [line] (the cursor's
     * line: a search box at the top) or of [bubble] (the bubble with its X and chips: a finger must reach it to stop).
     */
    fun top(width: Int, height: Int, area: Box, line: Box?, bubble: Box, sidePx: Int, gapPx: Int, marginPx: Int): Pair<Int, Int>? {
        val x = area.left + sidePx
        val y = area.top + gapPx
        val fits = x + width <= area.right - sidePx && y + height <= area.bottom
        val clear = !covers(x, y, width, height, bubble, marginPx) && (line == null || !covers(x, y, width, height, line, marginPx))
        return if (fits && clear) x to y else null
    }

    private fun covers(x: Int, y: Int, width: Int, height: Int, box: Box, margin: Int) =
        x < box.right && x + width > box.left && y < box.bottom + margin && y + height > box.top - margin
}
