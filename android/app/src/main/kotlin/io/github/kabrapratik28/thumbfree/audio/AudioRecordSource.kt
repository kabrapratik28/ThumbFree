package io.github.kabrapratik28.thumbfree.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRecordingConfiguration
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import io.github.kabrapratik28.thumbfree.core.session.Code
import java.util.concurrent.locks.LockSupport

/**
 * The device microphone: AudioRecord with VOICE_RECOGNITION (noise suppression and AGC off by default, CDD 5.4.2) at
 * 16 kHz mono PCM16. Reads never block: a blocking read on a stalled device may never return, and the Recorder checks
 * its stall rule, the tail and cancel only between reads. A read with nothing ready waits 10 ms and returns 0, so the
 * capture loop does not spin either. The Recorder handles only CaptureException and SecurityException, so every
 * failure here becomes a CaptureException, and stop() and release() never throw. start() after stop() builds a new
 * record, which is how the Recorder reopens after DEVICE_LOST.
 */
class AudioRecordSource(private val context: Context) : AudioSource {
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var record: AudioRecord? = null

    @Volatile private var sessionId = 0

    @Volatile override var silenced = false
        private set

    private val silencing = object : AudioManager.AudioRecordingCallback() {
        override fun onRecordingConfigChanged(configs: List<AudioRecordingConfiguration>) {
            silenced = configs.firstOrNull { it.clientAudioSessionId == sessionId }?.isClientSilenced ?: false
        }
    }

    // What the open record really runs at, for the 16 kHz probe; 0 while nothing is open.
    val sampleRate: Int get() = record?.sampleRate ?: 0
    val channelCount: Int get() = record?.channelCount ?: 0
    val encoding: Int get() = record?.audioFormat ?: AudioFormat.ENCODING_INVALID

    override fun start() {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            throw CaptureException(Code.MIC_PERMISSION, "RECORD_AUDIO is not granted")
        }
        release() // a dead record does not come back, so a reopen starts from a new one
        val minBuffer = AudioRecord.getMinBufferSize(RATE_HZ, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val notReady = if (minBuffer == AudioRecord.ERROR_BAD_VALUE) Code.FORMAT_UNSUPPORTED else Code.MIC_UNAVAILABLE

        fun fail(code: Code, message: String, cause: Throwable? = null): Nothing {
            release()
            throw CaptureException(code, message, cause)
        }

        val r = try {
            AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                .setAudioFormat(
                    AudioFormat.Builder().setSampleRate(RATE_HZ).setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build(),
                )
                .setBufferSizeInBytes(maxOf(4 * minBuffer, 16_000))
                .build()
        } catch (e: SecurityException) {
            fail(Code.MIC_PERMISSION, "AudioRecord refused RECORD_AUDIO", e)
        } catch (e: RuntimeException) { // build() throws UnsupportedOperationException when the record did not initialize
            fail(notReady, "AudioRecord did not initialize, min buffer $minBuffer", e)
        }
        record = r
        if (r.state != AudioRecord.STATE_INITIALIZED) fail(notReady, "AudioRecord did not initialize, min buffer $minBuffer")
        sessionId = r.audioSessionId
        silenced = false
        try {
            // Registered before startRecording, so the change that starts this client, silenced or not, is not missed.
            audioManager.registerAudioRecordingCallback(silencing, mainHandler)
            r.startRecording()
        } catch (e: RuntimeException) { // IllegalStateException, or the audio service died
            fail(Code.MIC_UNAVAILABLE, "startRecording failed", e)
        }
        if (r.recordingState != AudioRecord.RECORDSTATE_RECORDING) fail(Code.MIC_UNAVAILABLE, "AudioRecord did not start")
    }

    override fun read(buf: ShortArray): Int {
        val r = record ?: throw CaptureException(Code.MIC_UNAVAILABLE, "read before start")
        val n = try {
            r.read(buf, 0, buf.size, AudioRecord.READ_NON_BLOCKING)
        } catch (e: RuntimeException) {
            throw CaptureException(Code.MIC_UNAVAILABLE, "AudioRecord.read threw", e)
        }
        if (n == 0) LockSupport.parkNanos(this, IDLE_NS)
        if (n >= 0) return n
        val code = if (n == AudioRecord.ERROR_DEAD_OBJECT) Code.DEVICE_LOST else Code.MIC_UNAVAILABLE
        throw CaptureException(code, "AudioRecord.read returned $n")
    }

    override fun stop() {
        runCatching { record?.stop() }
    }

    override fun release() {
        val r = record ?: return // the callback is registered only while a record is held
        record = null
        runCatching { audioManager.unregisterAudioRecordingCallback(silencing) }
        runCatching { r.release() } // stops the record first if it is recording
    }

    private companion object {
        const val RATE_HZ = 16_000
        const val IDLE_NS = 10_000_000L // how long a read with nothing ready waits before it returns 0
    }
}
