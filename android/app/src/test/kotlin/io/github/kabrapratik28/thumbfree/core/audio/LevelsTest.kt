package io.github.kabrapratik28.thumbfree.core.audio

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LevelsTest {
    @Test
    fun dbfsMapping() {
        assertThat(Levels.toRing(-60f)).isEqualTo(0f)
        assertThat(Levels.toRing(-10f)).isEqualTo(1f)
        assertThat(Levels.toRing(-35f)).isEqualTo(0.5f)
        assertThat(Levels.toRing(-90f)).isEqualTo(0f)
        assertThat(Levels.toRing(0f)).isEqualTo(1f)
        assertThat(Levels.toRing(Float.NEGATIVE_INFINITY)).isEqualTo(0f) // digital silence
    }

    @Test
    fun smoothsFrameLevels() {
        // 0.7 of the old value plus 0.3 of the new one.
        val levels = Levels()

        assertThat(levels.onFrame(-10f, nowMs = 0)).isWithin(1e-6f).of(0.3f)
        assertThat(levels.onFrame(-10f, nowMs = 100)).isWithin(1e-6f).of(0.51f)
        assertThat(levels.onFrame(-60f, nowMs = 200)).isWithin(1e-6f).of(0.357f)
    }

    @Test
    fun throttle30Hz() {
        // 48 frames per second for 10 s.
        val levels = Levels()

        val shownAt = (0 until 480).map { it * 1000L / 48 }.filter { levels.onFrame(-30f, it) != null }

        shownAt.windowed(31).forEach { assertThat(it.last() - it.first()).isAtLeast(1000L) } // at most 30 in any second
        assertThat(shownAt.size).isAtLeast(200)
    }
}
