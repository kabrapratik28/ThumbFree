package io.github.kabrapratik28.thumbfree.e2e

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.google.common.truth.Truth.assertThat
import io.github.kabrapratik28.thumbfree.app.AppGraph
import io.github.kabrapratik28.thumbfree.audio.AudioSource
import io.github.kabrapratik28.thumbfree.core.models.Catalog
import io.github.kabrapratik28.thumbfree.core.models.ModelStatus
import io.github.kabrapratik28.thumbfree.data.Status
import io.github.kabrapratik28.thumbfree.testing.A11yRule
import io.github.kabrapratik28.thumbfree.testing.JFK_TEXT
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

/**
 * One dictation with Canary chosen, on the DictationE2ETest harness: the bubble over another app's field, jfk.wav at
 * 4x, Canary in :engine, one commit. Needs the Canary GGUF pushed (android/tools/push-test-model.sh <serial>
 * canary-180m-flash-Q8_0.gguf).
 */
@RunWith(AndroidJUnit4::class)
class CanaryE2ETest {
    @get:Rule(order = 0)
    val a11y = A11yRule()

    @get:Rule(order = 1)
    val strict = StrictModeRule()

    // Created after the rule has set the UiAutomation flags.
    private val device by lazy { UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()) }
    private val source = WavFileSource(jfkPcm())
    private lateinit var savedFactory: (Context) -> AudioSource
    private var savedModel: String? = null

    @Before
    fun setUp() {
        // The home screen offers only a verified model, so it has hashed the file before the owner can pick it.
        check(AppGraph.modelStore.status(Catalog.CANARY_180M_FLASH_Q8) == ModelStatus.VERIFIED) {
            "no verified Canary in filesDir/models: run android/tools/push-test-model.sh <serial> canary-180m-flash-Q8_0.gguf"
        }
        savedFactory = AppGraph.audioSourceFactory
        savedModel = AppGraph.settings.selectedModelId
        AppGraph.audioSourceFactory = { source }
        AppGraph.settings.selectedModelId = Catalog.CANARY_180M_FLASH_Q8.id
        launchInsertTargets(device)
    }

    @After
    fun tearDown() {
        try {
            checkIdleAfterTest()
        } finally {
            AppGraph.audioSourceFactory = savedFactory
            AppGraph.settings.selectedModelId = savedModel // later classes run on Parakeet
        }
    }

    @Test
    fun tapTapInsertsJfkWithCanary() {
        focusTarget(device, "empty")
        device.tapBubble()
        val id = awaitRecording()
        assertThat(source.awaitEnd(10_000)).isTrue()
        device.tapBubble()

        // The press loads Canary, after unloading any other model, before the text can come.
        val text = device.awaitText("empty", 60_000) { normalizeTranscript(it) == JFK_TEXT }
        assertThat(normalizeTranscript(text)).isEqualTo(JFK_TEXT)
        val row = awaitRow(id) { it.status == Status.INSERTED }
        assertThat(row?.status).isEqualTo(Status.INSERTED)
        assertThat(row!!.modelId).isEqualTo(Catalog.CANARY_180M_FLASH_Q8.id)
    }

    companion object {
        @AfterClass
        @JvmStatic
        fun afterClass() = releaseEngine()
    }
}
