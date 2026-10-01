package io.github.kabrapratik28.thumbfree.core.text

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class UnicodeTextTest {
    @Test
    fun whitespaceSetMatchesRust() {
        // Rust char::is_whitespace is the Unicode White_Space set. All of it is in the BMP.
        val rust = (0x09..0x0D) + 0x20 + 0x85 + 0xA0 + 0x1680 + (0x2000..0x200A) + 0x2028 + 0x2029 + 0x202F + 0x205F + 0x3000
        assertThat((0..0xFFFF).filter { isRustWhitespace(it.toChar()) }).containsExactlyElementsIn(rust).inOrder()
        // Char.isWhitespace gets these wrong: U+0085, U+00A0, U+2007 and U+202F split words, U+001C does not.
        assertThat(splitWhitespace(" a\u0085b\u00A0c\u2007d\u202Fe\u001Cf "))
            .containsExactly("a", "b", "c", "d", "e\u001Cf").inOrder()
    }

    @Test
    fun alphanumericMatchesRust() {
        // Rust char::is_alphanumeric: Alphabetic or any number (Nd, Nl, No). Char.isLetterOrDigit misses Nl and No.
        val cases = mapOf(
            "a" to true, "É" to true, "你" to true, "5" to true,
            "Ⅻ" to true, // letter number (Nl)
            "\uD800\uDF48" to true, // U+10348, a letter number outside the BMP
            "²" to true, "½" to true, "①" to true, // other numbers (No)
            "\u0903" to true, // Devanagari visarga, a mark that is Alphabetic
            "\u0301" to false, // combining acute, a mark that is not
            "_" to false, "-" to false, " " to false, "。" to false,
        )
        assertThat(cases.mapValues { isRustAlphanumeric(it.key.codePointAt(0)) }).containsExactlyEntriesIn(cases)
    }

    @Test
    fun wordCharMatchesRustRegex() {
        // Rust regex \w, which \b uses: Alphabetic, marks, Nd, Pc, U+200C and U+200D. Not the same as is_alphanumeric.
        val cases = mapOf(
            "a" to true, "ä" to true, "х" to true, "5" to true, "Ⅻ" to true,
            "_" to true, // connector punctuation
            "\u0301" to true, // any mark
            "\u200D" to true, // zero width joiner
            "²" to false, // other number
            "-" to false, " " to false, "。" to false, "😀" to false,
        )
        assertThat(cases.mapValues { isRustWordChar(it.key.codePointAt(0)) }).containsExactlyEntriesIn(cases)
    }
}
