package io.github.kabrapratik28.thumbfree.ui

import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.kabrapratik28.thumbfree.app.AppGraph
import io.github.kabrapratik28.thumbfree.core.models.Catalog
import io.github.kabrapratik28.thumbfree.core.models.ModelFile
import io.github.kabrapratik28.thumbfree.core.models.ModelStatus
import io.github.kabrapratik28.thumbfree.core.session.BubbleStyle
import io.github.kabrapratik28.thumbfree.data.Retention
import io.github.kabrapratik28.thumbfree.models.DownloadState
import io.github.kabrapratik28.thumbfree.models.FailReason
import io.github.kabrapratik28.thumbfree.testing.automation
import io.github.kabrapratik28.thumbfree.testing.shell
import java.io.File
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Screenshots for a visual review: every download state on the Try tab and in Settings, the multilingual model, and the
 * first run's screens. Runs only with `-e ui_shots 1`; the PNGs go to filesDir/ui-shots/, to pull with run-as.
 */
@RunWith(AndroidJUnit4::class)
class UiStateShots {
    @get:Rule val compose = createComposeRule()
    private val out = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "ui-shots")

    @Before
    fun optIn() {
        assumeTrue("pass -e ui_shots 1", InstrumentationRegistry.getArguments().getString("ui_shots") == "1")
        out.mkdirs()
    }

    private val states = listOf(
        "downloading" to DownloadState.Downloading(310_000_000, Catalog.PARAKEET_UNIFIED_Q8.sizeBytes),
        "waiting-wifi" to DownloadState.Queued(wifiOnly = true),
        "verifying" to DownloadState.Verifying,
        "no-internet" to DownloadState.Failed(FailReason.NO_INTERNET),
        "no-space" to DownloadState.Failed(FailReason.NOT_ENOUGH_SPACE),
        "interrupted" to DownloadState.Failed(FailReason.INTERRUPTED),
        "check-failed" to DownloadState.Failed(FailReason.FILE_CHECK_FAILED),
        "ready" to DownloadState.Ready,
    )

    @Test
    fun tryTab() = shoot("try") { state -> HomeScreen(Tab.TRY, {}, remember { SnackbarHostState() }) { TryScreen(setup(state), {}, state) } }

    @Test
    fun settings() = shoot("settings") { state ->
        SettingsScreen(
            setup(state), onFix = {}, onWelcome = {}, onChoose = {}, onManageModels = {},
            downloads = mapOf(Catalog.PARAKEET_UNIFIED_Q8 to state), bubbleStyle = BubbleStyle.RECOMMENDED, onBubbleStyle = {},
            wordCount = 0, onDictionary = {},
            retention = Retention(maxDays = null, maxTakes = 200), storageBytes = 0, onRetention = {},
            showRetention = false, onRetentionShown = {}, version = "0.1", onWebsite = {},
        )
    }

    /**
     * The multilingual model, light and dark: Settings > Speech model with its languages open (English chosen and
     * downloaded, the multilingual model downloading, Canary not downloaded), then the welcome screen with the English
     * model and its multilingual option, and with the multilingual model chosen, its languages open.
     */
    @Test
    fun multilingual() = withAnimationsOff {
        var dark by mutableStateOf(false)
        var chosen by mutableStateOf<ModelFile?>(null) // null: Settings; else the welcome screen with it chosen
        compose.setContent {
            AppTheme(dark) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    val welcome = chosen
                    if (welcome == null) {
                        SettingsScreen(
                            SetupState(true, true, ModelStatus.VERIFIED, offered = listOf(Catalog.PARAKEET_UNIFIED_Q8)),
                            onFix = {}, onWelcome = {}, onChoose = {}, onManageModels = {},
                            downloads = mapOf(
                                Catalog.PARAKEET_UNIFIED_Q8 to DownloadState.Ready,
                                Catalog.PARAKEET_TDT_V3_Q8 to DownloadState.Downloading(296_000_000, Catalog.PARAKEET_TDT_V3_Q8.sizeBytes),
                                Catalog.CANARY_180M_FLASH_Q8 to DownloadState.NotDownloaded,
                            ),
                            bubbleStyle = BubbleStyle.RECOMMENDED, onBubbleStyle = {}, wordCount = 0, onDictionary = {},
                            retention = Retention(maxDays = null, maxTakes = 200), storageBytes = 0, onRetention = {},
                            showRetention = false, onRetentionShown = {}, version = "0.1", onWebsite = {},
                        )
                    } else {
                        WelcomeScreen(
                            WelcomeStep.WELCOME, SetupState(true, true, ModelStatus.MISSING, chosen = welcome), null, false,
                            NO_ACTIONS, DownloadState.NotDownloaded, model = welcomeModel(welcome),
                        )
                    }
                }
            }
        }
        for (theme in listOf("light", "dark")) {
            dark = theme == "dark"
            chosen = null
            // Opened through its click action, not a touch, so no ripple shows in the picture.
            compose.onNodeWithText("See supported languages").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
            compose.onNodeWithText("Download or delete models").performScrollTo()
            save("settings-multilingual-$theme")
            chosen = Catalog.PARAKEET_UNIFIED_Q8
            save("welcome-1-multilingual-option-$theme")
            chosen = Catalog.PARAKEET_TDT_V3_Q8
            save("welcome-1-multilingual-$theme")
        }
    }

    /**
     * The first run, light and dark, as a phone with animations off shows it (each picture as its stills): the three
     * welcome steps, then the Try tab while the model downloads and once it is ready. Then the three steps at 200% font,
     * where the pictures shrink or go and nothing scrolls.
     */
    @Test
    fun firstRun() = withAnimationsOff {
        var dark by mutableStateOf(false)
        var shot by mutableStateOf("")
        var fontScale by mutableStateOf(1f)
        val nothing = SetupState(false, false, ModelStatus.MISSING)
        val english = Catalog.PARAKEET_UNIFIED_Q8
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                AppTheme(dark) {
                    Surface(color = MaterialTheme.colorScheme.background) {
                        when (shot) {
                            "welcome" -> WelcomeScreen(WelcomeStep.WELCOME, nothing, null, false, NO_ACTIONS, DownloadState.NotDownloaded)
                            "microphone" -> WelcomeScreen(WelcomeStep.MIC, nothing, null, false, NO_ACTIONS)
                            "bubble" -> WelcomeScreen(WelcomeStep.SERVICE, nothing.copy(micGranted = true), null, false, NO_ACTIONS)
                            "downloading" -> HomeScreen(Tab.TRY, {}, remember { SnackbarHostState() }) {
                                TryScreen(SetupState(true, true, ModelStatus.MISSING), {}, DownloadState.Downloading(310_000_000, english.sizeBytes))
                            }
                            "ready" -> HomeScreen(Tab.TRY, {}, remember { SnackbarHostState() }) {
                                TryScreen(SetupState(true, true, ModelStatus.VERIFIED, offered = listOf(english)), {}, DownloadState.Ready)
                            }
                        }
                    }
                }
            }
        }
        for (theme in listOf("light", "dark")) {
            dark = theme == "dark"
            for (name in listOf("welcome", "microphone", "bubble", "downloading", "ready")) {
                shot = name
                save("first-run-$name-$theme")
            }
        }
        dark = false
        fontScale = 2f
        for (name in listOf("welcome", "microphone", "bubble")) {
            shot = name
            save("first-run-$name-200-light")
        }
    }

    /** Runs [block] with the phone's animations off, as Remove animations would, then puts the setting back. */
    private fun withAnimationsOff(block: () -> Unit) {
        val before = shell("settings get global animator_duration_scale").trim()
        shell("settings put global animator_duration_scale 0")
        try {
            block()
        } finally {
            shell(if (before == "null") "settings delete global animator_duration_scale" else "settings put global animator_duration_scale $before")
        }
    }

    private fun save(name: String) {
        compose.waitForIdle()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(out, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** Everything granted; Parakeet verified only once it is ready, Canary verified throughout. */
    private fun setup(state: DownloadState) = SetupState(
        true, true, if (state == DownloadState.Ready) ModelStatus.VERIFIED else ModelStatus.MISSING,
        offered = listOfNotNull(Catalog.PARAKEET_UNIFIED_Q8.takeIf { state == DownloadState.Ready }, Catalog.CANARY_180M_FLASH_Q8),
    )

    private fun shoot(prefix: String, screen: @Composable (DownloadState) -> Unit) {
        var state by mutableStateOf(states.first().second)
        compose.setContent { AppTheme { Surface(color = MaterialTheme.colorScheme.background) { screen(state) } } }
        for ((name, each) in states) {
            state = each
            save("$prefix-$name")
        }
    }

    private companion object {
        val NO_ACTIONS = WelcomeActions({}, {}, null, {}, {}, {}, {}, {}, {}, {})
    }
}

/**
 * The three welcome screens in the real activity, system bars included (UiAutomation's screenshot), as a first run shows
 * them: no speech model on the phone, in the system's light or dark theme. For docs/brand, the store's raw captures and
 * a review. Only with `-e ui_shots 1`, into the same folder. The microphone step asks only while the permission is not
 * granted: revoke it with adb before the run (revoking it from here would end this process) and grant it again after.
 */
@RunWith(AndroidJUnit4::class)
class WelcomeShots {
    @get:Rule val graph = TestGraph(welcomeAt = WelcomeStep.WELCOME)
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val out = File(instrumentation.targetContext.filesDir, "ui-shots")

    @Before
    fun optIn() {
        assumeTrue("pass -e ui_shots 1", InstrumentationRegistry.getArguments().getString("ui_shots") == "1")
        out.mkdirs()
    }

    /** Each step at moments through its picture's loop, or once when animations are off and the pictures are stills. */
    @Test
    fun firstRunScreens() {
        val night = instrumentation.targetContext.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        val theme = if (night == Configuration.UI_MODE_NIGHT_YES) "dark" else "light"
        val still = shell("settings get global animator_duration_scale").trim().toFloatOrNull() == 0f
        for (step in WelcomeStep.entries) {
            AppGraph.settings.welcomeStep = step.ordinal
            ActivityScenario.launch(MainActivity::class.java).use {
                instrumentation.waitForIdleSync()
                val start = SystemClock.uptimeMillis()
                val moments = if (still) listOf(1_500L) else MOMENTS.getValue(step)
                for ((i, at) in moments.withIndex()) {
                    SystemClock.sleep((start + at - SystemClock.uptimeMillis()).coerceAtLeast(0))
                    val name = "real-welcome-${step.ordinal + 1}-${if (still) "still" else "${i + 1}"}-$theme.png"
                    val shot = automation().takeScreenshot()
                    File(out, name).outputStream().use { shot.compress(Bitmap.CompressFormat.PNG, 100, it) }
                }
            }
        }
    }

    /**
     * A screen recording of each step while its picture loops, with `-e ui_video 1`: Android's screenrecord, run as the
     * shell user, into /sdcard/thumbfree-welcome-<step>.mp4, to pull and delete with adb.
     */
    @Test
    fun firstRunVideos() {
        assumeTrue("pass -e ui_video 1", InstrumentationRegistry.getArguments().getString("ui_video") == "1")
        for (step in WelcomeStep.entries) {
            AppGraph.settings.welcomeStep = step.ordinal
            ActivityScenario.launch(MainActivity::class.java).use {
                instrumentation.waitForIdleSync()
                SystemClock.sleep(500)
                shell("screenrecord --time-limit 7 /sdcard/thumbfree-welcome-${step.ordinal + 1}.mp4") // returns when it ends
            }
        }
    }

    private companion object {
        /** When to take each step's screenshots, in ms after its screen starts: frames worth seeing in each loop. */
        val MOMENTS = mapOf(
            WelcomeStep.WELCOME to listOf(2_000L, 4_000L),
            WelcomeStep.MIC to listOf(1_300L, 2_000L),
            WelcomeStep.SERVICE to listOf(1_300L, 3_300L, 5_300L),
        )
    }
}
