package io.github.kabrapratik28.thumbfree.core.audio

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** The chunks of a finished or repaired take, cut the way the Recorder cuts them while recording (for Retry). */
object WavChunks {
    /** Replays the WAV through SpeechGate and ChunkPlanner. The last chunk ends at the last whole sample. */
    fun plan(file: File, planner: ChunkPlanner = ChunkPlanner()): List<Chunk> {
        val gate = SpeechGate()
        val chunks = mutableListOf<Chunk>()
        val total = (file.length() - WavWriter.HEADER_BYTES) / 2
        val bytes = ByteArray(BLOCK_SAMPLES * 2)
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(WavWriter.HEADER_BYTES.toLong())
            var done = 0L
            while (done < total) {
                val count = minOf(BLOCK_SAMPLES.toLong(), total - done).toInt()
                raf.readFully(bytes, 0, count * 2)
                val samples = ShortArray(count)
                ByteBuffer.wrap(bytes, 0, count * 2).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(samples)
                gate.add(samples) { dbfs, speech ->
                    planner.add(dbfs, speech)?.let { chunks += gate.withFirstFrames(it) }
                }
                done += count
            }
        }
        planner.finish(total).takeIf { it.toSample > it.fromSample }?.let { chunks += gate.withFirstFrames(it) }
        return chunks
    }

    private const val BLOCK_SAMPLES = 16_000 // 1 s per read
}
