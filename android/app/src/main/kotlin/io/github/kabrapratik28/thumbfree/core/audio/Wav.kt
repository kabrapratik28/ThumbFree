package io.github.kabrapratik28.thumbfree.core.audio

import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

object Wav {
    /**
     * Samples [from, to) of a 16 kHz mono PCM16 WAV as floats in [-1, 1).
     * Ignores the data size in the header, so a WAV that is still being written can be read.
     */
    fun readFloat(path: String, from: Long, to: Long): FloatArray = RandomAccessFile(path, "r").use { file ->
        val chunk = ByteArray(8)
        file.seek(12) // "RIFF", size, "WAVE"
        while (true) {
            file.readFully(chunk)
            if (String(chunk, 0, 4, Charsets.US_ASCII) == "data") break
            val size = ByteBuffer.wrap(chunk, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
            file.seek(file.filePointer + size + (size and 1)) // RIFF pads odd chunks
        }
        file.seek(file.filePointer + from * 2)
        val bytes = ByteArray(((to - from) * 2).toInt()).also { file.readFully(it) }
        val pcm = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        FloatArray(pcm.remaining()) { pcm.get(it) / 32768f }
    }
}
