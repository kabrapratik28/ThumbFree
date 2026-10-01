package io.github.kabrapratik28.thumbfree.engine

import io.github.kabrapratik28.thumbfree.core.session.Preview

/**
 * In :engine: the gated audio the live preview's stream has not taken yet, at most [capacity] samples
 * (Preview.BEHIND_MS: more, and the stream cannot keep up). [next] hands out at most one stream chunk at a time, up to
 * the stream's next chunk boundary (the first chunk with its lookahead, then each chunk) or all of it when that is
 * less, so a feed call computes at most one chunk and an offline call never waits behind more.
 */
class StreamBacklog(private val capacity: Int = Preview.BEHIND_MS * 16) {
    private val buf = FloatArray(capacity)

    var size = 0
        private set
    var fed = 0L // samples handed to the stream since clear
        private set
    var overflowed = false
        private set

    fun add(samples: FloatArray, from: Int, count: Int) {
        if (overflowed || size + count > capacity) {
            overflowed = true
            return
        }
        samples.copyInto(buf, size, from, from + count)
        size += count
    }

    /** Moves the samples to feed now into [out] (at least [FIRST] long) and returns how many. */
    fun next(out: FloatArray): Int {
        val boundary = if (fed < FIRST) FIRST - fed else CHUNK - (fed - FIRST) % CHUNK
        val n = minOf(size.toLong(), boundary).toInt()
        buf.copyInto(out, 0, 0, n)
        buf.copyInto(buf, 0, n, size)
        size -= n
        fed += n
        return n
    }

    fun clear() {
        size = 0
        fed = 0
        overflowed = false
    }

    companion object {
        const val CHUNK = Preview.CHUNK_MS * 16
        const val FIRST = (Preview.CHUNK_MS + Preview.RIGHT_MS) * 16
    }
}
