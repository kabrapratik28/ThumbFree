package io.github.kabrapratik28.thumbfree.core.text

/**
 * Cleanup for one dictation, in this order: custom words, then filler removal, then
 * [normalize]. [language] is the output language as an ISO 639-1 code, or null when unknown (see [removeFillers]).
 * [customWords] is the custom-word correction step; there is none by default.
 * Fails open: if a step throws, the raw text comes back.
 */
fun cleanup(raw: String, language: String?, customWords: (String) -> String = { it }): String =
    runCatching { normalize(removeFillers(customWords(raw), language)) }.getOrDefault(raw)
