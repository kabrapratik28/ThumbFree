package io.github.kabrapratik28.thumbfree.app

import android.content.Intent
import android.graphics.Rect
import android.os.Looper
import com.google.common.truth.Truth.assertThat
import com.google.mlkit.genai.common.FeatureStatus
import io.github.kabrapratik28.thumbfree.R
import io.github.kabrapratik28.thumbfree.a11y.FakeEditorPort
import io.github.kabrapratik28.thumbfree.a11y.Pin
import io.github.kabrapratik28.thumbfree.a11y.Replaced
import io.github.kabrapratik28.thumbfree.a11y.Sparkle
import io.github.kabrapratik28.thumbfree.core.insert.Surrounding
import io.github.kabrapratik28.thumbfree.core.text.CleanupCheck
import io.github.kabrapratik28.thumbfree.core.text.CleanupStyle
import io.github.kabrapratik28.thumbfree.ui.CleanupActivity
import kotlinx.coroutines.Dispatchers
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class CleanupTest {
    private val app = RuntimeEnvironment.getApplication()
    private val port = FakeEditorPort()
    private val pin = Pin("com.example", 1, "n1", 1)
    private val take = " yes yes see you at six no seven" // as typed after "Hi Maya!"
    private var on = true
    private var drawn: Sparkle? = null
    private val toasts = mutableListOf<Int>()
    private val started = mutableListOf<Intent>()
    private val cleanup = Cleanup(
        app, port, enabled = { on }, draw = { drawn = it }, anchor = { Rect(900, 1500, 1032, 1632) },
        io = Dispatchers.Unconfined, toast = { toasts += it }, start = { started += it },
    )

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    // The sparkle comes after a typed take while Clean up is on, and goes with a new take; off, or without Gemini Nano
    // on the phone, it never comes.
    @Test
    fun aTypedTakeOffersTheSparkle() {
        cleanup.offer(pin, take)
        assertThat(drawn).isEqualTo(Sparkle.OFFER)
        cleanup.clear()
        assertThat(drawn).isNull()

        on = false
        cleanup.offer(pin, take)
        assertThat(drawn).isNull()
        on = true
        cleanup.status = FeatureStatus.UNAVAILABLE
        cleanup.offer(pin, take)
        assertThat(drawn).isNull()
    }

    // A tap opens the card with the default style, a hold with the styles; the sparkle turns meanwhile, and a tap on it
    // then closes the card. The card gets the take's words without the space it was typed with.
    @Test
    fun aTapOpensTheCardAndAHoldTheStyles() {
        cleanup.offer(pin, take)
        cleanup.tap(hold = false)
        assertThat(started.single().getBooleanExtra(CleanupActivity.EXTRA_STYLES, true)).isFalse()
        assertThat(drawn).isEqualTo(Sparkle.WORKING)
        assertThat(cleanup.take()).isEqualTo("yes yes see you at six no seven")

        cleanup.tap(hold = false)
        assertThat(drawn).isEqualTo(Sparkle.OFFER)
        assertThat(cleanup.take()).isNull()

        cleanup.tap(hold = true)
        assertThat(started.last().getBooleanExtra(CleanupActivity.EXTRA_STYLES, false)).isTrue()
    }

    // Only the take's own words change: the tidy text, spaced as the take was, replaces them; Undo puts the take back.
    @Test
    fun aWriteReplacesTheTakeAndUndoPutsItBack() {
        port.surroundings += Surrounding("Hi Maya!$take", "", 0)
        cleanup.offer(pin, take)
        var wrote: Boolean? = null
        cleanup.write("See you at 7.") { wrote = it }
        idle()
        assertThat(wrote).isTrue()
        assertThat(port.replaced).containsExactly(take to " See you at 7.")
        assertThat(drawn).isEqualTo(Sparkle.UNDO)

        cleanup.tap(hold = false)
        idle()
        assertThat(port.replaced.last()).isEqualTo(" See you at 7." to take)
        assertThat(drawn).isEqualTo(Sparkle.OFFER)
        assertThat(toasts).isEmpty()
    }

    // The owner typed after the take, the field became a password field, or the write didn't read back: nothing more is
    // written, a toast says so, and the sparkle goes.
    @Test
    fun aChangedFieldGetsNothing() {
        port.surroundings += Surrounding("Hi Maya!$take, ok", "", 0)
        cleanup.offer(pin, take)
        cleanup.write("See you at 7.")
        idle()
        assertThat(port.replaced).isEmpty()
        assertThat(toasts).containsExactly(R.string.cleanup_text_changed)
        assertThat(drawn).isNull()

        port.password = true
        port.surroundings += Surrounding("Hi Maya!$take", "", 0)
        cleanup.offer(pin, take)
        cleanup.write("See you at 7.")
        idle()
        assertThat(port.replaced).isEmpty()

        port.password = false
        port.replaceAnswers += Replaced.UNSURE
        cleanup.offer(pin, take)
        cleanup.write("See you at 7.")
        idle()
        assertThat(toasts.last()).isEqualTo(R.string.cleanup_check_text)
        assertThat(drawn).isNull()
    }

    // Focus on another field ends the offer, unless the card is up: it can hide the bubble for its moment. A new
    // session of the same field keeps it.
    @Test
    fun anotherFieldEndsTheOffer() {
        cleanup.offer(pin, take)
        cleanup.followFocus(pin.copy(generation = 2))
        assertThat(drawn).isEqualTo(Sparkle.OFFER)
        cleanup.tap(hold = false)
        cleanup.followFocus(pin.copy(nodeKey = "n2", generation = 2))
        cleanup.bubbleHidden()
        assertThat(drawn).isEqualTo(Sparkle.WORKING)
        cleanup.tap(hold = false) // the card closes
        cleanup.followFocus(null) // the field has no session yet
        assertThat(drawn).isEqualTo(Sparkle.OFFER)
        cleanup.followFocus(pin.copy(nodeKey = "n2", generation = 2))
        assertThat(drawn).isNull()
    }

    // The card's answer is written once the card has closed, not while it is up.
    @Test
    fun theCardsAnswerIsWrittenOnceTheCardCloses() {
        val card = Robolectric.buildActivity(android.app.Activity::class.java).get()
        port.surroundings += Surrounding("Hi Maya!$take", "", 0)
        cleanup.offer(pin, take)
        cleanup.tap(hold = false)
        assertThat(cleanup.attach(card)).isTrue()
        cleanup.deliver("See you at 7.")
        idle()
        assertThat(port.replaced).isEmpty()

        cleanup.detach(card)
        idle()
        assertThat(port.replaced).containsExactly(take to " See you at 7.")
        assertThat(drawn).isEqualTo(Sparkle.UNDO)
    }

    // A web page's field loses its input session while the card is up and starts a new one when it closes: the write
    // goes through the new session of the same field, and so does Undo.
    @Test
    fun aFieldThatLostItsSessionIsWrittenInItsNewOne() {
        port.surroundings += Surrounding("Hi Maya!$take", "", 0)
        cleanup.offer(pin, take)
        port.field = 2
        port.pin = pin.copy(generation = 2)
        cleanup.write("See you at 7.")
        idle()
        assertThat(port.replaced).containsExactly(take to " See you at 7.")
        cleanup.tap(hold = false)
        idle()
        assertThat(port.replaced.last()).isEqualTo(" See you at 7." to take)
        assertThat(drawn).isEqualTo(Sparkle.OFFER)
    }

    // The card's answer is checked against the take before anything is written.
    @Test
    fun theCardsAnswerIsChecked() {
        cleanup.offer(pin, take)
        assertThat(cleanup.check(CleanupStyle.CLEAN, "See you at 7.")).isEqualTo(CleanupCheck.Verdict.Ok("See you at 7."))
        assertThat(cleanup.check(CleanupStyle.CLEAN, "Sure! Here is a poem about cats and dogs.")).isInstanceOf(CleanupCheck.Verdict.Rejected::class.java)
        cleanup.clear()
        assertThat(cleanup.check(CleanupStyle.CLEAN, "See you at 7.")).isInstanceOf(CleanupCheck.Verdict.Rejected::class.java)
    }
}
