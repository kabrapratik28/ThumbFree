package io.github.kabrapratik28.thumbfree.core.session

/** The bubble's size and how solid it is while idle, which the owner sets in Settings. */
data class BubbleStyle(val size: Size, val opacity: Int) {
    /** How big the bubble art is drawn. MEDIUM is the size it had before the setting; LARGE is recommended. */
    enum class Size(val artDp: Int) { SMALL(40), MEDIUM(48), LARGE(60), EXTRA_LARGE(72) }

    /** The touch target's side: the art's, never under 48 dp, so a small bubble stays easy to hit. */
    val touchDp: Int get() = maxOf(size.artDp, MIN_TOUCH_DP)

    companion object {
        const val MIN_TOUCH_DP = 48

        /**
         * Opacity while idle, in percent; Settings shows 100 minus it, the transparency. Recording is always fully opaque,
         * so its red ring shows.
         */
        const val MIN_OPACITY = 30
        const val MAX_OPACITY = 100

        /** Large and 50% opaque (50% transparent) while idle, by design; installs that saved a style keep it. */
        val RECOMMENDED = BubbleStyle(Size.LARGE, 50)
    }
}
