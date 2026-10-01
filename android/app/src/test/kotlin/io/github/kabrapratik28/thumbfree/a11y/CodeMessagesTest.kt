package io.github.kabrapratik28.thumbfree.a11y

import com.google.common.truth.Truth.assertThat
import io.github.kabrapratik28.thumbfree.core.session.Code
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class CodeMessagesTest {
    private val context = RuntimeEnvironment.getApplication()

    @Test
    fun everyCodeHasAMessage() {
        for (code in Code.entries) {
            val message = context.getString(CodeMessages.of(code))
            assertThat(message.isBlank()).isFalse()
            assertThat(message).doesNotContain("—")
        }
    }

    @Test
    fun noSpeechMessage() {
        assertThat(context.getString(CodeMessages.of(Code.NO_SPEECH))).isEqualTo("No speech heard.")
    }
}
