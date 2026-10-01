package io.github.kabrapratik28.thumbfree.testing

const val JFK_TEXT =
    "and so my fellow americans ask not what your country can do for you ask what you can do for your country"

/**
 * assets audio/fleurs-de.wav: FLEURS (google/fleurs, CC BY 4.0, Conneau et al. 2022), the German test clip
 * 10058299886985225661.wav at revision 70bb2e84b976b7e960aa89f1c648e09c59f894dd, as 16 kHz PCM16. Its reference text,
 * as [normalizeWords] gives it: "um" is a word here, which English filler removal would drop.
 */
const val FLEURS_DE_TEXT = "dieses sediment war nötig um sandbänke und strände zu bilden die als lebensräume für wildtiere dienten"

/** Lowercase, keep a-z, 0-9 and spaces, collapse runs of spaces, trim. */
fun normalizeTranscript(text: String) = text.lowercase().replace(Regex("[^a-z0-9 ]"), " ").replace(Regex(" +"), " ").trim()

/** [normalizeTranscript] for any language: lowercase letters and digits of any script, anything else one space. */
fun normalizeWords(text: String) = text.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
