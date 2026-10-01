package io.github.kabrapratik28.thumbfree.core.audio

import com.google.common.collect.Range
import com.google.common.truth.Truth.assertThat
import io.github.kabrapratik28.thumbfree.audio.bursts
import io.github.kabrapratik28.thumbfree.audio.burstsWithPause
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WavChunksTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun replansFile() {
        val file = File(tmp.root, "take.wav")
        WavWriter.create(file).use {
            it.append(burstsWithPause())
            it.finish()
        }

        val chunks = WavChunks.plan(file)

        assertThat(chunks).hasSize(2)
        val (first, second) = chunks
        assertThat(first.fromSample).isEqualTo(0L)
        assertThat(first.toSample).isIn(Range.closed(21_000 * 16L, 21_400 * 16L)) // inside the pause
        assertThat(second).isEqualTo(Chunk(first.toSample, 25_000 * 16L, true, second.speechFrames)) // to the last sample
        assertThat(second.speechFrames).isGreaterThan(0) // the bursts after the pause are gate speech
    }

    // A short take's speech lies in the first 3 s, which the gate reports frame by frame as non-speech. Its chunk still
    // counts that speech: Canary's PnC-off retry reads the count.
    @Test
    fun firstChunkCountsTheSpeechOfTheFirst3s() {
        val file = File(tmp.root, "take.wav")
        val pcm = bursts(1_500) + ShortArray(8_000)
        WavWriter.create(file).use {
            it.append(pcm)
            it.finish()
        }
        val gate = SpeechGate().apply { add(pcm) }

        val chunks = WavChunks.plan(file)

        assertThat(gate.speechFrames).isGreaterThan(20)
        assertThat(chunks.single().speechFrames).isEqualTo(gate.speechFrames)
    }
}
