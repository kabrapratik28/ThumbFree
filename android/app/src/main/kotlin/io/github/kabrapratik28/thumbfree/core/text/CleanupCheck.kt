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
    private val NEGATIONS = listOf("not", "never", "no longer", "cannot", "unable", "unavailable", "nothing", "none", "nobody", "nowhere", "neither")

    // Words Clean may drop or turn into marks and digits: function words, fillers, self-correction markers, spoken
    // punctuation and number words. Every other word of the take must survive a Clean (one may go, for a correction).
    private val FUNCTION_WORDS = setOf(
        "a", "an", "the", "and", "or", "but", "so", "to", "of", "in", "on", "at", "by", "for", "with", "from", "is", "are",
        "was", "were", "be", "been", "am", "it", "this", "that", "i", "you", "he", "she", "we", "they", "me", "my", "your",
        "our", "their", "its", "do", "does", "did", "have", "has", "had", "will", "would", "can", "could", "should",
        "just", "really", "very", "then", "there", "here", "um", "uh", "er", "erm", "hmm", "like", "no", "wait",
        "actually", "mean", "know", "comma", "period", "question", "mark", "exclamation", "colon", "new", "line",
        "paragraph", "dot",
    )
    // Words a correction's final version is checked for: the dates, times and numbers a wrong pick would change.
    private val WHEN_WORDS = setOf(
        "monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday", "today", "tonight", "tomorrow",
        "yesterday", "morning", "afternoon", "evening", "january", "february", "march", "april", "may", "june", "july",
        "august", "september", "october", "november", "december",
    )
    private val DIGIT_OF = listOf(
        "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven", "twelve", "thirteen",
        "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen",
    ).withIndex().associate { (i, w) -> w to i.toString() } +
        listOf("twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety").withIndex().associate { (i, w) -> w to ((i + 2) * 10).toString() }
    private val NEW_LINES = listOf("new line", "newline", "new paragraph", "next line")
    private val CORRECTIONS = setOf("no", "sorry", "mean", "actually", "wait", "rather")
    private val NUMBER_WORDS = setOf(
        "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven", "twelve",
        "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen", "twenty", "thirty", "forty",
        "fifty", "sixty", "seventy", "eighty", "ninety", "hundred", "thousand", "million", "billion", "percent", "dollar",
        "dollars", "cents", "o'clock", "pm", "first", "second", "third", "fourth", "fifth", "sixth", "seventh", "eighth",
        "ninth", "tenth", "half", "quarter", "noon", "midnight",
    )

    fun check(take: String, output: String?, style: CleanupStyle): Verdict {
        val out = tidy(output ?: return Verdict.Rejected("empty"))
        val raw = take.trim()
        if (out.isEmpty()) return Verdict.Rejected("empty")
        if (out == raw) return Verdict.Same
        val lowerOut = out.lowercase()
        // "I can't" opens a refusal, unless the speaker said they can't ("I won't be able to" may come back as "I cannot").
        val chatter = CHATTER.filter { !it.startsWith("i can") || !negated(raw) }
        if (chatter.any { lowerOut.startsWith(it) && !raw.lowercase().startsWith(it) }) return Verdict.Rejected("chatter")
        // A second paragraph the speaker didn't ask for: a label and two versions, say ("Warm and casual version:").
        if ('\n' in out && '\n' !in raw && NEW_LINES.none { it in raw.lowercase() }) return Verdict.Rejected("lines")
        val takeWords = words(raw)
        val outWords = words(out)
        val known = takeWords.toSet()
        val added = outWords.count { it !in known && it.none(Char::isDigit) }
        val tone = style == CleanupStyle.FRIENDLY || style == CleanupStyle.PROFESSIONAL || style == CleanupStyle.SIMPLE
        // A tone may reword most of it ("Thank you for assisting me with the move"); the owner sees a hold's answer first.
        val addedLimit = when {
            tone -> maxOf(6, outWords.size * 7 / 10)
            style == CleanupStyle.SHORTER -> maxOf(4, outWords.size / 3) // "won't be able to make it" is "can't attend"
            else -> maxOf(2, outWords.size / 5)
        }
        if (added > addedLimit) return Verdict.Rejected("new_words")
        // Clean checks the words that carry meaning instead: a self-correction can take most of a short take away
        // ("twenty five dollars no wait thirty dollars" is "$30").
        if (style != CleanupStyle.CLEAN && outWords.size < takeWords.size * (if (style == CleanupStyle.SHORTER) 0.25 else 0.4)) {
            return Verdict.Rejected("dropped")
        }
        if (style == CleanupStyle.CLEAN) {
            // What a correction replaced may go: the three words before "no", "sorry", "actually"...
            val replaced = takeWords.indices.filter { i -> (i + 1..minOf(i + 3, takeWords.lastIndex)).any { takeWords[it] in CORRECTIONS } }.toSet()
            val meaningful = takeWords.filterIndexed { i, it ->
                i !in replaced && it !in CORRECTIONS && it !in FUNCTION_WORDS && it !in NUMBER_WORDS && it.none(Char::isDigit)
            }.toSet()
            val have = outWords.toSet()
            if (meaningful.count { it !in have } > maxOf(1.0, meaningful.size * 0.3)) return Verdict.Rejected("dropped_words")
        }
        if (script(raw) != script(out)) return Verdict.Rejected("script")
        if (negated(raw) && !negated(out)) return Verdict.Rejected("negation")
        // A "no" that corrects the speaker is no "not": "by monday no tuesday" once came back as "by Monday, not Tuesday".
        // A tone may add an idiom ("Can't wait!"), and a hold shows its answer first.
        if (!tone && !negated(raw) && negated(out)) return Verdict.Rejected("negation_added")
        if (!numbersKept(takeWords, out)) return Verdict.Rejected("digits")
        if (!finalsKept(takeWords, outWords, out, anyWord = !tone)) return Verdict.Rejected("correction")
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
        for (label in CleanupPrompt.labels) if (out.startsWith(label, ignoreCase = true)) out = out.substring(label.length).trim()
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

    /**
     * The take's numbers all stay, in order, as digit groups (555 1212 may become 555-1212, never 555-121), except one
     * the speaker took back ("at 5, no, 6"); and the answer has no more numbers than the take said in digits or words.
     */
    private fun numbersKept(takeWords: List<String>, out: String): Boolean {
        val digits = Regex("\\p{Nd}+")
        val kept = takeWords.flatMapIndexed { i, word -> if (corrected(takeWords, i)) emptyList() else digits.findAll(word).map { it.value }.toList() }
        val given = digits.findAll(out).map { it.value }.toList()
        val said = takeWords.sumOf { word -> if (word in NUMBER_WORDS) 1 else digits.findAll(word).count() }
        // A time or an amount is one number however it is written: 10:00, 7.30, 1,200.
        val numbers = Regex("\\p{Nd}+(?:[:.,]\\p{Nd}+)*").findAll(out).count()
        return isSubsequence(kept, given) && numbers <= said
    }

    /**
     * What the speaker said last stays: after a correction word, the first word that carries meaning ([anyWord]; a tone
     * may reword it, so then only a date, time or number) must be in the answer, as a word or, for a number word, its
     * digits. Gemini Nano kept "Monday" for "monday no tuesday morning" and "Main Street" for "main street no oak street".
     */
    private fun finalsKept(takeWords: List<String>, outWords: List<String>, out: String, anyWord: Boolean): Boolean {
        val have = outWords.toSet()
        val digits = Regex("\\p{Nd}+").findAll(out).map { it.value }.toSet()
        for ((i, word) in takeWords.withIndex()) {
            if (word !in CORRECTIONS) continue
            // A date, time or number first ("actually make it friday", "b twelve no b fourteen"), else the first word.
            val next = (i + 1..minOf(i + 3, takeWords.lastIndex)).map { takeWords[it] }
            val last = next.firstOrNull { it in WHEN_WORDS || it in DIGIT_OF || it.any(Char::isDigit) }
                ?: next.firstOrNull { anyWord && it !in FUNCTION_WORDS && it !in CORRECTIONS && it !in NUMBER_WORDS } ?: continue
            val kept = last in have || DIGIT_OF[last]?.let { d -> digits.any { it == d || it.startsWith(d) } } == true ||
                (last.any(Char::isDigit) && Regex("\\p{Nd}+").findAll(last).all { it.value in digits })
            if (!kept) return false
        }
        return true
    }

    /** The number at [i] is taken back: a correction word ("no", "sorry", "I mean") follows it, then another number. */
    private fun corrected(words: List<String>, i: Int): Boolean {
        if (words[i].none(Char::isDigit)) return false
        val marker = (i + 1..minOf(i + 3, words.lastIndex)).firstOrNull { words[it] in CORRECTIONS } ?: return false
        return (marker + 1..minOf(marker + 3, words.lastIndex)).any { j -> words[j] in NUMBER_WORDS || words[j].any(Char::isDigit) }
    }

    private fun isSubsequence(small: List<String>, big: List<String>): Boolean {
        var i = 0
        for (item in big) if (i < small.size && small[i] == item) i++
        return i == small.size
    }
}
