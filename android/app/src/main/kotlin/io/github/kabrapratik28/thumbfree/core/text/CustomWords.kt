package io.github.kabrapratik28.thumbfree.core.text

import java.text.Normalizer
import kotlin.math.max
import kotlin.math.min

/**
 * Custom words: names, brands and jargon spelled the user's way. Parakeet takes no hotwords, so this corrects a take's
 * text after transcription, over n-grams of 1 to 3 words. The matching is adapted from MIT-licensed code; see
 * THIRD_PARTY_NOTICES.md. Words and entries are compared in Unicode NFC. A key is a word's letters and digits,
 * lowercased. An exact match is an equal key, so "github" is GitHub and "chat gpt" is ChatGPT. A fuzzy match scores the
 * keys' edit distance over the longer key's length, times 0.3 when their Soundex codes agree; it needs a score under
 * 0.18 and never covers more than 2 edits. Guards against the traps of plain fuzzy matching (short entries eating real
 * words, Soundex turning "save" into "SEV" and "div" into "diff"):
 * 1. an entry with a key under 4 characters, or with a letter outside ASCII, matches only exactly;
 * 2. an n-gram with a common English word in it ([COMMON_WORDS]) matches only exactly;
 * 3. the Soundex discount needs both keys to be 5 or more ASCII letters, and covers 1 edit between two 5-letter keys;
 * 4. an n-gram of 2 or 3 words may not take its first word when the rest of it alone matches the same entry at least as
 *    well;
 * 5. 2 or 3 common words merge into a one-word entry only when none is a function word ([FUNCTION_WORDS]): "whats app"
 *    is WhatsApp, but "can I phone you" keeps "I phone" and "one plus two" stays. An uncommon word lets any n-gram merge
 *    ("chat gpt" is ChatGPT), and a multi-word entry may take any words ("second brain" is Second Brain).
 * A merge never makes more words than it takes, and a merge into as many words keeps each word's key, so it only fixes
 * case. A word with a symbol, emoji or loose accent mark among its letters is never matched ("git😀hub" stays).
 * Punctuation around a match stays, and so do a possessive 's and a plural s: "Kubernetes's", "GPUs". An entry's own
 * leading and trailing marks ("#hashtag", "U.S.", "C++") appear once, reusing the ones the text already has there.
 * The common and function words (guards 2 and 5), Soundex (guard 3) and the plural s are English: text in another
 * language, or in an unknown one, takes exact matches only (exactOnly in [correct]).
 */
object CustomWords {
    /** The most entries [parse] keeps. */
    const val MAX_ENTRIES = 500

    /** The most characters (code points) an entry may have. */
    const val MAX_CHARS = 60

    private const val THRESHOLD = 0.18
    private const val MAX_EDITS = 2
    private const val MAX_PASSES = 64
    private const val TERMINATORS = ".!?…。！？"

    // Guard 5's function words: pronouns, articles, prepositions, conjunctions, auxiliaries and modals, number words.
    private val FUNCTION_WORDS = ("i you he she it we they me him her us them my your his its our their this that these " +
        "those who which what a an the about after against around at before between by down during for from in into of " +
        "off on onto out over through to under until up with within without and or but nor so yet if because though " +
        "while as than am is are was were be been being have has had do does did can could will would shall should may " +
        "might must zero one two three four five six seven eight nine ten twelve hundred thousand").split(' ').toHashSet()

    /**
     * The list from what the user typed: split at commas and line breaks, each entry in NFC, trimmed, with its inner
     * whitespace runs as one space. Empty entries, entries over [MAX_CHARS] characters and repeats that differ only in case
     * go, the first spelling staying, and at most [MAX_ENTRIES] are kept.
     */
    fun parse(input: String): List<String> {
        val seen = HashSet<String>()
        return input.split(',', '\n', '\r').asSequence()
            .map { splitWhitespace(nfc(it)).joinToString(" ") }
            .filter { it.isNotEmpty() && it.codePointCount(0, it.length) <= MAX_CHARS && seen.add(it.lowercase()) }
            .take(MAX_ENTRIES)
            .toList()
    }

