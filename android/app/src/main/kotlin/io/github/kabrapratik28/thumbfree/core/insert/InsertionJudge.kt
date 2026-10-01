package io.github.kabrapratik28.thumbfree.core.insert

/** A window of text read from a field: [before]/[after] the cursor, and [offset], the position within the
 * field where [before] starts (0 when it reaches the true start of the field, -1 when the editor does not know). */
data class Surrounding(val before: String, val after: String, val offset: Int)

/** VERIFIED: the payload verifiably landed. MISS: it did not. UNREADABLE: the reads cannot tell us either way. */
enum class Verdict { VERIFIED, MISS, UNREADABLE }

/**
 * Decides, from reads of a field taken before and after a single commitText, whether a payload verifiably
 * landed. Never justifies a second write: anything short of proof comes back MISS or UNREADABLE.
 */
object InsertionJudge {
    private const val TAIL = 32

    /**
     * VERIFIED when new.before != old.before, new.before ends with old.before.takeLast(32) + payload, and
     * new.after starts with old.after.take(32), in a complete window or one at offset -1. new.before, when shorter
     * than requestedBefore, counts as complete only when new.offset is 0. Offset -1 means the editor does not know
     * (InputConnection's default getSurroundingText, which Compose uses), so a short field there can never read as
     * complete, and all three checks passing is proof enough; a known offset other than 0 keeps the rule. MISS only
     * when a complete window shows the field unchanged at a known cursor (both offsets known). Anything else, such as
     * a payload cut short by a length limit or altered by the editor, or identical reads at offset -1, is UNREADABLE:
     * a MISS offers Insert here, which would type what landed a second time. old is only ever read through
     * takeLast(32), so its own completeness against requestedBefore is irrelevant
     * (old is typically read at a different, smaller request size than requestedBefore, which matches new's
     * request). Either read null: UNREADABLE.
     */
    fun judgeCommit(old: Surrounding?, new: Surrounding?, payload: String, requestedBefore: Int): Verdict {
        if (old == null || new == null) return Verdict.UNREADABLE
        val complete = isBeforeWindowComplete(new, requestedBefore)
        val landed = new.before != old.before &&
            new.before.endsWith(old.before.takeLast(TAIL) + payload) &&
            new.after.startsWith(old.after.take(TAIL))
        return when {
            landed && (complete || new.offset == -1) -> Verdict.VERIFIED
            complete && unchanged(old, new) -> Verdict.MISS
            else -> Verdict.UNREADABLE
        }
    }

    /**
     * VERIFIED when newText equals oldText with payload inserted exactly once at some index; otherwise, or on a null
     * read, UNREADABLE. Never MISS: node text has no cursor, so even an unchanged read cannot prove that nothing
     * landed (a selected word replaced by the same word leaves it unchanged), and a MISS would offer Insert here.
     */
    fun judgeNodeText(oldText: String?, newText: String?, payload: String): Verdict {
        if (oldText == null || newText == null) return Verdict.UNREADABLE
        if (newText.length != oldText.length + payload.length) return Verdict.UNREADABLE
        // Node text can run to 100,000 characters, so compare in place. An insert at i leaves oldText[0, i) as a common
        // prefix and oldText[i, end) as a common suffix, so i lies between the two common lengths.
        val n = oldText.length
        var prefix = 0
        while (prefix < n && oldText[prefix] == newText[prefix]) prefix++
        var suffix = 0
        while (suffix < n && oldText[n - 1 - suffix] == newText[newText.length - 1 - suffix]) suffix++
        // Across this range newText repeats every payload.length characters (newText[i] == oldText[i] ==
        // newText[i + payload.length]), so later candidates repeat the first payload.length + 1.
        // ponytail: still O(payload.length^2) comparisons when the payload itself repeats with a short period, which
        // dictated speech does not; a KMP search for the payload over this range would make it linear.
        for (i in n - suffix..minOf(prefix, n - suffix + payload.length)) {
            if (newText.startsWith(payload, i)) return Verdict.VERIFIED
        }
        return Verdict.UNREADABLE
    }

    /**
     * The field provably reads as it did before the write: both offsets are known and the cursor has not moved
     * (repeated text can look the same at a moved cursor, so identical reads at offset -1 prove nothing), the text
     * after the cursor is the same (both reads ask for the same 64 characters), and the text before it agrees where
     * both windows reach (those are read at different lengths).
     */
    private fun unchanged(old: Surrounding, new: Surrounding): Boolean {
        val before = minOf(old.before.length, new.before.length)
        val cursorStayed = old.offset >= 0 && new.offset >= 0 &&
            old.offset + old.before.length == new.offset + new.before.length
        return cursorStayed && old.before.takeLast(before) == new.before.takeLast(before) && old.after == new.after
    }

    private fun isBeforeWindowComplete(window: Surrounding, requestedBefore: Int): Boolean =
        window.before.length >= requestedBefore || window.offset == 0
}
