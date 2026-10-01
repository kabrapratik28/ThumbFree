package io.github.kabrapratik28.thumbfree.core.audio

import com.google.common.truth.Truth.assertThat
import java.util.Random
import kotlin.math.pow
import kotlin.math.roundToInt
import org.junit.Test

class SpeechGateTest {
    private val random = Random(7)

    @Test
    fun zerosAreSilentInput() {
        // Android 12+ feeds zeros when the microphone privacy toggle is off.
        val gate = SpeechGate()

        gate.add(ShortArray(3 * 16_000))

        assertThat(gate.silentMic).isTrue()
        assertThat(gate.hasSpeech).isFalse()
    }

    @Test
    fun shortSilentTapIsNoSpeech() {
        // Every model answered "Yeah." to a padded 0.25 s silent tap, so the gate must stop it before inference.
        val gate = SpeechGate()

        gate.add(ShortArray(4_000))

        assertThat(gate.hasSpeech).isFalse()
    }

    @Test
    fun coldStartYesIsSpeech() {
        // Tap and speak at once: 0.4 s of speech-like signal from sample 0, then room noise.
        val gate = SpeechGate()

        gate.add(noise(400, -25.0))
        gate.add(noise(350, -60.0))

        assertThat(gate.hasSpeech).isTrue()
    }

    @Test
    fun steadyNoiseAtTheStartIsNotSpeech() {
        // The floor is unknown when a take starts. Held at -50 dBFS for the first 0.9 s, it made 29 frames of -35 dBFS
        // noise speech (26 to 29 in the public noise clips): enough for the take-level rule and for Canary's retry.
        for (dbfs in listOf(-35.0, -25.0)) {
            val gate = SpeechGate()

            gate.add(noise(3_500, dbfs))

            assertThat(gate.speechFrames).isEqualTo(0)
        }
    }

    @Test
    fun noiseAfterAQuietStartIsNotSpeech() {
        // The Pixel's first two frames are near -74 dBFS (the bench's calibration take), then the room comes in.
        val gate = SpeechGate()

        gate.add(noise(30, -74.0) + noise(30, -72.0) + noise(3_000, -40.0))

        assertThat(gate.speechFrames).isEqualTo(0)
    }

    @Test
    fun speechAtTheStartIsSpeech() {
        // The user often speaks at once: speech from the first sample in a -45 dBFS room, then the stop tail.
        val gate = SpeechGate()

        gate.add(noise(150, -22.0) + noise(60, -42.0) + noise(240, -20.0) + noise(350, -45.0))

        assertThat(gate.speechFrames).isEqualTo(13) // the -22 and -20 dBFS frames; the dip between them is too quiet
        assertThat(gate.hasSpeech).isTrue()
    }

    @Test
    fun quietSpeechAtTheStartIsSpeech() {
        // -48 dBFS from the first sample in a -70 dBFS room: 22 dB over the floor and above -55 dBFS. The floor held
        // at -50 dBFS missed it: it asked for -38 dBFS until a quieter frame came, and by then the word was over.
        val gate = SpeechGate()

        gate.add(noise(390, -48.0) + noise(360, -70.0))

        assertThat(gate.speechFrames).isEqualTo(13)
    }

    @Test
    fun theFirst3sNeed8dBOverTheFloorAndLaterFrames12() {
        // A quiet word at the start of a noisy take is kept: in the first 3 s a frame needs 8 dB over the floor, after
        // them 12 (with 12 dB from the start, 5 or 6 of 150 private takes mixed under -40 dBFS noise typed nothing).
        val atStart = SpeechGate()
        atStart.add(noise(390, -41.0) + noise(360, -50.0)) // 9 dB over a -50 dBFS room
        assertThat(atStart.speechFrames).isEqualTo(13)

        val tooQuiet = SpeechGate()
        tooQuiet.add(noise(390, -45.0) + noise(360, -50.0)) // 5 dB over it
        assertThat(tooQuiet.speechFrames).isEqualTo(0)

        val later = SpeechGate()
        later.add(noise(3_000, -50.0) + noise(390, -41.0) + noise(360, -50.0)) // the 9 dB word after the first 3 s
        assertThat(later.speechFrames).isEqualTo(0)
    }

    @Test
    fun theFirst3sCountOnceTheirFloorIsKnown() {
        // onFrame cannot know the floor in the first 3 s, so it reports those frames as non-speech, and speechFrames
        // counts them against the floor of the first 3 s. From then on each frame is judged as it comes.
        val gate = SpeechGate()
        val reported = mutableListOf<Boolean>()

        gate.add(noise(990, -60.0) + noise(300, -20.0) + noise(1_710, -60.0)) { _, speech -> reported += speech }
        assertThat(reported).hasSize(100)
        assertThat(reported).doesNotContain(true)
        assertThat(gate.speechFrames).isEqualTo(10)

        gate.add(noise(300, -20.0)) { _, speech -> reported += speech }
        assertThat(reported.drop(100)).isEqualTo(List(10) { true })
        assertThat(gate.speechFrames).isEqualTo(20)
    }