    /**
     * Whether text in [language] (ISO 639-1, null when unknown, as for the multilingual model's takes) takes exact matches
     * only: the guards against near misses know English only.
     */
    fun exactOnlyFor(language: String?): Boolean = language != "en"

    /**
     * The one-word entries that are common English words, such as "Will" or "IT", for the Settings editor to warn about:
     * an exact match always applies, so every "will" is then written "Will".
     */
    fun riskyEntries(words: List<String>): List<String> = words.filter {
        val word = nfc(it)
        splitWhitespace(word).size == 1 && key(word) in COMMON_WORDS
    }

    /**
     * [text] with [words] (a list as [parse] gives it) applied. Words it leaves alone, and the spacing, stay exactly as
     * they were. Deterministic, and correcting the result again changes nothing. Never throws: returns [text] on any
     * error. An empty [words] list returns [text] unchanged. With [exactOnly], only an n-gram whose key equals an entry's
     * changes: no near miss, Soundex match or plural s.
     */
    fun correct(text: String, words: List<String>, exactOnly: Boolean = false): String {
        if (words.isEmpty()) return text
        return runCatching {
            val matcher = Matcher(words, exactOnly)
            // A pass can set up a match for the next ("kubernetis engine" gives "Kubernetes engine", an exact "Kubernetes
            // Engine"), so passes repeat until one changes nothing. Some lists loop (an entry splits a word that another
            // merges back) or chain for long ("R&D"-style aliases step down one entry per pass): a pass that brings back an
            // earlier text, or the 64th, gives back the text as it came, which correcting again does too.
            val seen = HashSet<String>()
            var out = text
            while (seen.add(out) && seen.size <= MAX_PASSES) {
                val next = matcher.pass(out)
                if (next == out) return out
                out = next
            }
            text
        }.getOrDefault(text)
    }

    /** Rust is_alphanumeric code points of [word] from [from] to [to], each lowercased (Rust char::to_lowercase). */
    internal fun key(word: String, from: Int = 0, to: Int = word.length): String {
        val out = StringBuilder(to - from)
        var i = from
        while (i < to) {
            val c = word[i]
            if (c.code < 128) {
                if (c in 'a'..'z' || c in '0'..'9') out.append(c) else if (c in 'A'..'Z') out.append(c + 32)
                i++
            } else {
                val cp = word.codePointAt(i)
                if (isRustAlphanumeric(cp)) out.append(String(Character.toChars(cp)).lowercase())
                i += Character.charCount(cp)
            }
        }
        return out.toString()
    }

    /** An entry: [lead] + [core] + [trail], its marks before its first and after its last letter or digit split off. */
    private class Entry(word: String, val index: Int) {
        val text = nfc(word)
        private val coreStart = firstAlphanumeric(text)
        private val coreEnd = lastAlphanumeric(text, coreStart)
        val lead = text.substring(0, coreStart)
        val core = text.substring(coreStart, coreEnd)
        val trail = text.substring(coreEnd)
        val lowercase = core.codePoints().noneMatch(Character::isUpperCase)
        val wordKeys = splitWhitespace(core).map { key(it) }
        val oneWord = wordKeys.size == 1
    }

    private class Fuzzy(val key: String, val entry: Entry) {
        val soundex = soundexOf(key)
        val mask = mask(key)
        val bigrams = bigrams(key)
    }

    /**
     * A word of the text, between whitespace: [start] to [end] in the text, [s] in NFC. In [s] its letters and digits run
     * from [coreStart] to [coreEnd] (both s.length when it has none); [stemEnd] is [coreEnd], or where a possessive 's
     * starts. [key] is the stem's, or "" when the word must not match: no letters or digits, or a symbol, emoji or loose
     * mark among them, which a match would drop.
     */
    private class Token(
        val start: Int, val end: Int, val s: String, val coreStart: Int, val coreEnd: Int, val stemEnd: Int, val key: String,
    ) {
        val lexical = coreStart < coreEnd
        val ascii = isAsciiKey(key)
        val mask = if (ascii) mask(key) else 0L
        val common = key in COMMON_WORDS
    }

