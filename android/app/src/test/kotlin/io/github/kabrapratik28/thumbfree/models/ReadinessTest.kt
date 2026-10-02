package io.github.kabrapratik28.thumbfree.models

import com.google.common.truth.Truth.assertThat
import io.github.kabrapratik28.thumbfree.core.session.SpeechWait
import org.junit.Test

// One rule for the welcome's last step, Home and the bubble: what a take still needs, one thing at a time.
class ReadinessTest {
    private val downloading = DownloadState.Downloading(310_000_000, 731_357_568) // 42.4%

    // The microphone first, then the bubble, then the speech model; ready only with all three, whatever the download says.
    @Test
    fun oneThingAtATimeInSetupOrder() {
        assertThat(Readiness.of(micGranted = false, serviceEnabled = false, verified = false, download = null)).isEqualTo(Readiness.MicOff)
        assertThat(Readiness.of(false, true, true, DownloadState.Ready)).isEqualTo(Readiness.MicOff)
        assertThat(Readiness.of(true, false, true, DownloadState.Ready)).isEqualTo(Readiness.BubbleOff)
        assertThat(Readiness.of(true, true, false, downloading)).isEqualTo(Readiness.Speech(SpeechWait.DOWNLOADING, 42))
        assertThat(Readiness.of(true, true, true, null)).isEqualTo(Readiness.Ready)
        assertThat(Readiness.of(true, true, false, DownloadState.Ready)).isEqualTo(Readiness.Ready)
    }

    // The model's part says why it isn't usable: a wait, a stop and its cause, the check, or a download never started. A
    // state not known yet reads as being prepared, never as ready.
    @Test
    fun eachDownloadStateSaysWhy() {
        val why = mapOf(
            downloading to SpeechWait.DOWNLOADING,
            DownloadState.Queued(wifiOnly = true) to SpeechWait.WIFI,
            DownloadState.Queued(wifiOnly = false) to SpeechWait.CONNECTION,
            DownloadState.Queued(wifiOnly = true, retrying = true) to SpeechWait.RETRYING, // picks up where it stopped, by itself
            DownloadState.Verifying to SpeechWait.PREPARING,
            DownloadState.Failed(FailReason.NO_INTERNET) to SpeechWait.PAUSED,
            DownloadState.Failed(FailReason.INTERRUPTED) to SpeechWait.PAUSED,
            DownloadState.Failed(FailReason.NOT_ENOUGH_SPACE) to SpeechWait.NO_SPACE,
            DownloadState.Failed(FailReason.FILE_CHECK_FAILED) to SpeechWait.CHECK_FAILED,
            DownloadState.NotDownloaded to SpeechWait.NOT_STARTED,
            null to SpeechWait.PREPARING,
        )
        for ((download, wait) in why) {
            assertThat(Readiness.speech(verified = false, download = download)?.wait).isEqualTo(wait)
        }
        assertThat(Readiness.speech(verified = false, download = DownloadState.Ready)).isNull()
        assertThat(Readiness.speech(verified = true, download = DownloadState.NotDownloaded)).isNull()
        assertThat(Readiness.percentOf(downloading)).isEqualTo(42)
        assertThat(Readiness.percentOf(DownloadState.Downloading(0, 0))).isEqualTo(0)
        assertThat(Readiness.percentOf(DownloadState.Verifying)).isEqualTo(0)
    }
}
