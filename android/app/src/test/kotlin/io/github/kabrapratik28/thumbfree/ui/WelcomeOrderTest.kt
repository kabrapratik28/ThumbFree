package io.github.kabrapratik28.thumbfree.ui

import com.google.common.truth.Truth.assertThat
import org.junit.Test

// The welcome's four steps, where a first run left midway resumes, and how the microphone's answer is taken.
class WelcomeOrderTest {
    // Get ready (what it does, the language, the wait for the model), the try, the bubble, all set.
    @Test
    fun fourStepsInOrder() {
        assertThat(WelcomeStep.entries).containsExactly(
            WelcomeStep.WELCOME, WelcomeStep.TRY, WelcomeStep.SERVICE, WelcomeStep.READY,
        ).inOrder()
    }

    // A step kept by name resumes there. Earlier orders had a microphone step, which the try now asks in its place and
    // which came after the download started: a run left there resumes on the first step, which shows that download.
    // Their other names are steps here too. An earlier build kept an index in its order (what it is, how it works, the
    // microphone, the bubble, ready): the first three are the first step now. That index's key is shared with the master
    // build's own order (what it is, the microphone, the bubble), which loses: the owner's phone had the later one, and
    // nothing older is in use. A name or index no build knows starts from the first step.
    @Test
    fun aBookmarkFromAnyEarlierOrderResumesOnTheRightStep() {
        for (step in WelcomeStep.entries) assertThat(resumeAt(step.name, 0)).isEqualTo(step)
        assertThat(resumeAt("MIC", 3)).isEqualTo(WelcomeStep.WELCOME) // a name wins over the index
        assertThat(resumeAt("TRY", 3)).isEqualTo(WelcomeStep.TRY)

        assertThat((0..4).map { resumeAt(null, it) }).containsExactly(
            WelcomeStep.WELCOME, WelcomeStep.WELCOME, WelcomeStep.WELCOME, WelcomeStep.SERVICE, WelcomeStep.READY,
        ).inOrder()
        assertThat(resumeAt("HOW", 1)).isEqualTo(WelcomeStep.WELCOME)
        assertThat(resumeAt(null, 9)).isEqualTo(WelcomeStep.WELCOME)
        assertThat(resumeAt(null, -1)).isEqualTo(WelcomeStep.WELCOME)
    }

    // Android's microphone answer: allowed; refused but Android would ask again; refused with no rationale on the first
    // answer ever, which may be a question dismissed with a tap outside, so Allow microphone asks again; refused with no
    // rationale once it was asked before ("don't ask again", or a device policy, where Android refuses at once): only App
    // info, so nobody is stuck on a button that does nothing.
    @Test
    fun aDismissedMicrophoneQuestionIsAskedAgainButAFinalRefusalOpensAppInfo() {
        assertThat(micRefusal(granted = true, rationale = false, askedBefore = true)).isNull()
        assertThat(micRefusal(granted = false, rationale = true, askedBefore = true)).isEqualTo(MicRefusal.DENIED)
        assertThat(micRefusal(granted = false, rationale = false, askedBefore = false)).isEqualTo(MicRefusal.DENIED)
        assertThat(micRefusal(granted = false, rationale = false, askedBefore = true)).isEqualTo(MicRefusal.BLOCKED)
    }

    // Allow microphone refused without Android's question, so only App info can grant it: App info at once, rather than
    // a tap that seems to do nothing. Android runs its request even then, so the time tells: a refusal back within
    // 700 ms was never asked, as nobody reads and answers the question that fast, while Android's activity starting
    // cold can take over 300 ms. Not after a question the user answered, nor for the bubble's tap, which the screen
    // answers with Microphone is off, nor for a request this activity never sent (recreated while Android asked).
    @Test
    fun aRefusalWithoutTheQuestionOpensAppInfoAtOnce() {
        val asked = 5_000_000L // the clock when the request went out
        fun atOnce(refusal: MicRefusal?, after: Long, fromBubble: Boolean = false, askedAt: Long = asked) =
            appInfoAtOnce(refusal, fromBubble, askedAt, answeredAt = asked + after)
        assertThat(atOnce(MicRefusal.BLOCKED, after = 120)).isTrue()
        assertThat(atOnce(MicRefusal.BLOCKED, after = 450)).isTrue() // Android's activity starting cold
        assertThat(atOnce(MicRefusal.BLOCKED, after = 699)).isTrue()
        assertThat(atOnce(MicRefusal.BLOCKED, after = 700)).isFalse()
        assertThat(atOnce(MicRefusal.BLOCKED, after = 2_500)).isFalse() // "Don't allow" on the question
        assertThat(atOnce(MicRefusal.BLOCKED, after = 120, fromBubble = true)).isFalse()
        assertThat(atOnce(MicRefusal.DENIED, after = 120)).isFalse()
        assertThat(atOnce(null, after = 120)).isFalse()
        assertThat(atOnce(MicRefusal.BLOCKED, after = 120, askedAt = 0)).isFalse()
    }

    // One microphone request at a time: a second tap while Android's question is out does nothing, as a second request
    // comes back refused at once (it could open App info over the question, or say the microphone is off while it is
    // still up). Otherwise App info when only it can grant, else ask.
    @Test
    fun aSecondTapWhileAndroidAsksDoesNothing() {
        for (refusal in listOf(null, MicRefusal.DENIED, MicRefusal.BLOCKED)) {
            assertThat(micTap(asking = true, refusal)).isEqualTo(MicTap.WAIT)
        }
        assertThat(micTap(asking = false, MicRefusal.BLOCKED)).isEqualTo(MicTap.APP_INFO)
        assertThat(micTap(asking = false, MicRefusal.DENIED)).isEqualTo(MicTap.ASK)
        assertThat(micTap(asking = false, null)).isEqualTo(MicTap.ASK)
    }
}
