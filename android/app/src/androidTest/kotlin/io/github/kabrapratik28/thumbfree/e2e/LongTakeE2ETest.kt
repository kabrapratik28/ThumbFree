package io.github.kabrapratik28.thumbfree.e2e

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.google.common.truth.Truth.assertThat
import io.github.kabrapratik28.thumbfree.app.AppGraph
import io.github.kabrapratik28.thumbfree.audio.AudioSource
import io.github.kabrapratik28.thumbfree.data.Status
import io.github.kabrapratik28.thumbfree.testing.A11yRule
import io.github.kabrapratik28.thumbfree.testing.StrictModeRule
import io.github.kabrapratik28.thumbfree.testing.WavFileSource
import io.github.kabrapratik28.thumbfree.testing.focusTarget
import io.github.kabrapratik28.thumbfree.testing.jfkPcm
import io.github.kabrapratik28.thumbfree.testing.launchInsertTargets
import io.github.kabrapratik28.thumbfree.testing.normalizeTranscript
import org.junit.After
import org.junit.AfterClass
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LongTakeE2ETest {
    @get:Rule(order = 0)
    val a11y = A11yRule()

    @get:Rule(order = 1)
    val strict = StrictModeRule()

    // Created after the rule has set the UiAutomation flags.
    private val device by lazy { UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()) }

    // JFK 12 times, each followed by 0.6 s of silence: 139 s of audio, 35 s at 4x.
    private val source = WavFileSource(
        jfkPcm().let { jfk -> ShortArray(12 * (jfk.size + 9_600)).also { pcm -> repeat(12) { jfk.copyInto(pcm, it * (jfk.size + 9_600)) } } },
    )
    private lateinit var savedFactory: (Context) -> AudioSource

    @Before
    fun setUp() {
        savedFactory = AppGraph.audioSourceFactory
        AppGraph.audioSourceFactory = { source }
        launchInsertTargets(device)
    }

    @After
    fun tearDown() {
        try {
            checkIdleAfterTest()
        } finally {
            AppGraph.audioSourceFactory = savedFactory
        }
    }

    @Test
    fun twoMinuteTakeIsChunkedAndComplete() {
        focusTarget(device, "empty")
        device.tapBubble() // a locked take
        val id = awaitRecording()
        assertThat(source.awaitEnd(60_000)).isTrue()

        device.tapBubble()

        val text = device.awaitText("empty", 90_000) { phrases(it) == 12 }
        assertThat(phrases(text)).isEqualTo(12)
        val row = awaitRow(id) { it.status == Status.INSERTED }
        assertThat(row?.status).isEqualTo(Status.INSERTED)
        assertThat(row!!.chunksDone).isAtLeast(5)
    }

    companion object {
        @AfterClass
        @JvmStatic
        fun afterClass() = releaseEngine()
    }

    private fun phrases(text: String) = Regex("ask not what your country").findAll(normalizeTranscript(text)).count()
}
