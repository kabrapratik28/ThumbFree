package io.github.kabrapratik28.thumbfree.ui

import android.Manifest
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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
import io.github.kabrapratik28.thumbfree.app.DictationController
import io.github.kabrapratik28.thumbfree.app.TrialHost
import io.github.kabrapratik28.thumbfree.core.models.Catalog
import io.github.kabrapratik28.thumbfree.core.models.DownloadProgress
import io.github.kabrapratik28.thumbfree.core.models.DownloadResult
import io.github.kabrapratik28.thumbfree.core.models.Downloader
import io.github.kabrapratik28.thumbfree.core.models.ModelFile
import io.github.kabrapratik28.thumbfree.core.models.ModelStatus
import io.github.kabrapratik28.thumbfree.core.models.ModelStore
import io.github.kabrapratik28.thumbfree.core.session.BubbleStyle
import io.github.kabrapratik28.thumbfree.core.session.BubbleUi
import io.github.kabrapratik28.thumbfree.core.session.ChipAction
import io.github.kabrapratik28.thumbfree.core.session.Code
import io.github.kabrapratik28.thumbfree.core.session.TouchOutput
import io.github.kabrapratik28.thumbfree.data.Retention
import io.github.kabrapratik28.thumbfree.e2e.releaseEngine
import io.github.kabrapratik28.thumbfree.engine.TranscriptionQueue
import io.github.kabrapratik28.thumbfree.models.DownloadState
import io.github.kabrapratik28.thumbfree.models.DownloadWorker
import io.github.kabrapratik28.thumbfree.models.FailReason
import io.github.kabrapratik28.thumbfree.models.ModelDownloads
import io.github.kabrapratik28.thumbfree.testing.SERVICE
import io.github.kabrapratik28.thumbfree.testing.WavFileSource
import io.github.kabrapratik28.thumbfree.testing.automation
import io.github.kabrapratik28.thumbfree.testing.jfkPcm
import io.github.kabrapratik28.thumbfree.testing.putSecure
import io.github.kabrapratik28.thumbfree.testing.secure
import io.github.kabrapratik28.thumbfree.testing.shell
import java.io.File
import java.io.RandomAccessFile
import java.util.Locale
import kotlin.concurrent.thread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Screenshots for a visual review: every download state on Home and in Settings, the multilingual model, and the
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
    fun homeTab() = shoot("home") { state -> HomeScreen(Tab.HOME, {}, remember { SnackbarHostState() }) { HomeTab(setup(state), {}, state) } }

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
     * downloaded, the multilingual model downloading, Canary not downloaded), then the first step's language choice as a
     * German phone's first run offers it, Other languages filled.
     */
    @Test
    fun multilingual() = withAnimationsOff {
        var dark by mutableStateOf(false)
        var welcome by mutableStateOf(false)
        compose.setContent {
            AppTheme(dark) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    if (!welcome) {
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
                            WelcomeStep.WELCOME, SetupState(false, false, ModelStatus.MISSING), null, false, NO_ACTIONS,
                            suggested = welcomeModel(null) { listOf(Locale.GERMANY) },
                        )
                    }
                }
            }
        }
        for (theme in listOf("light", "dark")) {
            dark = theme == "dark"
            welcome = false
            // Opened through its click action, not a touch, so no ripple shows in the picture.
            compose.onNodeWithText("See supported languages").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
            compose.onNodeWithText("Download or delete models").performScrollTo()
            save("settings-multilingual-$theme")
            welcome = true
            save("welcome-1-de-DE-$theme")
        }
    }

    /**
     * The first run, light and dark, as a phone with animations off shows it (each picture as its still): every state on
     * the boards: the language, the download, the check and load, each way it stops, and a load that failed; the try
     * before, during (and with a silent microphone) and after a take, with no words, and with the microphone off; the
     * bubble step, its return with the switch still off, and on; all set, the load it waits for, and what it says while
     * something is missing; Home while setup is left and once ready. Then steps 1 to 3 at 200% font.
     */
    @Test
    fun firstRun() = withAnimationsOff {
        var dark by mutableStateOf(false)
        var shot by mutableStateOf("")
        var fontScale by mutableStateOf(1f)
        val nothing = SetupState(false, false, ModelStatus.MISSING)
        val english = Catalog.PARAKEET_UNIFIED_Q8
        val downloading = DownloadState.Downloading(310_000_000, english.sizeBytes)
        val ready = SetupState(true, true, ModelStatus.VERIFIED, offered = listOf(english))
        val tried = listOf(BubbleUi.Arming, BubbleUi.Recording(0.6f, locked = true, 300), BubbleUi.Processing(false, 0, null), BubbleUi.Idle)
        val unheard = listOf(BubbleUi.Arming, BubbleUi.Recording(0.1f, locked = true, 300), BubbleUi.Chip(Code.NO_SPEECH, listOf(ChipAction.DISMISS)))
        fun ready(download: DownloadState?): @Composable () -> Unit =
            { WelcomeScreen(WelcomeStep.WELCOME, nothing, null, false, NO_ACTIONS, chosen = english, download = download) }
        fun trial(setup: SetupState, uis: List<BubbleUi>, words: String? = null, refusal: MicRefusal? = null): @Composable () -> Unit = {
            WelcomeScreen(WelcomeStep.TRY, setup, refusal, false, actions(Played(uis, words)), download = DownloadState.Ready)
        }
        fun finish(setup: SetupState, download: DownloadState = DownloadState.Ready, loaded: Boolean = true, loadFailed: Boolean = false): @Composable () -> Unit =
            { WelcomeScreen(WelcomeStep.READY, setup, null, false, NO_ACTIONS, download = download, loaded = loaded, loadFailed = loadFailed) }
        val screens: Map<String, @Composable () -> Unit> = linkedMapOf(
            "01-welcome" to { WelcomeScreen(WelcomeStep.WELCOME, nothing, null, false, NO_ACTIONS) },
            "02-downloading" to ready(downloading),
            "03-almost-ready" to ready(DownloadState.Ready),
            "12-waiting-for-wifi" to ready(DownloadState.Queued(wifiOnly = true)),
            "12-download-paused" to ready(DownloadState.Queued(wifiOnly = true, retrying = true)),
            "12-download-stopped" to ready(DownloadState.Failed(FailReason.NO_INTERNET)),
            "12-not-enough-space" to ready(DownloadState.Failed(FailReason.NOT_ENOUGH_SPACE)),
            "12-download-failed" to ready(DownloadState.Failed(FailReason.FILE_CHECK_FAILED)),
            "12-couldnt-prepare" to {
                WelcomeScreen(WelcomeStep.WELCOME, nothing, null, false, NO_ACTIONS, chosen = english, download = DownloadState.Ready, loadFailed = true)
            },
            "04-tap-the-bubble" to trial(ready, emptyList()),
            "06-speak-now" to trial(ready, listOf(BubbleUi.Arming, BubbleUi.Recording(0.8f, locked = true, 400))),
            "06-speak-now-silent-microphone" to trial(
                ready, listOf(BubbleUi.Arming, BubbleUi.Recording(0.05f, locked = true, 400), BubbleUi.Chip(Code.MIC_SILENT, listOf(ChipAction.DISMISS))),
            ),
            "07-thats-it" to trial(ready, tried, "Yes, see you at seven."),
            "07-no-words" to trial(ready, unheard),
            "12-microphone-off" to trial(ready.copy(micGranted = false), emptyList(), refusal = MicRefusal.DENIED),
            "08-turn-on-the-bubble" to { WelcomeScreen(WelcomeStep.SERVICE, nothing.copy(micGranted = true), null, false, NO_ACTIONS) },
            "12-bubble-still-off" to { WelcomeScreen(WelcomeStep.SERVICE, nothing.copy(micGranted = true), null, true, NO_ACTIONS) },
            "08-bubble-on" to { WelcomeScreen(WelcomeStep.SERVICE, ready, null, false, NO_ACTIONS) },
            "09-all-set" to finish(ready),
            "09-almost-ready" to finish(ready, loaded = false),
            "09-couldnt-prepare" to finish(ready, loaded = false, loadFailed = true),
            "09-bubble-off" to finish(ready.copy(serviceEnabled = false)),
            "09-microphone-off" to finish(nothing),
            "09-downloading" to finish(SetupState(true, true, ModelStatus.MISSING), downloading),
            "09-download-paused" to finish(SetupState(true, true, ModelStatus.MISSING), DownloadState.Queued(wifiOnly = true, retrying = true)),
            "09-download-stopped" to finish(SetupState(true, true, ModelStatus.MISSING), DownloadState.Failed(FailReason.NO_INTERNET)),
            "10-home-ready" to { HomeScreen(Tab.HOME, {}, remember { SnackbarHostState() }) { HomeTab(ready, {}, DownloadState.Ready) } },
            "10-home-setup" to {
                HomeScreen(Tab.HOME, {}, remember { SnackbarHostState() }) { HomeTab(SetupState(true, false, ModelStatus.VERIFIED), {}, DownloadState.Ready) }
            },
        )
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                AppTheme(dark) {
                    Surface(color = MaterialTheme.colorScheme.background) {
                        key(shot) { screens[shot]?.invoke() } // each on a fresh screen: the try's state is its own
                    }
                }
            }
        }
        for (theme in listOf("light", "dark")) {
            dark = theme == "dark"
            for (name in screens.keys) {
                shot = name
                save("first-run-$name-$theme")
            }
        }
        dark = false
        fontScale = 2f
        for (name in listOf("01-welcome", "02-downloading", "03-almost-ready", "04-tap-the-bubble", "08-turn-on-the-bubble", "12-bubble-still-off")) {
            shot = name
            save("first-run-$name-200-light")
        }
    }

    /** The welcome's actions for a render, with a try that has drawn [Played]'s takes. */
    private fun actions(trial: TrialLink) = WelcomeActions({}, {}, null, {}, {}, {}, {}, {}, {}, {}, trial)

    /** A try that has drawn [uis] and typed [words] by the time it shows. */
    private class Played(private val uis: List<BubbleUi>, private val words: String?) : TrialLink {
        override fun attach(host: TrialHost) {
            uis.forEach(host::render)
            words?.let(host::words)
        }

        override fun detach(host: TrialHost) = Unit
        override fun touch(output: TouchOutput) = Unit
        override fun chip(action: ChipAction) = Unit
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
        compose.mainClock.advanceTimeBy(2_000) // past the 1.5 s a wait for Wi-Fi lasts before the first step says so
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
 * The welcome screens in the real activity, system bars included (UiAutomation's screenshot), as a first run shows
 * them: no speech model on the phone, in the system's light or dark theme. For docs/brand, the store's raw captures and
 * a review. Only with `-e ui_shots 1`, into the same folder.
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

    /** Each step, once, as it opens. */
    @Test
    fun firstRunScreens() {
        val night = instrumentation.targetContext.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        val theme = if (night == Configuration.UI_MODE_NIGHT_YES) "dark" else "light"
        for (step in WelcomeStep.entries) {
            AppGraph.settings.welcomeScreen = step.name
            ActivityScenario.launch(MainActivity::class.java).use {
                instrumentation.waitForIdleSync()
                SystemClock.sleep(1_500)
                val shot = automation().takeScreenshot()
                File(out, "real-welcome-${step.ordinal + 1}-$theme.png").outputStream().use { shot.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
        }
    }

    /**
     * The owner's review set of the v4 boards, from the real activity with the system bars, in demo mode (9:30), light
     * and dark: the language; the English download held at 42% (a downloader that stops there, so nothing is fetched),
     * also at 200%; "Almost ready" while a slow fake engine loads the model, then recorded from the grey bubble turning
     * yellow into the try; the try's first tap, Android's microphone question (the run starts with the microphone
     * revoked; answered While using the app), listening and the words of a real take (the app's take machine and model,
     * fed jfk.wav); the bubble step as its picture plays and once it rests, recorded, also at 200%; its return with the
     * switch still off; all set; and Home. Only with `-e owner_shots 1` as well, into filesDir/ui-shots/owner/ and
     * /sdcard/thumbfree-owner-*.mp4. The theme, the font size, demo mode, the animator scale, the accessibility setting,
     * the downloader, the queue and the take machine go back as they were.
     */
    @Test
    fun ownerShots() {
        assumeTrue("pass -e owner_shots 1", InstrumentationRegistry.getArguments().getString("owner_shots") == "1")
        val dir = File(out, "owner").apply { mkdirs() }
        val app = instrumentation.targetContext
        val english = Catalog.PARAKEET_UNIFIED_Q8
        val night = shell("cmd uimode night").substringAfter(":").trim() // "yes", "no" or "auto"
        val font = shell("settings get system font_scale").trim()
        val demo = shell("settings get global sysui_demo_allowed").trim()
        val services = secure("enabled_accessibility_services")
        val realDownloader = DownloadWorker.downloaderFactory
        val realSource = AppGraph.audioSourceFactory
        val realQueue = AppGraph.queue
        fun save(name: String) {
            val shot = automation().takeScreenshot()
            File(dir, "$name.png").outputStream().use { shot.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        fun shoot(name: String, moments: List<Long> = listOf(1_500L)) = ActivityScenario.launch(MainActivity::class.java).use {
            val start = SystemClock.uptimeMillis() // about when the step's motion starts: the first composition
            instrumentation.waitForIdleSync()
            for ((i, at) in moments.withIndex()) {
                SystemClock.sleep((start + at - SystemClock.uptimeMillis()).coerceAtLeast(0))
                save(if (moments.size > 1) "$name-${i + 1}" else name)
            }
        }
        /** A screen recording of [seconds] while [during] runs, from the shell, as the owner would watch it. */
        fun recording(name: String, seconds: Int, during: () -> Unit) {
            val recorder = thread { shell("screenrecord --bit-rate 16000000 --time-limit $seconds /sdcard/thumbfree-owner-$name.mp4") }
            SystemClock.sleep(800) // screenrecord's own start
            during()
            recorder.join()
        }
        fun step(step: WelcomeStep) {
            AppGraph.settings.welcomeDone = false
            AppGraph.settings.welcomeScreen = step.name
        }
        fun themes(block: (String) -> Unit) {
            for (theme in listOf("light", "dark")) {
                shell("cmd uimode night ${if (theme == "dark") "yes" else "no"}")
                SystemClock.sleep(1_500) // the new configuration reaches the next activity
                block(theme)
            }
        }
        fun light() {
            shell("cmd uimode night no")
            SystemClock.sleep(1_500)
        }
        fun queue(loadMs: Long) = TranscriptionQueue(
            SlowEngine(loadMs), CoroutineScope(Dispatchers.IO), modelPath = { AppGraph.modelStore.verifiedPath(AppGraph.settings.model)?.path },
            threads = { 4 }, listener = NoQueueListener,
        )
        try {
            shell("settings put global sysui_demo_allowed 1")
            for (command in DEMO) shell("am broadcast -a com.android.systemui.demo -e command $command")
            putSecure("enabled_accessibility_services", null) // off, as on a first run

            // 01: the language, no bubble yet; at 200% too.
            themes { step(WelcomeStep.WELCOME); shoot("01-welcome-$it") }
            shell("settings put system font_scale 2.0")
            step(WelcomeStep.WELCOME); shoot("01-welcome-200-percent-light")
            shell(if (font == "null") "settings delete system font_scale" else "settings put system font_scale $font")

            // 02: English chosen, its download held at 42%; at 200% too.
            DownloadWorker.downloaderFactory = { modelsDir ->
                object : Downloader(modelsDir, usableSpace = { Long.MAX_VALUE }) {
                    override fun download(
                        model: ModelFile, url: String, isCancelled: () -> Boolean, onProgress: (DownloadProgress) -> Unit,
                    ): DownloadResult {
                        onProgress(DownloadProgress(310_000_000, model.sizeBytes))
                        while (!isCancelled()) Thread.sleep(50)
                        return DownloadResult.Failed(DownloadResult.Reason.CANCELLED, "cancelled")
                    }
                }
            }
            AppGraph.settings.selectedModelId = english.id // as the language's tap keeps it
            ModelDownloads.start(app, english)
            runBlocking { withTimeout(30_000) { ModelDownloads.state(app, english).first { it is DownloadState.Downloading } } }
            themes { step(WelcomeStep.WELCOME); shoot("02-downloading-$it") }
            light()
            shell("settings put system font_scale 2.0")
            step(WelcomeStep.WELCOME); shoot("02-downloading-200-percent-light")
            shell(if (font == "null") "settings delete system font_scale" else "settings put system font_scale $font")

            // 03: the download done (the model pushed for the device tests), loading into an engine that takes 20 s. The
            // shot is taken 2.5 s after the start from another thread: the launch may not return before the load ends.
            ModelDownloads.cancel(app, english)
            AppGraph.modelStore = ModelStore(File(app.filesDir, "models"))
            ModelDownloads.refresh()
            themes { theme ->
                AppGraph.queue = queue(20_000)
                welcomeLoaded = null // each theme waits for its own load
                step(WelcomeStep.WELCOME)
                val shot = thread { SystemClock.sleep(2_500); save("03-almost-ready-$theme") }
                ActivityScenario.launch(MainActivity::class.java).use { shot.join() }
            }

            // The grey bubble turning yellow and the try taking over, recorded, with a 2.5 s load.
            light()
            AppGraph.queue = queue(2_500)
            welcomeLoaded = null
            step(WelcomeStep.WELCOME)
            recording("1-into-2-light", 10) { ActivityScenario.launch(MainActivity::class.java).use { SystemClock.sleep(8_500) } }

            // 04: the try, its "Tap" label and halo; at 200% too, where the label keeps its place over the bubble.
            AppGraph.queue = realQueue
            themes { step(WelcomeStep.TRY); shoot("04-tap-the-bubble-$it", listOf(1_200L, 1_700L)) }
            light()
            shell("settings put system font_scale 2.0")
            step(WelcomeStep.TRY); shoot("04-tap-the-bubble-200-percent-light", listOf(1_200L))
            shell(if (font == "null") "settings delete system font_scale" else "settings put system font_scale $font")

            // 05 to 07: the first tap asks for the microphone; allowed, it listens at once; the real words.
            AppGraph.controller = DictationController(AppGraph.ports, SystemClock::elapsedRealtime)
            AppGraph.audioSourceFactory = { WavFileSource(jfkPcm()) }
            for (theme in listOf("light", "dark")) {
                shell("cmd uimode night ${if (theme == "dark") "yes" else "no"}")
                SystemClock.sleep(1_500)
                step(WelcomeStep.TRY)
                ActivityScenario.launch(MainActivity::class.java).use {
                    instrumentation.waitForIdleSync()
                    SystemClock.sleep(1_200)
                    node("Dictation, double tap").performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    if (app.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                        node("While using the app")
                        SystemClock.sleep(800)
                        save("05-microphone-$theme")
                        node("While using the app").performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    }
                    node("Speak now.")
                    SystemClock.sleep(1_500)
                    save("06-speak-now-$theme")
                    SystemClock.sleep(1_500) // the rest of the clip, at 4x
                    node("Dictation, double tap").performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    node("That's it.", 60_000)
                    SystemClock.sleep(1_500)
                    save("07-thats-it-$theme")
                }
            }

            // 08: the bubble step as its picture plays (each of its screens, the cross's pulse) and once it rests,
            // recorded; at 200% too.
            themes { step(WelcomeStep.SERVICE); shoot("08-turn-on-the-bubble-$it", listOf(1_200L, 3_800L, 4_900L, 7_800L, 19_000L)) }
            light()
            step(WelcomeStep.SERVICE)
            recording("08-settings-picture-light", 20) { ActivityScenario.launch(MainActivity::class.java).use { SystemClock.sleep(19_000) } }
            shell("settings put system font_scale 2.0")
            step(WelcomeStep.SERVICE); shoot("08-turn-on-the-bubble-200-percent-light", listOf(19_000L))
            shell(if (font == "null") "settings delete system font_scale" else "settings put system font_scale $font")

            // 12: back from Android's settings with the switch still off.
            themes {
                step(WelcomeStep.SERVICE)
                AppGraph.settings.accessibilityWait = System.currentTimeMillis()
                shoot("12-bubble-still-off-$it")
            }

            // 09 and 10: the bubble on, everything works: all set once the model is loaded again (a new activity, as a new
            // start of the app, has lost the first step's load; the take machine above left it in the engine), then Home.
            putSecure("enabled_accessibility_services", SERVICE)
            themes { theme ->
                step(WelcomeStep.READY)
                ActivityScenario.launch(MainActivity::class.java).use {
                    node("You're all set.", 30_000)
                    SystemClock.sleep(1_500)
                    save("09-all-set-$theme")
                }
                AppGraph.settings.welcomeDone = true
                shoot("10-home-$theme")
            }
        } finally {
            shell(if (font == "null") "settings delete system font_scale" else "settings put system font_scale $font")
            shell("cmd uimode night $night")
            putSecure("enabled_accessibility_services", services)
            shell("am broadcast -a com.android.systemui.demo -e command exit")
            shell(if (demo == "null") "settings delete global sysui_demo_allowed" else "settings put global sysui_demo_allowed $demo")
            ModelDownloads.cancel(app, english)
            DownloadWorker.downloaderFactory = realDownloader
            AppGraph.audioSourceFactory = realSource
            AppGraph.queue = realQueue
            releaseEngine()
        }
    }

    /**
     * A screen recording of each step as it opens, with `-e ui_video 1`: Android's screenrecord, run as the shell user,
     * into /sdcard/thumbfree-welcome-<step>.mp4, to pull and delete with adb.
     */
    @Test
    fun firstRunVideos() {
        assumeTrue("pass -e ui_video 1", InstrumentationRegistry.getArguments().getString("ui_video") == "1")
        for (step in WelcomeStep.entries) {
            AppGraph.settings.welcomeScreen = step.name
            ActivityScenario.launch(MainActivity::class.java).use {
                instrumentation.waitForIdleSync()
                SystemClock.sleep(500)
                shell("screenrecord --time-limit 7 /sdcard/thumbfree-welcome-${step.ordinal + 1}.mp4") // returns when it ends
            }
        }
    }

    /** The first node on screen whose text or description holds [text], waited for up to [timeoutMs]. */
    private fun node(text: String, timeoutMs: Long = 5_000): AccessibilityNodeInfo {
        fun find(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? = when {
            node == null -> null
            text in node.text?.toString().orEmpty() || text in node.contentDescription?.toString().orEmpty() -> node
            else -> (0 until node.childCount).firstNotNullOfOrNull { find(node.getChild(it)) }
        }
        val end = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < end) {
            // The active window first; Android's microphone question is another app's window.
            find(automation().rootInActiveWindow)?.let { return it }
            for (window in automation().windows) find(window.root)?.let { return it }
            SystemClock.sleep(100)
        }
        error("nothing on screen says $text")
    }

    private companion object {
        /** Demo mode's status bar for the owner's set: 9:30, full battery and signal, no notification icons. */
        val DEMO = listOf(
            "enter", "clock -e hhmm 0930", "battery -e level 100 -e plugged false",
            "network -e wifi show -e level 4 -e fully true", "network -e mobile hide", "notifications -e visible false",
        )
    }
}
