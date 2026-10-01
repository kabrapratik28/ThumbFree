package io.github.kabrapratik28.thumbfree.core.text

/**
 * Soundex exactly as the natural 0.5.0 crate (Rust) computes it, for custom words. Unlike American Soundex the first
 * char is kept as is, so a first letter never merges with the digit after it: "pfister" is p123 (not P236). Made for
 * lowercase ASCII keys: any other char counts as a vowel.
 */
object Soundex {
    /**
     * Keep the first char, map the rest, drop 9s, collapse runs, drop 0s, pad or cut to 4. In one loop, since custom
     * words ask for the code of each n-gram.
     */
    fun code(key: String): String {
        val out = StringBuilder(4)
        var previous = '\u0000' // the char before, once 9s are dropped: runs of one char collapse to it
        for (i in key.indices) {
            val c = if (i == 0) key[0] else digit(key[i])
            if (c == '9' || c == previous) continue
            previous = c
            if (c != '0' && out.length < 4) out.append(c)
        }
        while (out.length < 4) out.append('0')
        return out.toString()
    }

    private fun digit(c: Char): Char = when (c) {
        'b', 'f', 'p', 'v' -> '1'
        'c', 'g', 'j', 'k', 'q', 's', 'x', 'z' -> '2'
        'd', 't' -> '3'
        'l' -> '4'
        'm', 'n' -> '5'
        'r' -> '6'
        'h', 'w' -> '9' // dropped before runs collapse, so the letters on both sides of h or w merge
        else -> '0' // vowels and y; dropped after runs collapse, so they keep equal letters apart
    }
}
