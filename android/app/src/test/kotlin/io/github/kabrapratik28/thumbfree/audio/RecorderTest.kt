package io.github.kabrapratik28.thumbfree.audio

import com.google.common.collect.Range
import com.google.common.truth.Truth.assertThat
import io.github.kabrapratik28.thumbfree.core.audio.Chunk
import io.github.kabrapratik28.thumbfree.core.audio.FileSink
import io.github.kabrapratik28.thumbfree.core.audio.Sink
import io.github.kabrapratik28.thumbfree.core.audio.Wav
import io.github.kabrapratik28.thumbfree.core.audio.WavChunks
import io.github.kabrapratik28.thumbfree.core.audio.WavWriter
import io.github.kabrapratik28.thumbfree.core.session.Code
import java.io.File
import java.io.IOException
import java.lang.management.ManagementFactory
import java.nio.ByteBuffer
import java.util.Random
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.pow
import kotlin.math.roundToInt
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

// The fake source's clock is the take's audio time: 20 ms per 320-sample read.
class RecorderTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun rampIsContiguous() {
        val ramp = ramp(48_000)
        val random = Random(7)
        val blocks = mutableListOf<Any>()
        var at = 0
        while (at < ramp.size) {
            val n = minOf(1 + random.nextInt(640), ramp.size - at)
            blocks += ramp.copyOfRange(at, at + n)
            at += n
        }
        // Unpaced, the capture races the writer. Paced through a 1,009-sample ring, reads wrap it at shifting offsets.
        for ((paced, ringSamples) in listOf(false to 160_000, true to 1_009)) {
            val take = Take(blocks, paced = paced, ringSamples = ringSamples) { recorder.requestStop() }

            val result = take.run()

            // Everything the source handed out, the ramp and then the tail's zeros, is in the file once and in order; an
            // early tail's fill adds zeros after it.
            assertThat(result.samples).isAtLeast(take.source.delivered)
            assertThat(take.wav()).isEqualTo(FloatArray(result.samples.toInt()) { if (it < ramp.size) ramp[it] / 32768f else 0f })
        }
    }

    // Live preview: with a preview ring the writer puts every block there until the stop (the preview's gate is Silero,
    // in :engine) and nothing from the stop on; the WAV still gets every sample.
    @Test
    fun previewGetsAllTheAudioUntilTheStop() {
        val ring = PcmRing(10 * 16_000)
        val take = Take(listOf(ShortArray(3_300 * 16), bursts(2_000), ShortArray(3_000 * 16), STOP, bursts(1_000)), preview = ring)

        val result = take.run()

        val passed = ShortArray(ring.available.toInt()).also { ring.read(it) }
        val wav = take.wav().map { (it * 32768).toInt().toShort() }.toShortArray()
        assertThat(result.samples).isEqualTo(take.source.delivered)
        assertThat(passed).isEqualTo(wav.copyOfRange(0, 8_300 * 16)) // paced: the stop comes after exactly 8.3 s
    }

    @Test
    fun aFullPreviewRingNeverStopsTheTake() {
        val ring = PcmRing(1_000)
        val take = Take(listOf(bursts(4_000), STOP), preview = ring)

        val result = take.run()

        assertThat(ring.overflowed).isTrue()
        assertThat(result.error).isNull()
        assertThat(result.samples).isAtLeast(take.source.delivered) // the tail's zero fill may add some
        assertThat(take.wav().size.toLong()).isEqualTo(result.samples)
    }

    @Test
    fun firstBlockRaisesFirstBuffer() {
        val take = Take(listOf(0, 0, 0, ShortArray(320) { 1_000 })) { recorder.cancel() }

        take.run()

        assertThat(take.log.count { it == FIRST_BUFFER }).isEqualTo(1)
        assertThat(take.deliveredAtFirstBuffer).isEqualTo(320L) // right after the first read that returned audio
    }

    // The stop tail runs to its 350 ms cap while sound goes on after the stop.
    @Test
    fun speechThroughTheCapKeepsTheWhole350ms() {
        val take = Take(listOf(bursts(10_000), STOP, bursts(1_000))) // the stop at 10,000 ms, speech until 11,000

        val result = take.run()

        assertThat(result.tail).isEqualTo(TailEnd.CAP)
        assertThat(result.tailMs).isIn(Range.closed(350L, 370L)) // on the source's clock: the read that crosses 350 ms
        assertThat(result.samples - 160_000).isIn(Range.closed(5_600L, 5_600L + 320))
        assertThat(result.samples).isEqualTo(take.source.delivered)
    }

    // Sound stops at the stop; the mic closes one hangover later instead of at the cap.
    @Test
    fun quietTailEndsOneHangoverAfterTheLastSound() {
        // The bursts end with 100 ms at -50 dBFS, above -55: sound up to the stop at 9,600 ms (a frame edge), then zeros.
        val take = Take(listOf(bursts(9_600), STOP))

        val result = take.run()

        // The hangover, then the frame edge after it, the read that brings that frame and the read in progress.
        val hangover = Recorder.HANGOVER_MS * 16
        assertThat(take.source.delivered - 153_600).isIn(Range.closed(hangover, hangover + 1_440))
        assertThat(result.tail).isEqualTo(TailEnd.HANGOVER)
        assertThat(result.tailMs).isLessThan(350L)
    }

    // The model's last word needs the silence after it, so a tail that ends early is filled with zeros to the length
    // the cap would have recorded: the mic closes early, the engine hears the same length as before.
    @Test
    fun earlyTailIsFilledWithZerosToTheCapsLength() {
        val quiet = noise(1_000, -70.0) // under -55 dBFS, so the hangover runs out in it
        val take = Take(listOf(bursts(9_600), STOP, quiet))

        val result = take.run()

        assertThat(result.tail).isEqualTo(TailEnd.HANGOVER)
        val read = take.source.delivered.toInt()
        assertThat(read).isLessThan(153_600 + 5_600)
        assertThat(result.samples).isEqualTo(153_600 + 5_760L) // the cap, 350 ms, and the read that crosses it
        val wav = take.wav()
        assertThat(wav.copyOfRange(153_600, read)).isEqualTo(FloatArray(read - 153_600) { quiet[it] / 32768f })
        assertThat(wav.copyOfRange(read, wav.size)).isEqualTo(FloatArray(wav.size - read))
    }

    // Only a tail the hangover ends is filled; the quiet skip and the cap leave the take as the mic recorded it.
    @Test
    fun onlyATailTheHangoverEndsIsFilled() {
        val quiet = Take(listOf(bursts(5_000), ShortArray(600 * 16))) { recorder.requestStop() }
        val cap = Take(listOf(bursts(10_000), STOP, bursts(1_000)))
        val hangover = Take(listOf(bursts(9_600), STOP))

        val ends = listOf(quiet, cap, hangover).map { take -> take.run().let { it.tail to it.samples - take.source.delivered } }

        assertThat(ends[0]).isEqualTo(TailEnd.QUIET to 0L)
        assertThat(ends[1]).isEqualTo(TailEnd.CAP to 0L)
        assertThat(ends[2].first).isEqualTo(TailEnd.HANGOVER)
        assertThat(ends[2].second).isGreaterThan(0L)
    }

    // A Retry plans the WAV, fill included, into the chunks the Recorder sent. Past 20 s the planner cuts at a 300 ms
    // pause; here the pause is complete only inside the fill, so the Recorder must plan the fill too.
    @Test
    fun retryPlansAFilledTakeAsTheRecorderCutIt() {
        // Speech until 21,100 ms, then zeros: the stop at 21,200, the tail ends by the hangover, the fill runs to 21,560.
        val take = Take(listOf(bursts(21_100), ShortArray(100 * 16), STOP))

        val result = take.run()

        assertThat(result.tail).isEqualTo(TailEnd.HANGOVER)
        assertThat(take.source.delivered).isLessThan(21_420 * 16L) // the pause's 300 ms end past the captured audio
        assertThat(take.chunks).hasSize(2)
        assertThat(take.chunks.last().toSample).isEqualTo(result.samples)
        assertThat(WavChunks.plan(take.file)).isEqualTo(take.chunks)
    }

    @Test
    fun noTailAfter500msOfQuiet() {
        val speech = bursts(5_000)
        val take = Take(listOf(speech, ShortArray(600 * 16))) { recorder.requestStop() } // the stop 600 ms after speech

        val result = take.run()

        // The stop came inside a read (onScriptEnd), and that read of 320 zeros is the last one.
        assertThat(result.tail).isEqualTo(TailEnd.QUIET)
        assertThat(result.samples).isEqualTo(5_600 * 16L + 320)
        assertThat(result.samples).isEqualTo(take.source.delivered)
        assertThat(take.wav()).isEqualTo(FloatArray(result.samples.toInt()) { if (it < speech.size) speech[it] / 32768f else 0f })
    }

    @Test
    fun noisySteadyTailRunsToTheCap() {
        // Steady noise at -45 dBFS: never a gate speech frame (it needs floor + 12 dB), but every frame is above -55 dBFS,
        // so it may hold speech, like a word spoken less than 12 dB over a noisy room. It goes on after the stop.
        val take = Take(listOf(noise(2_000, -45.0), STOP, noise(1_000, -45.0)))

        val result = take.run()

        assertThat(result.tail).isEqualTo(TailEnd.CAP)
        assertThat(result.samples - 32_000).isIn(Range.closed(5_600L, 5_600L + 320))
        assertThat(result.samples).isEqualTo(take.source.delivered)
    }

    // A stop during a word's final fricative. Its fade under -55 dBFS is shorter than the hangover, so it is kept.
    @Test
    fun finalFricativeAndItsFadeAreKept() {
        val word = bursts(1_000)
        val fricative = noise(150, -48.0) // 1,000 to 1,150 ms; the stop comes 50 ms into it
        val fade = noise(90, -60.0) // then 90 ms under -55 dBFS: its last loud frame ends at 1,170 ms
        val take = Take(listOf(word, fricative.copyOfRange(0, 800), STOP, fricative.copyOfRange(800, 2_400), fade))

        val result = take.run()

        assertThat(result.tail).isEqualTo(TailEnd.HANGOVER)
        assertThat(take.source.delivered).isAtLeast(1_170 * 16L + Recorder.HANGOVER_MS * 16)
        val sound = word + fricative + fade
        assertThat(take.wav().copyOf(sound.size)).isEqualTo(FloatArray(sound.size) { sound[it] / 32768f })
    }

    @Test
    fun tailKeptWhenTheWriterHasNotJudgedTheLastAudio() {
        // The mic runs ahead of a slow disk: at the stop the writer has not yet fed the speech to the gate, and after it
        // the zeros it has not judged cannot end the tail early.
        val take = Take(listOf(bursts(1_000)), paced = false, sinkDelayMs = 20) { recorder.requestStop() }

        val result = take.run()

        assertThat(result.tail).isEqualTo(TailEnd.CAP)
        assertThat(result.samples - 16_000).isIn(Range.closed(5_600L - 320, 5_600L + 320))
        assertThat(result.samples).isEqualTo(take.source.delivered)
    }

    @Test
    fun securityExceptionIsPermissionError() {
        val take = Take(emptyList(), startError = SecurityException("RECORD_AUDIO"))

        take.run()

        assertThat(take.log).containsExactly(Code.MIC_PERMISSION, RecordingResult(0, false, Code.MIC_PERMISSION)).inOrder()
        assertThat(take.source.releases).isEqualTo(1)
    }

    @Test
    fun otherSourceExceptionIsMicUnavailable() {
        val take = Take(emptyList(), startError = IllegalStateException("AudioRecord not initialized"))

        take.run()

        assertThat(take.log).containsExactly(Code.MIC_UNAVAILABLE, RecordingResult(0, false, Code.MIC_UNAVAILABLE)).inOrder()
        assertThat(take.source.releases).isEqualTo(1)
    }

    @Test
    fun throwingStopStillFinishesTheTake() {
        val take = Take(listOf(ShortArray(16_000)), stopError = IllegalStateException("stop failed")) { recorder.requestStop() }

        val result = take.run() // the writer must not wait for the capture thread forever

        assertThat(result.samples).isEqualTo(take.source.delivered)
        assertThat(take.errors).containsExactly(Code.MIC_UNAVAILABLE)
        assertThat(take.source.releases).isEqualTo(1) // release still runs
    }

    @Test
    fun cancelBeforeStartNeverOpensTheMic() {
        val take = Take(listOf(ShortArray(16_000)))
        take.recorder.cancel()

        val result = take.run()

        assertThat(take.source.starts).isEqualTo(0)
        assertThat(take.source.releases).isEqualTo(1)
        assertThat(result).isEqualTo(RecordingResult(0, false, null))
    }

    @Test
    fun captureAllocatesNothingPerBlock() {
        // No allocation per block on the capture thread. The unpaced fake allocates nothing itself.
        val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        var atFirstBuffer = 0L
        var atEnd = 0L
        val take = Take(
            listOf(ShortArray(8 * 16_000)), // 400 blocks
            paced = false,
            onFirstBuffer = { atFirstBuffer = threads.currentThreadAllocatedBytes },
        ) {
            atEnd = threads.currentThreadAllocatedBytes
            recorder.cancel()
        }

        take.run()

        assertThat(atEnd - atFirstBuffer).isLessThan(1_024L) // a boxed Long per block alone would be 6,384 bytes
    }

    @Test
    fun writerAllocatesLittlePerRead() {
        // A mic that hands out 160 samples per read, paced so the writer drains each read on its own. Between two level
        // updates the writer thread allocates under 64 bytes per read. A copy of the read, a lambda per block, or a lambda
        // and a boxed Int per append would each pass that; what is left is a boxed clock value per read and boxed Floats.
        val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        lateinit var wav: WavWriter
        lateinit var recorder: Recorder
        val source = FakeAudioSource(
            List(400) { bursts(10) },
            mayDeliver = { wav.samplesWritten >= it },
            onScriptEnd = { recorder.cancel() },
        )
        val stopped = CountDownLatch(1)
        var first = -1L
        var firstSamples = 0L
        var last = 0L
        var lastSamples = 0L
        val listener = object : Recorder.Listener {
            override fun onFirstBuffer() = Unit

            override fun onLevel(unit: Float) { // on the writer thread; allocates nothing itself
                if (first < 0) {
                    first = threads.currentThreadAllocatedBytes
                    firstSamples = wav.samplesWritten
                } else {
                    last = threads.currentThreadAllocatedBytes
                    lastSamples = wav.samplesWritten
                }
            }

            override fun onChunk(chunk: Chunk) = Unit
            override fun onSilentMic() = Unit
            override fun onError(code: Code) = Unit
            override fun onStopped(result: RecordingResult) = stopped.countDown()
        }
        recorder = Recorder(source, { WavWriter.create(tmp.newFile()).also { wav = it } }, listener, source.clock)

        recorder.start()
        assertThat(stopped.await(20, TimeUnit.SECONDS)).isTrue()

        val perRead = (last - first) / ((lastSamples - firstSamples) / 160)
        assertThat(perRead).isLessThan(64L)
    }

    @Test
    fun storageFullAtCreateNeverOpensTheMic() {
        val take = Take(emptyList(), lowOnSpace = true)

        take.run()

        assertThat(take.log).containsExactly(Code.STORAGE_FULL, RecordingResult(0, false, Code.STORAGE_FULL)).inOrder()
        assertThat(take.source.starts).isEqualTo(0)
    }

    @Test
    fun deadObjectReopensOnce() {
        val lost = CaptureException(Code.DEVICE_LOST)
        val once = Take(listOf(ShortArray(16_000), lost, ShortArray(16_000))) { recorder.requestStop() }

        val kept = once.run()

        assertThat(once.errors).isEmpty()
        assertThat(kept.error).isNull()
        assertThat(once.source.starts).isEqualTo(2)
        assertThat(once.source.stops).isEqualTo(2) // the reopen, then the end of the take
        assertThat(kept.samples).isEqualTo(once.source.delivered)

        val twice = Take(listOf(ShortArray(16_000), lost, ShortArray(16_000), lost, ShortArray(16_000)))

        val stopped = twice.run()

        assertThat(twice.errors).containsExactly(Code.DEVICE_LOST)
        assertThat(stopped.samples).isEqualTo(32_000L)
        assertThat(stopped.error).isEqualTo(Code.DEVICE_LOST)
    }

    @Test
    fun stallAfter500msStops() {
        val take = Take(listOf<Any>(ShortArray(16_000)) + List(100) { 0 }) // then 2 s of reads that find nothing

        val result = take.run()

        assertThat(take.source.starts).isEqualTo(2) // one reopen after 500 ms without audio
        assertThat(take.errors).containsExactly(Code.CAPTURE_STALLED) // and 500 ms more without audio
        assertThat(result.samples).isEqualTo(16_000L)
        assertThat(result.error).isEqualTo(Code.CAPTURE_STALLED)
    }

    @Test
    fun overflowStopsTheTake() {
        val ramp = ramp(16_000)
        val take = Take(listOf(ramp), paced = false, ringSamples = 1_000, sinkDelayMs = 50)

        val result = take.run()

        assertThat(take.errors).containsExactly(Code.CAPTURE_OVERFLOW)
        assertThat(result.error).isEqualTo(Code.CAPTURE_OVERFLOW)
        assertThat(result.samples).isIn(Range.closed(1L, 15_999L))
        assertThat(take.wav()).isEqualTo(FloatArray(result.samples.toInt()) { ramp[it] / 32768f }) // a contiguous prefix
    }

    @Test
    fun storageFullStopsTheTake() {
        val take = Take(listOf(ramp(16_000)), diskBytes = WavWriter.HEADER_BYTES + 20_000L)

        val result = take.run()

        assertThat(take.errors).containsExactly(Code.STORAGE_FULL)
        assertThat(result.samples).isEqualTo(10_000L)
        assertThat(result.error).isEqualTo(Code.STORAGE_FULL)
        assertThat(take.sink.writesAfterFull).isEqualTo(0) // append is never called again after it threw
    }

    @Test
    fun chunkClosesDuringTheTake() {
        var beforeStop = emptyList<Chunk>()
        val take = Take(listOf(burstsWithPause())) {
            beforeStop = chunks
            recorder.requestStop()
        }

        val result = take.run()

        assertThat(beforeStop).hasSize(1)
        assertThat(beforeStop[0].fromSample).isEqualTo(0L)
        assertThat(beforeStop[0].toSample).isIn(Range.closed(21_000 * 16L, 21_400 * 16L)) // inside the pause
        val tail = take.chunks.last()
        assertThat(take.chunks).containsExactly(beforeStop[0], Chunk(beforeStop[0].toSample, result.samples, true, tail.speechFrames)).inOrder()
        assertThat(WavChunks.plan(take.file)).isEqualTo(take.chunks) // a retry re-plans the same chunks from the file
    }

    // The gate does not decide whether a take is speech; Silero does, chunk by chunk. So a take the gate hears no
    // speech in, but with a frame over -55 dBFS, goes on to the speech check; a take with none has nothing to check.
    @Test
    fun takeWithAFrameOverMinus55DbfsGoesToTheSpeechCheck() {
        val steady = Take(listOf(noise(5_000, -50.0))) { recorder.requestStop() } // no gate frame: never 8 dB over its floor
        val silent = Take(listOf(ShortArray(5_000 * 16))) { recorder.requestStop() }

        assertThat(steady.run().hasSpeech).isTrue()
        assertThat(steady.chunks.sumOf { it.speechFrames }).isEqualTo(0)
        assertThat(silent.run().hasSpeech).isFalse()
    }

    // The gate reports the first 3 s frame by frame as non-speech; the take's first chunk still counts their speech.
    @Test
    fun shortTakeChunkCountsTheSpeechOfTheFirst3s() {
        val take = Take(listOf(bursts(1_500))) { recorder.requestStop() }

        take.run()

        assertThat(take.chunks.single().speechFrames).isGreaterThan(20)
        assertThat(WavChunks.plan(take.file)).isEqualTo(take.chunks)
    }

    @Test
    fun silencedSourceStopsTheTake() {
        val take = Take(listOf(ramp(32_000)), silencedAfter = 16_000)

        val result = take.run()

        assertThat(take.log.filter { it is Code || it is RecordingResult }).containsExactly(Code.MIC_SILENCED, result).inOrder()
        assertThat(result.samples).isAtLeast(16_000L)
    }

    @Test
    fun levelsThrottled() {
        val take = Take(listOf(bursts(3_000))) { recorder.cancel() }

        take.run()

        assertThat(take.log.count { it is Float }).isIn(Range.closed(30, 90))
    }

    @Test
    fun silentMicReported() {
        val take = Take(listOf(ShortArray(24_000))) { recorder.requestStop() } // 1.5 s of zeros, the 50th frame at its end

        take.run()

        assertThat(take.log.count { it == SILENT_MIC }).isEqualTo(1)
    }

    @Test
    fun msSinceSpeechTracksLastSpeechFrame() {
        var atEnd = 0L
        val take = Take(listOf(bursts(5_000), ShortArray(3_000 * 16))) { // speech until clock 5,000, then zeros
            atEnd = recorder.msSinceSpeech // at clock 8,000
            recorder.cancel()
        }

        take.run()

        assertThat(atEnd).isIn(Range.closed(2_970L, 3_030L))
    }

    // The gate judges the first 3 s only when they end, so their speech counts for the 120 s silence stop from then:
    // that stop may come up to 3 s late, never early.
    @Test
    fun speechInTheFirst3sCountsForTheSilenceClockWhenTheyEnd() {
        var atEnd = 0L
        val take = Take(listOf(bursts(1_000), ShortArray(4_000 * 16))) { // speech until clock 1,000, then zeros
            atEnd = recorder.msSinceSpeech // at clock 5,000
            recorder.cancel()
        }

        take.run()

        assertThat(atEnd).isIn(Range.closed(1_970L, 2_030L)) // since clock 3,000, when the first 3 s were judged
    }

    /**
     * One take of [script] from a FakeAudioSource into a real WAV file, logging every listener call in order. [paced] keeps
     * the source from running ahead of the writer, like a real mic. A STOP in the script calls requestStop there.
     */
    private inner class Take(
        script: List<Any>,
        paced: Boolean = true,
        startError: Exception? = null,
        stopError: RuntimeException? = null,
        silencedAfter: Long = Long.MAX_VALUE,
        ringSamples: Int = 160_000,
        lowOnSpace: Boolean = false,
        diskBytes: Long = Long.MAX_VALUE,
        sinkDelayMs: Long = 0,
        preview: PcmRing? = null,
        private val onFirstBuffer: () -> Unit = {},
        onScriptEnd: Take.() -> Unit = {},
    ) : Recorder.Listener {
        val file: File = tmp.newFile()
        val log = CopyOnWriteArrayList<Any>()
        var deliveredAtFirstBuffer = -1L
        lateinit var sink: TestSink
            private set
        private val stopped = CountDownLatch(1)
        val source: FakeAudioSource = FakeAudioSource(
            script.map { if (it == STOP) Runnable { recorder.requestStop() } else it },
            startError = startError,
            stopError = stopError,
            silencedAfter = silencedAfter,
            mayDeliver = { delivered: Long -> sink.samples >= delivered || sink.full }.takeIf { paced },
            onScriptEnd = { onScriptEnd() },
        )
        val recorder: Recorder = Recorder(
            source,
            openWriter = {
                val space = if (lowOnSpace) 0L else Long.MAX_VALUE
                WavWriter.create(file, { space }) { TestSink(it, diskBytes, sinkDelayMs).also { s -> sink = s } }
            },
            listener = this,
            clock = source.clock,
            ringSamples = ringSamples,
            preview = preview,
        )

        val errors: List<Code> get() = log.filterIsInstance<Code>()
        val chunks: List<Chunk> get() = log.filterIsInstance<Chunk>()

        fun run(): RecordingResult {
            recorder.start()
            assertThat(stopped.await(20, TimeUnit.SECONDS)).isTrue()
            return log.last() as RecordingResult
        }

        fun wav(): FloatArray = Wav.readFloat(file.path, 0, (file.length() - WavWriter.HEADER_BYTES) / 2)

        override fun onFirstBuffer() {
            deliveredAtFirstBuffer = source.delivered
            log += FIRST_BUFFER
            onFirstBuffer.invoke()
        }

        override fun onLevel(unit: Float) {
            log += unit
        }

        override fun onChunk(chunk: Chunk) {
            log += chunk
        }

        override fun onSilentMic() {
            log += SILENT_MIC
        }

        override fun onError(code: Code) {
            log += code
        }

        override fun onStopped(result: RecordingResult) {
            log += result
            stopped.countDown()
        }
    }
}

