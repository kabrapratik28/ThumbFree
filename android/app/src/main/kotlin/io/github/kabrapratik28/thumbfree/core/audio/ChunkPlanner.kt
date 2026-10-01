package io.github.kabrapratik28.thumbfree.core.audio

import io.github.kabrapratik28.thumbfree.core.audio.SpeechGate.Companion.FRAME_SAMPLES
import io.github.kabrapratik28.thumbfree.core.audio.SpeechGate.Companion.PAUSE_FRAMES
import io.github.kabrapratik28.thumbfree.core.audio.SpeechGate.Companion.SPEECH_MIN_DBFS
import kotlin.math.pow

/**
 * Samples [fromSample, toSample) of a take. [hasSpeech] is false when no frame is above -55 dBFS: too quiet to hold
 * speech, so the queue skips it. Any other chunk goes to Silero's speech check, whatever the gate heard: speech under a
 * noisy room's floor + 12 dB is no gate speech frame. [speechFrames] counts the gate's speech frames: only a chunk with
 * enough of them (TranscriptionQueue) may have an empty Canary run retried without PnC, which invents words on noise.
 */
data class Chunk(val fromSample: Long, val toSample: Long, val hasSpeech: Boolean, val speechFrames: Int = 0)

/**
 * Cuts a take into chunks of at most 30 s while it records, so they can be transcribed before the stop. It is fed
 * each frame's level and speech decision from [SpeechGate]. Chunks tile the take with no overlap and no gap, and every
 * cut falls on a frame edge. [soft] (samples), [softPause] (frames) and [softPauseMaxDbfs] set the early
 * cut; the app keeps the defaults, and only experiments change them.
 */
class ChunkPlanner(
    private val soft: Int = SOFT,
    private val softPause: Int = SOFT_PAUSE,
    private val softPauseMaxDbfs: Float = SOFT_PAUSE_MAX_DBFS,
) {
    private val power = DoubleArray(HARD / FRAME_SAMPLES) // mean square of each frame of the open chunk
    private val speech = BooleanArray(HARD / FRAME_SAMPLES)
    private var frames = 0 // in the open chunk
    private var start = 0L // the open chunk's first sample
    private var nonSpeechRun = 0
    private var quietRun = 0 // non-speech frames at or under softPauseMaxDbfs, in a row

    /** Adds the next frame. Returns the chunk it closes, which can go to the engine at once, or null. */
    fun add(dbfs: Float, isSpeech: Boolean): Chunk? {
        power[frames] = 10.0.pow(dbfs / 10.0)
        speech[frames] = isSpeech
        frames++
        nonSpeechRun = if (isSpeech) 0 else nonSpeechRun + 1
        quietRun = if (!isSpeech && dbfs <= softPauseMaxDbfs) quietRun + 1 else 0
        // From 10 s: cut in the middle of the latest 990 ms of silence (non-speech at or under -55 dBFS) as soon as
        // there is one. From 20 s the same at 300 ms of pause, however loud. At 30 s: force a cut. Silence, because a
        // "pause" in a noisy take is often soft speech; 10 s, because an early cut then leaves chunks of 10 s or more,
        // which kept accuracy in every chunk-length sweep, while phrase-sized chunks cost about 2 points of WER.
        return when {
            paused(quietRun, softPause, soft) -> cut(frames - softPause / 2)
            paused(nonSpeechRun, PAUSE_FRAMES, LATE) -> cut(frames - PAUSE_FRAMES / 2)
            frames * FRAME_SAMPLES == HARD -> cut(quietestMiddle())
            else -> null
        }
    }

    /** Closes the last chunk at the stop. [endSample] is the take's length, so it includes a final partial frame. */
    fun finish(endSample: Long) = Chunk(start, endSample, mayHoldSpeech(frames), speechFrames(frames))

    private fun cut(frame: Int): Chunk {
        val chunk = Chunk(start, start + frame * FRAME_SAMPLES, mayHoldSpeech(frame), speechFrames(frame))
        power.copyInto(power, 0, frame, frames)
        speech.copyInto(speech, 0, frame, frames)
        frames -= frame
        start = chunk.toSample
        return chunk
    }

    private fun mayHoldSpeech(count: Int) = (0 until count).any { speech[it] || power[it] > SILENCE_POWER }

    private fun speechFrames(count: Int) = (0 until count).count { speech[it] }

    /** True once [run] covers the last [pause] frames and their middle is [from] samples or more into the chunk. */
    private fun paused(run: Int, pause: Int, from: Int) = run >= pause && (frames - pause / 2) * FRAME_SAMPLES >= from

    /** The middle of the quietest [WINDOW]-frame window that lies between 22 s and 30 s. */
    private fun quietestMiddle(): Int {
        val first = (QUIET_FROM + FRAME_SAMPLES - 1) / FRAME_SAMPLES // the first frame that starts at 22 s or later
        val quietest = (first..frames - WINDOW).minBy { w -> (w until w + WINDOW).sumOf { power[it] } }
        return quietest + WINDOW / 2
    }

    private companion object {
        const val SOFT = 10 * 16_000 // samples
        const val SOFT_PAUSE = 33 // frames: 990 ms
        const val SOFT_PAUSE_MAX_DBFS = SPEECH_MIN_DBFS.toFloat()
        val SILENCE_POWER = 10.0.pow(SPEECH_MIN_DBFS / 10.0) // mean square of a -55 dBFS frame
        const val LATE = 20 * 16_000
        const val HARD = 30 * 16_000
        const val QUIET_FROM = 22 * 16_000
        const val WINDOW = 4 // 120 ms: the shortest whole-frame window of at least 100 ms whose middle is a frame edge
    }
}
