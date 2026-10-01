package io.github.kabrapratik28.thumbfree.audio

import android.media.AudioAttributes
import android.media.AudioManager
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class AudioFocusTest {
    private val audioManager = RuntimeEnvironment.getApplication().getSystemService(AudioManager::class.java)
    private var losses = 0
    private val focus = AudioFocus(audioManager) { losses++ }

    private fun lastListener() = shadowOf(audioManager).lastAudioFocusRequest.listener

    @Test
    fun requestIsTransientExclusiveForSpeech() {
        focus.request()
        val request = shadowOf(audioManager).lastAudioFocusRequest.audioFocusRequest

        assertThat(request.focusGain).isEqualTo(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
        assertThat(request.audioAttributes.usage).isEqualTo(AudioAttributes.USAGE_ASSISTANT)
        assertThat(request.audioAttributes.contentType).isEqualTo(AudioAttributes.CONTENT_TYPE_SPEECH)

        focus.abandon()
        assertThat(shadowOf(audioManager).lastAbandonedAudioFocusRequest).isSameInstanceAs(request)
    }

    @Test
    fun neverTouchesVolumeOrMute() {
        val streams = listOf(AudioManager.STREAM_MUSIC, AudioManager.STREAM_RING, AudioManager.STREAM_VOICE_CALL)
        streams.forEachIndexed { i, stream -> audioManager.setStreamVolume(stream, i + 2, 0) }
        val volumes = streams.map(audioManager::getStreamVolume)

        focus.request()
        focus.abandon()

        assertThat(streams.map(audioManager::getStreamVolume)).isEqualTo(volumes)
        assertThat(audioManager.isMicrophoneMute).isFalse()
    }

    @Test
    fun lossCallsBack() {
        focus.request()

        lastListener().onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        assertThat(losses).isEqualTo(1)
        lastListener().onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS)
        assertThat(losses).isEqualTo(2)
    }

    @Test
    fun duckingDoesNotCallBack() {
        focus.request()

        lastListener().onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)

        assertThat(losses).isEqualTo(0)
    }
}
