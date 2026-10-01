package io.github.kabrapratik28.thumbfree.core.audio

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The speech gate's replay, run by android/tools/vad-parity.py replay. Each WAV that GATE_REPLAY lists ("set<TAB>path"
 * lines) goes through SpeechGate as a take and through WavChunks.plan as a Retry, and one line of numbers per WAV goes to
 * GATE_REPLAY_OUT: the take-level decision, the take's speech frames, those onFrame reports after the first 3 s, the cuts
 * and each chunk's skip flag and speech frames. No text. Skipped unless the script sets both.
 */
class GateReplayTest {
    @Test
    fun replay() {
        val list = System.getenv("GATE_REPLAY")
        val out = System.getenv("GATE_REPLAY_OUT")
        assumeTrue("no list: run android/tools/vad-parity.py replay", list != null && out != null)
        File(out!!).printWriter().use { w ->
            w.println("set\tfile\tseconds\ttake_speech\tspeech_frames\tlate_frames\tcuts\tchunk_sent\tchunk_frames")
            for (line in File(list!!).readLines()) {
                val (set, path) = line.split('\t')
                val bytes = File(path).readBytes()
                val pcm = ShortArray((bytes.size - WavWriter.HEADER_BYTES) / 2)
                ByteBuffer.wrap(bytes, WavWriter.HEADER_BYTES, pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                    .get(pcm)
                val gate = SpeechGate()
                var frames = 0
                var late = 0
                gate.add(pcm) { _, speech ->
                    if (speech && frames >= 100) late++
                    frames++
                }
                val chunks = WavChunks.plan(File(path))
                val fields = listOf(
                    set, File(path).name, "%.2f".format(Locale.ROOT, pcm.size / 16_000.0), if (gate.hasSpeech) 1 else 0,
                    gate.speechFrames, late, chunks.dropLast(1).joinToString(",") { "${it.toSample}" }.ifEmpty { "-" },
                    chunks.joinToString(",") { if (it.hasSpeech) "1" else "0" },
                    chunks.joinToString(",") { "${it.speechFrames}" },
                )
                w.println(fields.joinToString("\t"))
            }
        }
    }
}
