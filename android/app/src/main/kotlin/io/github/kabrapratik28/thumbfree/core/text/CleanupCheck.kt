package io.github.kabrapratik28.thumbfree.core.text

/**
 * The checks a model's clean-up must pass before it may replace the user's words (issue #1, part 4): anything that could
 * change the message's meaning keeps the words as they are. Tuned on the Pixel's Gemini Nano and reviewed with Codex
 * (2026-10-07). Pure functions; the caller writes nothing on a rejection.
 */
object CleanupCheck {
    /** [Ok] carries the text to write; [Same] means nothing to tidy; [Rejected] says which check failed (a code, no text). */
    sealed interface Verdict {
        data class Ok(val text: String) : Verdict
        data object Same : Verdict
        data class Rejected(val reason: String) : Verdict
    }

    private val CHATTER = listOf("sure", "here is", "here's", "i can't", "i cannot", "as an ai", "certainly", "of course")
    private val NEW_LINES = listOf("new line", "newline", "new paragraph", "next line")
    private val CORRECTIONS = setOf("no", "sorry", "mean", "actually", "wait", "rather")
    private val NEGATIONS = setOf("not", "never", "cannot", "unable", "unavailable", "nothing", "none", "nobody", "nowhere", "neither", "nor")
    private val ARTICLES = setOf("the", "a", "an")

    // Words Clean may drop or turn into marks and digits: function words, fillers, spoken punctuation and number words.
    private val FUNCTION_WORDS = setOf(
        "a", "an", "the", "and", "or", "but", "so", "to", "of", "in", "on", "at", "by", "for", "with", "from", "is", "are",
        "was", "were", "be", "been", "am", "it", "this", "that", "i", "you", "he", "she", "we", "they", "me", "my", "your",
        "our", "their", "its", "do", "does", "did", "have", "has", "had", "will", "would", "can", "could", "should",
        "just", "really", "very", "then", "there", "here", "um", "uh", "er", "erm", "hmm", "like", "no", "wait",
        "actually", "mean", "know", "comma", "period", "question", "mark", "exclamation", "colon", "new", "line",
        "paragraph", "dot", "oh", "let's", "lets",
    )

    // Dates and times: what a correction's final version is checked for, as a wrong pick would change them.
    private val WHEN_WORDS = setOf(
        "monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday", "today", "tonight", "tomorrow",
        "yesterday", "morning", "afternoon", "evening", "january", "february", "march", "april", "may", "june", "july",
        "august", "september", "october", "november", "december",
    )

    private val UNITS = listOf(
        "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven", "twelve", "thirteen",
        "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen",
    ).withIndex().associate { (i, w) -> w to i.toLong() }
    private val TENS = listOf("twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety")
        .withIndex().associate { (i, w) -> w to (i + 2) * 10L }

    // "second" is left out: "wait a second" says no number.
    private val ORDINALS = listOf(
        "first", "", "third", "fourth", "fifth", "sixth", "seventh", "eighth", "ninth", "tenth", "eleventh", "twelfth",
        "thirteenth", "fourteenth", "fifteenth", "sixteenth", "seventeenth", "eighteenth", "nineteenth",
    ).withIndex().filter { it.value.isNotEmpty() }.associate { (i, w) -> w to i + 1L } + mapOf("twentieth" to 20L, "thirtieth" to 30L)
    private val SCALES = mapOf("thousand" to 1_000L, "million" to 1_000_000L)
    private val NUMBER_WORDS = UNITS.keys + TENS.keys + ORDINALS.keys + SCALES.keys + setOf(
        "hundred", "percent", "dollar", "dollars", "cents", "o'clock", "pm", "second", "half", "quarter", "noon", "midnight", "point",
    )

