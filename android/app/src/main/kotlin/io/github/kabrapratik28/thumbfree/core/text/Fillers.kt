package io.github.kabrapratik28.thumbfree.core.text

// Not a word in any language the models output, so safe to remove with no language known.
private val UNIVERSAL_FILLERS = listOf("uh", "uhm", "umm", "uhh", "uhhh", "ehh", "ehm", "ahm", "hmm", "hm", "mmm", "хм", "ммм")

// Real words elsewhere (Portuguese and German "um", Spanish "ha"), so these need the language.
private fun gatedFillers(language: String): List<String> = when (language.substringBefore('-').substringBefore('_')) {
    "en" -> listOf("um", "ah", "eh", "ha")
    "de" -> listOf("äh", "ähm")
    "fr" -> listOf("euh")
    else -> emptyList()
}

/**
 * Filler removal, adapted from MIT-licensed code; see THIRD_PARTY_NOTICES.md. [language] is the output language as an
 * ISO 639-1 code, or null when unknown, which removes only the universal fillers (fail closed). A [custom] list replaces
 * both built-in lists and needs no language; an empty one removes nothing. With [enabled] false the text comes back
 * unchanged.
 * Each filler goes as a whole word in any case, together with one comma or period right after it.
 */
fun removeFillers(text: String, language: String?, custom: List<String>? = null, enabled: Boolean = true): String {
    if (!enabled) return text
    val fillers = custom ?: (UNIVERSAL_FILLERS + language?.let(::gatedFillers).orEmpty())
    return fillers.fold(text, ::removeWord)
}

// The regex (?i)\b<word>\b[,.]? replaced with nothing, matched by hand so the JVM and
// Android agree. Case is compared char by char, which equals the regex's case folding for every built-in filler.
private fun removeWord(text: String, word: String): String {
    // "" matches at every word boundary: that regex then strips the comma or period after every word, and a
    // naive loop never ends. Remove nothing instead.
    if (word.isEmpty()) return text
    val out = StringBuilder(text.length)
    var kept = 0 // start of the text not yet copied to out
    var i = 0
    while (i + word.length <= text.length) {
        if (isBoundary(text, i) && text.regionMatches(i, word, 0, word.length, ignoreCase = true) &&
            isBoundary(text, i + word.length)
        ) {
            out.append(text, kept, i)
            i += word.length
            if (i < text.length && (text[i] == ',' || text[i] == '.')) i++
            kept = i
        } else {
            i++
        }
    }
    return out.append(text, kept, text.length).toString()
}

// Rust regex \b: a word character on exactly one side of index i.
private fun isBoundary(text: String, i: Int): Boolean =
    (i > 0 && isRustWordChar(Character.codePointBefore(text, i))) !=
        (i < text.length && isRustWordChar(Character.codePointAt(text, i)))
