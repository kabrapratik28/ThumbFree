package io.github.kabrapratik28.thumbfree.core.audio

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Energy speech gate for a take of 16 kHz PCM16 audio. It judges 30 ms frames against a noise floor that follows steady
 * noise. It never drops, trims or changes audio.
 *
 * A take starts with no floor to judge against. A floor guessed at the start either calls steady noise speech (a floor
 * held at -50 dBFS gave -35 dBFS noise 29 speech frames) or misses a word said at once. So the frames of the first 3 s
 * are judged against the floor of those 3 s once it is known, and onFrame reports them as non-speech. They need 8 dB
 * over it, not 12: a quiet word at the start of a noisy take is often the whole take, and push-to-talk would type
 * nothing (12 dB dropped 5 or 6 of 150 private takes mixed under -40 dBFS noise).
 */
class SpeechGate {
    /** True once the take has 5 speech frames (150 ms) in total, so a single click is not speech. */
    val hasSpeech get() = speechFrames >= SPEECH_FRAMES

    /** True when the peak stayed under -60 dBFS for the first 1.5 s: the mic is blocked or turned off. */
    val silentMic get() = frames >= SILENT_CHECK_FRAMES && dbfs(peak.toDouble()) < SILENT_PEAK_DBFS

    /** The take's speech frames so far, those of the first 3 s included. */
    val speechFrames get() = firstSpeechFrames + laterSpeechFrames

    /**
     * Speech frames in the first 3 s: 8 dB over the floor of the first 3 s, or of the take so far while it is shorter.
     * onFrame reported them as non-speech.
     */
    val firstSpeechFrames get() = firstJudged ?: judgeFirst()

    /**
     * [chunk], with the speech frames of the first 3 s added when it is the take's first chunk. The planner never cuts
     * before 10 s, so the first chunk holds all of the first 3 s.
     */
    fun withFirstFrames(chunk: Chunk) =
        if (chunk.fromSample == 0L) chunk.copy(speechFrames = chunk.speechFrames + firstSpeechFrames) else chunk

    private val histogram = IntArray(-FLOOR_MIN_DBFS + 1) // frames per 1 dB bin, -80 to 0 dBFS
    private val recentBins = IntArray(FLOOR_FRAMES) // the bin of each of the last 3 s of frames
    private val firstLevels = FloatArray(FLOOR_FRAMES) // the level of each frame of the first 3 s
    private var firstJudged: Int? = null // firstSpeechFrames, fixed once the first 3 s are over
    private var laterSpeechFrames = 0
    private var frames = 0
    private var frameFill = 0
    private var sumSquares = 0L
    private var peak = 0

    /**
     * Feeds the first [count] samples, so a caller can reuse one block for reads of any size. [onFrame] gets the level
     * and the speech decision of each frame they complete; for the first 3 s the decision is always non-speech.
     */
    fun add(samples: ShortArray, count: Int = samples.size, onFrame: (dbfs: Float, speech: Boolean) -> Unit = { _, _ -> }) {
        for (i in 0 until count) {
            val s = samples[i].toInt()
            sumSquares += s * s
            if (frames < SILENT_CHECK_FRAMES) peak = maxOf(peak, abs(s))
            if (++frameFill == FRAME_SAMPLES) {
                val dbfs = dbfs(sqrt(sumSquares.toDouble() / FRAME_SAMPLES)) // digital silence is -Infinity
                sumSquares = 0
                frameFill = 0
                onFrame(dbfs, judge(dbfs))
            }
        }
    }

    private fun judge(dbfs: Float): Boolean {
        val slot = frames % FLOOR_FRAMES
        if (frames >= FLOOR_FRAMES) histogram[recentBins[slot]]--
        recentBins[slot] = (floor(dbfs).toInt() - FLOOR_MIN_DBFS).coerceIn(0, histogram.lastIndex)
        histogram[recentBins[slot]]++
        if (frames < FLOOR_FRAMES) {
            firstLevels[frames++] = dbfs
            if (frames == FLOOR_FRAMES) firstJudged = judgeFirst()
            return false
        }
        frames++
        val speech = isSpeech(dbfs, noiseFloor() + ABOVE_FLOOR_DB)
        if (speech) laterSpeechFrames++
        return speech
    }

    private fun judgeFirst(): Int {
        val threshold = noiseFloor() + FIRST_ABOVE_FLOOR_DB
        var count = 0 // a plain loop: a range's count() allocates on the recorder's writer thread
        for (i in 0 until minOf(frames, FLOOR_FRAMES)) if (isSpeech(firstLevels[i], threshold)) count++
        return count
    }

    private fun isSpeech(dbfs: Float, threshold: Int) = dbfs >= threshold && dbfs > SPEECH_MIN_DBFS

    /** 10th percentile of the last 3 s of frame levels, to 1 dB. Frames under -80 dBFS count as -80, so it never goes lower. */
    private fun noiseFloor(): Int {
        var rank = (minOf(frames, FLOOR_FRAMES) + 9) / 10 // the 10th percentile is the rank-th quietest frame
        var bin = 0
        while (histogram[bin] < rank) rank -= histogram[bin++]
        return bin + FLOOR_MIN_DBFS
    }

    companion object {
        const val FRAME_SAMPLES = 480 // 30 ms at 16 kHz
        const val PAUSE_FRAMES = 10 // 300 ms
        private const val SPEECH_FRAMES = 5 // 150 ms
        const val FLOOR_FRAMES = 100 // 3 s: the floor's window, and the first frames, judged together once it is full
        private const val FLOOR_MIN_DBFS = -80
        private const val ABOVE_FLOOR_DB = 12
        private const val FIRST_ABOVE_FLOOR_DB = 8 // for the first 3 s
        const val SPEECH_MIN_DBFS = -55 // a frame at or under this is never speech
        private const val SILENT_CHECK_FRAMES = 50 // 1.5 s
        private const val SILENT_PEAK_DBFS = -60

        private fun dbfs(amplitude: Double) = (20 * log10(amplitude / 32768)).toFloat()
    }
}
