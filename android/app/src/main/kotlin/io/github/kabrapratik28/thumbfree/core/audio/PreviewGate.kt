package io.github.kabrapratik28.thumbfree.core.audio

/**
 * Live preview: the part of a take that goes to the preview stream, decided in :engine by Silero run as a stream (a
 * smoothed streaming gate). Each [WINDOW]-sample window comes with Silero's speech probability; a start needs [ONSET]
 * windows at or over [THRESHOLD] in a row and brings [PREFILL] windows of pre-roll, since a word begins before Silero
 * is sure of it; after each end, [HANGOVER] windows more pass: at least the stream's chunk plus lookahead, so the last
 * words of a phrase get their right context before a pause stops the audio (as measured, a shorter hangover held a
 * quarter of phrase-final words back until the next phrase). Noise, music and clicks that Silero does not take for
 * speech never pass, however loud. A window passes at most once. [emit] gets whole windows, in order.
 */
class PreviewGate(private val emit: (samples: FloatArray, from: Int, count: Int) -> Unit) {
    private val ring = FloatArray((PREFILL + 1) * WINDOW) // the pre-roll and the window being judged
    private var index = 0L // windows judged, ever
    private var sent = -1L // the last window passed on
    private var inSpeech = false
    private var onset = 0
    private var hang = 0

    /** The next window: [samples] from [from], [WINDOW] of them, and Silero's probability that it is speech. */
    fun window(samples: FloatArray, from: Int, prob: Float) {
        val i = index++
        samples.copyInto(ring, slot(i), from, from + WINDOW)
        val voice = prob >= THRESHOLD
        when {
            inSpeech && voice -> {
                hang = HANGOVER
                send(i, i)
            }
            inSpeech && hang > 0 -> {
                hang--
                send(i, i)
            }
            inSpeech -> inSpeech = false
            voice && ++onset >= ONSET -> {
                inSpeech = true
                hang = HANGOVER
                onset = 0
                send(maxOf(sent + 1, i - PREFILL, 0), i)
            }
            !voice -> onset = 0
        }
    }

    private fun slot(window: Long) = (window % (PREFILL + 1)).toInt() * WINDOW

    private fun send(from: Long, to: Long) {
        for (w in from..to) emit(ring, slot(w), WINDOW)
        sent = to
    }

    companion object {
        const val WINDOW = 512 // Silero's window: 32 ms at 16 kHz
        const val THRESHOLD = 0.3f
        const val PREFILL = 14 // 448 ms
        const val ONSET = 2 // 64 ms
        const val HANGOVER = 52 // 1,664 ms: at least Preview.CHUNK_MS + RIGHT_MS (1,360 ms)
    }
}
