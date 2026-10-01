package io.github.kabrapratik28.thumbfree.core.audio

import com.google.common.truth.Truth.assertThat
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WavTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun readsFloatRange() {
        val samples = ShortArray(300) { (it * 219 - 32768).toShort() }
        val file = tmp.newFile("pcm16.wav").apply { writeBytes(wav(samples)) }

        val out = Wav.readFloat(file.path, 100, 200)

        assertThat(out).isEqualTo(FloatArray(100) { samples[100 + it] / 32768f })
    }

    // 16 kHz mono PCM16 with an odd-sized LIST chunk before "data": ffmpeg adds a LIST chunk (jfk.wav has one),
    // and RIFF pads odd chunks to an even size.
    private fun wav(samples: ShortArray): ByteArray {
        val list = "INFOx".toByteArray()
        val size = 12 + 24 + 8 + list.size + 1 + 8 + samples.size * 2
        return ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()).putInt(size - 8).put("WAVE".toByteArray())
            // PCM, mono, 16 kHz, 32000 bytes/s, 2-byte frames, 16 bits
            put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(16_000).putInt(32_000)
            putShort(2).putShort(16)
            put("LIST".toByteArray()).putInt(list.size).put(list).put(0)
            put("data".toByteArray()).putInt(samples.size * 2)
            samples.forEach { putShort(it) }
        }.array()
    }
}