    @Test
    fun steadyNoiseIsNotSpeech() {
        // Like edge/noise-3s: 3 s of Gaussian noise at -40 dBFS.
        val gate = SpeechGate()
        val levels = mutableListOf<Float>()
        var speechFrames = 0

        gate.add(noise(3_000, -40.0)) { dbfs, speech ->
            levels += dbfs
            if (speech) speechFrames++
        }

        assertThat(levels).hasSize(100) // 30 ms frames
        levels.forEach { assertThat(it).isWithin(1f).of(-40f) }
        assertThat(speechFrames).isEqualTo(0)
        assertThat(gate.hasSpeech).isFalse()
    }

    @Test
    fun burstsAboveFloorAreSpeech() {
        // After the first 3 s, -40 dBFS noise sets the floor: bursts 8 dB above it are not speech, bursts 20 dB above it are.
        val gate = SpeechGate()
        gate.add(noise(3_000, -40.0))

        repeat(3) {
            gate.add(noise(300, -32.0))
            gate.add(noise(600, -40.0))
        }
        assertThat(gate.hasSpeech).isFalse()

        repeat(3) {
            gate.add(noise(300, -20.0))
            gate.add(noise(600, -40.0))
        }
        assertThat(gate.hasSpeech).isTrue()
    }

    @Test
    fun burstsUnder55DbfsAreNotSpeech() {
        // Over digital silence the floor is -80 dBFS: -58 dBFS bursts are 22 dB above it but too quiet to be speech.
        val gate = SpeechGate()
        gate.add(ShortArray(900 * 16))

        repeat(3) {
            gate.add(noise(300, -58.0))
            gate.add(ShortArray(600 * 16))
        }
        assertThat(gate.hasSpeech).isFalse()

        gate.add(noise(300, -50.0))
        assertThat(gate.hasSpeech).isTrue()
    }

    @Test
    fun singleClickIsNotSpeech() {
        val take = noise(2_000, -60.0)
        for (i in 16_000 until 16_016) take[i] = 30_000 // a 1 ms click at 1 s
        val gate = SpeechGate()

        gate.add(take)

        assertThat(gate.speechFrames).isEqualTo(1) // the click frame is loud enough...
        assertThat(gate.hasSpeech).isFalse() // ...but a take needs 150 ms of speech frames
    }

    // The Recorder reads into one reused block, so only the first count samples may reach the frames.
    @Test
    fun countLimitsTheSamplesUsed() {
        val block = noise(30, -40.0) + noise(30, -10.0) // the loud second frame lies past the count
        val counted = mutableListOf<Float>()
        val copied = mutableListOf<Float>()

        SpeechGate().apply {
            add(block, 700) { dbfs, _ -> counted += dbfs }
            add(ShortArray(260)) { dbfs, _ -> counted += dbfs }
        }
        SpeechGate().apply {
            add(block.copyOf(700)) { dbfs, _ -> copied += dbfs }
            add(ShortArray(260)) { dbfs, _ -> copied += dbfs }
        }

        assertThat(counted).hasSize(2)
        assertThat(counted).isEqualTo(copied)
    }

    @Test
    fun needs150msOfSpeech() {
        val gate = SpeechGate()
        gate.add(noise(900, -60.0))
        gate.add(noise(120, -20.0)) // 4 speech frames
        gate.add(noise(900, -60.0))
        assertThat(gate.hasSpeech).isFalse()

        gate.add(noise(30, -20.0)) // a 5th, 150 ms in total
        assertThat(gate.hasSpeech).isTrue()
    }

    @Test
    fun silentMicAfter1500ms() {
        val blocked = SpeechGate()
        blocked.add(ShortArray(23_999) { 32 }) // peak -60.2 dBFS
        assertThat(blocked.silentMic).isFalse() // not judged before 1.5 s
        blocked.add(ShortArray(1) { 32 })
        assertThat(blocked.silentMic).isTrue()

        val live = SpeechGate()
        live.add(ShortArray(24_000).also { it[12_000] = 33 }) // one sample at -59.9 dBFS
        assertThat(live.silentMic).isFalse()
    }

    /** [ms] of 16 kHz Gaussian noise with an RMS level of [dbfs]. */
    private fun noise(ms: Int, dbfs: Double): ShortArray {
        val sigma = 32768 * 10.0.pow(dbfs / 20)
        return ShortArray(ms * 16) { (random.nextGaussian() * sigma).roundToInt().coerceIn(-32768, 32767).toShort() }
    }
}
