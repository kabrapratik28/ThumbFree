package io.github.kabrapratik28.thumbfree.audio

import android.os.Process
import io.github.kabrapratik28.thumbfree.core.audio.Chunk
import io.github.kabrapratik28.thumbfree.core.audio.ChunkPlanner
import io.github.kabrapratik28.thumbfree.core.audio.Levels
import io.github.kabrapratik28.thumbfree.core.audio.SpeechGate
import io.github.kabrapratik28.thumbfree.core.audio.SpeechGate.Companion.FLOOR_FRAMES
import io.github.kabrapratik28.thumbfree.core.audio.SpeechGate.Companion.FRAME_SAMPLES
import io.github.kabrapratik28.thumbfree.core.audio.SpeechGate.Companion.SPEECH_MIN_DBFS
import io.github.kabrapratik28.thumbfree.core.audio.StorageFullException
import io.github.kabrapratik28.thumbfree.core.audio.WavWriter
import io.github.kabrapratik28.thumbfree.core.session.Code
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport
import kotlin.concurrent.thread

/**
 * How a take ended: the samples in its WAV, whether a chunk has a frame above -55 dBFS, so the take may hold speech and
 * its chunks go to Silero's speech check, the error that stopped it, if any, and how the stop tail ended and how long
 * it ran on the clock (null and 0 when no stop ended the take).
 */
data class RecordingResult(
    val samples: Long,
    val hasSpeech: Boolean,
    val error: Code?,
    val tail: TailEnd? = null,
    val tailMs: Long = 0,
)

/** Why the stop tail ended: the user had already paused, sound stopped for the hangover, or the 350 ms cap. */
enum class TailEnd { QUIET, HANGOVER, CAP }

/**
 * Records one take. A capture thread reads 20 ms blocks from [source] into a preallocated ring. A writer thread drains
 * the ring into the WAV and feeds each block to the speech gate, the level ring and the chunk planner. Samples are
 * never dropped or repeated: an overflow or a full disk ends the take and keeps the contiguous prefix. With a [preview]
 * ring (the live preview), the writer also puts every block there until the stop, for the preview feed, whose Silero
 * gate in :engine picks the speech; a full ring only stops the preview.
 */
