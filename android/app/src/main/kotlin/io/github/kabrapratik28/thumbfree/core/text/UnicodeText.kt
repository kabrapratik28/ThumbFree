package io.github.kabrapratik28.thumbfree.core.text

// Unicode predicates that match Rust's, so cleanup gives the same output on the JVM and on Android. No java.util.regex
// \s or \b: OpenJDK and Android's ICU-backed engine can disagree on them.

// Rust char::is_whitespace: the Unicode White_Space set, all of it in the BMP. Char.isWhitespace differs:
// it leaves out U+0085, U+00A0, U+2007 and U+202F and adds U+001C to U+001F.
private val WHITESPACE = charArrayOf(
    '\t', '\n', '\u000B', '\u000C', '\r', ' ', '\u0085', '\u00A0', '\u1680',
    '\u2000', '\u2001', '\u2002', '\u2003', '\u2004', '\u2005', '\u2006', '\u2007', '\u2008', '\u2009', '\u200A',
    '\u2028', '\u2029', '\u202F', '\u205F', '\u3000',
)

fun isRustWhitespace(c: Char): Boolean = c in WHITESPACE

/** Rust str::split_whitespace: the words between runs of whitespace, never an empty string. */
fun splitWhitespace(text: String): List<String> = text.split(*WHITESPACE).filter { it.isNotEmpty() }

/** Rust char::is_alphanumeric: Alphabetic, or a number of any kind (Nd, Nl, No). */
fun isRustAlphanumeric(cp: Int): Boolean = Character.isAlphabetic(cp) || when (Character.getType(cp).toByte()) {
    Character.DECIMAL_DIGIT_NUMBER, Character.LETTER_NUMBER, Character.OTHER_NUMBER -> true
    else -> false
}

/** Rust regex \w, which its \b uses: Alphabetic, marks, decimal digits, connector punctuation, U+200C and U+200D. */
fun isRustWordChar(cp: Int): Boolean = Character.isAlphabetic(cp) || cp == 0x200C || cp == 0x200D ||
    when (Character.getType(cp).toByte()) {
        Character.NON_SPACING_MARK, Character.ENCLOSING_MARK, Character.COMBINING_SPACING_MARK,
        Character.DECIMAL_DIGIT_NUMBER, Character.CONNECTOR_PUNCTUATION -> true
        else -> false
    }
