package io.github.kabrapratik28.thumbfree.core.audio

import com.google.common.truth.Truth.assertThat
import io.github.kabrapratik28.thumbfree.core.audio.PreviewGate.Companion.HANGOVER
import io.github.kabrapratik28.thumbfree.core.audio.PreviewGate.Companion.ONSET
import io.github.kabrapratik28.thumbfree.core.audio.PreviewGate.Companion.PREFILL
import io.github.kabrapratik28.thumbfree.core.audio.PreviewGate.Companion.WINDOW
import org.junit.Test

/**
 * Live preview: the preview's gate on Silero's per-window probabilities. Each window's samples hold its number, so the
 * test sees exactly which windows passed, in order. Real Silero on real noise, music, clicks and speech:
 * EngineServiceTest.
 */
class PreviewGateTest {
    private val passed = mutableListOf<Int>() // window numbers, in the order they came out
    private val gate = PreviewGate { samples, from, count ->
        assertThat(count).isEqualTo(WINDOW)
        passed += samples[from].toInt()
    }
    private var next = 0

    private fun windows(prob: Float, count: Int) = repeat(count) {
        val n = next++
        gate.window(FloatArray(WINDOW) { n.toFloat() }, 0, prob)
    }

    @Test
    fun speechPassesWithItsPreRollAndHangover() {
        windows(0.05f, 40) // silence
        windows(0.9f, 20) // speech from window 40
        windows(0.05f, 100)

        val first = 40 + ONSET - 1 - PREFILL // the pre-roll ends at the window that confirmed the start
        assertThat(passed.first()).isEqualTo(first)
        assertThat(passed.last()).isEqualTo(40 + 20 - 1 + HANGOVER)
        assertThat(passed).isEqualTo((first..40 + 19 + HANGOVER).toList()) // each window once, in order
    }

    @Test
    fun noiseSileroDoesNotCallSpeechNeverPassesHoweverLong() {
        windows(0.29f, 500) // just under the threshold: loud noise, music, clicks at startup
        windows(0.9f, 1) // one speech-like window is not a start either

        assertThat(passed).isEmpty()
    }

    @Test
    fun aShortPauseInsideTheHangoverKeepsThePhraseWhole() {
        windows(0.9f, 10)
        windows(0.1f, HANGOVER - 5) // a pause shorter than the hangover
        windows(0.9f, 10)

        assertThat(passed).isEqualTo((0 until 10 + HANGOVER - 5 + 10).toList())
    }

    @Test
    fun aNewStartAfterALongPauseNeverSendsAWindowTwice() {
        windows(0.9f, 5)
        windows(0.1f, HANGOVER + 3) // the gate closes
        windows(0.9f, 5) // the new start's pre-roll reaches back into windows already passed

        assertThat(passed).isEqualTo(passed.distinct())
        assertThat(passed).isEqualTo(passed.sorted())
        assertThat(passed.last()).isEqualTo(next - 1) // the new phrase passes (its hangover not over yet)
    }
}