    private class Match(val n: Int, val entry: Entry, val score: Double, val resume: Int) // resume: last word's s goes on from here

    internal class Matcher(words: List<String>, private val exactOnly: Boolean = false) {
        private val exact = HashMap<String, MutableList<Entry>>() // every entry with the key, in list order
        private val fuzzyByLength: Map<Int, List<Fuzzy>>
        private var row0 = IntArray(64) // levenshtein's rows, reused
        private var row1 = IntArray(64)

        init {
            val fuzzy = LinkedHashMap<String, Fuzzy>() // one per key: the first entry with it wins every tie anyway
            words.forEachIndexed { index, word ->
                val entry = Entry(word, index)
                // The key and, for "&", the key with "&" read as "and": "R&D" gives rd and randd.
                val keys = listOf(key(entry.text), key(entry.text.replace("&", " and "))).distinct().filter(String::isNotEmpty)
                keys.forEach { exact.getOrPut(it) { ArrayList(1) } += entry }
                // Guard 1 holds for the whole entry, or "R&D" would match "rained" through randd.
                if (keys.all { it.length >= 4 && isAsciiKey(it) }) keys.forEach { fuzzy.getOrPut(it) { Fuzzy(it, entry) } }
            }
            fuzzyByLength = fuzzy.values.groupBy { it.key.length }
        }

        fun pass(text: String): String {
            val tokens = tokenize(text)
            val out = StringBuilder(text.length)
            var copied = 0 // the text before this index is in out
            var i = 0
            while (i < tokens.size) {
                val match = best(tokens, i)
                if (match == null) {
                    i++
                    continue
                }
                val first = tokens[i]
                val last = tokens[i + match.n - 1]
                out.append(text, copied, first.start).append(render(match, first, last, sentenceStart(tokens, i)))
                copied = last.end
                i += match.n
            }
            return out.append(text, copied, text.length).toString()
        }

        // The n-grams of 1 to 3 words at i: the lowest score wins, and a tie goes to the longer n-gram.
        private fun best(tokens: List<Token>, i: Int): Match? {
            var best: Match? = null
            for (n in 1..3) {
                if (i + n > tokens.size) break
                val last = tokens[i + n - 1]
                if (last.key.isEmpty()) break
                // Never across punctuation or a possessive: in "chat, gpt" the comma ends the n-gram at "chat,".
                if (n > 1) {
                    val before = tokens[i + n - 2]
                    if (before.coreEnd < before.s.length || before.stemEnd < before.coreEnd || last.coreStart > 0) break
                }
                val match = match(tokens, i, n) ?: continue
                if (best == null || match.score <= best.score) best = match
            }
            return best
        }

        private fun match(tokens: List<Token>, i: Int, n: Int): Match? {
            val words = tokens.subList(i, i + n)
            val last = words.last()
            val key = if (n == 1) last.key else words.joinToString("") { it.key }
            val keys = words.map(Token::key)
            exact[key]?.let { entries ->
                // The first entry with the key that guard 5 allows and that fits: "Mac Book Pro" listed before "MacBook Pro"
                // still lets "macbook pro" take the second.
                val function = n > 1 && words.all(Token::common) && words.any { it.key in FUNCTION_WORDS }
                val entry = entries.firstOrNull { (!function || !it.oneWord) && fits(it, keys) } ?: return null
                return Match(n, entry, 0.0, last.stemEnd)
            }
            if (exactOnly) return null
            // A plural keeps its s: "gpus" is GPUs. Never a common word's s, or "this" would become an entry "Thi" and an s.
            if (!last.common && last.stemEnd == last.coreEnd && last.s[last.coreEnd - 1] in "sS") {
                exact[key.dropLast(1)]?.let { entries ->
                    val stems = keys.dropLast(1) + keys.last().dropLast(1)
                    val entry = entries.firstOrNull { fits(it, stems) } ?: return null
                    return Match(n, entry, 0.0, last.coreEnd - 1)
                }
            }
            // Guard 2; and a 50-character cap on a key to score.
            if (words.any { it.common || !it.ascii } || key.length > 50) return null
            return fuzzy(words, key)?.let { (entry, score) -> Match(n, entry, score, last.stemEnd) }
        }

