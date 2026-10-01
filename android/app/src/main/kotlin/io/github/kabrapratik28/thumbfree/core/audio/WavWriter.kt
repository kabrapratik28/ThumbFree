package io.github.kabrapratik28.thumbfree.core.audio

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

class StorageFullException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** The seam for disk faults. FileSink wraps a FileChannel. */
interface Sink : Closeable {
    fun write(src: ByteBuffer): Int // bytes written, may be fewer than remaining
    fun writeAt(src: ByteBuffer, position: Long): Int // bytes written, may be fewer than remaining
    fun size(): Long
    fun truncate(size: Long)
    fun sync()
}

class FileSink(file: File) : Sink {
    private val raf = RandomAccessFile(file, "rw")
    private val channel = raf.channel

    override fun write(src: ByteBuffer): Int = channel.write(src)

    override fun writeAt(src: ByteBuffer, position: Long): Int = channel.write(src, position)

    override fun size(): Long = channel.size()

    override fun truncate(size: Long) {
        channel.truncate(size)
    }

    override fun sync() = channel.force(true)

    override fun close() = raf.close()
}

class WavWriter private constructor(private val sink: Sink) : Closeable {
    var samplesWritten: Long = 0
        private set

    private val buffer: ByteBuffer = ByteBuffer.allocateDirect(BUFFER_BYTES).order(ByteOrder.LITTLE_ENDIAN)

    /**
     * Appends PCM16 little-endian. Loops until every byte is written.
     * Throws StorageFullException on IOException or on two writes in a row that make no progress.
     * Do not call again after it throws: call finish() instead, which keeps every complete sample
     * that reached disk, including a partial slice from the failing call.
     */
    fun append(samples: ShortArray, count: Int = samples.size) {
        var offset = 0
        while (offset < count) {
            val slice = minOf(count - offset, buffer.capacity() / 2)
            buffer.clear()
            for (i in 0 until slice) buffer.putShort(samples[offset + i])
            buffer.flip()
            try {
                writeFully(sink, buffer)
            } catch (e: StorageFullException) {
                // Credit whatever whole samples made it to disk before the failure; buffer's
                // position is how far writeFully got into this slice.
                samplesWritten += buffer.position() / 2
                throw e
            }
            offset += slice
            samplesWritten += slice
        }
    }

    /** fsync without patching the header. Throws StorageFullException on IOException. */
    fun sync() = guarded { sink.sync() }

    /**
     * Truncates any partial sample, patches the RIFF and data sizes, fsyncs; returns samplesWritten.
     * Safe after a StorageFullException.
     */
    fun finish(): Long = guarded {
        val dataSize = samplesWritten * 2
        sink.truncate(HEADER_BYTES + dataSize)
        patchSizes(sink, dataSize)
        sink.sync()
        samplesWritten
    }

    override fun close() = sink.close()

    companion object {
        const val HEADER_BYTES = 44
        const val MIN_FREE_BYTES = 67_108_864L

        /** Throws StorageFullException, without creating the file, when usableSpace() < MIN_FREE_BYTES. */
        fun create(
            file: File,
            usableSpace: () -> Long = { file.absoluteFile.parentFile!!.usableSpace },
            openSink: (File) -> Sink = ::FileSink,
        ): WavWriter {
            if (usableSpace() < MIN_FREE_BYTES) throw StorageFullException("low on space")
            val sink = openSink(file)
            try {
                guarded { writeEmptyHeader(sink) }
            } catch (e: StorageFullException) {
                // The file (and possibly the sink's fd) exist but the header never landed: don't
                // leave an orphan behind.
                sink.close()
                file.delete()
                throw e
            }
            return WavWriter(sink)
        }

        /**
         * Rewrites the header from the file length, dropping a trailing odd byte. Returns the sample count. A file
         * shorter than the header holds no audio and becomes a valid empty WAV.
         */
        fun repair(file: File): Long = FileSink(file).use { sink ->
            guarded {
                if (sink.size() < HEADER_BYTES) writeEmptyHeader(sink) // over the partial header, from position 0
                val samples = (sink.size() - HEADER_BYTES) / 2
                val dataSize = samples * 2
                sink.truncate(HEADER_BYTES + dataSize)
                patchSizes(sink, dataSize)
                sink.sync()
                samples
            }
        }
    }
}

private const val BUFFER_BYTES = 64 * 1024

/** Builds the 44-byte PCM16 mono 16 kHz header with an empty data chunk and writes all of it with writeFully. */
private fun writeEmptyHeader(sink: Sink) {
    val header = ByteBuffer.allocate(WavWriter.HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN).apply {
        put("RIFF".toByteArray(Charsets.US_ASCII))
        putInt(36)
        put("WAVE".toByteArray(Charsets.US_ASCII))
        put("fmt ".toByteArray(Charsets.US_ASCII))
        putInt(16)
        putShort(1) // audio format: PCM
        putShort(1) // channels: mono
        putInt(16_000) // sample rate
        putInt(32_000) // byte rate
        putShort(2) // block align
        putShort(16) // bits per sample
        put("data".toByteArray(Charsets.US_ASCII))
        putInt(0)
    }
    header.flip()
    writeFully(sink, header)
}

/** Converts a thrown IOException into StorageFullException; an existing StorageFullException passes through unchanged. */
private fun <T> guarded(block: () -> T): T = try {
    block()
} catch (e: StorageFullException) {
    throw e
} catch (e: IOException) {
    throw StorageFullException(e.message ?: "write failed", e)
}

/**
 * Loops [attempt] until src is drained. Two consecutive zero-byte writes, or any IOException, become StorageFullException.
 * Inline, so an append on the recorder's writer thread makes no lambda and boxes no Int.
 */
private inline fun writeAllChecked(src: ByteBuffer, attempt: (ByteBuffer) -> Int) {
    var noProgress = 0
    while (src.hasRemaining()) {
        val written = try {
            attempt(src)
        } catch (e: IOException) {
            throw StorageFullException(e.message ?: "write failed", e)
        }
        if (written <= 0) {
            if (++noProgress >= 2) throw StorageFullException("no progress")
        } else {
            noProgress = 0
        }
    }
}

private fun writeFully(sink: Sink, src: ByteBuffer) = writeAllChecked(src) { sink.write(it) }

/** Same checked write-all loop as writeFully, but for a positional write: retries at an advancing offset. */
private fun writeAtFully(sink: Sink, src: ByteBuffer, position: Long) {
    var pos = position
    writeAllChecked(src) { buf -> sink.writeAt(buf, pos).also { pos += it } }
}

private fun patchSizes(sink: Sink, dataSize: Long) {
    writeAtFully(sink, leInt((36 + dataSize).toInt()), 4)
    writeAtFully(sink, leInt(dataSize.toInt()), 40)
}

private fun leInt(value: Int): ByteBuffer =
    ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).apply { flip() }