    fun check(take: String, output: String?, style: CleanupStyle): Verdict {
        val out = tidy(output ?: return Verdict.Rejected("empty"))
        val raw = take.trim()
        if (out.isEmpty()) return Verdict.Rejected("empty")
        if (out == raw) return Verdict.Same
        val tone = style == CleanupStyle.FRIENDLY || style == CleanupStyle.PROFESSIONAL || style == CleanupStyle.SIMPLE
        val takeWords = words(raw)
        val outWords = words(out)
        val takeNots = negations(takeWords)
        val outNots = negations(outWords)
        // "I can't" opens a refusal, unless the speaker said they can't ("I won't be able to" may come back as "I cannot").
        val chatter = CHATTER.filter { !it.startsWith("i can") || takeNots.isEmpty() }
        if (chatter.any { opens(out, it) && !opens(raw, it) }) return Verdict.Rejected("chatter")
        // A second paragraph the speaker didn't ask for: a label and two versions, say ("Warm and casual version:").
        if ('\n' in out && '\n' !in raw && NEW_LINES.none { it in raw.lowercase() }) return Verdict.Rejected("lines")
        val known = takeWords.map(::key).toSet()
        val added = outWords.count { key(it) !in known && it.none(Char::isDigit) }
        // A tone may reword most of it ("Thank you for assisting me with the move"); its answer is shown before it is written.
        val addedLimit = when {
            tone -> maxOf(6, outWords.size * 7 / 10)
            style == CleanupStyle.SHORTER -> maxOf(4, outWords.size / 3) // "won't be able to make it" is "can't attend"
            else -> maxOf(2, outWords.size / 5)
        }
        if (added > addedLimit) return Verdict.Rejected("new_words")
        if (style != CleanupStyle.CLEAN && outWords.size < takeWords.size * (if (style == CleanupStyle.SHORTER) 0.25 else 0.4)) {
            return Verdict.Rejected("dropped")
        }
        if (style == CleanupStyle.CLEAN) meaning(takeWords, outWords)?.let { return Verdict.Rejected(it) }
        if (out.any(Char::isLetter) && script(raw) != script(out)) return Verdict.Rejected("script") // "five" may be "5."
        if (takeNots.isNotEmpty() && outNots.isEmpty()) return Verdict.Rejected("negation")
        // A "no" that corrects the speaker is no "not": "by monday no tuesday" once came back as "by Monday, not Tuesday".
        // A tone may add an idiom ("Can't wait!"), and its answer is shown first.
        if (!tone && outNots.size > takeNots.size) return Verdict.Rejected("negation_added")
        // Clean keeps each negation on the word it negates: "not injured and hurt" is not "injured and not hurt".
        if (style == CleanupStyle.CLEAN && takeNots.sorted() != outNots.sorted()) return Verdict.Rejected("negation_moved")
        if (!numbersKept(raw, out)) return Verdict.Rejected("digits")
        if (!finalsKept(takeWords, outWords, anyWord = !tone)) return Verdict.Rejected("correction")
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

    /**
     * Clean keeps the speaker's words: few words that carry meaning may go (what a correction replaced always may), none
     * may be swapped for a new one ("Alice" for "Bob", "Go!" for "Stop"), and the rest keep their order ("Bob owes
     * Alice" is not "Alice owes Bob"). Null when it does; else the check that failed.
     */
    private fun meaning(takeWords: List<String>, outWords: List<String>): String? {
        val replaced = takeWords.indices.filter { i ->
            (i + 1..minOf(i + 3, takeWords.lastIndex)).any { j -> correction(takeWords, j) }
        }.toSet()
        val kept = takeWords.filterIndexed { i, w -> i !in replaced && meaningful(w) }.map(::key)
        val have = outWords.filter(::meaningful).map(::key)
        val allowed = maxOf(1.0, kept.distinct().size * 0.3)
        val missing = kept.distinct().count { it !in have }
        if (missing > allowed) return "dropped_words"
        val known = takeWords.map(::key).toSet()
        if (missing >= 1 && have.any { it !in known }) return "substitution"
        // The words that stay keep their order, each where the speaker said it last ("meet at 6:30, oh no, actually,
        // let's meet at 7:30" ends on its second "meet").
        val stayed = kept.withIndex().filter { (i, w) -> kept.lastIndexOf(w) == i && w in have }.map { it.value }
        if (commonInOrder(stayed, have) < stayed.size) return "order"
        return null
    }

    /** [text] starts with the words [phrase], whole ("Surely" doesn't open with "sure"), with any apostrophe ("I can’t"). */
    private fun opens(text: String, phrase: String): Boolean {
        val lower = text.lowercase().replace('’', '\'')
        return lower.startsWith(phrase) && (lower.length == phrase.length || !lower[phrase.length].isLetter())
    }

    /** The answer without an echoed label or one pair of surrounding quotes. */
    private fun tidy(output: String): String {
        var out = output.trim()
        for (label in CleanupPrompt.labels) if (out.startsWith(label, ignoreCase = true)) out = out.substring(label.length).trim()
        if (out.length >= 2 && out.first() == '"' && out.last() == '"') out = out.substring(1, out.length - 1).trim()
        return out
    }

    private fun words(text: String): List<String> =
        text.lowercase().replace('’', '\'').split(Regex("[^\\p{L}\\p{N}']+")).map { it.trim('\'') }.filter { it.isNotEmpty() }

    /** A word compared as the speaker said it: "let's" is "lets". */
    private fun key(word: String) = word.replace("'", "")

    private fun meaningful(word: String) =
        word !in FUNCTION_WORDS && word !in CORRECTIONS && word !in NUMBER_WORDS && word.none(Char::isDigit)

    private fun numberish(word: String) = word in UNITS || word in TENS || word in ORDINALS || word.any(Char::isDigit)

    /** [words] at [i] marks a correction: "sorry", "actually"... or a "no" that is one ([correctionNo]). */
    private fun correction(words: List<String>, i: Int) = words[i] in CORRECTIONS && (words[i] != "no" || correctionNo(words, i))

    /**
     * A "no" that corrects the speaker rather than negates: the words around it are of a kind ("five no six", "monday no
     * tuesday", "the fifth no the sixth"), or what follows repeats a word just before it ("main street no oak street",
     * "to marco no sorry to luca"). "I have no allergies" is a negation.
     */
    private fun correctionNo(words: List<String>, i: Int): Boolean {
        if (i == 0) return false
        // "oh no", "no, actually", "no, sorry", "no, wait", "no, I mean": a correction, said so.
        val next = words.getOrNull(i + 1)
        if (words[i - 1] == "oh" || next in setOf("actually", "sorry", "wait", "rather") || next == "i" && words.getOrNull(i + 2) == "mean") return true
        val before = words.subList(maxOf(0, i - 3), i)
        val after = words.subList(i + 1, minOf(words.size, i + 5)).filter { it !in CORRECTIONS && it != "i" }
        val first = after.firstOrNull { it !in ARTICLES } ?: return false
        val prev = words[i - 1]
        return (numberish(prev) && numberish(first)) || (prev in WHEN_WORDS && first in WHEN_WORDS) ||
            after.take(2).any { it in before && it !in ARTICLES }
    }

    /** Each negation, as the word that carries meaning after it ("" at the end): "not injured" is "injured". */
    private fun negations(words: List<String>): List<String> = words.indices.filter { i ->
        val w = words[i]
        w in NEGATIONS || w.endsWith("n't") || (w == "no" && !correctionNo(words, i))
    }.map { i -> words.drop(i + 1).firstOrNull(::meaningful).orEmpty() }

    /** The script most letters are in (Latin, Cyrillic, Devanagari…), so an answer in another alphabet is caught. */
    private fun script(text: String): Character.UnicodeScript? =
        text.codePoints().filter(Character::isLetter).boxed().toList()
            .groupingBy { Character.UnicodeScript.of(it) }.eachCount().maxByOrNull { it.value }?.key

    /** How many items [a] and [b] share in the same order (their longest common subsequence). */
    private fun commonInOrder(a: List<String>, b: List<String>): Int {
        val row = IntArray(b.size + 1)
        for (x in a) {
            var diagonal = 0
            for (j in 1..b.size) {
                val up = row[j]
                row[j] = if (x == b[j - 1]) diagonal + 1 else maxOf(row[j], row[j - 1])
                diagonal = up
            }
        }
        return row[b.size]
    }

    /**
     * What the speaker said last stays: after a correction, the first date or time ("monday no tuesday morning") or, with
     * [anyWord] (a tone may reword it), the first word that carries meaning ("main street no oak street") must be in the
     * answer. Numbers are [numbersKept]'s.
     */
    private fun finalsKept(takeWords: List<String>, outWords: List<String>, anyWord: Boolean): Boolean {
        val have = outWords.map(::key).toSet()
        for (i in takeWords.indices) {
            if (!correction(takeWords, i)) continue
            // In a chain ("monday no tuesday no wednesday") the last correction decides.
            if ((i + 1..minOf(i + 3, takeWords.lastIndex)).any { correction(takeWords, it) }) continue
            val next = (i + 1..minOf(i + 3, takeWords.lastIndex)).map { takeWords[it] }.filter { it !in CORRECTIONS }
            if (next.any(::numberish)) continue
            val last = next.firstOrNull { it in WHEN_WORDS } ?: next.firstOrNull { anyWord && meaningful(it) } ?: continue
            if (key(last) !in have) return false
        }
        return true
    }

    /** A number as said, and the token it starts at. */
    private class Said(val value: String, val at: Int)

    /**
     * Every number the text says, in digits or words, as plain digits, with the token it starts at: "twenty five" and
     * "25" are 25, "$1,200" and "twelve hundred" are 1200, "two point three" is 2.3, "7:30", "7.30" and "seven thirty" are
     * 7 and 30, "10:00" is 10, noon and midnight are 12.
     */
    private fun numbers(tokens: List<String>): List<Said> {
        val out = mutableListOf<Said>()
        var value: Long? = null
        var total = 0L
        var start = 0
        fun flush() {
            value?.let { out += Said((total + it).toString(), start) }
            value = null
            total = 0
        }
        fun begin(v: Long, at: Int) {
            flush()
            value = v
            start = at
        }
        var i = 0
        while (i < tokens.size) {
            val t = tokens[i]
            val v = value
            when {
                t[0].isDigit() -> {
                    flush()
                    digitsOf(t).forEach { out += Said(it, i) }
                }
                t == "noon" || t == "midnight" -> {
                    flush()
                    out += Said("12", i)
                }
                t in UNITS || t in ORDINALS -> {
                    val n = UNITS[t] ?: ORDINALS.getValue(t)
                    when {
                        v == 0L && total > 0 -> value = n // two thousand five
                        v != null && v % 100 in 20L..90L && v % 10 == 0L && n < 10 -> value = v + n // twenty five
                        v != null && v >= 100 && v % 100 == 0L -> value = v + n // one hundred and five
                        else -> begin(n, i)
                    }
                    if (t in ORDINALS) flush()
                }
                t in TENS -> {
                    val n = TENS.getValue(t)
                    if (v == 0L && total > 0 || v != null && v >= 100 && v % 100 == 0L) value = (v ?: 0) + n else begin(n, i)
                }
                t == "hundred" && v != null -> value = v * 100
                t in SCALES && v != null -> {
                    total += v * SCALES.getValue(t)
                    value = 0
                }
                t == "point" && v != null && tokens.getOrNull(i + 1) in UNITS -> {
                    out += Said("${total + v}.${UNITS.getValue(tokens[i + 1])}", start)
                    value = null
                    total = 0
                    i++
                }
                t == "and" && v != null && v >= 100 -> Unit
                else -> flush()
            }
            i++
        }
        flush()
        return out
    }

    /** A number written in digits, as [numbers] counts it: "10:00" is 10, "7:30" and "7.30" are 7 and 30, "1,200" is 1200. */
    private fun digitsOf(token: String): List<String> = when {
        ':' in token -> token.split(':').filterIndexed { i, part -> i == 0 || part.trimStart('0').isNotEmpty() }
            .map { it.trimStart('0').ifEmpty { "0" } }
        Regex("\\d{1,2}\\.\\d{2}").matches(token) && token.substringBefore('.').toInt() <= 23 -> token.split('.')
        else -> listOf(token.replace(",", ""))
    }

    private fun tokens(text: String): List<String> =
        Regex("\\p{Nd}+(?:[.,:]\\p{Nd}+)*|[\\p{L}']+").findAll(text.lowercase().replace('’', '\'')).map { it.value }.toList()

    /**
     * The answer's numbers are the take's: every number said, except what a correction replaced ("at five no six" is "at
     * 6"), is in the answer, and the answer has no number the speaker didn't say. A run of digits said one by one may come
     * back joined ("4 8 1 5" is "4815").
     */
    private fun numbersKept(take: String, out: String): Boolean {
        val tokens = tokens(take)
        val said = numbers(tokens)
        val replaced = tokens.indices.filter { i -> (i + 1..minOf(i + 3, tokens.lastIndex)).any { j -> correction(tokens, j) } }.toSet()
        val required = said.filter { it.at !in replaced }.map { it.value }
        val given = numbers(tokens(out)).map { it.value }.toMutableList()
        var k = 0
        while (k < required.size) {
            if (given.remove(required[k])) {
                k++
                continue
            }
            var joined = required[k]
            var j = k
            var found = false
            while (!found && j + 1 < required.size && joined.length < 16) {
                j++
                joined += required[j]
                found = given.remove(joined)
            }
            if (!found) return false
            k = j + 1
        }
        // What is left in the answer was said. A number the speaker took back may stay ("the fifth, no, the sixth works
        // better"), but not joined to the one they kept as a range nobody said: "two no three bags" is not "2-3 bags".
        val all = said.map { it.value }
        if (given.any { it !in all }) return false
        return Regex("(\\p{Nd}+) *[-–] *(\\p{Nd}+)").findAll(out).none { m ->
            val (a, b) = m.destructured
            (a in given && b in required) || (b in given && a in required)
        }
    }
}
