package io.github.kabrapratik28.thumbfree.core.text

// ponytail: always one space; scripts written without spaces (CJK, Thai) need a script-aware join if such a model ships.

/**
 * The text of a take cut into chunks: each chunk's text trimmed, empty ones dropped, one space between.
 * Cleanup then runs once on the whole text.
 */
fun joinChunks(chunks: List<String>): String =
    chunks.map { it.trim(::isRustWhitespace) }.filter { it.isNotEmpty() }.joinToString(" ")
