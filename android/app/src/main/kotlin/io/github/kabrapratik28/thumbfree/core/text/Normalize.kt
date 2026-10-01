package io.github.kabrapratik28.thumbfree.core.text

/**
 * Output normalization, adapted from MIT-licensed code; see THIRD_PARTY_NOTICES.md. It runs even with filler removal
 * off. A word made only of letters that appears 3 or more times in a row, ignoring case, is kept once with its
 * first spelling. Words are then joined with single spaces, so runs of whitespace (newlines included) become
 * one space and the ends are trimmed, which is what a `\s{2,}` replace and trim do.
 */
fun normalize(text: String): String {
    val words = splitWhitespace(text)
    val out = ArrayList<String>(words.size)
    var i = 0
    while (i < words.size) {
        val lower = words[i].lowercase()
        var count = 1
        if (lower.codePoints().allMatch(Character::isAlphabetic)) {
            while (i + count < words.size && words[i + count].lowercase() == lower) count++
        }
        out += words[i]
        i += if (count >= 3) count else 1
    }
    return out.joinToString(" ")
}