        // A merge never makes more words than it takes, and a merge into as many words keeps each word's key: only its case
        // changes. One word may still become an entry of several ("macbookpro" is MacBook Pro).
        private fun fits(entry: Entry, keys: List<String>) =
            keys.size == 1 || entry.wordKeys.size < keys.size || entry.wordKeys == keys

        // The best fuzzy match of an n-gram of uncommon ASCII words: the lowest score, a tie going to the earlier entry.
        private fun fuzzy(words: List<Token>, key: String): Pair<Entry, Double>? {
            val soundex = soundexOf(key)
            val mask = maskOf(words)
            val bigrams = bigrams(key)
            val rest = words.subList(1, words.size)
            val restKey by lazy(LazyThreadSafetyMode.NONE) { rest.joinToString("") { it.key } }
            var best: Fuzzy? = null
            var bestScore = THRESHOLD
            for (length in key.length - MAX_EDITS..key.length + MAX_EDITS) {
                val bucket = fuzzyByLength[length] ?: continue
                val longest = max(key.length, length)
                val plain = mostEdits(longest, phonetic = false)
                val alike = mostEdits(longest, phonetic = true)
                for (f in bucket) {
                    val phonetic = soundex != 0 && soundex == f.soundex
                    val score = score(key, mask, bigrams, f, longest, if (phonetic) alike else plain, phonetic) ?: continue
                    if (best != null && (score > bestScore || score == bestScore && f.entry.index > best.entry.index)) continue
                    // A fuzzy merge also changes keys, so it must drop words: "ab cd" never becomes "Abc De".
                    if (words.size > 1 && f.entry.wordKeys.size >= words.size) continue
                    // Guard 4: "b zendesk" is no near miss for Zendesk when "zendesk" alone matches it at least as well.
                    val restMatches = rest.isNotEmpty() &&
                        (exact[restKey]?.contains(f.entry) == true || restScore(restKey, rest, f)?.let { it <= score } == true)
                    if (restMatches) continue
                    best = f
                    bestScore = score
                }
            }
            return best?.let { it.entry to bestScore }
        }

        private fun restScore(key: String, words: List<Token>, f: Fuzzy): Double? {
            val longest = max(key.length, f.key.length)
            val soundex = soundexOf(key)
            val phonetic = soundex != 0 && soundex == f.soundex
            return score(key, maskOf(words), bigrams(key), f, longest, mostEdits(longest, phonetic), phonetic)
        }

        // The best fuzzy match for one key, within [most] edits: the score, or null. An edit
        // adds or drops at most 2 of the letters present and 4 of the letter pairs, so the two cheap tests skip nearly
        // every pair before any distance, even 500 entries that share their letters and Soundex code.
        // ponytail: two 64-bit masks, not an index. Entries built to share their letters, all their letter pairs and their
        // Soundex code (Eulerian circuits over b, f, p and v) still cost one banded distance each: 30 to 55 ms per 1,000
        // words for 500 of them (CustomWordsTest.fast). A radius-2 deletion-signature index, cached per list, would lift
        // that ceiling if a real list ever reaches it.
        private fun score(key: String, mask: Long, bigrams: Long, f: Fuzzy, longest: Int, most: Int, phonetic: Boolean): Double? {
            if (most == 0 || java.lang.Long.bitCount(mask xor f.mask) > 2 * most) return null
            if (java.lang.Long.bitCount(bigrams xor f.bigrams) > 4 * most) return null
            val edits = levenshtein(key, f.key, most) ?: return null
            return edits.toDouble() / longest * if (phonetic) 0.3 else 1.0
        }

