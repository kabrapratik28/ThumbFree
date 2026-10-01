package io.github.kabrapratik28.thumbfree.core.audio

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PaddingTest {
    @Test
    fun boundaries() {
        assertThat(Padding.forEngine(FloatArray(0))).isEmpty()

        // 8,000 zeros (0.5 s) on each side, then trailing zeros up to 20,000 samples (1.25 s).
        for ((clip, padded) in listOf(1 to 20_000, 6_240 to 22_240, 15_999 to 31_999)) {
            val out = Padding.forEngine(FloatArray(clip) { 0.5f })

            assertThat(out.size).isEqualTo(padded)
            assertThat(out.indexOfFirst { it != 0f }).isEqualTo(8_000)
            assertThat(out.indexOfLast { it != 0f }).isEqualTo(8_000 + clip - 1)
            assertThat(out.count { it != 0f }).isEqualTo(clip)
        }

        val oneSecond = FloatArray(16_000) { 0.5f }
        assertThat(Padding.forEngine(oneSecond)).isEqualTo(FloatArray(16_000) { 0.5f })
    }

    @Test
    fun wavKeepsRealAudio() {
        // The engine pads a copy: the samples read from the WAV stay exactly as recorded.
        val yes = FloatArray(6_240) { (it % 100 + 1) / 1000f } // a 0.39 s "yes"
        val recorded = yes.copyOf()

        val padded = Padding.forEngine(yes)

        assertThat(yes).isEqualTo(recorded)
        assertThat(padded).isEqualTo(FloatArray(22_240).also { recorded.copyInto(it, 8_000) })
    }
}
