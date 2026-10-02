package io.github.kabrapratik28.thumbfree.core.session

import com.google.common.truth.Truth.assertThat
import org.junit.Test

// The bubble's grey look for each reason the speech model waits: its badge says the real state.
class GreyTest {
    // Downloading, its part on the ring; Wi-Fi or any connection, each its own badge; not started, a still badge and an
    // empty ring; a retry's pause, its own badge, so TalkBack says paused as the panel does; stopped, out of space or
    // damaged, the stop badge; checked or loaded, the full ring turning.
    @Test
    fun eachWaitHasItsOwnLook() {
        assertThat(Grey.of(SpeechWait.DOWNLOADING, 42)).isEqualTo(Grey(Grey.Badge.DOWNLOAD, 0.42f))
        assertThat(Grey.of(SpeechWait.WIFI, 42)).isEqualTo(Grey(Grey.Badge.WIFI, 0.42f))
        assertThat(Grey.of(SpeechWait.CONNECTION, 42)).isEqualTo(Grey(Grey.Badge.CONNECTION, 0.42f))
        assertThat(Grey.of(SpeechWait.NOT_STARTED, 0)).isEqualTo(Grey(Grey.Badge.NOT_STARTED, 0f))
        assertThat(Grey.of(SpeechWait.RETRYING, 10)).isEqualTo(Grey(Grey.Badge.RETRYING, 0.1f))
        for (stopped in listOf(SpeechWait.PAUSED, SpeechWait.NO_SPACE, SpeechWait.CHECK_FAILED)) {
            assertThat(Grey.of(stopped, 10)).isEqualTo(Grey(Grey.Badge.STOPPED, 0.1f))
        }
        assertThat(Grey.of(SpeechWait.PREPARING, 100)).isEqualTo(Grey.PREPARING)
    }
}
