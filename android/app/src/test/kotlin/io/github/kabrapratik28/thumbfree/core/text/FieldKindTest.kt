package io.github.kabrapratik28.thumbfree.core.text

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class FieldKindTest {
    @Test
    fun passwordVariants() {
        assertThat(FieldKind.isPassword(0x81, false)).isTrue()
        assertThat(FieldKind.isPassword(0x91, false)).isTrue()
        assertThat(FieldKind.isPassword(0xe1, false)).isTrue()
        assertThat(FieldKind.isPassword(0x12, false)).isTrue()
        assertThat(FieldKind.isPassword(0x1, false)).isFalse()
        assertThat(FieldKind.isPassword(0x1, true)).isTrue()
    }

    @Test
    fun isDictatable() {
        assertThat(FieldKind.isDictatable(0x1, false)).isTrue()
        assertThat(FieldKind.isDictatable(0x21, false)).isTrue()
        assertThat(FieldKind.isDictatable(0x0, false)).isTrue()
        assertThat(FieldKind.isDictatable(0x2, false)).isFalse()
        assertThat(FieldKind.isDictatable(0x3, false)).isFalse()
        assertThat(FieldKind.isDictatable(0x4, false)).isFalse()
        assertThat(FieldKind.isDictatable(0x81, false)).isFalse()
        assertThat(FieldKind.isDictatable(0x1, true)).isFalse()
    }

    @Test
    fun exactText() {
        assertThat(FieldKind.isExactText(0x11)).isTrue()
        assertThat(FieldKind.isExactText(0x21)).isTrue()
        assertThat(FieldKind.isExactText(0xd1)).isTrue()
        assertThat(FieldKind.isExactText(0x81)).isTrue()
        assertThat(FieldKind.isExactText(0x1)).isFalse()
        assertThat(FieldKind.isExactText(0x2)).isFalse()
    }

    // The only place in this file tree allowed to import android.*: it lives under test/, not core.
    @Test
    fun matchesAndroidConstants() {
        assertThat(FieldKind.TYPE_CLASS_TEXT).isEqualTo(android.text.InputType.TYPE_CLASS_TEXT)
        assertThat(FieldKind.CLASS_MASK).isEqualTo(android.text.InputType.TYPE_MASK_CLASS)
        assertThat(FieldKind.VARIATION_MASK).isEqualTo(android.text.InputType.TYPE_MASK_VARIATION)
    }
}
