package io.github.kabrapratik28.thumbfree.engine

import com.google.common.truth.Truth.assertThat
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Test

class SpeechCheckTest {
    private val background = Executors.newSingleThreadExecutor()
    private val logs = mutableListOf<String>()
    private var transcribes = 0
    private val transcribe = { ++transcribes; "text" }

    @After
    fun tearDown() {
        background.shutdownNow()
    }

    private fun check(probabilities: (FloatArray) -> FloatArray?) = SpeechCheck(probabilities, background) { logs += it }

    @Test
    fun speechIsTwoWindowsInARowAtOrOverTheThreshold() {
        assertThat(SpeechCheck.isSpeech(floatArrayOf(0.01f, 0.15f, 0.9f))).isTrue()
        assertThat(SpeechCheck.isSpeech(floatArrayOf(0.9f, 0.149f, 0.9f, 0.01f))).isFalse() // never two in a row
        assertThat(SpeechCheck.isSpeech(floatArrayOf(0.9f))).isFalse()
        assertThat(SpeechCheck.isSpeech(floatArrayOf())).isFalse()
    }

    @Test
    fun theTextOfAChunkWithoutSpeechIsDropped() {
        assertThat(check { NOISE }.run(CLIP, transcribe)).isNull()
        assertThat(check { SPEECH }.run(CLIP, transcribe)).isEqualTo("text")
        assertThat(transcribes).isEqualTo(2)
    }

    // The text must not wait for the check. The transcribe waits until the check has started, which it only does while
    // both run at once.
    @Test
    fun theCheckRunsWhileTheTranscribeRuns() {
        val started = CountDownLatch(1)
        val check = check {
            started.countDown()
            SPEECH
        }

        val text = check.run(CLIP) { if (started.await(5, TimeUnit.SECONDS)) "text" else "waited" }

        assertThat(text).isEqualTo("text")
    }

    // EngineService frees the model once a transcribe returns: the check must have ended by then, even when the
    // transcribe throws.
    @Test
    fun theCheckHasEndedWhenATranscribeThrows() {
        var ended = false
        val check = check {
            Thread.sleep(200)
            ended = true
            SPEECH
        }

        val thrown = runCatching { check.run(CLIP) { throw OutOfMemoryError("result") } }.exceptionOrNull()

        assertThat(thrown).isInstanceOf(OutOfMemoryError::class.java)
        assertThat(ended).isTrue()
    }

    // No model, or a failed run: the chunk counts as speech.
    @Test
    fun failsOpen() {
        assertThat(check { null }.run(CLIP, transcribe)).isEqualTo("text")
        assertThat(check { throw IllegalStateException("vad") }.run(CLIP, transcribe)).isEqualTo("text")
        assertThat(logs.filter { it.startsWith("speech_check failed_open") })
            .containsExactly("speech_check failed_open reason=unavailable",
                "speech_check failed_open reason=IllegalStateException")
    }

    // Silero hears the chunk as the WAV holds it: the engine's padding of short clips is not the check's business.
    @Test
    fun theCheckHearsTheChunkAsItIs() {
        var heard: FloatArray? = null
        val check = check {
            heard = it
            SPEECH
        }

        check.run(CLIP, transcribe)

        assertThat(heard).isSameInstanceAs(CLIP)
        assertThat(logs.first()).matches("speech_check heard=true peak=0\\.600 run=3 windows=3 ms=\\d+")
    }

    private companion object {
        val CLIP = FloatArray(1_536)
        val SPEECH = floatArrayOf(0.2f, 0.6f, 0.3f)
        val NOISE = floatArrayOf(0.9f, 0.01f, 0.01f)
    }
}
