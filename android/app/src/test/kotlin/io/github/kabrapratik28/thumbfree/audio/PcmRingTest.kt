package io.github.kabrapratik28.thumbfree.audio

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PcmRingTest {
    @Test
    fun fifoAcrossWrap() {
        val ring = PcmRing(1_000)
        assertThat(ring.write(ShortArray(600) { it.toShort() }, 600)).isTrue()
        assertThat(ring.read(ShortArray(600))).isEqualTo(600)

        val block = ShortArray(900) { (1_000 + it).toShort() }
        assertThat(ring.write(block, 900)).isTrue() // runs past the end of the array

        val out = ShortArray(1_000)
        assertThat(ring.read(out)).isEqualTo(900)
        assertThat(out.copyOf(900)).isEqualTo(block)
        assertThat(ring.read(out)).isEqualTo(0)
    }

    @Test
    fun overflowIsReported() {
        val ring = PcmRing(100)

        assertThat(ring.write(ShortArray(150), 150)).isFalse()

        assertThat(ring.overflowed).isTrue()
        assertThat(ring.read(ShortArray(100))).isEqualTo(0) // nothing was written
        assertThat(ring.write(ShortArray(10), 10)).isFalse() // and it stays overflowed
    }

    // The live preview's feed reads batches into a buffer it fills in parts, and its gate writes frames from a slot.
    @Test
    fun offsetsAndAvailable() {
        val ring = PcmRing(1_000)
        val src = ShortArray(900) { it.toShort() }
        assertThat(ring.write(src, 300, from = 600)).isTrue()
        assertThat(ring.available).isEqualTo(300)

        val out = ShortArray(500)
        assertThat(ring.read(out, from = 100, max = 200)).isEqualTo(200)
        assertThat(out.copyOfRange(100, 300)).isEqualTo(src.copyOfRange(600, 800))
        assertThat(ring.available).isEqualTo(100)
        assertThat(ring.read(out, from = 300)).isEqualTo(100) // to the end of out at most
        assertThat(out.copyOfRange(300, 400)).isEqualTo(src.copyOfRange(800, 900))
        assertThat(ring.available).isEqualTo(0)
    }
}
