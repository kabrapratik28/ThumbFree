package io.github.kabrapratik28.thumbfree.ui

import android.content.Context
import android.os.SystemClock
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import io.github.kabrapratik28.thumbfree.app.AppGraph
import io.github.kabrapratik28.thumbfree.audio.AudioSource
import io.github.kabrapratik28.thumbfree.audio.ForegroundHooks
import io.github.kabrapratik28.thumbfree.core.models.Catalog
import io.github.kabrapratik28.thumbfree.core.models.ModelStore
import io.github.kabrapratik28.thumbfree.core.session.State
import io.github.kabrapratik28.thumbfree.data.Settings
import io.github.kabrapratik28.thumbfree.e2e.checkIdleAfterTest
import io.github.kabrapratik28.thumbfree.e2e.onMain
import io.github.kabrapratik28.thumbfree.e2e.releaseEngine
import io.github.kabrapratik28.thumbfree.models.ModelDownloads
import io.github.kabrapratik28.thumbfree.testing.TestModels
import io.github.kabrapratik28.thumbfree.testing.WavFileSource
import io.github.kabrapratik28.thumbfree.testing.jfkPcm
import io.github.kabrapratik28.thumbfree.testing.waitFor
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.AfterClass
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The welcome's try with the real take machine: the app's AndroidPorts, controller, queue and :engine, in MainActivity on
 * the try's step, with a take fed from jfk.wav at 4x through AppGraph.audioSourceFactory. Only its settings are a test
 * file of its own (the welcome left at the try, the English model chosen), and they go back after.
 */
@RunWith(AndroidJUnit4::class)
class TryOnceTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val app = InstrumentationRegistry.getInstrumentation().targetContext
    private val source = WavFileSource(jfkPcm())
    private lateinit var settings: Settings
    private lateinit var store: ModelStore
    private lateinit var factory: (Context) -> AudioSource

    @Before
    fun setUp() {
        settings = AppGraph.settings
        store = AppGraph.modelStore
        factory = AppGraph.audioSourceFactory
        val prefs = app.getSharedPreferences("try-settings", Context.MODE_PRIVATE).apply { edit().clear().commit() }
        AppGraph.settings = Settings(prefs).apply {
            welcomeScreen = WelcomeStep.TRY.name
            selectedModelId = Catalog.PARAKEET_UNIFIED_Q8.id
        }
        AppGraph.audioSourceFactory = { source }
    }

    @After
    fun tearDown() {
        try {
            checkIdleAfterTest()
        } finally {
            AppGraph.settings = settings
            AppGraph.modelStore = store
            AppGraph.audioSourceFactory = factory
            ModelDownloads.refresh() // the shared download state reads the real folder again
        }
    }

    // With the model on the phone the try's take is a real one: the bubble listens, saying so, and after the second tap
    // the words appear in the example's reply. It keeps nothing: no History row, no recording.
    @Test
    fun withTheModelTheWordsAppearAndNothingIsKept() {
        assumeTrue("no model: run android/tools/push-test-model.sh", TestModels.find(TestModels.PARAKEET_Q8) != null)
        val rows = AppGraph.history.list(Int.MAX_VALUE).size
        ActivityScenario.launch(MainActivity::class.java).use {
            tryOnce()

            // The reply is drawn, never a field: TalkBack, and so the test, reads it as "Your dictated words, read only: ...".
            compose.waitUntil(60_000) {
                compose.onAllNodesWithContentDescription("Your dictated words, read only: ", substring = true).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNode(hasContentDescription("fellow Americans", substring = true)).assertIsDisplayed()
            compose.onNodeWithText("That's it.").assertIsDisplayed()
            compose.onNodeWithText("Continue").assertIsDisplayed()
            assertKeptNothing(rows)
        }
    }

    // Going to the background mid-take (Home, the power button, another app) ends the try's take at once: the microphone
    // and its service are let go, and nothing is kept.
    @Test
    fun goingToTheBackgroundEndsTheTake() {
        assumeTrue("no model: run android/tools/push-test-model.sh", TestModels.find(TestModels.PARAKEET_Q8) != null)
        val rows = AppGraph.history.list(Int.MAX_VALUE).size
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitForText("Tap the yellow bubble.")
            compose.onNodeWithTag(TRY_BUBBLE).performTouchInput { click() }
            waitForText("Speak now.")

            scenario.moveToState(Lifecycle.State.CREATED)

            assertThat(waitFor(5_000) { onMain { AppGraph.controller.state } == State.Idle }).isTrue()
            assertThat(waitFor(5_000) { !ForegroundHooks.isForeground }).isTrue()
            assertKeptNothing(rows)
        }
    }

    /** Tap, let the whole source play, tap: a locked take, as the try asks for. */
    private fun tryOnce() {
        waitForText("Tap the yellow bubble.")
        compose.onNodeWithTag(TRY_BUBBLE).performTouchInput { click() }
        waitForText("Speak now.")
        compose.onNodeWithText("Listening").assertIsDisplayed()
        assertThat(source.awaitEnd(10_000)).isTrue()
        compose.onNodeWithTag(TRY_BUBBLE).performTouchInput { click() }
        waitForText("That's it.")
    }

    /** The take has ended with no History row added and nothing in the try's recording folder. */
    private fun assertKeptNothing(rows: Int) {
        assertThat(waitFor(30_000) { onMain { AppGraph.controller.state } == State.Idle }).isTrue()
        runBlocking { AppGraph.ports.db.barrier() }
        assertThat(AppGraph.history.list(Int.MAX_VALUE).size).isEqualTo(rows)
        assertThat(File(app.filesDir, "trial").listFiles().orEmpty().toList()).isEmpty()
    }

    private fun waitForText(text: String) =
        compose.waitUntil(10_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }

    companion object {
        @AfterClass
        @JvmStatic
        fun afterClass() = releaseEngine()
    }
}