        // strsim's levenshtein (every edit costs 1), within [most] cells of the diagonal: null once it must exceed most.
        internal fun levenshtein(a: String, b: String, most: Int): Int? {
            if (a.length - b.length > most || b.length - a.length > most) return null
            if (row0.size < b.length + 2) {
                row0 = IntArray(b.length + 2)
                row1 = IntArray(b.length + 2)
            }
            val over = most + 1
            var previous = row0
            var current = row1
            for (j in 0..b.length) previous[j] = if (j <= most) j else over
            for (i in 1..a.length) {
                val lo = max(1, i - most)
                val hi = min(b.length, i + most)
                current[lo - 1] = if (lo == 1 && i <= most) i else over
                var least = current[lo - 1]
                for (j in lo..hi) {
                    var v = previous[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1
                    if (previous[j] + 1 < v) v = previous[j] + 1
                    if (current[j - 1] + 1 < v) v = current[j - 1] + 1
                    current[j] = min(v, over)
                    if (v < least) least = v
                }
                if (hi < b.length) current[hi + 1] = over // the next row reads one cell past this band
                if (least > most) return null
                val swap = previous
                previous = current
                current = swap
            }
            return previous[b.length].takeIf { it <= most }
        }

        /**
         * The entry in place of the n-gram, between the first word's leading and the last word's trailing punctuation. An
         * entry's own marks are written once: "#" before "hashtag" and "." after "U.S" reuse the text's, if it has them
         * there. An all-lowercase entry takes the capital of a word that starts a sentence.
         */
        private fun render(match: Match, first: Token, last: Token, sentenceStart: Boolean): String {
            val entry = match.entry
            val before = first.s.substring(0, first.coreStart)
            val after = last.s.substring(match.resume)
            var core = entry.core
            if (entry.lowercase && sentenceStart && Character.isUpperCase(first.s.codePointAt(first.coreStart))) {
                val head = core.substring(0, core.offsetByCodePoints(0, 1))
                core = head.uppercase() + core.substring(head.length)
            }
            return before.dropLast(sharedEnd(before, entry.lead)) + entry.lead + core + entry.trail +
                after.drop(sharedStart(after, entry.trail))
        }

        // A sentence starts at the text's start, or after . ! ? … in the punctuation since the last word, looking past
        // tokens with no letters or digits ("Done. 😀 Kubectl").
        private fun sentenceStart(tokens: List<Token>, i: Int): Boolean {
            for (j in i - 1 downTo 0) {
                val t = tokens[j]
                if (((if (t.lexical) t.coreEnd else 0) until t.s.length).any { t.s[it] in TERMINATORS }) return true
                if (t.lexical) return false
            }
            return true
        }
    }

    private fun tokenize(text: String): List<Token> {
        val tokens = ArrayList<Token>()
        var end = 0
        while (end < text.length) {
            if (isRustWhitespace(text[end])) {
                end++
                continue
            }
            val start = end
            while (end < text.length && !isRustWhitespace(text[end])) end++
            val s = nfc(text.substring(start, end))
            val coreStart = firstAlphanumeric(s)
            val coreEnd = lastAlphanumeric(s, coreStart)
            val possessive = coreEnd - coreStart >= 3 && s[coreEnd - 2] in "'’" && s[coreEnd - 1] in "sS"
            val stemEnd = if (possessive) coreEnd - 2 else coreEnd
            val key = if (sealed(s, coreStart, coreEnd)) "" else key(s, coreStart, stemEnd)
            tokens += Token(start, end, s, coreStart, coreEnd, stemEnd, key)
        }
        return tokens
    }

    // A symbol, emoji, format char or loose accent mark among the word's letters and digits, or a mark on its last one.
    private fun sealed(s: String, from: Int, to: Int): Boolean {
        var i = from
        while (i < to) {
            val cp = s.codePointAt(i)
            if (!isRustAlphanumeric(cp) && !isPunctuation(cp)) return true
            i += Character.charCount(cp)
        }
        return to < s.length && Character.getType(s.codePointAt(to)).let {
            it == Character.NON_SPACING_MARK.toInt() || it == Character.ENCLOSING_MARK.toInt() ||
                it == Character.COMBINING_SPACING_MARK.toInt()
        }
    }

