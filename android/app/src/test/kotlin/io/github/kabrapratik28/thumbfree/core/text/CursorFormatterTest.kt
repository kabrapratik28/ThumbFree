package io.github.kabrapratik28.thumbfree.core.text

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

class CursorFormatterTest {
    private data class Row(
        val text: String,
        val before: String?,
        val after: String?,
        val caps: Boolean?,
        val inputType: Int,
        val trailingSpace: Boolean,
        val expected: String,
    )

    @Test
    fun payload() {
        val text = FieldKind.TYPE_CLASS_TEXT
        val rows = listOf(
            Row("hello world", "", "", true, text, false, "Hello world"),
            Row("Hello", "I said.", "", true, text, false, " Hello"),
            Row("hello", "I said. ", "", true, text, false, "Hello"),
            Row("there", "Hello", "", false, text, false, " there"),
            Row("Paris", "I went to", "", false, text, false, " Paris"),
            Row("world", "Hello ", "", false, text, false, "world"),
            Row("big", "Hello ", "world", false, text, false, "big "),
            Row("big", "Hello ", " world", false, text, false, "big"),
            Row("quote", "(", ")", false, text, false, "quote"),
            Row(", okay", "Yes", "", false, text, false, ", okay"),
            Row("mid", "abc", "def", false, text, false, "mid"),
            Row("example.com", "www.", "", false, 0x11, false, "example.com"),
            Row("me@x.com", "mail ", "", false, 0x21, false, "me@x.com"),
            Row("done", "", "", null, text, true, "done "),
            Row("done", "", " next", null, text, true, "done"),
            Row("  padded  ", "", "", null, text, false, "padded"),
            Row("hi", null, null, null, text, false, "hi"),
            Row("hello", "line\n", "", true, text, false, "Hello"),
            Row("😀 ok", "Hi", "", false, text, false, " 😀 ok"),
            Row("iPhone", "", "", true, text, false, "IPhone"),
        )

        for ((i, row) in rows.withIndex()) {
            val actual = CursorFormatter.payload(row.text, row.before, row.after, row.caps, row.inputType, row.trailingSpace)
            assertWithMessage("row ${i + 1}: ${row.text}").that(actual).isEqualTo(row.expected)
        }
        assertThat(rows).hasSize(20)
    }
}
