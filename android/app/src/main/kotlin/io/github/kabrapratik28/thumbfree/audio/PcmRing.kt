package io.github.kabrapratik28.thumbfree.audio

/**
 * A preallocated FIFO of samples from one thread (the only writer) to another (the only reader): the capture thread to
 * the writer thread, and, for the live preview, the writer thread to the preview feed. The volatile totals publish the
 * samples, so it needs no lock.
 */
class PcmRing(capacity: Int) {
    private val buf = ShortArray(capacity)

    /** Samples in, ever. */
    @Volatile var written = 0L
        private set

    @Volatile private var read = 0L // samples out, ever

    /** True once a write did not fit. The ring then keeps what it had, a contiguous prefix of the take. */
    @Volatile var overflowed = false
        private set

    /** Samples written and not read yet. The reader's view: the writer only adds to it. */
    val available: Long get() = written - read

    /** Adds [count] samples of [src] from [from]. Returns false, and writes nothing, once it overflows. */
    fun write(src: ShortArray, count: Int, from: Int = 0): Boolean {
        if (overflowed || written - read + count > buf.size) {
            overflowed = true
            return false
        }
        val at = (written % buf.size).toInt()
        val first = minOf(count, buf.size - at)
        src.copyInto(buf, at, from, from + first)
        src.copyInto(buf, 0, from + first, from + count)
        written += count
        return true
    }

    /** Moves up to [max] samples into [dst] from [from] (by default to its end). Returns how many, 0 when empty. */
    fun read(dst: ShortArray, from: Int = 0, max: Int = dst.size - from): Int {
        val count = minOf(written - read, max.toLong()).toInt()
        val at = (read % buf.size).toInt()
        val first = minOf(count, buf.size - at)
        buf.copyInto(dst, from, at, at + first)
        buf.copyInto(dst, from + first, 0, count - first)
        read += count
        return count
    }
}
