package io.github.kabrapratik28.thumbfree.core.text

/**
 * The checks a model's clean-up must pass before it may replace the user's words (issue #1, part 4): anything that could
 * change the message's meaning keeps the words as they are. Pure functions; the caller writes nothing on a rejection.
 */
object CleanupCheck {
    /** [Ok] carries the text to write; [Same] means nothing to tidy; [Rejected] says which check failed (a code, no text). */
    sealed interface Verdict {
        data class Ok(val text: String) : Verdict
        data object Same : Verdict
        data class Rejected(val reason: String) : Verdict
    }

    private val CHATTER = listOf("sure", "here is", "here's", "i can't", "i cannot", "as an ai", "certainly", "of course")
    private val NEGATIONS = listOf("not", "never", "no longer", "cannot")

    // Words Clean may drop or turn into marks and digits: function words, fillers, self-correction markers, spoken
    // punctuation and number words. Every other word of the take must survive a Clean (one may go, for a correction).
    private val FUNCTION_WORDS = setOf(
        "a", "an", "the", "and", "or", "but", "so", "to", "of", "in", "on", "at", "by", "for", "with", "from", "is", "are",
        "was", "were", "be", "been", "am", "it", "this", "that", "i", "you", "he", "she", "we", "they", "me", "my", "your",
        "our", "their", "its", "do", "does", "did", "have", "has", "had", "will", "would", "can", "could", "should",
        "just", "really", "very", "then", "there", "here", "um", "uh", "er", "erm", "hmm", "like", "no", "sorry", "wait",
        "actually", "mean", "know", "comma", "period", "question", "mark", "exclamation", "colon", "new", "line",
        "paragraph", "dot",
    )
    private val NUMBER_WORDS = setOf(
        "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven", "twelve",
        "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen", "twenty", "thirty", "forty",
        "fifty", "sixty", "seventy", "eighty", "ninety", "hundred", "thousand", "million", "billion", "percent", "dollar",
        "dollars", "cents", "o'clock", "pm", "first", "second", "third", "fourth", "fifth", "sixth", "seventh", "eighth",
        "ninth", "tenth", "half", "quarter",
    )

    fun check(take: String, output: String?, style: CleanupStyle): Verdict {
        val out = tidy(output ?: return Verdict.Rejected("empty"))
        val raw = take.trim()
        if (out.isEmpty()) return Verdict.Rejected("empty")
        if (out == raw) return Verdict.Same
        val lowerOut = out.lowercase()
        if (CHATTER.any { lowerOut.startsWith(it) && !raw.lowercase().startsWith(it) }) return Verdict.Rejected("chatter")
        val takeWords = words(raw)
        val outWords = words(out)
        val known = takeWords.toSet()
        val added = outWords.count { it !in known && it.none(Char::isDigit) }
        val tone = style == CleanupStyle.FRIENDLY || style == CleanupStyle.PROFESSIONAL || style == CleanupStyle.SIMPLE
        val addedLimit = if (tone) maxOf(4, outWords.size / 2) else maxOf(2, outWords.size / 5)
        if (added > addedLimit) return Verdict.Rejected("new_words")
        val keepAtLeast = if (style == CleanupStyle.SHORTER) 0.25 else 0.4
        if (outWords.size < takeWords.size * keepAtLeast) return Verdict.Rejected("dropped")
        if (style == CleanupStyle.CLEAN) {
            val meaningful = takeWords.filter { it !in FUNCTION_WORDS && it !in NUMBER_WORDS && it.none(Char::isDigit) }.toSet()
            val have = outWords.toSet()
            if (meaningful.count { it !in have } > maxOf(1.0, meaningful.size * 0.3)) return Verdict.Rejected("dropped_words")
        }
        if (script(raw) != script(out)) return Verdict.Rejected("script")
        if (negated(raw) && !negated(out)) return Verdict.Rejected("negation")
        if (!isSubsequence(raw.filter(Char::isDigit), out.filter(Char::isDigit))) return Verdict.Rejected("digits")
        return Verdict.Ok(out)
    }

    /**
     * The text to write in place of [take], formatted against the text before it as the take itself was (spacing, a
     * sentence's capital); null when [before], the text before the cursor, no longer ends with the take.
     */
    fun replacement(before: String, take: String, cleaned: String, after: String, inputType: Int): String? {
        if (take.isEmpty() || !before.endsWith(take)) return null
        val prefix = before.dropLast(take.length)
        val sentenceStart = prefix.trimEnd(' ').let { it.isEmpty() || it.last() in ".!?\n" }
        return CursorFormatter.payload(cleaned, prefix, after, sentenceStart, inputType)
    }

    /** The model's answer without an echoed label or one pair of surrounding quotes. */
    private fun tidy(output: String): String {
        var out = output.trim()
        if (out.startsWith("Cleaned text:", ignoreCase = true)) out = out.substring("Cleaned text:".length).trim()
        if (out.length >= 2 && out.first() == '"' && out.last() == '"') out = out.substring(1, out.length - 1).trim()
        return out
    }

    private fun words(text: String): List<String> =
        text.lowercase().split(Regex("[^\\p{L}\\p{N}']+")).map { it.trim('\'') }.filter { it.isNotEmpty() }

    private fun negated(text: String): Boolean {
        val lower = " " + text.lowercase().replace('’', '\'') + " "
        return lower.contains("n't") || NEGATIONS.any { Regex("[^\\p{L}]$it[^\\p{L}]").containsMatchIn(lower) }
    }

    /** The script most letters are in (Latin, Cyrillic, Devanagari…), so a reply in another alphabet is caught. */
    private fun script(text: String): Character.UnicodeScript? =
        text.codePoints().filter(Character::isLetter).boxed().toList()
            .groupingBy { Character.UnicodeScript.of(it) }.eachCount().maxByOrNull { it.value }?.key

    private fun isSubsequence(small: String, big: String): Boolean {
        var i = 0
        for (c in big) if (i < small.length && small[i] == c) i++
        return i == small.length
    }
}
