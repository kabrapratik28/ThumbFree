package io.github.kabrapratik28.thumbfree.ui

import com.google.common.truth.Truth.assertThat
import io.github.kabrapratik28.thumbfree.data.Dictation
import io.github.kabrapratik28.thumbfree.data.Status
import org.junit.Test

class HistoryScreenTest {
    private fun row(status: Status, text: String?, insertedText: String? = null, partialText: String? = null) =
        Dictation(1, "s1", 0, 0, "recordings/s1.wav", "m", status, null, null, text, partialText, 0, insertedText, null, false)

    // After "I said." the formatter typed " Hello. " for "hello.": history shows and copies what landed, without its
    // spacing.
    @Test
    fun insertedRowsUseTheTypedText() {
        for (status in listOf(Status.INSERTED, Status.UNVERIFIED)) {
            val row = row(status, "hello.", insertedText = " Hello. ")
            assertThat(row.finalText).isEqualTo("Hello.")
            assertThat(row.copyText).isEqualTo("Hello.")
        }
        assertThat(row(Status.INSERTED, "hello.").copyText).isEqualTo("hello.") // nothing recorded as typed
        assertThat(row(Status.NOT_INSERTED, "hello.", insertedText = " Hello.").copyText).isEqualTo("hello.")
        val partial = row(Status.FAILED, null, partialText = "Hel")
        assertThat(partial.finalText).isNull() // the row labels it as partial
        assertThat(partial.copyText).isEqualTo("Hel")
    }

    // A take transcribed again from history shows that new transcript, whatever was typed before; Copy copies it.
    @Test
    fun retranscribedRowsShowTheNewText() {
        val typed = row(Status.INSERTED, text = "New text.", insertedText = "Old text.").copy(retranscribed = true)

        assertThat(typed.finalText).isEqualTo("New text.") // not what was typed before
        assertThat(typed.copyText).isEqualTo("New text.")
        assertThat(row(Status.INSERTED, text = "New text.", insertedText = "Old text.").finalText).isEqualTo("Old text.")
    }

    // A NO_SPEECH row never shows or copies text, whatever it holds (rows saved by older builds kept their chunk text).
    // Transcribe stays: the WAV is kept.
    @Test
    fun noSpeechRowShowsNoText() {
        val row = row(Status.NO_SPEECH, "Yeah.", insertedText = "Yeah.", partialText = "Yeah.")

        assertThat(row.finalText).isNull()
        assertThat(row.copyText).isNull()
    }

    // An INTERRUPTED take died before its speech decision. Recovery now clears its text, but rows recovered by older
    // builds kept their chunk text: it never shows or copies.
    @Test
    fun interruptedRowShowsNoText() {
        val row = row(Status.INTERRUPTED, "Yeah.", partialText = "Yeah.")

        assertThat(row.finalText).isNull()
        assertThat(row.copyText).isNull()
    }

    // A take in progress shows no text and offers no Copy until it has ended with speech: its chunk text is unconfirmed.
    @Test
    fun liveRowShowsNoText() {
        for (status in Status.entries.filterNot { it.terminal }) {
            val row = row(status, "Text.", partialText = "Te")

            assertThat(row.finalText).isNull()
            assertThat(row.copyText).isNull()
        }
    }

    // Search matches the text a row shows, in any case; a take's hidden (unconfirmed) text never matches, so search can't
    // reveal it.
    @Test
    fun searchMatchesOnlyShownText() {
        val shown = row(Status.INSERTED, "Meet at the Café", insertedText = " Meet at the Café ")
        assertThat(shown.matches("café")).isTrue()
        assertThat(shown.matches("  MEET ")).isTrue()
        assertThat(shown.matches("lunch")).isFalse()
        assertThat(row(Status.FAILED, null, partialText = "half a sent").matches("half")).isTrue()

        for (status in Status.entries.filterNot { it.terminal } + Status.NO_SPEECH + Status.INTERRUPTED) {
            assertThat(row(status, "Secret words", partialText = "Secret").matches("secret")).isFalse()
        }
        assertThat(row(Status.NO_SPEECH, "Secret words").matches("")).isTrue() // no query shows every row
    }

    @Test
    fun durationsReadLikeAPlayer() {
        assertThat(duration(3_900)).isEqualTo("0:03")
        assertThat(duration(754_000)).isEqualTo("12:34")
        assertThat(duration(3_725_000)).isEqualTo("1:02:05")
    }
}