    private fun isPunctuation(cp: Int) = when (Character.getType(cp).toByte()) {
        Character.CONNECTOR_PUNCTUATION, Character.DASH_PUNCTUATION, Character.START_PUNCTUATION, Character.END_PUNCTUATION,
        Character.INITIAL_QUOTE_PUNCTUATION, Character.FINAL_QUOTE_PUNCTUATION, Character.OTHER_PUNCTUATION -> true
        else -> false
    }

    private fun firstAlphanumeric(s: String): Int {
        var i = 0
        while (i < s.length && !isRustAlphanumeric(s.codePointAt(i))) i = s.offsetByCodePoints(i, 1)
        return i
    }

    // After the last letter or digit: s.length when there is none from [from] on.
    private fun lastAlphanumeric(s: String, from: Int): Int {
        if (from == s.length) return from
        var i = s.length
        while (i > from && !isRustAlphanumeric(s.codePointBefore(i))) i = s.offsetByCodePoints(i, -1)
        return i
    }

    // How many chars at the end of [text] are also the end of [marks]: the marks the text already has there.
    private fun sharedEnd(text: String, marks: String): Int = (min(text.length, marks.length) downTo 1).firstOrNull { k ->
        text.regionMatches(text.length - k, marks, marks.length - k, k) &&
            !Character.isLowSurrogate(text[text.length - k]) && !Character.isLowSurrogate(marks[marks.length - k])
    } ?: 0

    // How many chars at the start of [text] are also the start of [marks].
    private fun sharedStart(text: String, marks: String): Int = (min(text.length, marks.length) downTo 1).firstOrNull { k ->
        text.regionMatches(0, marks, 0, k) &&
            (k == text.length || !Character.isLowSurrogate(text[k])) && (k == marks.length || !Character.isLowSurrogate(marks[k]))
    } ?: 0

    // Only non-ASCII text can be other than NFC, and most of it (no char from U+0300 up) is NFC already.
    private fun nfc(s: String): String =
        if (s.all { it < '\u0300' }) s else Normalizer.normalize(s, Normalizer.Form.NFC)

    private fun isAsciiKey(key: String) = key.isNotEmpty() && key.all { it in 'a'..'z' || it in '0'..'9' }

    // Guard 3: only a key of 5 or more ASCII letters has a Soundex code to agree on; its 4 chars packed in an Int, else 0.
    private fun soundexOf(key: String): Int =
        if (key.length >= 5 && key.all { it in 'a'..'z' }) Soundex.code(key).fold(0) { code, c -> code shl 8 or c.code } else 0

    // The most edits between keys whose longer one has [longest] chars that still score under the threshold, and never
    // more than 2. Soundex keeps 4 chars, so "installation" sounds like Instagram to it: the discount alone would let 60%
    // of a word differ. Between two 5-letter keys it covers 1 edit (guard 3).
    private fun mostEdits(longest: Int, phonetic: Boolean): Int {
        val weight = if (phonetic) 0.3 else 1.0
        var most = min(MAX_EDITS, (THRESHOLD / weight * longest).toInt() + 1)
        while (most > 0 && most.toDouble() / longest * weight >= THRESHOLD) most--
        return if (phonetic && longest == 5) min(most, 1) else most
    }

    // The ASCII letters and digits present in a key, one bit each.
    private fun mask(key: String): Long = key.fold(0L) { m, c -> m or (1L shl (if (c <= '9') c - '0' + 26 else c - 'a')) }

    private fun maskOf(words: List<Token>): Long = words.fold(0L) { m, t -> m or t.mask }

    // The pairs of adjacent chars in a key, each hashed to one of 64 bits.
    private fun bigrams(key: String): Long {
        var bits = 0L
        for (i in 1 until key.length) bits = bits or (1L shl (((key[i - 1].code * 131 + key[i].code) * -0x61c88647) ushr 26))
        return bits
    }
}
