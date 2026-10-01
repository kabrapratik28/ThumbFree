package io.github.kabrapratik28.thumbfree.core.audio

import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WavWriterTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun createWritesEmptyHeader() {
        val file = File(tmp.root, "rec.wav")

        val samples = WavWriter.create(file).finish()

        assertThat(samples).isEqualTo(0L)
        val bytes = file.readBytes()
        assertThat(bytes.size).isEqualTo(44)
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertThat(String(bytes, 0, 4, Charsets.US_ASCII)).isEqualTo("RIFF")
        assertThat(buf.getInt(4)).isEqualTo(36)
        assertThat(String(bytes, 8, 4, Charsets.US_ASCII)).isEqualTo("WAVE")
        assertThat(String(bytes, 12, 4, Charsets.US_ASCII)).isEqualTo("fmt ")
        assertThat(buf.getInt(16)).isEqualTo(16)
        assertThat(buf.getShort(20)).isEqualTo(1.toShort())
        assertThat(buf.getShort(22)).isEqualTo(1.toShort())
        assertThat(buf.getInt(24)).isEqualTo(16_000)
        assertThat(buf.getInt(28)).isEqualTo(32_000)
        assertThat(buf.getShort(32)).isEqualTo(2.toShort())
        assertThat(buf.getShort(34)).isEqualTo(16.toShort())
        assertThat(String(bytes, 36, 4, Charsets.US_ASCII)).isEqualTo("data")
        assertThat(buf.getInt(40)).isEqualTo(0)
    }

    @Test
    fun appendThenFinishPatchesSizes() {
        val file = File(tmp.root, "rec.wav")
        val writer = WavWriter.create(file)

        writer.append(ShortArray(1000) { it.toShort() })
        val samples = writer.finish()

        assertThat(samples).isEqualTo(1000L)
        val buf = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        assertThat(buf.getInt(4)).isEqualTo(2036)
        assertThat(buf.getInt(40)).isEqualTo(2000)
        assertThat(Wav.readFloat(file.path, 999, 1000)[0]).isEqualTo(999 / 32768f)
    }

    @Test
    fun repairFixesFileCutAtAnyByte() {
        val original = File(tmp.root, "original.wav")
        val writer = WavWriter.create(original)
        writer.append(ShortArray(16_000) { it.toShort() })
        writer.finish()
        val fullBytes = original.readBytes()

        for (cut in listOf(44, 45, 1_000, 1_001, 32_043, 32_044)) {
            val expectedSamples = ((cut - 44) / 2).toLong()
            val cutFile = tmp.newFile("cut-$cut.wav")
            cutFile.writeBytes(fullBytes.copyOfRange(0, cut))

            val samples = WavWriter.repair(cutFile)

            assertThat(samples).isEqualTo(expectedSamples)
            val bytes = cutFile.readBytes()
            assertThat(bytes.size.toLong()).isEqualTo(44 + 2 * expectedSamples)
            val dataSize = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getInt(40)
            assertThat(dataSize.toLong()).isEqualTo(2 * expectedSamples)
        }
    }

    @Test
    fun repairZeroByteFileGivesEmptyValidWav() {
        val file = tmp.newFile("zero.wav") // power lost before the header landed

        assertThat(WavWriter.repair(file)).isEqualTo(0L)

        assertThat(file.readBytes()).isEqualTo(emptyWav())
    }

    @Test
    fun repairTruncatedHeaderGivesEmptyValidWav() {
        val empty = emptyWav()
        val file = tmp.newFile("cut.wav")
        file.writeBytes(empty.copyOf(20))

        assertThat(WavWriter.repair(file)).isEqualTo(0L)

        assertThat(file.readBytes()).isEqualTo(empty)
    }

    @Test
    fun preflightRefusesWhenLowOnSpace() {
        val file = File(tmp.root, "rec.wav")

        assertThrows(StorageFullException::class.java) {
            WavWriter.create(file, usableSpace = { 10L * 1024 * 1024 })
        }

        assertThat(file.exists()).isFalse()
    }

    @Test
    fun shortWritesAreRetried() {
        val sink = LimitedSink(limit = 3)
        val writer = WavWriter.create(File(tmp.root, "rec.wav"), openSink = { sink })
        val prefixLength = sink.bytes.size()

        writer.append(ShortArray(10) { (it + 1).toShort() })

        val appended = sink.bytes.toByteArray().copyOfRange(prefixLength, sink.bytes.size())
        assertThat(appended.size).isEqualTo(20)
        val shorts = ByteBuffer.wrap(appended).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        assertThat(ShortArray(10) { shorts.get(it) }).isEqualTo(ShortArray(10) { (it + 1).toShort() })
    }

    @Test
    fun shortHeaderWritesAreRetried() {
        val empty = emptyWav()
        val file = File(tmp.root, "rec.wav")

        WavWriter.create(file, openSink = { f -> OneByteWritesSink(FileSink(f)) }).use { it.finish() }

        assertThat(file.readBytes()).isEqualTo(empty)
    }

    @Test
    fun noProgressIsStorageFull() {
        // Stuck from the first byte: caught at the header, before any audio.
        val atCreate = assertThrows(StorageFullException::class.java) {
            WavWriter.create(File(tmp.root, "stuck.wav"), openSink = { ZeroSink(takeFirst = 0) })
        }
        assertThat(atCreate).hasMessageThat().isEqualTo("no progress")

        val writer = WavWriter.create(File(tmp.root, "rec.wav")) { ZeroSink(takeFirst = WavWriter.HEADER_BYTES) }

        val error = assertThrows(StorageFullException::class.java) {
            writer.append(ShortArray(4))
        }

        assertThat(error).hasMessageThat().isEqualTo("no progress")
    }

    @Test
    fun finishConvertsPatchFailureToStorageFull() {
        val file = File(tmp.root, "rec.wav")
        val writer = WavWriter.create(file, openSink = { f -> FailingWriteAtSink(FileSink(f), failAtPosition = 40) })
        writer.append(ShortArray(1000) { it.toShort() })

        assertThrows(StorageFullException::class.java) { writer.finish() }

        // The data-size patch failed, so the file was left with a stale data size (still 0).
        // repair() must still be able to fix it from the file length alone.
        val samples = WavWriter.repair(file)

        assertThat(samples).isEqualTo(1000L)
        val bytes = file.readBytes()
        assertThat(bytes.size.toLong()).isEqualTo(44 + 2000L)
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertThat(buf.getInt(40)).isEqualTo(2000)
    }

    @Test
    fun enospcInsideFirstAppendKeepsCompleteSamples() {
        val file = File(tmp.root, "rec.wav")
        lateinit var sink: EnospcSink
        val writer = WavWriter.create(file, openSink = { f -> EnospcSink(FileSink(f)).also { sink = it } })
        sink.failAfter(1001)

        val error = assertThrows(StorageFullException::class.java) {
            writer.append(ShortArray(1000) { it.toShort() })
        }
        assertThat(error.cause).isInstanceOf(IOException::class.java)

        val samples = writer.finish()

        assertThat(samples).isEqualTo(500L)
        val bytes = file.readBytes()
        val dataSize = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getInt(40)
        assertThat(dataSize).isEqualTo(1000)
        val dataBytes = bytes.copyOfRange(44, 44 + 1000)
        val expected = ByteBuffer.allocate(1000).order(ByteOrder.LITTLE_ENDIAN).apply {
            for (i in 0 until 500) putShort(i.toShort())
        }.array()
        assertThat(dataBytes).isEqualTo(expected)
    }

    @Test
    fun shortPositionalWritesAreCompleted() {
        val file = File(tmp.root, "rec.wav")
        val writer = WavWriter.create(file, openSink = { f -> OneByteAtATimeSink(FileSink(f)) })
        writer.append(ShortArray(1000) { it.toShort() })

        val samples = writer.finish()

        assertThat(samples).isEqualTo(1000L)
        val buf = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        assertThat(buf.getInt(4)).isEqualTo(2036)
        assertThat(buf.getInt(40)).isEqualTo(2000)
    }

    @Test
    fun createCleansUpOrphanFileWhenHeaderWriteFails() {
        val file = File(tmp.root, "rec.wav")

        assertThrows(StorageFullException::class.java) {
            WavWriter.create(file, openSink = { f -> ThrowingHeaderSink(FileSink(f)) })
        }

        assertThat(file.exists()).isFalse()
    }

    @Test
    fun enospcKeepsThePrefix() {
        val file = File(tmp.root, "rec.wav")
        lateinit var sink: EnospcSink
        val writer = WavWriter.create(file, openSink = { f -> EnospcSink(FileSink(f)).also { sink = it } })
        sink.failAfter(2000)

        writer.append(ShortArray(1000) { it.toShort() })
        assertThat(writer.samplesWritten).isEqualTo(1000L)

        val error = assertThrows(StorageFullException::class.java) {
            writer.append(ShortArray(1000) { it.toShort() })
        }
        assertThat(error.cause).isInstanceOf(IOException::class.java)
        assertThat(writer.samplesWritten).isEqualTo(1000L)

        val samples = writer.finish()

        assertThat(samples).isEqualTo(1000L)
        val dataSize = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN).getInt(40)
        assertThat(dataSize).isEqualTo(2000)
    }

    @Test
    fun syncConvertsIoFailureToStorageFull() {
        // An fsync at a chunk boundary can be the first call to see ENOSPC (delayed allocation).
        val writer = WavWriter.create(File(tmp.root, "rec.wav"), openSink = { f -> FailingSyncSink(FileSink(f)) })
        writer.append(ShortArray(1000) { it.toShort() })

        val error = assertThrows(StorageFullException::class.java) { writer.sync() }

        assertThat(error.cause).isInstanceOf(IOException::class.java)
    }

    /** The 44 bytes that create() and finish() leave for a take with no samples. */
    private fun emptyWav(): ByteArray {
        val file = File(tmp.root, "reference.wav")
        WavWriter.create(file).use { it.finish() }
        return file.readBytes()
    }

    /** Accepts at most [limit] bytes per write call; records everything it accepts, in order. */
    private class LimitedSink(private val limit: Int) : Sink {
        val bytes = ByteArrayOutputStream()

        override fun write(src: ByteBuffer): Int {
            val n = minOf(limit, src.remaining())
            val chunk = ByteArray(n)
            src.get(chunk)
            bytes.write(chunk)
            return n
        }

        override fun writeAt(src: ByteBuffer, position: Long): Int = 0
        override fun size(): Long = bytes.size().toLong()
        override fun truncate(size: Long) = Unit
        override fun sync() = Unit
        override fun close() = Unit
    }

    /** Takes the first [takeFirst] bytes, then reports zero bytes written, simulating a stuck sink. */
    private class ZeroSink(private val takeFirst: Int) : Sink {
        private var taken = 0

        override fun write(src: ByteBuffer): Int {
            val n = minOf(src.remaining(), takeFirst - taken)
            src.position(src.position() + n)
            taken += n
            return n
        }
        override fun writeAt(src: ByteBuffer, position: Long): Int = 0
        override fun size(): Long = 0
        override fun truncate(size: Long) = Unit
        override fun sync() = Unit
        override fun close() = Unit
    }

    /** Wraps a real Sink; once armed via failAfter, throws ENOSPC after that many bytes pass through write(). */
    private class EnospcSink(private val real: Sink) : Sink {
        private var threshold = Long.MAX_VALUE
        private var total = 0L

        fun failAfter(bytes: Long) {
            threshold = bytes
            total = 0
        }

        override fun write(src: ByteBuffer): Int {
            if (total >= threshold) throw IOException("No space left on device")
            val allowed = minOf(src.remaining().toLong(), threshold - total).toInt()
            val slice = src.duplicate()
            slice.limit(slice.position() + allowed)
            var written = 0
            while (slice.hasRemaining()) {
                val n = real.write(slice)
                if (n <= 0) break
                written += n
            }
            src.position(src.position() + written)
            total += written
            return written
        }

        override fun writeAt(src: ByteBuffer, position: Long): Int = real.writeAt(src, position)
        override fun size(): Long = real.size()
        override fun truncate(size: Long) = real.truncate(size)
        override fun sync() = real.sync()
        override fun close() = real.close()
    }

    /** Wraps a real Sink; writeAt throws on the given position only, leaving other patches to succeed. */
    private class FailingWriteAtSink(private val real: Sink, private val failAtPosition: Long) : Sink {
        override fun write(src: ByteBuffer): Int = real.write(src)
        override fun writeAt(src: ByteBuffer, position: Long): Int {
            if (position == failAtPosition) throw IOException("ENOSPC")
            return real.writeAt(src, position)
        }
        override fun size(): Long = real.size()
        override fun truncate(size: Long) = real.truncate(size)
        override fun sync() = real.sync()
        override fun close() = real.close()
    }

    /** Wraps a real Sink; writeAt accepts only 1 byte per call, forcing the caller to retry. */
    private class OneByteAtATimeSink(private val real: Sink) : Sink {
        override fun write(src: ByteBuffer): Int = real.write(src)
        override fun writeAt(src: ByteBuffer, position: Long): Int {
            if (!src.hasRemaining()) return 0
            val one = ByteBuffer.allocate(1).apply { put(src.get(src.position())); flip() }
            real.writeAt(one, position)
            src.position(src.position() + 1)
            return 1
        }
        override fun size(): Long = real.size()
        override fun truncate(size: Long) = real.truncate(size)
        override fun sync() = real.sync()
        override fun close() = real.close()
    }

    /** Wraps a real Sink; write() accepts only 1 byte per call, forcing the caller to retry. */
    private class OneByteWritesSink(private val real: Sink) : Sink {
        override fun write(src: ByteBuffer): Int {
            if (!src.hasRemaining()) return 0
            return real.write(ByteBuffer.allocate(1).apply { put(src.get()); flip() })
        }
        override fun writeAt(src: ByteBuffer, position: Long): Int = real.writeAt(src, position)
        override fun size(): Long = real.size()
        override fun truncate(size: Long) = real.truncate(size)
        override fun sync() = real.sync()
        override fun close() = real.close()
    }

    /** Wraps a real Sink whose sync() fails with ENOSPC. */
    private class FailingSyncSink(private val real: Sink) : Sink {
        override fun write(src: ByteBuffer): Int = real.write(src)
        override fun writeAt(src: ByteBuffer, position: Long): Int = real.writeAt(src, position)
        override fun size(): Long = real.size()
        override fun truncate(size: Long) = real.truncate(size)
        override fun sync() = throw IOException("No space left on device")
        override fun close() = real.close()
    }

    /** Wraps a real Sink whose write() always fails, simulating a header write that never lands. */
    private class ThrowingHeaderSink(private val real: Sink) : Sink {
        override fun write(src: ByteBuffer): Int = throw IOException("disk full")
        override fun writeAt(src: ByteBuffer, position: Long): Int = real.writeAt(src, position)
        override fun size(): Long = real.size()
        override fun truncate(size: Long) = real.truncate(size)
        override fun sync() = real.sync()
        override fun close() = real.close()
    }
}
