package io.github.kabrapratik28.thumbfree.models

import io.github.kabrapratik28.thumbfree.core.session.SpeechWait

/**
 * What a take still needs, one thing at a time in setup order: the microphone, then the bubble's switch, then the chosen
 * speech model on the phone and checked. [Ready] only with all three. The welcome's last step, Home and the bubble all go
 * by it, so no screen says ready while another says something is missing.
 */
sealed interface Readiness {
    /** The microphone isn't allowed: nothing can be heard. */
    data object MicOff : Readiness

    /** The bubble's accessibility switch is off: no bubble shows in other apps. */
    data object BubbleOff : Readiness
    /** The chosen model is not usable yet: why ([wait]) and, while it downloads, how far ([percent]). */
    data class Speech(val wait: SpeechWait, val percent: Int) : Readiness

    /** The microphone, the bubble and the chosen model all work: a take can run. */
    data object Ready : Readiness

    companion object {
        /** [verified]: the chosen model checked out on disk; [download]: its download, null until known. */
        fun of(micGranted: Boolean, serviceEnabled: Boolean, verified: Boolean, download: DownloadState?): Readiness = when {
            !micGranted -> MicOff
            !serviceEnabled -> BubbleOff
            else -> speech(verified, download) ?: Ready
        }

        /**
         * The model's part alone, null once it is usable: for the bubble, whose service is on and whose take asks for the
         * microphone itself. A state not known yet reads as being prepared.
         */
        fun speech(verified: Boolean, download: DownloadState?): Speech? =
            if (verified || download == DownloadState.Ready) null else Speech(waitOf(download), percentOf(download))

        private fun waitOf(download: DownloadState?): SpeechWait = when (download) {
            is DownloadState.Downloading -> SpeechWait.DOWNLOADING
            is DownloadState.Queued -> when {
                download.retrying -> SpeechWait.RETRYING // it picks up where it stopped, by itself
                download.wifiOnly -> SpeechWait.WIFI
                else -> SpeechWait.CONNECTION
            }
            is DownloadState.Failed -> when (download.reason) {
                FailReason.NOT_ENOUGH_SPACE -> SpeechWait.NO_SPACE
                FailReason.FILE_CHECK_FAILED -> SpeechWait.CHECK_FAILED
                FailReason.NO_INTERNET, FailReason.INTERRUPTED -> SpeechWait.PAUSED // its partial file is kept
            }
            DownloadState.NotDownloaded -> SpeechWait.NOT_STARTED
            DownloadState.Verifying, DownloadState.Ready, null -> SpeechWait.PREPARING
        }

        /** How far a download is, in whole percent (rounded down, as the screens show it); 0 when it isn't downloading. */
        fun percentOf(download: DownloadState?): Int =
            (download as? DownloadState.Downloading)?.takeIf { it.total > 0 }?.let { (it.bytes * 100 / it.total).toInt().coerceIn(0, 100) } ?: 0
    }
}
