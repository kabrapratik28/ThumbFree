package io.github.kabrapratik28.thumbfree.engine

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** :engine hands the preview stream at most one chunk per feed call, up to its chunk boundaries, and nothing twice. */
class StreamBacklogTest {
    private val out = FloatArray(StreamBacklog.FIRST)

    private fun StreamBacklog.addCounting(from: Int, count: Int) = add(FloatArray(count) { (from + it).toFloat() }, 0, count)

    @Test
    fun handsOutUpToEachChunkBoundaryInOrder() {
        val backlog = StreamBacklog()
        backlog.addCounting(0, StreamBacklog.FIRST + StreamBacklog.CHUNK + 100)

        assertThat(backlog.next(out)).isEqualTo(StreamBacklog.FIRST) // the first chunk and its lookahead
        assertThat(out[0]).isEqualTo(0f)
        assertThat(backlog.next(out)).isEqualTo(StreamBacklog.CHUNK)
        assertThat(out[0]).isEqualTo(StreamBacklog.FIRST.toFloat())
        assertThat(backlog.next(out)).isEqualTo(100) // less than a chunk: all of it, no compute
        assertThat(backlog.next(out)).isEqualTo(0)
        assertThat(backlog.fed).isEqualTo(StreamBacklog.FIRST + StreamBacklog.CHUNK + 100L)
    }

    @Test
    fun aPartFedEarlierCountsTowardTheNextBoundary() {
        val backlog = StreamBacklog()
        backlog.addCounting(0, 1_000)
        assertThat(backlog.next(out)).isEqualTo(1_000)
        backlog.addCounting(1_000, StreamBacklog.FIRST)

        assertThat(backlog.next(out)).isEqualTo(StreamBacklog.FIRST - 1_000) // just up to the first boundary
        assertThat(out[0]).isEqualTo(1_000f)
    }

    @Test
    fun moreThanItHoldsIsBehindForGood() {
        val backlog = StreamBacklog(capacity = 1_000)
        backlog.addCounting(0, 900)
        backlog.addCounting(900, 200)

        assertThat(backlog.overflowed).isTrue()
        assertThat(backlog.size).isEqualTo(900) // nothing torn: the part that did not fit is not there
        backlog.clear()
        assertThat(backlog.overflowed).isFalse()
    }
}
