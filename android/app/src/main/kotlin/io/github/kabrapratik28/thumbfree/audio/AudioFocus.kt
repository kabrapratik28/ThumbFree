package io.github.kabrapratik28.thumbfree.audio

import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager

/**
 * Transient exclusive focus for the length of a take, so media pauses while the user speaks. It never changes a volume
 * or the mic mute. [onLoss] runs when another app takes the focus outright (a call, for example); a request to duck
 * does not count.
 */
class AudioFocus(private val audioManager: AudioManager, private val onLoss: () -> Unit) {
    private val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        .setOnAudioFocusChangeListener { change ->
            if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) onLoss()
        }
        .build()

    fun request(): Boolean = audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED

    fun abandon() {
        audioManager.abandonAudioFocusRequest(request)
    }
}
