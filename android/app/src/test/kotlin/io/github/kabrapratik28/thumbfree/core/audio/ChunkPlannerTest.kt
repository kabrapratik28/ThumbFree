package io.github.kabrapratik28.thumbfree.core.audio

import com.google.common.truth.Truth.assertThat
import java.util.Random
import org.junit.Test

// Frames are 30 ms (480 samples): 10 s is frame 333.3, 20 s is 666.7, 22 s is 733.3, 30 s is 1,000. quiet() frames
// are non-speech at -60 dBFS: silence, since they are at or under -55 dBFS.
class ChunkPlannerTest {
    @Test
    fun cutsAt990msOfSilenceFrom10s() {
        // A second of silence at 3 s is before 10 s and 600 ms at 11 s is too short: neither cuts. The 1.2 s from 12 s
        // does, 510 ms into it, once 990 ms of it have passed.
        val closed = ChunkPlanner().feed(
            speech(100) + quiet(34) + speech(233) + quiet(20) + speech(13) + quiet(40) + speech(10),
        )

        assertThat(closed).containsExactly(432, Chunk(0, 417 * 480L, true, 346))
    }

    @Test
    fun longSilenceCutsAtFirstFrameEdgePast10s() {
        // 3.3 s of silence from 7.5 s: its cut waits for the first frame edge past 10 s.
        val closed = ChunkPlanner().feed(speech(250) + quiet(110) + speech(10))

        assertThat(closed).containsExactly(349, Chunk(0, 334 * 480L, true, 250))
    }

    @Test
    fun pauseAbove55DbfsIsNoSilence() {
        // A second of non-speech at -50 dBFS from 12 s, like a word less than 12 dB over a noisy room, is no silence:
        // no cut before 20 s.
        val closed = ChunkPlanner().feed(speech(400) + quiet(34, -50f) + speech(100))

        assertThat(closed).isEmpty()
    }

    @Test
    fun chunkWithoutGateSpeechAbove55DbfsIsSent() {
        // 22.5 s of speech, then 21 s of non-speech at -50 dBFS: the gate heard no speech in it, but it may hold some.
        val planner = ChunkPlanner()

        val closed = planner.feed(speech(750) + quiet(700, -50f)).values + planner.finish(1_450 * 480L)

        assertThat(closed).containsExactly(
            Chunk(0, 755 * 480L, true, 750),
            Chunk(755 * 480L, 1_422 * 480L, true, 0), // sent, but with no gate speech frame: no PnC-off retry
            Chunk(1_422 * 480L, 1_450 * 480L, true, 0),
        ).inOrder()
    }

    @Test
    fun cutsAtFirstPauseAfter20s() {
        // Pauses under 990 ms cut only from 20 s. A 270 ms gap at 20.49 s is not a pause. The pause from 21.36 s is:
        // the cut goes 150 ms into it as soon as it lasts 300 ms. The next pause is 3.3 s into the new chunk and does
        // not cut: the 20 s count restarts at a cut.
        val closed = ChunkPlanner().feed(
            speech(683) + quiet(9) + speech(20) + quiet(15) + speech(100) + quiet(15) + speech(50),
        )

        assertThat(closed).containsExactly(721, Chunk(0, 717 * 480L, true, 703))
    }

    @Test
    fun forcedCutAtQuietestWindowBefore30s() {
        // Speech with no pause, only short dips: -80 dBFS at 21 s (before 22 s), a one-frame dropout at 23.1 s (one quiet
        // frame in loud speech is not a quiet window), -60 dBFS at 25.5 s and -50 dBFS at 28.5 s.
        val planner = ChunkPlanner()

        val closed = planner.feed(
            speech(700) + quiet(4, -80f) + speech(66) + quiet(1, Float.NEGATIVE_INFINITY) + speech(79) +
                quiet(4, -60f) + speech(96) + quiet(4, -50f) + speech(46),
        )

        assertThat(closed).containsExactly(999, Chunk(0, 852 * 480L, true, 845)) // at 30 s, in the middle of the -60 dip
        assertThat(planner.finish(1_000 * 480L)).isEqualTo(Chunk(852 * 480L, 1_000 * 480L, true, 142))
    }

    @Test
    fun neverExceeds30s() {
        for ((chunks, _) in randomTakes()) {
            chunks.forEach { assertThat(it.toSample - it.fromSample).isAtMost(30 * 16_000L) }
        }
    }

    @Test
    fun noOverlapAndNoGap() {
        for ((chunks, end) in randomTakes()) {
            assertThat(chunks.first().fromSample).isEqualTo(0L)
            chunks.zipWithNext { a, b -> assertThat(b.fromSample).isEqualTo(a.toSample) }
            assertThat(chunks.last().toSample).isEqualTo(end)
            chunks.forEach { assertThat(it.toSample).isGreaterThan(it.fromSample) }
            if (end > 30 * 16_000) assertThat(chunks.size).isAtLeast(2)
        }
    }

    @Test
    fun speechFreeChunkIsSkipped() {
        // 22.5 s of speech, then 15 s of silence: the silence becomes chunks without speech, to be skipped.
        val planner = ChunkPlanner()

        val closed = planner.feed(speech(750) + quiet(500, -70f)).values + planner.finish(1_250 * 480L)

        assertThat(closed).containsExactly(
            Chunk(0, 755 * 480L, true, 750),
            Chunk(755 * 480L, 1_089 * 480L, false, 0),
            Chunk(1_089 * 480L, 1_250 * 480L, false, 0),
        ).inOrder()
    }

    @Test
    fun finalChunkClosesAtStop() {
        // After the cut in the pause at 24 s, the last chunk runs to the last sample, a partial frame included.
        val planner = ChunkPlanner()
        planner.feed(speech(800) + quiet(12) + speech(300))

        assertThat(planner.finish(1_112 * 480L + 123)).isEqualTo(Chunk(805 * 480L, 1_112 * 480L + 123, true, 300))
        assertThat(ChunkPlanner().finish(0)).isEqualTo(Chunk(0, 0, false, 0)) // an empty take gives the engine nothing
    }

    private fun speech(frames: Int) = List(frames) { -20f to true }

    private fun quiet(frames: Int, dbfs: Float = -60f) = List(frames) { dbfs to false }

    /** Feeds (dbfs, speech) frames and returns each closed chunk keyed by the index of the frame that closed it. */
    private fun ChunkPlanner.feed(frames: List<Pair<Float, Boolean>>): Map<Int, Chunk> =
        frames.withIndex().mapNotNull { (i, frame) -> add(frame.first, frame.second)?.let { i to it } }.toMap()

    /** 100 takes of up to 15 min and their end sample: speech runs up to 60 s, gaps up to 1.2 s, random levels. */
    private fun randomTakes(): List<Pair<List<Chunk>, Long>> = (0L until 100L).map { seed ->
        val random = Random(seed)
        val planner = ChunkPlanner()
        val chunks = mutableListOf<Chunk>()
        var speech = random.nextBoolean()
        var runLeft = 0
        val frames = 1 + random.nextInt(30_000)
        repeat(frames) {
            if (runLeft == 0) {
                speech = !speech
                runLeft = 1 + random.nextInt(if (speech) 2_000 else 40)
            }
            runLeft--
            val dbfs = if (speech) -35f + 25f * random.nextFloat() else -80f + 30f * random.nextFloat()
            planner.add(dbfs, speech)?.let { chunks += it }
        }
        val end = frames * 480L + random.nextInt(480)
        chunks += planner.finish(end)
        chunks to end
    }
}
