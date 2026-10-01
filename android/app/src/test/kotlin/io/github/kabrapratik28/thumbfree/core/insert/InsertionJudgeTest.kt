package io.github.kabrapratik28.thumbfree.core.insert

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class InsertionJudgeTest {
    @Test
    fun commitVerified() {
        val old = Surrounding("Hello ", "", 0)
        val new = Surrounding("Hello world", "", 0)
        assertThat(InsertionJudge.judgeCommit(old, new, "world", 69)).isEqualTo(Verdict.VERIFIED)
    }

    @Test
    fun commitVerifiedWithTextAfter() {
        val old = Surrounding("Hi ", " end", 0)
        val new = Surrounding("Hi there", " end", 0)
        assertThat(InsertionJudge.judgeCommit(old, new, "there", 69)).isEqualTo(Verdict.VERIFIED)
    }

    @Test
    fun unchangedIsMiss() {
        val old = Surrounding("Hello world", "", 0)
        val new = Surrounding("Hello world", "", 0)
        assertThat(InsertionJudge.judgeCommit(old, new, "world", 69)).isEqualTo(Verdict.MISS)
    }

    // The two reads ask for different lengths, so a long unchanged field is compared where both windows reach.
    @Test
    fun unchangedLongFieldIsMiss() {
        val old = Surrounding("x".repeat(75), " end", 4_920) // read at text.length + 64
        val new = Surrounding("x".repeat(76), " end", 4_919) // read at payload.length + 64
        assertThat(InsertionJudge.judgeCommit(old, new, " hello there", 76)).isEqualTo(Verdict.MISS)
    }

    // A length limit cuts a long payload short: 5,000 characters in a single-line EditText, or an app's maxLength.
    // The field changed, and Insert here would type the part that landed a second time: unreadable, never a miss.
    @Test
    fun partialLandingIsUnreadable() {
        val payload = " hello there"
        val long = Surrounding("x".repeat(75), "", 4_920) // the cursor at 4,995
        val capped = Surrounding("x".repeat(71) + " hell", "", 4_924) // five characters landed, the cursor at 5,000
        assertThat(InsertionJudge.judgeCommit(long, capped, payload, payload.length + 64)).isEqualTo(Verdict.UNREADABLE)

        val short = Surrounding("I said ", "", 0)
        assertThat(InsertionJudge.judgeCommit(short, Surrounding("I said hello", "", 0), "hello world", 75))
            .isEqualTo(Verdict.UNREADABLE)

        // Repeated text reads the same at the cursor, but the cursor moved.
        assertThat(InsertionJudge.judgeCommit(Surrounding("aaaa", "", 0), Surrounding("aaaaa", "", 0), "ab", 66))
            .isEqualTo(Verdict.UNREADABLE)
    }

    // Both reads ask for the same 64 characters after the cursor, so an untouched field shows the same text there.
    // Text that landed after a cursor that stayed put changes it.
    @Test
    fun textAfterTheCursorMustMatchForAMiss() {
        assertThat(InsertionJudge.judgeCommit(Surrounding("Hi ", "", 0), Surrounding("Hi ", "there", 0), "there", 69))
            .isEqualTo(Verdict.UNREADABLE)
    }

    @Test
    fun nullReadIsUnreadable() {
        val old = Surrounding("Hello ", "", 0)
        val new = Surrounding("Hello world", "", 0)
        assertThat(InsertionJudge.judgeCommit(old, null, "world", 69)).isEqualTo(Verdict.UNREADABLE)
        assertThat(InsertionJudge.judgeCommit(null, new, "world", 69)).isEqualTo(Verdict.UNREADABLE)
    }

    @Test
    fun truncatedWindowNeedsOffsetZero() {
        val old = Surrounding("abc", "", 0)
        assertThat(InsertionJudge.judgeCommit(old, Surrounding("abcdef", "", 5), "def", 69))
            .isEqualTo(Verdict.UNREADABLE)
        assertThat(InsertionJudge.judgeCommit(old, Surrounding("abcdef", "", 0), "def", 69))
            .isEqualTo(Verdict.VERIFIED)
    }

    // Compose, and any editor on InputConnection's default getSurroundingText, reports offset -1 (unknown), so a short
    // field's window never reads as complete. Positive evidence still verifies; anything else is unreadable, even in a
    // complete window: an unknown cursor cannot prove that nothing landed.
    @Test
    fun unknownOffsetVerifiesWhatItShows() {
        val old = Surrounding("Hello ", "", -1)
        assertThat(InsertionJudge.judgeCommit(old, Surrounding("Hello world", "", -1), "world", 69))
            .isEqualTo(Verdict.VERIFIED)
        assertThat(InsertionJudge.judgeCommit(old, Surrounding("Hello ", "", -1), "world", 69))
            .isEqualTo(Verdict.UNREADABLE)
        val full = Surrounding("x".repeat(69), "", -1) // reaches requestedBefore
        assertThat(InsertionJudge.judgeCommit(full, full, "world", 69)).isEqualTo(Verdict.UNREADABLE)
    }

    // Dictating "a" into a long run of "a" in a Compose field: after a good insert both windows at offset -1 read the
    // same as before, so without a known cursor the judge must not call it a miss (Insert here would add a second "a").
    @Test
    fun runOfTheSameLetterAtUnknownOffsetIsUnreadable() {
        val run = Surrounding("a".repeat(65), "a".repeat(64), -1)
        assertThat(InsertionJudge.judgeCommit(run, run, "a", 65)).isEqualTo(Verdict.UNREADABLE)
    }

    @Test
    fun truncatedOldReadStillVerifies() {
        // CursorFormatter's payload is routinely longer than the raw text (leading space, capitalization), so
        // old is read at text.length + 64 while requestedBefore here is payload.length + 64. Once the field has
        // more than about text.length + 64 characters before the cursor (the ordinary case), old's own window
        // can never reach requestedBefore and old.offset is not 0. That must not block a verified insert: only
        // new's completeness gates UNREADABLE.
        val payload = " Hello"
        val requestedBefore = payload.length + 64
        val old = Surrounding("f".repeat(69), "", 1) // 70 filler chars before the cursor, read at 69 (text.length + 64)
        val new = Surrounding("f".repeat(64) + payload, "", 6) // 76 chars before the cursor now, read at requestedBefore
        assertThat(InsertionJudge.judgeCommit(old, new, payload, requestedBefore)).isEqualTo(Verdict.VERIFIED)
    }

    // The editor changed what landed (here its capitalization). Something is there, so Insert here would repeat it.
    @Test
    fun editorChangedTheTextIsUnreadable() {
        val old = Surrounding("Hello ", "", 0)
        val new = Surrounding("Hello World", "", 0)
        assertThat(InsertionJudge.judgeCommit(old, new, "world", 69)).isEqualTo(Verdict.UNREADABLE)
    }

    @Test
    fun alreadyPresentNeedsAChange() {
        val old = Surrounding("say world", "", 0)
        assertThat(InsertionJudge.judgeCommit(old, Surrounding("say world", "", 0), "world", 69))
            .isEqualTo(Verdict.MISS)
        assertThat(InsertionJudge.judgeCommit(old, Surrounding("say worldworld", "", 0), "world", 69))
            .isEqualTo(Verdict.VERIFIED)
    }

    @Test
    fun nodeSpliceAnywhere() {
        assertThat(InsertionJudge.judgeNodeText("Hello world", "Hello big world", "big "))
            .isEqualTo(Verdict.VERIFIED)
    }

    @Test
    fun nodeDoubleInsertIsUnreadable() {
        assertThat(InsertionJudge.judgeNodeText("Hello world", "Hello big big world", "big "))
            .isEqualTo(Verdict.UNREADABLE)
    }

    // Node text has no cursor, so it never proves that nothing landed: a selected "world" replaced by "world" leaves it
    // the same. A payload cut short by a length limit is unreadable too.
    @Test
    fun nodeNeverMisses() {
        assertThat(InsertionJudge.judgeNodeText("I said ", "I said hello", "hello world")).isEqualTo(Verdict.UNREADABLE)
        assertThat(InsertionJudge.judgeNodeText("Hello world", "Hello world", "world")).isEqualTo(Verdict.UNREADABLE)
    }

    @Test
    fun nodeNullIsUnreadable() {
        assertThat(InsertionJudge.judgeNodeText(null, "Hello world", "world")).isEqualTo(Verdict.UNREADABLE)
        assertThat(InsertionJudge.judgeNodeText("Hello ", null, "world")).isEqualTo(Verdict.UNREADABLE)
    }

    // Dictating the word that follows the cursor: the splice is not at the end of the common prefix.
    @Test
    fun nodeRepeatedWordAfterCursor() {
        assertThat(InsertionJudge.judgeNodeText("Hello world", "Hello world world", "world "))
            .isEqualTo(Verdict.VERIFIED)
    }

    // Gmail and web editors fall back to node text, which can run to about 100,000 characters.
    @Test
    fun nodeLongTextIsFast() {
        val old = "word ".repeat(20_000)
        val at = old.length - 10
        val new = old.substring(0, at) + " Hello there" + old.substring(at)

        // The fastest of three, so one pause of a busy machine cannot fail it.
        val fastestMs = (1..3).minOf {
            val start = System.nanoTime()
            val verdict = InsertionJudge.judgeNodeText(old, new, " Hello there")
            val ms = (System.nanoTime() - start) / 1_000_000
            assertThat(verdict).isEqualTo(Verdict.VERIFIED)
            ms
        }

        assertThat(fastestMs).isLessThan(50L)
    }

    // Text that repeats around the cursor makes every position a candidate, and a long payload that matches it up to
    // its last character is compared almost to the end at each one. The curly quote, common in real text, stores the
    // node text as UTF-16, so the JVM compares the ASCII payload a character at a time, as the phone always does.
    @Test
    fun nodeRepeatedTextNoSpliceIsFast() {
        val old = "’" + "a".repeat(99_999)
        val new = "’" + "a".repeat(104_999) // changed by something other than the payload
        val payload = "a".repeat(4_999) + "b"

        val fastestMs = (1..3).minOf {
            val start = System.nanoTime()
            val verdict = InsertionJudge.judgeNodeText(old, new, payload)
            val ms = (System.nanoTime() - start) / 1_000_000
            assertThat(verdict).isEqualTo(Verdict.UNREADABLE)
            ms
        }

        assertThat(fastestMs).isLessThan(50L)
    }
}
