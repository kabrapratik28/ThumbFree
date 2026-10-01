package io.github.kabrapratik28.thumbfree.core.audio

/** Engine input for short clips. Only the engine gets the padding: the WAV keeps the real audio. */
object Padding {
    /**
     * A clip of 1 to 15,999 samples gets 8,000 zeros (0.5 s) before and after it, then trailing zeros up to 20,000
     * samples (1.25 s) if it is still shorter. Parakeet returned nothing for an unpadded 0.39 s "yes".
     * Empty clips and clips of 1 s or more come back as they are.
     */
    fun forEngine(clip: FloatArray): FloatArray {
        if (clip.isEmpty() || clip.size >= 16_000) return clip
        return FloatArray(maxOf(clip.size + 16_000, 20_000)).also { clip.copyInto(it, destinationOffset = 8_000) }
    }
}
