package io.github.kabrapratik28.thumbfree.audio

import io.github.kabrapratik28.thumbfree.core.session.Code

/** A microphone failure, with the [Code] the take ends with. */
class CaptureException(val code: Code, message: String? = null, cause: Throwable? = null) : Exception(message, cause)

/** The microphone as the Recorder sees it: AudioRecordSource on a device, a scripted fake in host tests. */
interface AudioSource {
    /** Opens the mic at 16 kHz mono PCM16. Throws CaptureException. */
    fun start()

    /** Blocks for the next samples and returns how many it read; 0 means nothing yet. Throws CaptureException. */
    fun read(buf: ShortArray): Int

    fun stop()

    fun release()

    /** True while the system feeds this client zeros, for example because another app took the mic. */
    val silenced: Boolean
}
