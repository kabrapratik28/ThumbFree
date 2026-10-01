package io.github.kabrapratik28.thumbfree.core.text

/** Formats the text ThumbFree is about to insert at the cursor: spacing it against what is already there, and
 * capitalizing it at a sentence start. Never lowercases. */
object CursorFormatter {
    private val OPEN_PUNCTUATION = "([{\"'“‘".map { it.code }.toIntArray()
    private val CLOSE_PUNCTUATION = ".,;:!?)]}".map { it.code }.toIntArray()

    /**
     * The text to commit at the cursor. [before]/[after]: text around the cursor, null when unreadable.
     * [capsExpected]: getCursorCapsMode(CAP_MODE_SENTENCES) != 0, null when unknown. Never lowercases.
     */
    fun payload(
        text: String,
        before: String?,
        after: String?,
        capsExpected: Boolean?,
        inputType: Int,
        trailingSpace: Boolean = false,
    ): String {
        val trimmed = text.trim(::isRustWhitespace)
        if (FieldKind.isExactText(inputType)) return trimmed

        val beforeLastCp = before?.takeIf { it.isNotEmpty() }?.lastCodePoint()
        val afterFirstCp = after?.takeIf { it.isNotEmpty() }?.firstCodePoint()

        // Letters or digits touching on both sides: a mid-word or mid-sentence insertion, leave it alone.
        if (beforeLastCp != null && isRustAlphanumeric(beforeLastCp) &&
            afterFirstCp != null && isRustAlphanumeric(afterFirstCp)
        ) {
            return trimmed
        }

        val textFirstCp = trimmed.takeIf { it.isNotEmpty() }?.firstCodePoint()
        val leading = beforeLastCp != null &&
            !isCodePointWhitespace(beforeLastCp) &&
            beforeLastCp !in OPEN_PUNCTUATION &&
            (textFirstCp == null || textFirstCp !in CLOSE_PUNCTUATION)
        val afterIsAlnum = afterFirstCp != null && isRustAlphanumeric(afterFirstCp)
        val afterStartsWhitespace = afterFirstCp != null && isCodePointWhitespace(afterFirstCp)
        val trailing = after != null && (afterIsAlnum || (trailingSpace && !afterStartsWhitespace))

        val capitalized = if (capsExpected == true) capitalizeFirstCodePoint(trimmed) else trimmed
        return buildString {
            if (leading) append(' ')
            append(capitalized)
            if (trailing) append(' ')
        }
    }

    private fun isCodePointWhitespace(cp: Int): Boolean = cp <= Char.MAX_VALUE.code && isRustWhitespace(cp.toChar())

    private fun capitalizeFirstCodePoint(text: String): String {
        if (text.isEmpty()) return text
        val cp = text.codePointAt(0)
        if (!Character.isLowerCase(cp)) return text
        return String(Character.toChars(Character.toUpperCase(cp))) + text.substring(Character.charCount(cp))
    }

    private fun String.firstCodePoint(): Int = codePointAt(0)
    private fun String.lastCodePoint(): Int = Character.codePointBefore(this, length)
}
