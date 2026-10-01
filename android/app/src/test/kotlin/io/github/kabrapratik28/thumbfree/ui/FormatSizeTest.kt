package io.github.kabrapratik28.thumbfree.ui

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FormatSizeTest {
    @Test
    fun decimalUnits() {
        assertThat(formatSize(731_357_568)).isEqualTo("731 MB")
        assertThat(formatSize(477_274_496)).isEqualTo("477 MB")
        assertThat(formatSize(1_558_162_944)).isEqualTo("1.6 GB")
        assertThat(formatSize(999_999)).isEqualTo("1 MB")
    }
}
