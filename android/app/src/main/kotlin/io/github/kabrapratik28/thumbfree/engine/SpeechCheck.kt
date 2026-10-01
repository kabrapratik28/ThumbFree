package io.github.kabrapratik28.thumbfree.engine

import java.util.Locale
import java.util.concurrent.ExecutorService

/**
 * Silero's check that a chunk holds speech, in :engine. It runs alongside the transcribe, on a thread of its own, so
 * the text waits for it only if it is the slower of the two, and the text of a chunk Silero hears no speech in is
 * dropped. [probabilities] gives Silero's speech probability for each 512-sample window of a chunk, or null when the
 * model is not loaded; that, or a failed run, fails open: the chunk counts as speech. The check never changes the audio
 * the engine transcribes. [log] gets numbers only.
 */
class SpeechCheck(
    private val probabilities: (FloatArray) -> FloatArray?,
    private val background: ExecutorService,
    private val log: (String) -> Unit,
) {
    /**
     * [transcribe]'s result, or null when Silero heard no speech in [clip]. The check has ended when this returns or
     * throws, so the caller may free the model after it.
     */
    fun <T> run(clip: FloatArray, transcribe: () -> T): T? {
        val heard = background.submit<Boolean> { hears(clip) }
        var speech = true
        val result = try {
            transcribe()
        } finally {
            val waited = System.nanoTime()
            speech = runCatching { heard.get() }.getOrDefault(true) // hears() fails open itself: belt and braces
            log("speech_check_wait ms=${(System.nanoTime() - waited) / 1_000_000}") // the time the text waited for it
        }
        return if (speech) result else null
    }

    private fun hears(clip: FloatArray): Boolean {
        val start = System.nanoTime()
        val p = try {
            probabilities(clip) ?: return true.also { log("speech_check failed_open reason=unavailable") }
        } catch (e: Exception) {
            return true.also { log("speech_check failed_open reason=${e.javaClass.simpleName}") }
        }
        val heard = isSpeech(p)
        log(String.format(Locale.ROOT, "speech_check heard=%b peak=%.3f run=%d windows=%d ms=%d", heard,
            p.maxOrNull() ?: 0f, longestRun(p), p.size, (System.nanoTime() - start) / 1_000_000))
        return heard
    }

    companion object {
        /**
         * A window is speech at this probability or more. Swept from 0.05 to 0.5 (android/tools/vad-parity.py replay):
         * from 0.20 up, a 23-word chunk of the long private take (peak 0.196) is lost; nothing under 0.43 refuses the
         * one public noise clip Parakeet still types a word for.
         */
        const val THRESHOLD = 0.15f

        /** Windows of 32 ms in a row that make a chunk speech; each chunk with words had 9 or more in the sweep. */
        const val MIN_RUN = 2

        fun isSpeech(probabilities: FloatArray) = longestRun(probabilities) >= MIN_RUN

        private fun longestRun(probabilities: FloatArray): Int {
            var longest = 0
            var run = 0
            for (p in probabilities) {
                run = if (p >= THRESHOLD) run + 1 else 0
                longest = maxOf(longest, run)
            }
            return longest
        }
    }
}