private const val FIRST_BUFFER = "first buffer"
private const val SILENT_MIC = "silent mic"
private const val STOP = "stop"

private fun ramp(samples: Int) = ShortArray(samples) { (it % 32768).toShort() }

/** [ms] of Gaussian noise at [dbfs] (seed 7). */
private fun noise(ms: Int, dbfs: Double): ShortArray {
    val random = Random(7)
    val sigma = 32768 * 10.0.pow(dbfs / 20)
    return ShortArray(ms * 16) { (random.nextGaussian() * sigma).roundToInt().coerceIn(-32768, 32767).toShort() }
}

/** Writes to a real file and counts the bytes it takes. Past [diskBytes] in all it throws ENOSPC; each write can be slow. */
private class TestSink(file: File, private val diskBytes: Long, private val delayMs: Long) : Sink {
    private val real = FileSink(file)

    @Volatile private var bytes = 0L

    @Volatile var full = false
        private set

    @Volatile var writesAfterFull = 0
        private set

    val samples get() = (bytes - WavWriter.HEADER_BYTES) / 2

    override fun write(src: ByteBuffer): Int {
        Thread.sleep(delayMs)
        if (full) writesAfterFull++
        if (bytes >= diskBytes) {
            full = true
            throw IOException("No space left on device")
        }
        val slice = src.duplicate()
        slice.limit(slice.position() + minOf(slice.remaining().toLong(), diskBytes - bytes).toInt())
        val n = real.write(slice)
        src.position(src.position() + n)
        bytes += n
        return n
    }

    override fun writeAt(src: ByteBuffer, position: Long): Int = real.writeAt(src, position)
    override fun size(): Long = real.size()
    override fun truncate(size: Long) = real.truncate(size)
    override fun sync() = real.sync()
    override fun close() = real.close()
}
