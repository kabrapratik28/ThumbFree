package io.github.kabrapratik28.thumbfree.ui

import com.google.common.truth.Truth.assertThat
import io.github.kabrapratik28.thumbfree.a11y.BubbleView
import io.github.kabrapratik28.thumbfree.core.session.BubbleUi
import io.github.kabrapratik28.thumbfree.core.session.ChipAction
import io.github.kabrapratik28.thumbfree.core.session.Code
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

// The try's state as the take machine draws it.
@RunWith(RobolectricTestRunner::class)
class TrialStateTest {
    // A warning while the take still records (a silent microphone, the time limit coming) is not the stop: the try keeps
    // listening and says to speak near the phone, and it completes only when the take stops. A silence that then ended
    // is not held against a take that went on to hear speech.
    @Test
    fun aWarningDuringTheTakeIsNotTheStop() {
        val trial = TrialState()
        trial.render(BubbleUi.Arming)
        trial.render(BubbleUi.Recording(0.2f, locked = true, 100))
        trial.render(BubbleUi.Chip(Code.MIC_SILENT, listOf(ChipAction.DISMISS)))
        assertThat(trial.listening).isTrue()
        assertThat(trial.done).isFalse()
        assertThat(trial.unheard).isTrue()

        trial.render(BubbleUi.Recording(0.6f, locked = true, 200))
        trial.render(BubbleUi.Chip(Code.TAKE_ENDS_SOON, listOf(ChipAction.DISMISS)))
        assertThat(trial.listening).isTrue()
        assertThat(trial.done).isFalse()

        trial.render(BubbleUi.Processing(false, 0, null))
        assertThat(trial.listening).isFalse()
        assertThat(trial.done).isTrue()
        assertThat(trial.unheard).isFalse()
    }

    // A take that heard nothing says so once it ends.
    @Test
    fun aTakeThatHeardNothingSaysSo() {
        val trial = TrialState()
        trial.render(BubbleUi.Recording(0.1f, locked = true, 100))
        trial.render(BubbleUi.Chip(Code.NO_SPEECH, listOf(ChipAction.DISMISS)))
        assertThat(trial.done).isTrue()
        assertThat(trial.unheard).isTrue()
    }

    // The try's bubble keeps one label from before the take until it stops: listening, a new level and a warning never
    // set it again, so TalkBack has nothing to read into the open microphone. Stopped, it says it transcribes.
    @Test
    fun theBubblesLabelStaysTheSameWhileItListens() {
        val trial = TrialState()
        val view = BubbleView(RuntimeEnvironment.getApplication()) {}
        trial.view = view
        val label = view.contentDescription
        assertThat(label.toString()).isEqualTo("Dictation, double tap to start or stop")

        trial.render(BubbleUi.Arming)
        trial.render(BubbleUi.Recording(0.2f, locked = true, 100))
        for (level in listOf(0.4f, 0.7f, 0.3f)) trial.render(BubbleUi.Recording(level, locked = true, 100))
        trial.render(BubbleUi.Chip(Code.MIC_SILENT, listOf(ChipAction.DISMISS)))
        assertThat(view.contentDescription).isSameInstanceAs(label)

        trial.render(BubbleUi.Processing(false, 0, null))
        assertThat(view.contentDescription.toString()).isEqualTo("Transcribing")
        trial.words("Yes, see you at seven")
        trial.render(BubbleUi.Idle)
        assertThat(view.contentDescription.toString()).isEqualTo("Dictation, double tap to start or stop")
    }

    // The try's line: tap until a take listens, speak while it does and until its words are worked out, that it worked
    // once words came, how to try again after a take without words or one that never listened; the microphone first,
    // whatever the take does.
    @Test
    fun theTrysLineFollowsTheTake() {
        val trial = TrialState()
        assertThat(tryLine(trial, micOff = false)).isEqualTo(TryLine.TAP)
        assertThat(tryLine(trial, micOff = true)).isEqualTo(TryLine.MIC_OFF)

        trial.render(BubbleUi.Arming)
        trial.render(BubbleUi.Chip(Code.MIC_NOT_READY, listOf(ChipAction.DISMISS)))
        assertThat(tryLine(trial, micOff = false)).isEqualTo(TryLine.AGAIN) // never listened

        trial.render(BubbleUi.Recording(0.5f, locked = true, 100))
        assertThat(tryLine(trial, micOff = false)).isEqualTo(TryLine.SPEAK)
        assertThat(tryLine(trial, micOff = true)).isEqualTo(TryLine.MIC_OFF)
        trial.render(BubbleUi.Chip(Code.NO_SPEECH, listOf(ChipAction.DISMISS)))
        assertThat(tryLine(trial, micOff = false)).isEqualTo(TryLine.AGAIN) // no words

        trial.render(BubbleUi.Recording(0.5f, locked = true, 100))
        trial.render(BubbleUi.Processing(false, 0, null))
        assertThat(tryLine(trial, micOff = false)).isEqualTo(TryLine.SPEAK) // stopped: listening's line until the words
        trial.words("Yes, see you at seven")
        assertThat(tryLine(trial, micOff = false)).isEqualTo(TryLine.DONE)
        trial.render(BubbleUi.Idle)
        assertThat(tryLine(trial, micOff = false)).isEqualTo(TryLine.DONE)

        trial.render(BubbleUi.Recording(0.5f, locked = true, 100))
        trial.render(BubbleUi.Processing(false, 0, null))
        trial.render(BubbleUi.Chip(Code.NO_SPEECH, listOf(ChipAction.DISMISS)))
        assertThat(tryLine(trial, micOff = false)).isEqualTo(TryLine.AGAIN) // worked out, and no words
    }

    // The engine's own failures are kept even after the stop, so the try says speech couldn't run rather than asking to
    // speak nearer; the next take forgets them.
    @Test
    fun anEngineFailureAfterTheStopIsKept() {
        val trial = TrialState()
        trial.render(BubbleUi.Recording(0.5f, locked = true, 100))
        trial.render(BubbleUi.Processing(false, 0, null))
        trial.render(BubbleUi.Chip(Code.ENGINE_CRASHED, listOf(ChipAction.DISMISS)))
        assertThat(trial.problem).isEqualTo(Code.ENGINE_CRASHED)
        assertThat(tryLine(trial, micOff = false)).isEqualTo(TryLine.AGAIN)

        trial.render(BubbleUi.Recording(0.5f, locked = true, 100))
        assertThat(trial.problem).isNull()
        trial.render(BubbleUi.Processing(false, 0, null))
        trial.render(BubbleUi.Chip(Code.TARGET_CHANGED, listOf(ChipAction.DISMISS)))
        assertThat(trial.problem).isNull() // not the engine's: a stopped take's other news stays out of the try
    }

    // A tap on the grey bubble counts, for its shake; leaving the step forgets the count with the rest.
    @Test
    fun greyTapsCountUntilTheStepGoes() {
        val trial = TrialState()
        trial.greyTap()
        trial.greyTap()
        assertThat(trial.greyTaps).isEqualTo(2)
        trial.reset()
        assertThat(trial.greyTaps).isEqualTo(0)
    }
}
