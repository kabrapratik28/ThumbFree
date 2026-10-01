package io.github.kabrapratik28.thumbfree.core.audio

/** The bubble's level ring: frame levels as 0..1, smoothed, at most 30 updates per second. */
class Levels {
    private var ring = 0f
    private var shownAtMs: Long? = null

    /** Smooths in one frame level. Returns the value to show, or null when showing it would pass 30 updates per second. */
    fun onFrame(dbfs: Float, nowMs: Long): Float? {
        ring = 0.7f * ring + 0.3f * toRing(dbfs)
        val last = shownAtMs
        if (last != null && (nowMs - last) * MAX_PER_SECOND < 1000) return null
        shownAtMs = nowMs
        return ring
    }

    companion object {
        private const val MAX_PER_SECOND = 30

        /** -60 dBFS and below is 0, -10 dBFS and above is 1, linear in between. */
        fun toRing(dbfs: Float) = ((dbfs + 60) / 50).coerceIn(0f, 1f)
    }
}
