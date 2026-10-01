package io.github.kabrapratik28.thumbfree.audio

import android.Manifest
import android.media.AudioFormat
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import io.github.kabrapratik28.thumbfree.testing.automation
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AudioRecordSourceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    // What GrantPermissionRule would do; androidx.test:rules is not a dependency.
    @Before
    fun grantMic() = automation().grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)

    // The timeout fails the test, instead of looping on the shared emulator forever, if the mic never delivers.
    @Test(timeout = 10_000)
    fun opensAt16kHzReadsAndReleases() {
        val source = AudioRecordSource(context)
        try {
            source.start()
            val started = SystemClock.elapsedRealtime()
            val buf = ShortArray(320)
            var reads = 0
            var total = 0
            var end = Long.MAX_VALUE // the second of reading starts at the first buffer, so cold start is not counted
            while (SystemClock.elapsedRealtime() < end) {
                val n = source.read(buf)
                if (n <= 0) continue
                if (reads == 0) end = SystemClock.elapsedRealtime() + 1_000
                reads++
                total += n
            }
            Log.i(
                TAG,
                "probe first_buffer_ms=${end - 1_000 - started} reads=$reads total=$total rate=${source.sampleRate} " +
                    "channels=${source.channelCount} encoding=${source.encoding}",
            )

            assertThat(source.sampleRate).isEqualTo(16_000)
            assertThat(source.channelCount).isEqualTo(1)
            assertThat(source.encoding).isEqualTo(AudioFormat.ENCODING_PCM_16BIT)
            assertThat(reads).isAtLeast(40)
            assertThat(total).isAtLeast(14_000)
            source.stop()
            source.release()
        } finally {
            source.release() // the second release on the passing path: it must not throw
        }
    }

    private companion object {
        const val TAG = "ThumbFree"
    }
}
