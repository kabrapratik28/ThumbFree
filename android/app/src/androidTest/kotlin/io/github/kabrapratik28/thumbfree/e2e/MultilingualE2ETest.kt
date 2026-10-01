package io.github.kabrapratik28.thumbfree.e2e

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.google.common.truth.Truth.assertThat
import io.github.kabrapratik28.thumbfree.app.AppGraph
import io.github.kabrapratik28.thumbfree.audio.AudioSource
import io.github.kabrapratik28.thumbfree.core.models.Catalog
import io.github.kabrapratik28.thumbfree.core.models.ModelFile
import io.github.kabrapratik28.thumbfree.core.models.ModelStatus
import io.github.kabrapratik28.thumbfree.data.Dictation
import io.github.kabrapratik28.thumbfree.data.Status
import io.github.kabrapratik28.thumbfree.testing.A11yRule
import io.github.kabrapratik28.thumbfree.testing.FLEURS_DE_TEXT
import io.github.kabrapratik28.thumbfree.testing.JFK_TEXT
import io.github.kabrapratik28.thumbfree.testing.StrictModeRule
import io.github.kabrapratik28.thumbfree.testing.WavFileSource
import io.github.kabrapratik28.thumbfree.testing.assetPcm
import io.github.kabrapratik28.thumbfree.testing.focusTarget
import io.github.kabrapratik28.thumbfree.testing.jfkPcm
import io.github.kabrapratik28.thumbfree.testing.launchInsertTargets
import io.github.kabrapratik28.thumbfree.testing.normalizeTranscript
import io.github.kabrapratik28.thumbfree.testing.normalizeWords
import org.junit.After
import org.junit.AfterClass
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Dictation with the multilingual model (Parakeet TDT 0.6B v3) chosen, on the DictationE2ETest harness: the bubble over
 * another app's field, a clip at 4x, the model in :engine, one commit. It hears English and German with no language set
 * anywhere, and its German keeps "um", which English cleanup would drop. The round trip goes English, multilingual,
 * English, each take on the model chosen at its press. Needs both GGUFs pushed: android/tools/push-test-model.sh
 * <serial> for Parakeet Unified, and android/tools/push-test-model.sh <serial> parakeet-tdt-0.6b-v3-Q8_0.gguf.
 */
@RunWith(AndroidJUnit4::class)
class MultilingualE2ETest {
    @get:Rule(order = 0)
    val a11y = A11yRule()

    @get:Rule(order = 1)
    val strict = StrictModeRule()

    // Created after the rule has set the UiAutomation flags.
    private val device by lazy { UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()) }
    private var source = WavFileSource(jfkPcm())
    private lateinit var savedFactory: (Context) -> AudioSource
    private var savedModel: String? = null

    @Before
    fun setUp() {
        // Settings offers only a verified model, so it has hashed the file before the owner can pick it.
        for (model in listOf(MULTILINGUAL, ENGLISH)) {
            check(AppGraph.modelStore.status(model) == ModelStatus.VERIFIED) {
                "no verified ${model.fileName} in filesDir/models: run android/tools/push-test-model.sh <serial> ${model.fileName}"
            }
        }
        savedFactory = AppGraph.audioSourceFactory
        savedModel = AppGraph.settings.selectedModelId
        AppGraph.audioSourceFactory = { source }
    }

    @After
    fun tearDown() {
        if (!::savedFactory.isInitialized) return // setUp stopped at a missing model, before it changed anything
        try {
            checkIdleAfterTest()
        } finally {
            AppGraph.audioSourceFactory = savedFactory
            AppGraph.settings.selectedModelId = savedModel // later classes run on Parakeet Unified
        }
    }

    @Test
    fun hearsEnglishAndGermanWithNoLanguageSet() {
        val (english, first) = take(MULTILINGUAL, jfkPcm()) { normalizeTranscript(it) == JFK_TEXT }
        assertThat(normalizeTranscript(english)).isEqualTo(JFK_TEXT)
        assertThat(first.modelId).isEqualTo(MULTILINGUAL.id)

        val (german, second) = take(MULTILINGUAL, assetPcm("fleurs-de.wav")) { normalizeWords(it) == FLEURS_DE_TEXT }
        assertThat(normalizeWords(german)).isEqualTo(FLEURS_DE_TEXT)
        // English filler removal would have taken "um", from the field and from the saved text.
        assertThat(german).contains("nötig, um Sandbänke")
        assertThat(second.text).contains("nötig, um Sandbänke")
    }

    @Test
    fun modelSwitchRoundTrip() {
        val (english, first) = take(ENGLISH, jfkPcm()) { normalizeTranscript(it) == JFK_TEXT }
        assertThat(normalizeTranscript(english)).isEqualTo(JFK_TEXT)
        assertThat(first.modelId).isEqualTo(ENGLISH.id)

        val (german, second) = take(MULTILINGUAL, assetPcm("fleurs-de.wav")) { normalizeWords(it) == FLEURS_DE_TEXT }
        assertThat(normalizeWords(german)).isEqualTo(FLEURS_DE_TEXT)
        assertThat(second.modelId).isEqualTo(MULTILINGUAL.id)

        val (again, third) = take(ENGLISH, jfkPcm()) { normalizeTranscript(it) == JFK_TEXT }
        assertThat(normalizeTranscript(again)).isEqualTo(JFK_TEXT)
        assertThat(third.modelId).isEqualTo(ENGLISH.id)
    }

    /**
     * One tap-tap take of [pcm] into a fresh "empty" field with [model] chosen: the text once [done] holds (the press
     * loads the model, after unloading any other), and the take's row once it is INSERTED.
     */
    private fun take(model: ModelFile, pcm: ShortArray, done: (String) -> Boolean): Pair<String, Dictation> {
        AppGraph.settings.selectedModelId = model.id
        source = WavFileSource(pcm)
        launchInsertTargets(device)
        focusTarget(device, "empty")
        device.tapBubble()
        val id = awaitRecording()
        assertThat(source.awaitEnd(10_000)).isTrue()
        device.tapBubble()
        val text = device.awaitText("empty", 90_000, done)
        val row = awaitRow(id) { it.status == Status.INSERTED }
        assertThat(row?.status).isEqualTo(Status.INSERTED)
        return text to row!!
    }

    companion object {
        private val MULTILINGUAL = Catalog.PARAKEET_TDT_V3_Q8
        private val ENGLISH = Catalog.PARAKEET_UNIFIED_Q8

        @AfterClass
        @JvmStatic
        fun afterClass() = releaseEngine()
    }
}
