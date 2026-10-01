package io.github.kabrapratik28.thumbfree.audio

import android.Manifest
import android.media.AudioRecord
import com.google.common.truth.Truth.assertThat
import io.github.kabrapratik28.thumbfree.core.session.Code
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowAudioRecord

// Revoking a granted permission on a device kills the instrumented process, so this case runs on Robolectric.
@RunWith(RobolectricTestRunner::class)
class AudioRecordSourcePermissionTest {
    private val app = RuntimeEnvironment.getApplication()

    @Test
    fun missingPermissionIsTyped() {
        shadowOf(app).denyPermissions(Manifest.permission.RECORD_AUDIO)

        val error = assertThrows(CaptureException::class.java) { AudioRecordSource(app).start() }

        assertThat(error.code).isEqualTo(Code.MIC_PERMISSION)
    }

    // DEVICE_LOST makes the Recorder reopen the mic once; any other read error ends the take.
    @Test
    fun readErrorsAreTyped() {
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        var result = AudioRecord.ERROR_DEAD_OBJECT
        val mic = object : ShadowAudioRecord.AudioRecordSource {
            override fun readInShortArray(audioData: ShortArray, offsetInShorts: Int, sizeInShorts: Int, isBlocking: Boolean) = result
        }
        ShadowAudioRecord.setSourceProvider { mic }
        val source = AudioRecordSource(app)
        try {
            source.start()

            assertThat(assertThrows(CaptureException::class.java) { source.read(ShortArray(320)) }.code)
                .isEqualTo(Code.DEVICE_LOST)
            result = AudioRecord.ERROR
            assertThat(assertThrows(CaptureException::class.java) { source.read(ShortArray(320)) }.code)
                .isEqualTo(Code.MIC_UNAVAILABLE)
        } finally {
            source.release()
            ShadowAudioRecord.clearSource()
        }
    }

    // A blocking read on a stalled device never returns, and the Recorder checks its stall rule, the tail and cancel
    // only between reads. So reads must come back with 0 when nothing is ready, after a short wait instead of a spin.
    @Test
    fun stalledMicReadsReturnZeroWithoutSpinning() {
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        val stall = CountDownLatch(1)
        val mic = object : ShadowAudioRecord.AudioRecordSource {
            override fun readInShortArray(audioData: ShortArray, offsetInShorts: Int, sizeInShorts: Int, isBlocking: Boolean): Int {
                if (isBlocking) stall.await() // what AudioRecord does when no audio arrives
                return 0
            }
        }
        ShadowAudioRecord.setSourceProvider { mic }
        val source = AudioRecordSource(app)
        try {
            source.start()
            val counts = mutableListOf<Int>()
            val started = System.nanoTime()
            val reader = thread(isDaemon = true) { repeat(10) { counts += source.read(ShortArray(320)) } }
            reader.join(2_000)
            val elapsedMs = (System.nanoTime() - started) / 1_000_000

            assertThat(reader.isAlive).isFalse()
            assertThat(counts).isEqualTo(List(10) { 0 })
            assertThat(elapsedMs).isAtLeast(90L) // 10 reads that each wait about 10 ms, with room for an early wakeup
        } finally {
            stall.countDown()
            source.release()
            ShadowAudioRecord.clearSource()
        }
    }
}