class Recorder(
    private val source: AudioSource,
    private val openWriter: () -> WavWriter, // called on the writer thread, so no disk work runs on the caller's thread
    private val listener: Listener,
    private val clock: () -> Long,
    private val tailMs: Long = 350,
    ringSamples: Int = 160_000,
    private val preview: PcmRing? = null,
) {
    /** onFirstBuffer and mic errors come on the capture thread; the rest, STORAGE_FULL too, on the writer thread. */
    interface Listener {
        fun onFirstBuffer()
        fun onLevel(unit: Float) // throttled to 30 per second
        fun onChunk(chunk: Chunk) // chunks closed while recording, and the last one at stop
        fun onSilentMic() // at most once per take
        fun onError(code: Code) // capture keeps what it has and moves to stop
        fun onStopped(result: RecordingResult)
    }

    private val ring = PcmRing(ringSamples)
    private val capSamples = (tailMs * 16 + BLOCK_SAMPLES - 1) / BLOCK_SAMPLES * BLOCK_SAMPLES // the cap's whole reads
    private val error = AtomicReference<Code?>()
    private val stopped = CountDownLatch(1)

    @Volatile private var stop: Stop? = null

    @Volatile private var tail: TailEnd? = null // how the stop tail ended and how long it ran, set by the capture thread

    @Volatile private var tailRanMs = 0L

    @Volatile private var cancelled = false

    @Volatile private var captureDone = false

    @Volatile private var lastSpeechAt = 0L

    @Volatile private var judged = 0L // samples the writer has fed to the gate, in whole frames

    @Volatile private var lastSound = 0L // sample at the end of the last frame that was speech or above -55 dBFS

    /** clock() minus the time of the last speech frame, or of start() before any speech. */
    val msSinceSpeech: Long get() = clock() - lastSpeechAt

    /** Opens the WAV and then the mic, off the caller's thread. Any failure goes to onError, then onStopped. */
    fun start() {
        lastSpeechAt = clock()
        thread(name = "recorder-writer") {
            try {
                write()
            } finally {
                stopped.countDown()
            }
        }
    }

    /** Waits up to [timeoutMs] for the end of the take: the WAV finished and synced, and onStopped called. */
    fun awaitStopped(timeoutMs: Long): Boolean = stopped.await(timeoutMs, TimeUnit.MILLISECONDS)

    /**
     * Keeps reading until the stop tail ends, then stops the mic, drains the ring and finishes the WAV. When the last
     * 500 ms of audio had no frame that was speech or above -55 dBFS, the user has already paused: no tail, the read in
     * progress is the last. Otherwise the tail ends once TAIL_FLOOR_MS of audio after the stop and HANGOVER_MS after
     * the last such frame are judged, and zeros fill the WAV to the cap's length; after tailMs on the clock at the
     * latest. Quiet is counted in samples the writer has judged, so a writer more than 50 ms behind the mic keeps the
     * tail.
     */
    fun requestStop() {
        if (stop != null) return
        val now = clock()
        val end = judged // first: lastSound and ring.written, read after it, are then at least as new
        val read = ring.written
        stop = Stop(now, read, end - lastSound >= QUIET_SAMPLES && read - end <= UNJUDGED_SAMPLES)
    }

    /** Stops the mic now, without the tail. What was read still goes to the WAV, which is kept. */
    fun cancel() {
        cancelled = true
    }

    /** Sets the take's error and reports it, unless an earlier error already ended the take. */
    private fun fail(code: Code) {
        if (error.compareAndSet(null, code)) listener.onError(code)
    }

    private fun write() {
        val wav = try {
            openWriter()
        } catch (e: IOException) { // StorageFullException under 64 MiB free, or the file could not be made
            fail(Code.STORAGE_FULL)
            source.release()
            listener.onStopped(RecordingResult(0, false, Code.STORAGE_FULL))
            return
        }
        val writer = Thread.currentThread()
        thread(name = "recorder-capture") { capture(writer) }

        val gate = SpeechGate()
        var previewing = preview != null // the live preview's audio: until the stop, and while the ring has room
        val levels = Levels()
        val planner = ChunkPlanner()
        val block = ShortArray(BLOCK_SAMPLES)
        var diskFull = false
        var silentMicRaised = false
        var mayHoldSpeech = false // a chunk has a frame above -55 dBFS
        var now = 0L // the clock when the block was taken from the ring
        val onFrame = { dbfs: Float, speech: Boolean -> // made once, not per block
            planner.add(dbfs, speech)?.let { chunk ->
                try {
                    wav.sync()
                } catch (e: StorageFullException) {
                    diskFull = true
                    fail(Code.STORAGE_FULL)
                }
                mayHoldSpeech = mayHoldSpeech || chunk.hasSpeech
                listener.onChunk(gate.withFirstFrames(chunk)) // its samples are in the file either way
            }
            levels.onFrame(dbfs, now)?.let(listener::onLevel)
            val end = judged + FRAME_SAMPLES
            // The gate judges the first 3 s when they end, so their speech counts as heard then: the 120 s silence stop can
            // come up to 3 s late, never early.
            if (speech || (end == FIRST_WINDOW_END && gate.firstSpeechFrames > 0)) lastSpeechAt = now
            if (speech || dbfs > SPEECH_MIN_DBFS) lastSound = end
            judged = end // after lastSound: whoever sees this frame judged sees its sound too
        }
        while (true) {
            val done = captureDone // read before the ring, so the last blocks are drained
            val n = ring.read(block)
            if (n == 0) {
                if (done) break
                LockSupport.park(this)
                continue
            }
            if (diskFull) continue // WavWriter.append must not run again after it threw; the take is ending
            now = clock()
            try {
                wav.append(block, n)
            } catch (e: StorageFullException) {
                diskFull = true
                fail(Code.STORAGE_FULL)
                continue
            }
            previewing = previewing && stop == null // the stop ends the preview's audio: the tail and fill stay out
            if (previewing) previewing = preview!!.write(block, n)
            gate.add(block, n, onFrame)
            if (!silentMicRaised && gate.silentMic) {
                silentMicRaised = true
                listener.onSilentMic()
            }
        }
        // The model's last word needs the silence after it (a shorter silence breaks more final words than it fixes),
        // so a tail that ended early is filled with zeros to the length the cap would have recorded. The mic closes
        // early; the engine hears the same length as before. The zeros go through the gate and the planner like audio,
        // so a Retry, which plans the WAV, cuts the take where it was cut live.
        val stop = stop
        if (tail == TailEnd.HANGOVER && stop != null && !diskFull) {
            block.fill(0)
            var left = stop.sample + capSamples - wav.samplesWritten
            try {
                while (left > 0) {
                    val n = minOf(left, BLOCK_SAMPLES.toLong()).toInt()
                    wav.append(block, n)
                    gate.add(block, n, onFrame)
                    left -= n
                }
            } catch (e: StorageFullException) {
                fail(Code.STORAGE_FULL)
            }
        }
        val samples = try {
            wav.use { it.finish() }
        } catch (e: IOException) {
            fail(Code.STORAGE_FULL)
            wav.samplesWritten
        }
        planner.finish(samples).takeIf { it.toSample > it.fromSample }?.let {
            mayHoldSpeech = mayHoldSpeech || it.hasSpeech
            listener.onChunk(gate.withFirstFrames(it))
        }
        listener.onStopped(RecordingResult(samples, mayHoldSpeech, error.get(), tail, tailRanMs))
    }

    private fun capture(writer: Thread) {
        // Best effort: a device may refuse it, and the host test JVM has no android.os.Process.
        runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO) }
        // No allocation per block. clock() returns a boxed Long, so a read that returns audio never calls it.
        val buf = ShortArray(BLOCK_SAMPLES)
        var open = false
        var reopened = false
        var began = false // a read has returned audio
        var emptySince = -1L // clock() at the first empty read after audio, once audio began; -1 while audio flows

        fun reopen() { // once per take, after DEVICE_LOST or a stall
            reopened = true
            source.stop()
            open = false
            source.start()
            open = true
            emptySince = -1L // the stall clock restarts at the next empty read
        }

        try {
            if (cancelled) return // cancelled before the mic opened: never open it
            source.start()
            open = true
            while (error.get() == null && !cancelled && !tailDone()) {
                val n = try {
                    source.read(buf)
                } catch (e: CaptureException) {
                    if (e.code != Code.DEVICE_LOST || reopened) throw e
                    reopen()
                    continue
                }
                if (n > 0) {
                    if (!ring.write(buf, n)) {
                        fail(Code.CAPTURE_OVERFLOW)
                        break
                    }
                    LockSupport.unpark(writer)
                    emptySince = -1L
                    if (!began) {
                        began = true
                        listener.onFirstBuffer()
                    }
                } else if (began) {
                    val now = clock()
                    if (emptySince == -1L) {
                        emptySince = now
                    } else if (now - emptySince >= STALL_MS) {
                        if (reopened) {
                            fail(Code.CAPTURE_STALLED)
                            break
                        }
                        reopen()
                    }
                }
                if (source.silenced) {
                    fail(Code.MIC_SILENCED)
                    break
                }
            }
        } catch (e: CaptureException) {
            fail(e.code)
        } catch (e: SecurityException) {
            fail(Code.MIC_PERMISSION)
        } catch (e: RuntimeException) { // an escape here would kill the process and never reach the listener
            fail(Code.MIC_UNAVAILABLE)
        } finally {
            try {
                if (open) {
                    try {
                        source.stop()
                    } catch (e: RuntimeException) {
                        fail(Code.MIC_UNAVAILABLE)
                    }
                }
                try {
                    source.release()
                } catch (e: RuntimeException) {
                    fail(Code.MIC_UNAVAILABLE)
                }
            } finally {
                captureDone = true // the writer finishes the WAV whatever the source did
                LockSupport.unpark(writer)
            }
        }
    }

    /** True once the stop tail is over, noting how it ended and how long it ran. Runs only after a stop: clock() boxes. */
    private fun tailDone(): Boolean {
        val stop = stop ?: return false
        val now = clock()
        val end = judged // first: lastSound and ring.written, read after it, are then at least as new
        tail = when {
            stop.quiet -> TailEnd.QUIET
            now - stop.at >= tailMs -> TailEnd.CAP
            end - stop.sample >= TAIL_FLOOR_SAMPLES && end - lastSound >= HANGOVER_SAMPLES &&
                ring.written - end <= UNJUDGED_SAMPLES -> TailEnd.HANGOVER
            else -> return false
        }
        tailRanMs = now - stop.at
        return true
    }

    /** A stop request: the clock and the samples read at it, and whether the user had already paused. */
    private class Stop(val at: Long, val sample: Long, val quiet: Boolean)

    companion object {
        const val TAIL_FLOOR_MS = 100L // the tail runs at least this long after the stop
        const val HANGOVER_MS = 100L // and this long after the last frame that was speech or above -55 dBFS
        private const val TAIL_FLOOR_SAMPLES = TAIL_FLOOR_MS * 16
        private const val HANGOVER_SAMPLES = HANGOVER_MS * 16
        private const val BLOCK_SAMPLES = 320 // 20 ms at 16 kHz
        private const val STALL_MS = 500
        private const val QUIET_SAMPLES = 8_000 // 500 ms
        private const val UNJUDGED_SAMPLES = 800 // one 20 ms read and a partial 30 ms frame
        private const val FIRST_WINDOW_END = FLOOR_FRAMES * FRAME_SAMPLES.toLong() // the sample where the gate's first 3 s end
    }
}
