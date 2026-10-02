package io.github.kabrapratik28.thumbfree.ui

import android.Manifest
import android.app.Activity
import android.app.Instrumentation
import android.content.ActivityNotFoundException
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.github.kabrapratik28.thumbfree.a11y.BubbleView
import io.github.kabrapratik28.thumbfree.a11y.DictationAccessibilityService
import io.github.kabrapratik28.thumbfree.app.AndroidPorts
import io.github.kabrapratik28.thumbfree.app.AppGraph
import io.github.kabrapratik28.thumbfree.app.DictationController
import io.github.kabrapratik28.thumbfree.app.DictationPorts
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
import io.github.kabrapratik28.thumbfree.core.session.Grey
import io.github.kabrapratik28.thumbfree.core.session.TouchOutput
import io.github.kabrapratik28.thumbfree.data.Dictation
import io.github.kabrapratik28.thumbfree.data.HistoryDb
import io.github.kabrapratik28.thumbfree.data.Retention
import io.github.kabrapratik28.thumbfree.data.Settings
import io.github.kabrapratik28.thumbfree.data.Status
import io.github.kabrapratik28.thumbfree.engine.Engine
import io.github.kabrapratik28.thumbfree.engine.EngineResult
import io.github.kabrapratik28.thumbfree.engine.TranscriptionQueue
import io.github.kabrapratik28.thumbfree.models.DownloadState
import io.github.kabrapratik28.thumbfree.models.DownloadWorker
import io.github.kabrapratik28.thumbfree.models.FailReason
import io.github.kabrapratik28.thumbfree.models.ModelDownloads
import io.github.kabrapratik28.thumbfree.testing.SERVICE
import io.github.kabrapratik28.thumbfree.testing.TestModels
import io.github.kabrapratik28.thumbfree.testing.enabledServices
import io.github.kabrapratik28.thumbfree.testing.putSecure
import io.github.kabrapratik28.thumbfree.testing.secure
import io.github.kabrapratik28.thumbfree.testing.shell
import io.github.kabrapratik28.thumbfree.testing.waitFor
import java.io.File
import java.io.RandomAccessFile
import java.lang.reflect.Proxy
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.experimental.runners.Enclosed
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith

/**
 * One class for the IT command: Screens hosts each screen alone; Main drives MainActivity over test doubles in AppGraph;
 * FirstLaunch starts it with the welcome screens left at the bubble step (and checks the return from Android's
 * settings), and FirstRun at their first.
 */
/** Home's one-time Dictionary card. */
private const val HINT = "Add names ThumbFree should spell your way: family, friends, work terms."

/** Each welcome step's words, and what its pictures tell TalkBack. */
private const val WELCOME_TITLE = "Your voice becomes text in any app."
private const val WELCOME_LINE = "Offline after setup. Private on your phone."
private const val QUESTION = "Which language do you speak?"
private const val OTHER_NOTE = "Spanish, French, German and 21 more"
private const val MODEL_ROW = "Speech model: English · 731 MB"
private const val GETTING_READY = "Getting your bubble ready"
private const val DOWNLOADS_ONCE = "It downloads once. Then ThumbFree works offline."
private const val LOADING_LINE = "Loading the speech model so it answers at once."
private const val SERVICE_TITLE = "Turn on the bubble."
private const val SERVICE_LINE =
    "ThumbFree uses Android accessibility only to show its bubble and type your words. It reads only the field you're typing in, and nothing leaves your phone."
private const val RULE_USE = "Turn on Use ThumbFree."
private const val RULE_SHORTCUT = "Don't turn on ThumbFree shortcut."
private const val ALL_SET = "You're all set."
private const val ALL_SET_LINE = "In any app, tap where you type. Tap the yellow bubble, speak, then tap it again."
private const val MIC_OFF = "Microphone is off"
private const val MIC_OFF_LINE = "ThumbFree needs it to hear you. It listens only while the bubble says Listening."
private const val SETTINGS_PICTURE = "Picture: in Android Settings, turn on Use ThumbFree, leave ThumbFree shortcut off, then tap Allow."
private const val LIST_PICTURE = "Picture of Android's accessibility list: ThumbFree under Downloaded apps."
private const val RING_PICTURE = "Picture: the speech model, 42% downloaded."
private const val CLOSER = "Try speaking near your phone."
private const val TRY_SAYING = "Try saying “Yes, see you at seven.” Or say anything."
private const val TRY_FINISH = "When you finish, tap the bubble again."
private const val DAMAGED_LINE = "The download was damaged. Try again to start it over."
private const val LOAD_FAILED = "Couldn't prepare speech"
private const val LOAD_FAILED_LINE = "The speech model didn't load. Try again, or change the language."
private const val LOAD_FAILED_RETRY = "The speech model didn't load. Try again."
private const val NOT_LOADED_PICTURE = "Picture: the speech model, which didn't load."
private const val HOME_HOW = "Tap where you type in any app. Tap the yellow bubble, speak, then tap it again."

/** What TalkBack hears for Sam's card and its reply, which can't be typed in. */
private const val CARD = "Example chat: Sam asks, Are you coming tonight?"
private const val REPLY = "Your dictated words, read only."

/** TalkBack's names for the steps. */
private const val READY_STEP = "Step 1 of 4, Get ready"
private const val TRY_STEP = "Step 2 of 4, Try it"
private const val SERVICE_STEP = "Step 3 of 4, Turn on the bubble"
private const val FINISH_STEP = "Step 4 of 4, All set"

/**
 * The phones the fit test lays the steps out on: a 1080 x 2400 one, the design's 360 x 800 reference, and a small one
 * under its system bars.
 */
private val PHONE = DpSize(411.dp, 914.dp)
private val REFERENCE_PHONE = DpSize(360.dp, 800.dp)
private val SMALL_PHONE = DpSize(360.dp, 640.dp)

/** 42% of the English model, and what TalkBack reads for the grey bubble then, in steps of 10%. */
private val DOWNLOADING = DownloadState.Downloading(310_000_000, 731_357_568)
private const val GREY_DOWNLOADING = "Speech is downloading, 40 percent"

private val ENGLISH = Catalog.PARAKEET_UNIFIED_Q8
private val MULTILINGUAL = Catalog.PARAKEET_TDT_V3_Q8

/**
 * One welcome step in one state: the words and buttons that must show whole in it, and the pictures that must show at
 * the default font size; on the small phone only where [smallPhonePictures]. The first step's state comes from the
 * [chosen] model, its [download] and whether it is [loaded] (or its load failed, [loadFailed]); the try's from what the
 * take machine draws ([trial]) and the [words] it types. The percentage is a picture here: TalkBack hears it in steps of
 * 10.
 */
private class FitCase(
    val name: String, val step: WelcomeStep, val setup: SetupState, val refusal: MicRefusal?, val texts: List<String>,
    val pictures: List<String>, val download: DownloadState = DownloadState.NotDownloaded, val smallPhonePictures: Boolean = true,
    val trial: List<BubbleUi> = emptyList(), val words: String? = null, val stillOff: Boolean = false,
    val chosen: ModelFile? = null, val loaded: Boolean = false, val loadFailed: Boolean = false,
)

/** What the take machine draws through one start and stop of a take, and one that heard nothing. */
private val TRIED = listOf(BubbleUi.Arming, BubbleUi.Recording(0.6f, locked = true, 300), BubbleUi.Processing(false, 0, null), BubbleUi.Idle)
private val UNHEARD = listOf(
    BubbleUi.Arming, BubbleUi.Recording(0.1f, locked = true, 300), BubbleUi.Chip(Code.NO_SPEECH, listOf(ChipAction.DISMISS)),
)

/** A long take's words, more than the try's fixed reply shows: it shows two lines of them, and TalkBack hears them all. */
private const val LONG_WORDS = "Yes, see you at seven, and I can bring the salad and the drinks if you like"

private val NOTHING = SetupState(false, false, ModelStatus.MISSING)
private val VERIFIED = SetupState(true, true, ModelStatus.VERIFIED, offered = listOf(Catalog.PARAKEET_UNIFIED_Q8))

private val FIT_CASES = listOf(
    // Step 1: what it does and the language, then the wait for the model on the same screen.
    FitCase(
        "get ready", WelcomeStep.WELCOME, NOTHING, null,
        listOf(WELCOME_TITLE, WELCOME_LINE, QUESTION, "English", "Best for English", "Other languages", OTHER_NOTE), listOf(CARD),
    ),
    FitCase(
        "get ready, downloading", WelcomeStep.WELCOME, NOTHING, null,
        listOf(GETTING_READY, DOWNLOADS_ONCE, MODEL_ROW, "Change"), listOf(CARD, "40%"), DOWNLOADING, chosen = ENGLISH,
    ),
    FitCase(
        "get ready, multilingual", WelcomeStep.WELCOME, NOTHING, null,
        listOf(GETTING_READY, "Speech model: 25 languages · 740 MB", "Change"), listOf(CARD, "40%"),
        DownloadState.Downloading(296_000_000, 739_508_576), chosen = MULTILINGUAL,
    ),
    FitCase(
        "get ready, almost", WelcomeStep.WELCOME, NOTHING, null, listOf("Almost ready", LOADING_LINE, MODEL_ROW),
        listOf(CARD, "100%"), DownloadState.Ready, chosen = ENGLISH,
    ),
    FitCase(
        "get ready, load failed", WelcomeStep.WELCOME, NOTHING, null, listOf(LOAD_FAILED, LOAD_FAILED_LINE, "Try again", "Change"),
        listOf(CARD), DownloadState.Ready, chosen = ENGLISH, loadFailed = true,
    ),
    FitCase(
        "get ready, waiting for Wi-Fi", WelcomeStep.WELCOME, NOTHING, null,
        listOf("Waiting for Wi-Fi", "The English download is 731 MB. Connect to Wi-Fi, or use mobile data.", "Use mobile data", "Change"),
        listOf(CARD), DownloadState.Queued(wifiOnly = true), chosen = ENGLISH,
    ),
    FitCase(
        "get ready, paused", WelcomeStep.WELCOME, NOTHING, null,
        listOf("Download paused", "It picks up where it stopped, by itself.", "Try again", "Change"), listOf(CARD),
        DownloadState.Queued(wifiOnly = true, retrying = true), chosen = ENGLISH,
    ),
    FitCase(
        "get ready, stopped", WelcomeStep.WELCOME, NOTHING, null,
        listOf("Download stopped", "Check your connection. It picks up where it stopped.", "Try again", "Change"), listOf(CARD),
        DownloadState.Failed(FailReason.NO_INTERNET), chosen = ENGLISH,
    ),
    FitCase(
        "get ready, download failed", WelcomeStep.WELCOME, NOTHING, null, listOf("Download failed", DAMAGED_LINE, "Try again", "Change"),
        listOf(CARD), DownloadState.Failed(FailReason.FILE_CHECK_FAILED), chosen = ENGLISH,
    ),
    FitCase(
        "get ready, no space", WelcomeStep.WELCOME, NOTHING, null,
        listOf("Not enough space", "Free up 1.8 GB, then try again.", "Try again", "Change"), listOf(CARD),
        DownloadState.Failed(FailReason.NOT_ENOUGH_SPACE), chosen = ENGLISH,
    ),
    // Step 2: the try, on the same card with the real bubble. Its states come from the take machine's drawing.
    FitCase("try", WelcomeStep.TRY, VERIFIED, null, listOf("Tap the yellow bubble."), listOf(CARD), DownloadState.Ready),
    FitCase(
        "try, listening", WelcomeStep.TRY, VERIFIED, null, listOf("Listening", "Speak now.", TRY_FINISH),
        listOf(CARD, TRY_SAYING), DownloadState.Ready, trial = listOf(BubbleUi.Recording(0.7f, locked = true, 400)),
    ),
    FitCase(
        "try, done with words", WelcomeStep.TRY, VERIFIED, null, listOf("That's it.", "Your words appear wherever you type.", "Continue"),
        listOf("Your dictated words, read only: $LONG_WORDS"), DownloadState.Ready, trial = TRIED, words = LONG_WORDS,
    ),
    FitCase(
        "try, no words", WelcomeStep.TRY, VERIFIED, null, listOf("Tap the yellow bubble.", CLOSER, "Continue"),
        listOf(CARD), DownloadState.Ready, trial = UNHEARD,
    ),
    FitCase(
        "try, microphone off", WelcomeStep.TRY, VERIFIED.copy(micGranted = false), MicRefusal.DENIED,
        listOf(MIC_OFF, MIC_OFF_LINE, "Allow microphone", "Not now"), listOf(CARD), DownloadState.Ready,
    ),
    // Step 3: the Settings picture is kept at any size; with animations off it rests on the two switches.
    FitCase(
        "bubble", WelcomeStep.SERVICE, SetupState(true, false, null), null,
        listOf(SERVICE_TITLE, RULE_USE, RULE_SHORTCUT, SERVICE_LINE, "Agree and open settings", "Not now"), listOf(SETTINGS_PICTURE),
    ),
    FitCase(
        "bubble, still off", WelcomeStep.SERVICE, SetupState(true, false, null), null,
        listOf("The bubble is still off.", RULE_USE, RULE_SHORTCUT, "Switch greyed out?", SERVICE_LINE, "Open settings again", "Not now"),
        listOf(LIST_PICTURE), stillOff = true,
    ),
    FitCase(
        "bubble, on", WelcomeStep.SERVICE, SetupState(true, true, null), null, listOf(SERVICE_TITLE, "The bubble is on", "Continue"),
        listOf(SETTINGS_PICTURE),
    ),
    // Step 4: all set only once everything works; else what a take still needs, with its fix.
    FitCase("all set", WelcomeStep.READY, VERIFIED, null, listOf(ALL_SET, ALL_SET_LINE, "Done"), emptyList(), DownloadState.Ready, loaded = true),
    FitCase(
        "finish, load failed", WelcomeStep.READY, VERIFIED, null, listOf(LOAD_FAILED, LOAD_FAILED_RETRY, "Try again", "Go to Home"),
        listOf(NOT_LOADED_PICTURE), DownloadState.Ready, loadFailed = true,
    ),
    FitCase(
        "finish, bubble off", WelcomeStep.READY, SetupState(true, false, ModelStatus.VERIFIED), null,
        listOf("The bubble is still off.", RULE_USE, RULE_SHORTCUT, "Turn on the bubble", "Not now"), listOf(LIST_PICTURE), DownloadState.Ready,
    ),
    FitCase(
        "finish, microphone off", WelcomeStep.READY, NOTHING, null, listOf(MIC_OFF, MIC_OFF_LINE, "Allow microphone", "Not now"), emptyList(),
    ),
    FitCase(
        "finish, downloading", WelcomeStep.READY, SetupState(true, true, ModelStatus.MISSING), null,
        listOf("Finishing the speech download", "Dictation starts when this finishes.", "Go to Home"), listOf(RING_PICTURE), DOWNLOADING,
    ),
)

/** Waits for the last step, whatever it asks for. */
private fun ComposeTestRule.waitForFinish() =
    waitUntil(10_000) { onAllNodesWithContentDescription(FINISH_STEP).fetchSemanticsNodes().isNotEmpty() }

/** Waits for the try. */
private fun ComposeTestRule.waitForTry() =
    waitUntil(20_000) { onAllNodesWithContentDescription(TRY_STEP).fetchSemanticsNodes().isNotEmpty() }

/** Leaves the last step for Home, by the way it offers: Not now while something is off, Go to Home, or Done. */
private fun ComposeTestRule.leaveFinish() = onAllNodes(hasText("Not now") or hasText("Done") or hasText("Go to Home")).onFirst().performClick()

/** The try's take machine, played by a screen test: what it draws and types reaches the try on the main thread. */
private class FakeTrialLink : TrialLink {
    val touches = CopyOnWriteArrayList<String>()
    @Volatile private var host: TrialHost? = null

    override fun attach(host: TrialHost) {
        this.host = host
    }

    override fun detach(host: TrialHost) {
        if (this.host === host) this.host = null
    }

    override fun touch(output: TouchOutput) {
        touches += when (output) {
            TouchOutput.Press -> "Press"
            is TouchOutput.Release -> "Release"
            else -> "other"
        }
    }

    override fun chip(action: ChipAction) = Unit

    fun render(vararg uis: BubbleUi) = onMain { uis.forEach { checkNotNull(host) { "the try is not on screen" }.render(it) } }

    fun words(text: String) = onMain { checkNotNull(host).words(text) }

    private fun onMain(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
}

/** A try that has drawn [uis] and typed [words] by the time it shows, for the fit test's cases. */
private class Replay(private val uis: List<BubbleUi>, private val words: String?) : TrialLink {
    override fun attach(host: TrialHost) {
        uis.forEach(host::render)
        words?.let(host::words)
    }

    override fun detach(host: TrialHost) = Unit
    override fun touch(output: TouchOutput) = Unit
    override fun chip(action: ChipAction) = Unit
}

/** Runs [block] on the main thread and returns what it gave. */
private fun <T> onMain(block: () -> T): T {
    var result: Result<T>? = null
    InstrumentationRegistry.getInstrumentation().runOnMainSync { result = runCatching(block) }
    return result!!.getOrThrow()
}

/** Every BubbleView in the resumed activity's views: the try's own control, or none. Main thread. */
private fun bubbleViews(): List<BubbleView> {
    fun all(view: View): List<View> = listOf(view) + ((view as? ViewGroup)?.let { g -> (0 until g.childCount).flatMap { all(g.getChildAt(it)) } }.orEmpty())
    return ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
        .flatMap { all(it.window.decorView) }.filterIsInstance<BubbleView>()
}

/** The try's bubble's grey look, and what TalkBack hears for it. */
private fun grey(): Grey? = onMain { bubbleViews().single().grey }
private fun bubbleLabel(): String? = onMain { bubbleViews().single().contentDescription?.toString() }

/** The welcome in one fit case, as the fit tests lay it out. */
@Composable
private fun Fit(case: FitCase, actions: WelcomeActions) = WelcomeScreen(
    case.step, case.setup, case.refusal, case.stillOff, actions, chosen = case.chosen, download = case.download, loaded = case.loaded,
    loadFailed = case.loadFailed,
)

@RunWith(Enclosed::class)
class HomeScreenTest {
    @RunWith(AndroidJUnit4::class)
    class Screens {
        @get:Rule val compose = createComposeRule()
        private val fixed = CopyOnWriteArrayList<SetupItem>()

        // While a take can't run, Home's Finish setup card leads with the one thing needed next, the microphone first,
        // then the bubble, then the speech model, each with its fix; nothing to try until then.
        @Test
        fun setupCardShowsWhatIsNeededNext() {
            var setup by mutableStateOf(SetupState(false, false, ModelStatus.MISSING))
            compose.setContent { AppTheme { HomeTab(setup, { fixed += it }) } }

            compose.onNodeWithText("Finish setup").assertIsDisplayed()
            compose.onNodeWithText("0 of 3 done").assertIsDisplayed()
            compose.onNodeWithText("Needed to hear you").assertIsDisplayed()
            compose.onNodeWithText("Off in Accessibility settings").assertDoesNotExist()
            compose.onNodeWithText("How to use it").assertDoesNotExist()
            compose.onNodeWithText("Allow").performClick()
            setup = SetupState(true, false, ModelStatus.MISSING)
            compose.onNodeWithText("1 of 3 done").assertIsDisplayed()
            compose.onNodeWithText("Off in Accessibility settings").assertIsDisplayed()
            compose.onNodeWithText("Turn on").performClick()
            setup = SetupState(true, true, ModelStatus.MISSING)
            compose.onNodeWithText("English speech model").assertIsDisplayed()
            compose.onNodeWithText("Not downloaded · 731 MB").assertIsDisplayed()
            compose.onNodeWithText("Get it").performClick()
            compose.runOnIdle { assertThat(fixed).containsExactly(SetupItem.MIC, SetupItem.SERVICE, SetupItem.MODEL).inOrder() }
        }

        // Once a take can run: Ready, then how to use the bubble, in one sentence and no picture. Home has nothing to type
        // into: the bubble works in other apps.
        @Test
        fun readyHomeSaysHowToUseIt() {
            compose.setContent { AppTheme { HomeTab(SetupState(true, true, ModelStatus.VERIFIED), {}) } }

            compose.onNodeWithText("Ready · works offline").assertIsDisplayed()
            compose.onNodeWithText("Finish setup").assertDoesNotExist()
            compose.onNodeWithText("How to use it").assertIsDisplayed()
            compose.onNodeWithText(HOME_HOW).assertIsDisplayed()
            compose.onNodeWithText("Or hold while speaking.").assertDoesNotExist()
            compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
            compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion)).assertCountEquals(0) // ready all along
        }

        // Turning ready while Home shows, the setup card gives way to Ready, and TalkBack hears it once: "Ready. Works offline."
        @Test
        fun homeSaysWhenItTurnsReady() {
            var setup by mutableStateOf(SetupState(true, true, ModelStatus.MISSING))
            compose.setContent { AppTheme { HomeTab(setup, {}) } }

            compose.onNodeWithText("Finish setup").assertIsDisplayed()
            setup = SetupState(true, true, ModelStatus.VERIFIED)
            compose.onNodeWithText("Finish setup").assertDoesNotExist()
            compose.onNodeWithContentDescription("Ready. Works offline.").assertIsDisplayed()
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
        }

        // The offered (verified) models show with the chosen one selected, and a tap on another picks it.
        @Test
        fun modelChoicePicksAnOfferedModel() {
            val picked = CopyOnWriteArrayList<ModelFile>()
            val offered = listOf(Catalog.PARAKEET_UNIFIED_Q8, Catalog.CANARY_180M_FLASH_Q8)
            compose.setContent {
                AppTheme { settings(SetupState(true, true, ModelStatus.VERIFIED, offered = offered), onChoose = { picked += it }) }
            }

            compose.onNodeWithText("English (recommended)").assertIsSelected()
            compose.onNodeWithText("Canary (experimental)").performScrollTo().assertIsNotSelected().performClick()
            compose.runOnIdle { assertThat(picked).containsExactly(Catalog.CANARY_180M_FLASH_Q8) }
        }

        // Each model says what it is for, and its size with its download; the multilingual model's languages fold out
        // under its row, outside its tap.
        @Test
        fun speechModelsSayWhatTheyAreForWithTheirSizeAndLanguages() {
            val picked = CopyOnWriteArrayList<ModelFile>()
            compose.setContent {
                AppTheme {
                    settings(
                        SetupState(true, true, ModelStatus.VERIFIED, offered = listOf(Catalog.PARAKEET_UNIFIED_Q8)),
                        onChoose = { picked += it },
                        downloads = mapOf(Catalog.PARAKEET_TDT_V3_Q8 to DownloadState.Downloading(296_000_000, 739_508_576)),
                    )
                }
            }

            compose.onNodeWithText("English (recommended)").performScrollTo().assertIsSelected()
            compose.onNodeWithText("Most accurate for English").assertIsDisplayed()
            compose.onNodeWithText("Downloaded · 731 MB").assertIsDisplayed()
            compose.onNodeWithText("Multilingual").performScrollTo().assertIsNotEnabled()
            compose.onNodeWithText("Supports 25 languages").assertIsDisplayed()
            compose.onNodeWithText("Downloading · 40%").assertIsDisplayed()
            compose.onNodeWithText("Not downloaded · 218 MB").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Ukrainian", substring = true).assertDoesNotExist()
            // TalkBack hears the state and what a tap does: Collapsed and Show, then Expanded and Hide.
            val languages = compose.onNodeWithText("See supported languages")
            languages.performScrollTo().assert(disclosure("Collapsed", "Show the languages")).performClick()
            languages.assert(disclosure("Expanded", "Hide the languages"))
            compose.onNodeWithText("Bulgarian, Croatian, Czech", substring = true).assertIsDisplayed()
            compose.onNodeWithText("Swedish, Ukrainian", substring = true).assertIsDisplayed()
            compose.runOnIdle { assertThat(picked).isEmpty() }
        }

        // The multilingual model's CC BY 4.0 credit says how it was changed and links, tappably, NVIDIA's model at the
        // revision it came from, the GGUF file at its pinned revision and the licence.
        @Test
        fun creditsLinkTheMultilingualModelsSourceAndLicense() {
            val opened = CopyOnWriteArrayList<String>()
            compose.setContent { AppTheme { settings(SetupState(true, true, ModelStatus.VERIFIED), onLink = { opened += it }) } }

            compose.onNodeWithText("Open-source licenses and credits").performScrollTo().performClick()
            val credit = hasText("Converted to GGUF, 8-bit (Q8_0).", substring = true)
            compose.onNode(credit).performScrollTo().assertIsDisplayed()
            val links = compose.onAllNodes(hasClickAction() and hasAnyAncestor(credit), useUnmergedTree = true)
            links.assertCountEquals(3)
            for (i in 0 until 3) links[i].performClick()
            compose.runOnIdle {
                assertThat(opened).containsExactly(
                    "https://huggingface.co/nvidia/parakeet-tdt-0.6b-v3/tree/6d590f77001d318fb17a0b5bf7ee329a91b52598",
                    "https://huggingface.co/handy-computer/parakeet-tdt-0.6b-v3-gguf/tree/90f082450fcbacdb54e5900c44ef697c9ea59622",
                    "https://creativecommons.org/licenses/by/4.0/",
                ).inOrder()
            }
        }

        // About's Privacy policy row, beside Website, opens the policy's published page: the address the store listing gives.
        @Test
        fun privacyPolicyOpensThePublishedPage() {
            val opened = CopyOnWriteArrayList<String>()
            compose.setContent { AppTheme { settings(SetupState(true, true, ModelStatus.VERIFIED), onLink = { opened += it }) } }

            compose.onNodeWithText("Privacy policy").performScrollTo().assertIsDisplayed().performClick()
            compose.runOnIdle {
                assertThat(opened).containsExactly("https://kabrapratik28.github.io/ThumbFree/android-privacy.html")
            }
        }

        // The credits travel with the APK: every license line shows once the list opens, each a text node TalkBack reads.
        @Test
        fun creditsNameEveryShippedLicense() {
            compose.setContent { AppTheme { settings(SetupState(true, true, ModelStatus.VERIFIED)) } }

            compose.onNodeWithText("Open-source licenses and credits").performScrollTo().performClick()
            for (line in listOf(
                "transcribe.cpp, ggml and miniz: MIT License",
                "Text cleanup rules adapted from MIT-licensed code, Copyright (c) 2025 CJ Pais",
                "LLVM libc++, the C++ runtime from the Android NDK: Apache License 2.0 with LLVM Exceptions",
                "Material icons by Google: Apache License 2.0",
                "Parakeet speech model by NVIDIA: NVIDIA Open Model License",
                "Canary speech model by NVIDIA: CC BY 4.0",
                "wordfreq word frequency data (the common-word list): CC BY-SA 4.0",
            )) compose.onNodeWithText(line).performScrollTo().assertIsDisplayed()
        }

        @Test
        fun retentionChipsChangeOneRule() {
            val rules = CopyOnWriteArrayList<Retention>()
            compose.setContent { AppTheme { settings(SetupState(true, true, ModelStatus.VERIFIED), onRetention = { rules += it }) } }

            compose.onNodeWithText("30 days").performScrollTo().performClick()
            compose.onNodeWithText("No limit").performScrollTo().performClick()
            compose.onNodeWithText("200").performScrollTo().assertIsSelected().performClick() // already chosen: nothing
            compose.runOnIdle {
                assertThat(rules).containsExactly(Retention(maxDays = 30, maxTakes = 200), Retention(maxDays = null, maxTakes = null)).inOrder()
            }
        }

        // The Dictionary tab: nothing is saved until Add. The field says why an entry can't be added (a repeat in any case,
        // several at once) and warns about a common word before it is added; Paste a list says what it will add and skip
        // before Add; Edit saves in place; Try a phrase shows what the list changes, or that it changes nothing; Delete
        // takes one entry out. Each change is saved as CustomWords.parse reads it.
        @Test
        fun dictionaryAddsEditsPastesAndDeletes() {
            val saved = CopyOnWriteArrayList<List<String>>()
            var words by mutableStateOf(emptyList<String>())
            compose.setContent { AppTheme { DictionaryScreen(words) { saved += it; words = it } } }
            val field = hasText("Word or phrase") and hasSetTextAction() and !hasAnyAncestor(isDialog())
            val add = hasText("Add") and hasClickAction() and !hasAnyAncestor(isDialog())
            compose.onNodeWithText("No words yet").assertIsDisplayed()
            compose.onNode(add).assertIsNotEnabled()

            compose.onNode(field).performTextInput("Anika")
            compose.runOnIdle { assertThat(saved).isEmpty() } // typing saves nothing
            compose.onNode(add).assertIsEnabled().performClick()
            compose.onNodeWithText("Added “Anika”.").assertIsDisplayed()

            compose.onNode(field).performTextInput("anika")
            compose.onNodeWithText("Already in your Dictionary.").assertIsDisplayed()
            compose.onNode(add).assertIsNotEnabled()
            compose.onNode(field).performTextReplacement("Priya, Will")
            compose.onNodeWithText("One at a time here. Use Paste a list for several.").assertIsDisplayed()
            compose.onNode(field).performTextReplacement("Will")
            compose.onNodeWithText("“Will” is also a common word, so every “will” will be written “Will”.").assertIsDisplayed()
            compose.onNode(add).assertIsEnabled().performClick()

            compose.onNodeWithText("Paste a list").performClick()
            compose.onNode(hasText("Names and terms") and hasSetTextAction()).performTextInput("GitHub, kubernetes\nanika, ${"x".repeat(61)}")
            compose.onNodeWithText("2 will be added. 1 duplicate and 1 long entry will be skipped.").assertIsDisplayed()
            compose.onNode(hasText("Add") and hasClickAction() and hasAnyAncestor(isDialog())).performClick()
            compose.onNodeWithText("Added 2 words.").assertIsDisplayed()

            compose.onNodeWithContentDescription("Edit kubernetes").performScrollTo().performClick()
            compose.onNode(hasText("Word or phrase") and hasSetTextAction() and hasAnyAncestor(isDialog())).performTextReplacement("Kubernetes")
            compose.onNodeWithText("Save").performClick()

            val sentence = hasText("Sentence") and hasSetTextAction()
            compose.onNode(sentence).performScrollTo().performTextInput("ask anika about kubernetes")
            compose.onNodeWithText("ThumbFree would type: ask Anika about Kubernetes").performScrollTo().assertIsDisplayed()
            compose.onNode(sentence).performTextReplacement("see you soon")
            compose.onNodeWithText("No Dictionary changes.").performScrollTo().assertIsDisplayed()

            compose.onNodeWithContentDescription("Delete GitHub").performScrollTo().performClick()
            compose.runOnIdle {
                assertThat(saved[0]).containsExactly("Anika")
                assertThat(saved[1]).containsExactly("Anika", "Will").inOrder()
                assertThat(saved[2]).containsExactly("Anika", "Will", "GitHub", "kubernetes").inOrder()
                assertThat(saved[3]).containsExactly("Anika", "Will", "GitHub", "Kubernetes").inOrder() // edited in place
                assertThat(saved.last()).containsExactly("Anika", "Will", "Kubernetes").inOrder()
            }
        }

        // Try a phrase matches as the chosen model's takes do: near misses too on an English model, exact matches only
        // on the multilingual one, where Italian "grazie" is no near miss of Grazia.
        @Test
        fun tryAPhraseMatchesAsTheChosenModelDoes() {
            var exactOnly by mutableStateOf(false)
            compose.setContent { AppTheme { DictionaryScreen(listOf("Grazia", "Łukasz"), exactOnly) {} } }
            val sentence = hasText("Sentence") and hasSetTextAction()

            compose.onNode(sentence).performScrollTo().performTextInput("grazie łukasz")
            compose.onNodeWithText("ThumbFree would type: Grazia Łukasz").performScrollTo().assertIsDisplayed()
            exactOnly = true
            compose.onNodeWithText("ThumbFree would type: grazie Łukasz").performScrollTo().assertIsDisplayed()
        }

        // Settings keeps one row for the Dictionary: its word count, and a tap opens the tab.
        @Test
        fun settingsRowOpensTheDictionary() {
            var opened = 0
            compose.setContent {
                AppTheme { settings(SetupState(true, true, ModelStatus.VERIFIED), wordCount = 3, onDictionary = { opened++ }) }
            }
            compose.onNodeWithText("3 words").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Open the dictionary").performClick()
            compose.runOnIdle { assertThat(opened).isEqualTo(1) }
        }

        // Home's one-time card: Open Dictionary goes there and retires it, and so does Not now.
        @Test
        fun homeHintOpensTheDictionaryOrRetires() {
            val calls = CopyOnWriteArrayList<String>()
            compose.setContent {
                AppTheme {
                    HomeTab(
                        SetupState(true, true, ModelStatus.VERIFIED), {}, dictionaryHint = true,
                        onDictionary = { calls += "open" }, onHintDone = { calls += "done" },
                    )
                }
            }
            compose.onNodeWithText(HINT).assertIsDisplayed()
            compose.onNodeWithText("Open Dictionary").performClick()
            compose.onNodeWithText("Not now").performClick()
            compose.runOnIdle { assertThat(calls).containsExactly("done", "open", "done").inOrder() }
        }

        // Settings > Bubble: a size tile, the transparency slider (TalkBack hears "20 percent", not the thumb's place in the
        // range), and Reset to recommended, which is off while the style is the recommended one.
        @Test
        fun bubbleSectionSetsSizeOpacityAndResets() {
            val picked = CopyOnWriteArrayList<BubbleStyle>()
            var style by mutableStateOf(BubbleStyle.RECOMMENDED)
            compose.setContent {
                AppTheme { settings(SetupState(true, true, ModelStatus.VERIFIED), bubble = style, onBubble = { picked += it; style = it }) }
            }
            val slider = compose.onNodeWithContentDescription("Transparency while idle")
            slider.performScrollTo().assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "20 percent"))
            compose.onNode(hasText("Large\nRecommended") and hasClickAction()).assertIsSelected()
            compose.onNodeWithText("20% is recommended", substring = true).assertExists()
            compose.onNodeWithText("Reset to recommended").performScrollTo().assertIsNotEnabled()

            compose.onNodeWithText("Small").performScrollTo().performClick()
            slider.performSemanticsAction(SemanticsActions.SetProgress) { it(40f) } // 40% transparent is 60% opaque
            slider.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "40 percent"))
            compose.onNodeWithText("40%").assertExists()
            compose.onNodeWithText("Reset to recommended").performScrollTo().assertIsEnabled().performClick()

            compose.runOnIdle {
                assertThat(picked).containsExactly(
                    BubbleStyle(BubbleStyle.Size.SMALL, 80), BubbleStyle(BubbleStyle.Size.SMALL, 60), BubbleStyle.RECOMMENDED,
                ).inOrder()
            }
        }

        // Settings > Bubble > Position: the line follows whether the owner dropped the bubble somewhere, Snap to screen
        // edge is a switch (off at first), and Reset position is on only for a dropped bubble.
        @Test
        fun bubblePositionSnapAndReset() {
            val snaps = CopyOnWriteArrayList<Boolean>()
            var resets = 0
            var placed by mutableStateOf(false)
            compose.setContent {
                AppTheme {
                    settings(
                        SetupState(true, true, ModelStatus.VERIFIED), placed = placed, onSnap = { snaps += it },
                        onResetPosition = { resets++; placed = false },
                    )
                }
            }
            compose.onNodeWithText("Next to the keyboard", substring = true).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Reset position").performScrollTo().assertIsNotEnabled()
            compose.onNodeWithText("Snap to screen edge", substring = true).performScrollTo().assertIsOff().performClick()

            placed = true
            compose.onNodeWithText("Where you dropped it", substring = true).assertIsDisplayed()
            compose.onNodeWithText("Reset position").assertIsEnabled().performClick()

            compose.onNodeWithText("Next to the keyboard", substring = true).assertIsDisplayed()
            compose.runOnIdle {
                assertThat(snaps).containsExactly(true)
                assertThat(resets).isEqualTo(1)
            }
        }

        // A take typed into an app and then transcribed again keeps its Typed status and shows the new text, saying
        // plainly that this text wasn't typed in; one never typed says Not inserted, and just Transcribed again.
        @Test
        fun historyShowsATakeTranscribedAgain() {
            val rows = listOf(
                row(1, Status.INSERTED, "New words").copy(insertedText = "Old words", retranscribed = true),
                row(2, Status.NOT_INSERTED, "Second try").copy(retranscribed = true),
            )
            compose.setContent { AppTheme { history(rows) } }

            compose.onNodeWithText("Typed").assertIsDisplayed()
            compose.onNodeWithText("New words").assertIsDisplayed()
            compose.onNodeWithText("Old words").assertDoesNotExist()
            compose.onNodeWithText("Transcribed again. This text wasn't typed in.").assertIsDisplayed()
            compose.onNodeWithText("Not inserted").assertIsDisplayed()
            compose.onNodeWithText("Transcribed again").assertIsDisplayed()
        }

        // Search looks at the text rows show; a no-speech take's text stays hidden and never matches. Every ended take can
        // be transcribed again.
        @Test
        fun historySearchFiltersShownText() {
            val rows = listOf(row(1, Status.INSERTED, "Apple pie tonight"), row(2, Status.INSERTED, "Banana split"), row(3, Status.NO_SPEECH, "Apple secret"))
            compose.setContent { AppTheme { history(rows) } }
            compose.onAllNodesWithTag("history_row").assertCountEquals(3)
            compose.onAllNodesWithText("Transcribe again").assertCountEquals(3) // every ended take, typed ones included

            compose.onNode(hasText("Search your takes") and hasSetTextAction()).performTextInput("apple")

            compose.onAllNodesWithTag("history_row").assertCountEquals(1)
            compose.onNodeWithText("Apple pie tonight").assertIsDisplayed()
            compose.onNode(hasSetTextAction()).performTextInput("zzz")
            compose.onNodeWithText("No takes match \"applezzz\".").assertIsDisplayed()
        }

        @Test
        fun clearAllAsksFirst() {
            var cleared = 0
            compose.setContent { AppTheme { history(listOf(row(1, Status.INSERTED, "One"), row(2, Status.NOT_INSERTED, "Two")), onClearAll = { cleared++ }) } }

            compose.onNodeWithText("Clear all").performClick()
            compose.onNodeWithText("This deletes 2 takes and their recordings from this phone. It can't be undone.").assertIsDisplayed()
            compose.onNodeWithText("Cancel").performClick()
            compose.runOnIdle { assertThat(cleared).isEqualTo(0) }

            compose.onNodeWithText("Clear all").performClick()
            compose.onNodeWithText("Delete all").performClick()
            compose.runOnIdle { assertThat(cleared).isEqualTo(1) }
        }

        // An empty History points to how to use the bubble, on Home.
        @Test
        fun historyRetentionLineAndEmptyState() {
            var changed = 0
            var home = 0
            compose.setContent {
                AppTheme { history(emptyList(), retention = Retention(maxDays = 30, maxTakes = 1_000), onRetention = { changed++ }, onHome = { home++ }) }
            }

            compose.onNodeWithText("Keeps takes for 30 days, up to 1,000").assertIsDisplayed() // no size for an empty history
            compose.onNodeWithText("No takes yet").assertIsDisplayed()
            compose.onNodeWithText("Clear all").assertDoesNotExist()
            compose.onNodeWithText("Change").performClick()
            compose.onNode(hasText("How to use it") and hasClickAction()).performClick()
            compose.runOnIdle { assertThat(changed to home).isEqualTo(1 to 1) }
        }

        // Step 1 asks the language with two buttons, each the whole of it a target, English first whichever is filled (the
        // smart default fills Other languages on a phone set to one of its languages); the card has no bubble yet. A tap
        // chooses that model, whose download MainActivity starts. No big button and no Back.
        @Test
        fun theFirstStepAsksTheLanguage() {
            val calls = CopyOnWriteArrayList<String>()
            var suggested by mutableStateOf(ENGLISH)
            compose.setContent { AppTheme { WelcomeScreen(WelcomeStep.WELCOME, NOTHING, null, false, actions(calls), suggested = suggested) } }

            compose.onNodeWithContentDescription(READY_STEP).assertExists()
            compose.onNodeWithText(WELCOME_TITLE).assertIsDisplayed()
            compose.onNodeWithText(WELCOME_LINE).assertIsDisplayed()
            compose.onNodeWithText(QUESTION).assertIsDisplayed()
            compose.onNodeWithContentDescription(CARD).assertIsDisplayed()
            compose.onNodeWithTag(TRY_BUBBLE).assertDoesNotExist() // the user meets the bubble only when it works
            compose.onNodeWithContentDescription("Back").assertDoesNotExist()
            compose.onAllNodesWithTag(PRIMARY_BUTTON).assertCountEquals(0)
            for (each in listOf(ENGLISH, MULTILINGUAL)) {
                suggested = each
                compose.waitForIdle()
                assertThat(compose.onNodeWithTag(LANGUAGE_ENGLISH).getBoundsInRoot().bottom)
                    .isLessThan(compose.onNodeWithTag(LANGUAGE_OTHER).getBoundsInRoot().top)
            }
            compose.onNodeWithTag(LANGUAGE_OTHER).assert(hasText(OTHER_NOTE, substring = true)).performClick()
            compose.onNodeWithTag(LANGUAGE_ENGLISH).assert(hasText("Best for English", substring = true)).performClick()
            compose.runOnIdle { assertThat(calls).containsExactly("language ${MULTILINGUAL.fileName}", "language ${ENGLISH.fileName}").inOrder() }
        }

        // The language's tap starts the download and the same screen waits for it: the grey bubble in the card's slot, with
        // its ring and download badge, which TalkBack hears in steps of 10% and which is no button; the big percentage,
        // heard in steps of 10% too; the speech model's line with Change, which goes back to the choice. No big button and
        // no Skip; a tap on the grey bubble starts nothing.
        @Test
        fun theFirstStepWaitsForTheDownload() {
            val calls = CopyOnWriteArrayList<String>()
            compose.setContent {
                AppTheme { WelcomeScreen(WelcomeStep.WELCOME, NOTHING, null, false, actions(calls), chosen = ENGLISH, download = DOWNLOADING) }
            }

            compose.onNodeWithText(GETTING_READY).assertIsDisplayed()
            // 42% on screen; TalkBack is given 40% only, as a live region, so it hears the number every 10% at most.
            compose.onNodeWithContentDescription("40%").assertIsDisplayed()
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
                .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Text))
            compose.onAllNodesWithText("42%").assertCountEquals(0)
            compose.onNodeWithText(DOWNLOADS_ONCE).assertIsDisplayed()
            compose.onNodeWithText(MODEL_ROW).assertIsDisplayed()
            compose.onAllNodesWithTag(PRIMARY_BUTTON).assertCountEquals(0)
            compose.onNodeWithText("Skip for now").assertDoesNotExist()
            assertThat(grey()).isEqualTo(Grey(Grey.Badge.DOWNLOAD, 0.42f))
            assertThat(bubbleLabel()).isEqualTo(GREY_DOWNLOADING)
            assertThat(onMain { bubbleViews().single().isClickable }).isFalse()
            compose.onNodeWithTag(TRY_BUBBLE).assertIsNotEnabled().performTouchInput { click() }
            compose.onNodeWithText("Change").performClick()
            compose.runOnIdle { assertThat(calls).containsExactly("change") }
        }

        // Downloaded, the model is checked, then loaded into the engine: "Almost ready", the full ring turning, Change gone.
        // Only a checked model is loaded. Loaded, the bubble turns yellow and the step moves on to the try by itself, at
        // once, so the bubble is never yellow before its taps go to the try.
        @Test
        fun theFirstStepLoadsTheModelThenTheTryTakesOver() {
            val calls = CopyOnWriteArrayList<String>()
            var download by mutableStateOf<DownloadState>(DownloadState.Verifying)
            var loaded by mutableStateOf(false)
            compose.setContent {
                AppTheme { WelcomeScreen(WelcomeStep.WELCOME, NOTHING, null, false, actions(calls), chosen = ENGLISH, download = download, loaded = loaded) }
            }

            compose.onNodeWithText("Almost ready").assertIsDisplayed()
            compose.onNodeWithContentDescription("100%").assertIsDisplayed()
            compose.onNodeWithText(LOADING_LINE).assertIsDisplayed()
            compose.onNodeWithText(MODEL_ROW).assertIsDisplayed()
            compose.onNodeWithText("Change").assertDoesNotExist()
            assertThat(grey()).isEqualTo(Grey.PREPARING)
            compose.runOnIdle { assertThat(calls).isEmpty() } // still being checked
            download = DownloadState.Ready
            compose.runOnIdle { assertThat(calls).containsExactly("loadModel") }
            loaded = true
            compose.runOnIdle { assertThat(calls).containsExactly("loadModel", "next").inOrder() }
            assertThat(grey()).isNull()
            assertThat(bubbleLabel()).isEqualTo("Dictation, double tap to start or stop")
        }

        // A model the engine couldn't load leaves the bubble grey (never yellow) with its own badge and says so, with Try
        // again, which loads it again, and Change; once it loads again, the step goes on as ever.
        @Test
        fun aModelThatWontLoadSaysSoWithTryAgain() {
            val calls = CopyOnWriteArrayList<String>()
            var loadFailed by mutableStateOf(true)
            compose.setContent {
                AppTheme {
                    WelcomeScreen(WelcomeStep.WELCOME, NOTHING, null, false, actions(calls), chosen = ENGLISH, download = DownloadState.Ready, loadFailed = loadFailed)
                }
            }

            compose.onNodeWithText(LOAD_FAILED).assertIsDisplayed()
            compose.onNodeWithText(LOAD_FAILED_LINE).assertIsDisplayed()
            assertThat(grey()).isEqualTo(Grey(Grey.Badge.LOAD_FAILED, 1f))
            assertThat(bubbleLabel()).isEqualTo("Speech couldn't be prepared")
            compose.runOnIdle { assertThat(calls).isEmpty() } // no load by itself while it says so
            compose.onNodeWithTag(PRIMARY_BUTTON).assert(hasText("Try again")).performClick()
            compose.onNodeWithText("Change").performClick()
            compose.runOnIdle { assertThat(calls).containsExactly("loadModel", "change").inOrder() }

            loadFailed = false // Try again's load under way
            compose.onNodeWithText("Almost ready").assertIsDisplayed()
            assertThat(grey()).isEqualTo(Grey.PREPARING)
        }

        // A download that stops says why, with its one remedy as the big button: a wait for Wi-Fi that lasts (a start
        // passes through one briefly), a stop that picks up where it was, a damaged file that starts over, or too little
        // space, which says how much to free; each with the small Change under it.
        @Test
        fun aStoppedDownloadSaysWhyWithItsRemedy() {
            val calls = CopyOnWriteArrayList<String>()
            var download by mutableStateOf<DownloadState>(DownloadState.Queued(wifiOnly = true))
            compose.setContent {
                AppTheme { WelcomeScreen(WelcomeStep.WELCOME, NOTHING, null, false, actions(calls), chosen = ENGLISH, download = download) }
            }

            // Past the 1.5 s a wait for Wi-Fi must last before it shows, on the test's clock: a slow emulator can't time it out.
            compose.mainClock.advanceTimeBy(2_000)
            compose.onNodeWithText("Waiting for Wi-Fi").assertIsDisplayed()
            compose.onNodeWithText("The English download is 731 MB. Connect to Wi-Fi, or use mobile data.").assertIsDisplayed()
            assertThat(grey()!!.badge).isEqualTo(Grey.Badge.WIFI)
            compose.onNodeWithText("Use mobile data").performClick()
            // WorkManager's pause before it starts a stopped download again by itself: never "Waiting for Wi-Fi".
            download = DownloadState.Queued(wifiOnly = true, retrying = true)
            compose.onNodeWithText("Download paused").assertIsDisplayed()
            compose.onNodeWithText("It picks up where it stopped, by itself.").assertIsDisplayed()
            compose.onNodeWithText("Waiting for Wi-Fi").assertDoesNotExist()
            compose.onNodeWithText("Use mobile data").assertDoesNotExist()
            assertThat(grey()!!.badge).isEqualTo(Grey.Badge.RETRYING) // TalkBack: "Speech download paused"
            download = DownloadState.Failed(FailReason.NO_INTERNET)
            compose.onNodeWithText("Download stopped").assertIsDisplayed()
            compose.onNodeWithText("Check your connection. It picks up where it stopped.").assertIsDisplayed()
            assertThat(grey()!!.badge).isEqualTo(Grey.Badge.STOPPED)
            compose.onNodeWithText("Try again").performClick()
            download = DownloadState.Failed(FailReason.FILE_CHECK_FAILED)
            compose.onNodeWithText("Download failed").assertIsDisplayed()
            compose.onNodeWithText(DAMAGED_LINE).assertIsDisplayed()
            compose.onNodeWithText("It picks up where it stopped.", substring = true).assertDoesNotExist()
            assertThat(grey()).isEqualTo(Grey(Grey.Badge.STOPPED, 0f)) // its file is gone: the ring starts over
            compose.onNodeWithText("Try again").performClick()
            download = DownloadState.Failed(FailReason.NOT_ENOUGH_SPACE)
            compose.onNodeWithText("Not enough space").assertIsDisplayed()
            compose.onNodeWithText("Free up 1.8 GB, then try again.").assertIsDisplayed()
            compose.onNodeWithText("Try again").performClick()
            compose.onNodeWithText("Change").performClick() // on every stop, as while it downloads
            compose.onNodeWithText("Not now").assertDoesNotExist()
            compose.runOnIdle {
                assertThat(calls).containsExactly("useMobileData", "retryDownload", "retryDownload", "retryDownload", "change").inOrder()
            }
        }

        // The try, on the same card: the yellow bubble with its "Tap" label, and the line to tap it; nothing else to tap.
        // A tap goes to the take machine, and the label goes. Listening: Speak now (not read aloud: the microphone is
        // open), a card with a line to say, how to finish 16 dp under it, and Listening with the meter; a silent
        // microphone fills a quiet line kept for it. Stopped, it keeps that until the words come: in the read-only tray,
        // That's it with a check, read aloud, and Continue, the card gone; the bubble stays yellow and still takes taps.
        @Test
        fun theTryListensThenShowsTheWords() {
            val calls = CopyOnWriteArrayList<String>()
            val link = FakeTrialLink()
            compose.setContent { AppTheme { WelcomeScreen(WelcomeStep.TRY, VERIFIED, null, false, actions(calls, link), download = DownloadState.Ready) } }

            compose.onNodeWithContentDescription(TRY_STEP).assertExists()
            compose.onNodeWithText("Tap the yellow bubble.").assertIsDisplayed()
            compose.onNodeWithContentDescription(REPLY).assertIsDisplayed()
            compose.onNodeWithTag(TAP_LABEL).assertExists()
            compose.onAllNodesWithTag(PRIMARY_BUTTON).assertCountEquals(0)
            assertThat(grey()).isNull()
            compose.onNodeWithTag(TRY_BUBBLE).assertIsEnabled().performTouchInput { click() }
            compose.runOnIdle { assertThat(link.touches).containsExactly("Press", "Release").inOrder() }
            compose.waitUntil(5_000) { compose.onAllNodesWithTag(TAP_LABEL).fetchSemanticsNodes().isEmpty() }

            compose.onNodeWithContentDescription(TRY_SAYING).assertDoesNotExist() // no suggestion before the tap
            link.render(BubbleUi.Arming, BubbleUi.Recording(0.8f, locked = true, 200))
            compose.onNodeWithText("Speak now.").assertIsDisplayed().assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.LiveRegion))
            compose.onNodeWithContentDescription(TRY_SAYING).assertIsDisplayed() // one label for the card, which is no button
                .assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick))
            val card = compose.onNodeWithContentDescription(TRY_SAYING).getBoundsInRoot()
            val finish = compose.onNodeWithText(TRY_FINISH).assertIsDisplayed().getBoundsInRoot()
            assertThat(finish.top - card.bottom).isEqualTo(16.dp)
            compose.onNodeWithText("Listening").assertIsDisplayed()
            compose.onNodeWithText(CLOSER).assertDoesNotExist()
            // A silent microphone while it records: quietly, in the line kept under it, never read aloud into the open
            // microphone; nothing above it moves.
            link.render(BubbleUi.Chip(Code.MIC_SILENT, listOf(ChipAction.DISMISS)))
            compose.onNodeWithText(CLOSER).assertIsDisplayed().assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.LiveRegion))
            assertThat(compose.onNodeWithText(TRY_FINISH).getBoundsInRoot()).isEqualTo(finish)
            // Stopped, its words not here yet: the listening line stays, so nothing says it failed meanwhile.
            link.render(BubbleUi.Processing(false, 0, null))
            compose.onNodeWithText("Speak now.").assertIsDisplayed()
            compose.onAllNodesWithText("Continue").assertCountEquals(0)
            link.words("Yes, see you at seven")
            link.render(BubbleUi.Idle)
            compose.onNodeWithText("That's it.").assertIsDisplayed()
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
            compose.onNodeWithContentDescription(TRY_SAYING).assertDoesNotExist()
            compose.onNodeWithText("Your words appear wherever you type.").assertIsDisplayed()
            compose.onNodeWithContentDescription("Your dictated words, read only: Yes, see you at seven").assertIsDisplayed()
            compose.onAllNodes(hasSetTextAction()).assertCountEquals(0) // never a field
            compose.onNodeWithTag(TRY_BUBBLE).assertIsEnabled()
            compose.onNodeWithText("Continue").performClick()
            compose.runOnIdle { assertThat(calls).containsExactly("next") }
        }

        // Before the microphone is allowed, the try's first tap asks Android for it instead of listening (and, allowed, that
        // tap listens). Refused, the try says the microphone is off: the bubble grey with its badge, Allow microphone (App
        // info once Android won't ask again), which only asks, and Not now, which goes on. Allowed, it is the yellow bubble
        // again, waiting for a tap.
        @Test
        fun theTryAsksForTheMicrophoneAtItsFirstTap() {
            val calls = CopyOnWriteArrayList<String>()
            val link = FakeTrialLink()
            var setup by mutableStateOf(VERIFIED.copy(micGranted = false))
            var refusal by mutableStateOf<MicRefusal?>(null)
            compose.setContent { AppTheme { WelcomeScreen(WelcomeStep.TRY, setup, refusal, false, actions(calls, link), download = DownloadState.Ready) } }

            compose.onNodeWithTag(TRY_BUBBLE).performTouchInput { click() }
            compose.runOnIdle {
                assertThat(calls).containsExactly("allowMicAndListen") // a grant then starts this tap's take
                assertThat(link.touches).isEmpty()
            }
            refusal = MicRefusal.DENIED
            compose.onNodeWithText(MIC_OFF).assertIsDisplayed()
            compose.onNodeWithText(MIC_OFF_LINE).assertIsDisplayed()
            assertThat(grey()).isEqualTo(Grey.MIC_OFF)
            assertThat(bubbleLabel()).isEqualTo("Microphone is off")
            compose.onNodeWithText("Allow microphone").performClick()
            refusal = MicRefusal.BLOCKED
            compose.onNodeWithText("Allow microphone").performClick()
            compose.onNodeWithText("Not now").performClick()
            // Allow microphone only asks: a grant from it starts nothing, and the try says to tap the bubble again.
            compose.runOnIdle { assertThat(calls).containsExactly("allowMicAndListen", "allowMic", "openAppSettings", "next").inOrder() }

            setup = VERIFIED
            refusal = null
            compose.onNodeWithText("Tap the yellow bubble.").assertIsDisplayed()
            assertThat(grey()).isNull()
        }

        // A take that ends without words says to speak near the phone, one that never listened says what stopped it, and
        // one the engine couldn't run says so; a quiet Continue goes on, so a phone whose microphone can't hear isn't stuck.
        @Test
        fun aTakeWithoutWordsSaysWhy() {
            val calls = CopyOnWriteArrayList<String>()
            val link = FakeTrialLink()
            compose.setContent { AppTheme { WelcomeScreen(WelcomeStep.TRY, VERIFIED, null, false, actions(calls, link), download = DownloadState.Ready) } }

            compose.onNodeWithText("Tap the yellow bubble.").assertIsDisplayed()
            link.render(BubbleUi.Arming, BubbleUi.Chip(Code.MIC_NOT_READY, listOf(ChipAction.DISMISS)))
            compose.onNodeWithText("Microphone was not ready. Try again.").assertIsDisplayed()
            link.render(BubbleUi.Arming, BubbleUi.Recording(0.1f, locked = true, 300), BubbleUi.Chip(Code.NO_SPEECH, listOf(ChipAction.DISMISS)))
            compose.onNodeWithText("Tap the yellow bubble.").assertIsDisplayed()
            compose.onNodeWithText(CLOSER).assertIsDisplayed()
            compose.onNodeWithText("That's it.").assertDoesNotExist()
            compose.onAllNodesWithTag(PRIMARY_BUTTON).assertCountEquals(0) // the bubble is what to tap now
            // The engine failing after the stop says so plainly, never "speak nearer".
            link.render(
                BubbleUi.Arming, BubbleUi.Recording(0.5f, locked = true, 300), BubbleUi.Processing(false, 0, null),
                BubbleUi.Chip(Code.ENGINE_CRASHED, listOf(ChipAction.DISMISS)),
            )
            compose.onNodeWithText("Speech couldn't run. Tap the bubble to try again.").assertIsDisplayed()
            compose.onNodeWithText(CLOSER).assertDoesNotExist()
            compose.onNodeWithText("Continue").performClick()
            compose.runOnIdle { assertThat(calls).containsExactly("next") }
        }

        // On a first run the bubble is made on the first step, grey, while the try has no link yet; the step then moves on
        // to the try in the same composition, the view kept. Its taps still reach the take machine, and so does TalkBack's
        // double tap, a click; before the microphone is allowed both ask for it instead.
        @Test
        fun theBubbleMadeOnTheFirstStepTakesTheTrysTaps() {
            val calls = CopyOnWriteArrayList<String>()
            val link = FakeTrialLink()
            var step by mutableStateOf(WelcomeStep.WELCOME)
            var setup by mutableStateOf(SetupState(true, false, ModelStatus.MISSING)) // the microphone allowed
            var download by mutableStateOf<DownloadState>(DOWNLOADING)
            var loaded by mutableStateOf(false)
            compose.setContent {
                AppTheme {
                    WelcomeScreen(
                        step, setup, null, false, actions(calls, link) { step = WelcomeStep.TRY }, chosen = ENGLISH, download = download, loaded = loaded,
                    )
                }
            }

            assertThat(grey()).isEqualTo(Grey(Grey.Badge.DOWNLOAD, 0.42f))
            val made = onMain { bubbleViews().single() }
            download = DownloadState.Ready
            compose.runOnIdle { assertThat(calls).containsExactly("loadModel") }
            loaded = true
            compose.waitForTry()
            assertThat(onMain { bubbleViews().single() }).isSameInstanceAs(made) // the same view, made on the first step
            compose.onNodeWithTag(TRY_BUBBLE).performTouchInput { click() }
            compose.runOnIdle { assertThat(link.touches).containsExactly("Press", "Release").inOrder() }
            onMain { made.performClick() } // TalkBack's double tap
            compose.runOnIdle { assertThat(link.touches).containsExactly("Press", "Release", "Press", "Release").inOrder() }

            setup = setup.copy(micGranted = false)
            compose.onNodeWithTag(TRY_BUBBLE).performTouchInput { click() }
            onMain { made.performClick() }
            compose.runOnIdle {
                assertThat(calls).containsExactly("loadModel", "next", "allowMicAndListen", "allowMicAndListen").inOrder()
                assertThat(link.touches).hasSize(4)
            }
        }

        // A tap on the grey bubble gives it a small shake and starts nothing: no take, no microphone question.
        @Test
        fun aTapOnTheGreyBubbleShakesItAndStartsNothing() {
            val calls = CopyOnWriteArrayList<String>()
            val link = FakeTrialLink()
            val trial = TrialState()
            compose.setContent {
                AppTheme {
                    TryCard(
                        trial, link, showBubble = true, grey = Grey(Grey.Badge.DOWNLOAD, 0.42f), cue = false, compact = false, micGranted = false,
                        onNeedsMic = { calls += "allowMic" },
                    )
                }
            }

            compose.onNodeWithTag(TRY_BUBBLE).assertIsNotEnabled().performTouchInput { click() }
            compose.runOnIdle {
                assertThat(trial.greyTaps).isEqualTo(1)
                assertThat(link.touches).isEmpty()
                assertThat(calls).isEmpty()
            }
            assertThat(onMain { bubbleViews().single().isClickable }).isFalse() // not a button for TalkBack
        }

        // With TalkBack on, the try never speaks into the open microphone: Speak now is no live region and doesn't take
        // TalkBack's focus from the bubble, nor does the silent microphone's line; That's it is read aloud once the
        // microphone is closed.
        @Test
        fun withTalkBackTheListeningLinesAreNeitherReadAloudNorFocused() {
            val link = FakeTrialLink()
            compose.setContent {
                CompositionLocalProvider(LocalTalkBack provides true) {
                    AppTheme { WelcomeScreen(WelcomeStep.TRY, VERIFIED, null, false, actions(CopyOnWriteArrayList(), link), download = DownloadState.Ready) }
                }
            }

            compose.onNodeWithText("Tap the yellow bubble.").assertIsFocused() // a new step's heading takes focus
            link.render(BubbleUi.Arming, BubbleUi.Recording(0.8f, locked = true, 200), BubbleUi.Chip(Code.MIC_SILENT, listOf(ChipAction.DISMISS)))
            compose.onNodeWithText("Speak now.").assertIsNotFocused().assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.LiveRegion))
            compose.onNodeWithContentDescription(TRY_SAYING).assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.LiveRegion))
            compose.onNodeWithText(TRY_FINISH).assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.LiveRegion))
            compose.onNodeWithText(CLOSER).assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.LiveRegion))
                .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Focused)) // never focused: not even focusable
            link.render(BubbleUi.Processing(false, 0, null))
            link.words("Yes, see you at seven")
            link.render(BubbleUi.Idle)
            compose.onNodeWithText("That's it.").assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
        }

        // The Settings picture's red cross is the shortcut's alone: 2.7 s in, ThumbFree's page shows with Use ThumbFree
        // still off, a plain off switch, and only the shortcut's knob is red.
        @Test
        fun onlyTheShortcutsSwitchHasTheRedCross() {
            compose.mainClock.autoAdvance = false
            compose.setContent { AppTheme(dark = false) { SettingsPicture() } }
            compose.mainClock.advanceTimeBy(2_700)
            compose.waitForIdle()

            val picture = compose.onNodeWithContentDescription(SETTINGS_PICTURE).captureToImage().asAndroidBitmap()
            val red = (0 until picture.height).filter { y ->
                (0 until picture.width).any { x ->
                    val c = picture.getPixel(x, y)
                    android.graphics.Color.red(c) > 150 && android.graphics.Color.green(c) < 80 && android.graphics.Color.blue(c) < 80
                }
            }
            assertThat(red).isNotEmpty()
            // One knob: its rows span less than one switch row, never both rows.
            assertThat(red.last() - red.first()).isLessThan(with(compose.density) { 32.dp.roundToPx() })
            assertThat(red.first()).isGreaterThan(picture.height / 2) // the shortcut's row, the lower one
        }

        // At a large font the card is the reply beside the bubble: before the language, with no bubble yet, there is no
        // lone empty tray; once the language is tapped, the reply and the bubble show.
        @Test
        fun aLargeFontsChoiceHasNoLoneTray() {
            var chosen by mutableStateOf<ModelFile?>(null)
            compose.setContent {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                    AppTheme { WelcomeScreen(WelcomeStep.WELCOME, NOTHING, null, false, actions(CopyOnWriteArrayList()), chosen = chosen, download = DOWNLOADING) }
                }
            }

            compose.onNodeWithText(QUESTION).assertExists()
            compose.onAllNodesWithContentDescription(REPLY).assertCountEquals(0)
            chosen = ENGLISH
            compose.onNodeWithContentDescription(REPLY).assertExists()
            compose.onNodeWithTag(TRY_BUBBLE).assertExists()
        }

        // The "Tap" label keeps its place over the bubble at any font size: at 200% its bottom is still above the bubble.
        @Test
        fun theTapLabelStaysAboveTheBubbleAtALargeFont() {
            compose.setContent {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                    AppTheme { WelcomeScreen(WelcomeStep.TRY, VERIFIED, null, false, actions(CopyOnWriteArrayList(), FakeTrialLink()), download = DownloadState.Ready) }
                }
            }

            val label = compose.onNodeWithTag(TAP_LABEL, useUnmergedTree = true).getBoundsInRoot()
            val bubble = compose.onNodeWithTag(TRY_BUBBLE).getBoundsInRoot()
            assertThat(label.bottom).isAtMost(bubble.top + 6.dp) // the art sits 6 dp inside the bubble's 72 dp target
        }

        // The bubble step: the Settings picture (one description for TalkBack, no step controls), its two rules, and the
        // disclosure right above Agree and open settings; Not now goes on. Back with the bubble still off: the picture holds
        // on the list, the heading says so, Open settings again, and the greyed-out switch's help. Once on, Continue.
        @Test
        fun theBubbleStepShowsThePictureTheRulesAndTheDisclosure() {
            val calls = CopyOnWriteArrayList<String>()
            var stillOff by mutableStateOf(false)
            var setup by mutableStateOf(SetupState(true, false, null))
            compose.setContent { AppTheme { WelcomeScreen(WelcomeStep.SERVICE, setup, null, stillOff, actions(calls)) } }

            compose.onNodeWithContentDescription(SERVICE_STEP).assertExists()
            compose.onNodeWithText(SERVICE_TITLE).assertIsDisplayed()
            compose.onNodeWithContentDescription(SETTINGS_PICTURE).assertIsDisplayed()
            compose.onNodeWithText(RULE_USE).assertIsDisplayed()
            compose.onNodeWithText(RULE_SHORTCUT).assertIsDisplayed()
            compose.onAllNodesWithContentDescription("Guide step", substring = true).assertCountEquals(0)
            val disclosure = compose.onNodeWithText(SERVICE_LINE).assertIsDisplayed().getBoundsInRoot()
            val agree = compose.onNodeWithTag(PRIMARY_BUTTON).assert(hasText("Agree and open settings")).getBoundsInRoot()
            assertThat(agree.top - disclosure.bottom).isLessThan(32.dp) // right above the button
            compose.onNodeWithText("Agree and open settings").performClick()
            compose.onNodeWithText("Not now").performClick()

            stillOff = true
            compose.onNodeWithText("The bubble is still off.").assertIsDisplayed()
            compose.onNodeWithContentDescription(LIST_PICTURE).assertIsDisplayed()
            compose.onNodeWithText(RULE_USE).assertIsDisplayed()
            compose.onNodeWithText(SERVICE_LINE).assertIsDisplayed()
            compose.onNodeWithText("Open settings again").performClick()
            compose.onNodeWithText("Switch greyed out?").performClick()
            compose.onNodeWithText("Open App info").performClick()

            setup = SetupState(true, true, null)
            compose.onNodeWithText("The bubble is on").assertIsDisplayed()
            compose.onNodeWithText("Continue").performClick()
            compose.runOnIdle {
                assertThat(calls).containsExactly("openAccessibility", "next", "openAccessibility", "openAppSettings", "next").inOrder()
            }
        }

        // The last step: "You're all set." with a plain check, and no Back, only once the microphone, the bubble and the
        // model all work; else the one thing missing, the microphone first, then the bubble, with its fix and Not now.
        @Test
        fun theLastStepIsAllSetOnlyOnceEverythingWorks() {
            val calls = CopyOnWriteArrayList<String>()
            var setup by mutableStateOf(NOTHING)
            compose.setContent { AppTheme { WelcomeScreen(WelcomeStep.READY, setup, null, false, actions(calls), download = DownloadState.Ready, loaded = true) } }

            compose.onNodeWithContentDescription(FINISH_STEP).assertExists()
            compose.onNodeWithText(MIC_OFF).assertIsDisplayed()
            compose.onNodeWithText(ALL_SET).assertDoesNotExist()
            compose.onNodeWithText("Allow microphone").performClick()
            setup = SetupState(true, false, ModelStatus.VERIFIED)
            compose.onNodeWithText("The bubble is still off.").assertIsDisplayed()
            compose.onNodeWithText(RULE_SHORTCUT).assertIsDisplayed()
            compose.onNodeWithContentDescription("Back").assertExists()
            compose.onNodeWithText("Turn on the bubble").performClick()
            compose.onNodeWithText("Not now").assertIsDisplayed()
            setup = VERIFIED
            compose.onNodeWithText(ALL_SET).assertIsDisplayed()
            compose.onNodeWithText(ALL_SET_LINE).assertIsDisplayed()
            compose.onAllNodesWithContentDescription(ALL_SET).assertCountEquals(0) // the check is silent: TalkBack hears it once
            compose.onNodeWithContentDescription("Back").assertDoesNotExist()
            compose.onNodeWithText("Not now").assertDoesNotExist()
            compose.onNodeWithText("Done").performClick()
            compose.runOnIdle { assertThat(calls).containsExactly("allowMic", "turnOnBubble", "next").inOrder() }
        }

        // All set needs the model loaded as well: a new start of the app that lost the first step's load says almost ready
        // and loads it; a load that fails says so with Try again, which loads it again, and Go to Home; loaded, all set.
        @Test
        fun theLastStepWaitsForTheModelToLoad() {
            val calls = CopyOnWriteArrayList<String>()
            var loaded by mutableStateOf(false)
            var loadFailed by mutableStateOf(false)
            compose.setContent {
                AppTheme {
                    WelcomeScreen(WelcomeStep.READY, VERIFIED, null, false, actions(calls), download = DownloadState.Ready, loaded = loaded, loadFailed = loadFailed)
                }
            }

            compose.onNodeWithText("Almost ready").assertIsDisplayed()
            compose.onNodeWithText(ALL_SET).assertDoesNotExist()
            compose.runOnIdle { assertThat(calls).containsExactly("loadModel") }
            loadFailed = true
            compose.onNodeWithText(LOAD_FAILED).assertIsDisplayed()
            compose.onNodeWithText(LOAD_FAILED_RETRY).assertIsDisplayed() // no Change here, so no "change the language"
            compose.onNodeWithContentDescription(NOT_LOADED_PICTURE).assertIsDisplayed()
            compose.onNodeWithTag(PRIMARY_BUTTON).assert(hasText("Try again")).performClick()
            compose.onNodeWithText("Go to Home").assertIsDisplayed()
            loadFailed = false
            loaded = true
            compose.onNodeWithText(ALL_SET).assertIsDisplayed()
            compose.runOnIdle { assertThat(calls).containsExactly("loadModel", "loadModel").inOrder() }
        }

        // Before the grants are first read the last step gives no verdict: no "You're all set.", no check and no button,
        // even with the model's download done; once they are read, the verdict comes.
        @Test
        fun finishWaitsForTheFirstReadBeforeAnyVerdict() {
            var setup by mutableStateOf<SetupState?>(null)
            compose.setContent {
                AppTheme { WelcomeScreen(WelcomeStep.READY, setup, null, false, actions(CopyOnWriteArrayList()), download = DownloadState.Ready, loaded = true) }
            }

            compose.onNodeWithText(ALL_SET).assertDoesNotExist()
            compose.onAllNodesWithTag(PRIMARY_BUTTON).assertCountEquals(0)
            setup = VERIFIED
            compose.onNodeWithText(ALL_SET).assertIsDisplayed()
            compose.onNodeWithText("Done").assertIsDisplayed()
        }

        // The last step judges the model takes use: with another model chosen and still downloading, the first step's
        // finished download makes nothing ready.
        @Test
        fun theFinishJudgesTheModelTakesUse() {
            val chosen = Catalog.CANARY_180M_FLASH_Q8
            compose.setContent {
                AppTheme {
                    WelcomeScreen(
                        WelcomeStep.READY, SetupState(true, true, ModelStatus.MISSING, chosen = chosen), null, false, actions(CopyOnWriteArrayList()),
                        chosen = ENGLISH, download = DownloadState.Ready,
                        inUseDownload = DownloadState.Downloading(chosen.sizeBytes * 42 / 100 + 1, chosen.sizeBytes),
                    )
                }
            }

            compose.onNodeWithText("Finishing the speech download").assertIsDisplayed()
            compose.onNodeWithContentDescription(RING_PICTURE).assertIsDisplayed()
            compose.onNodeWithText(ALL_SET).assertDoesNotExist()
        }

        // The last step while the bubble and the microphone are on but the speech model is not usable (it changed or went
        // since step 1): it says what holds the download and its remedy is the big button: Go to Home while it downloads,
        // waits for a connection or is being prepared; Use mobile data while it waits for Wi-Fi; Try again once it stopped,
        // the file was damaged, or it never started; Open storage when there is no room; under each remedy a quiet Go to
        // Home. All set once the model is ready and loaded.
        @Test
        fun finishStepFollowsTheDownload() {
            val calls = CopyOnWriteArrayList<String>()
            var download by mutableStateOf<DownloadState>(DOWNLOADING)
            var setup by mutableStateOf(SetupState(true, true, ModelStatus.MISSING))
            var loaded by mutableStateOf(false)
            compose.setContent { AppTheme { WelcomeScreen(WelcomeStep.READY, setup, null, false, actions(calls), download = download, loaded = loaded) } }

            compose.onNodeWithText("Finishing the speech download").assertIsDisplayed()
            compose.onNodeWithText(ALL_SET).assertDoesNotExist()
            compose.onNodeWithText("Go to Home").performClick()
            download = DownloadState.Queued(wifiOnly = true)
            compose.onNodeWithText("Waiting for Wi-Fi").assertIsDisplayed()
            compose.onNodeWithText("Use mobile data").performClick()
            compose.onNodeWithText("Go to Home").assertIsDisplayed() // quietly, under each remedy
            // A retry's pause: in step 1's words, never "Download stopped".
            download = DownloadState.Queued(wifiOnly = true, retrying = true)
            compose.onNodeWithText("Download paused").assertIsDisplayed()
            compose.onNodeWithText("It picks up where it stopped, by itself.").assertIsDisplayed()
            compose.onNodeWithText("Download stopped").assertDoesNotExist()
            download = DownloadState.Failed(FailReason.NO_INTERNET)
            compose.onNodeWithText("Download stopped").assertIsDisplayed()
            compose.onNodeWithText("Try again").performClick()
            compose.onNodeWithText("Go to Home").assertIsDisplayed()
            download = DownloadState.Failed(FailReason.FILE_CHECK_FAILED)
            compose.onNodeWithText("Download failed").assertIsDisplayed()
            compose.onNodeWithText(DAMAGED_LINE).assertIsDisplayed()
            compose.onNodeWithText("Try again").performClick()
            download = DownloadState.Failed(FailReason.NOT_ENOUGH_SPACE)
            compose.onNodeWithText("Not enough space").assertIsDisplayed()
            compose.onNodeWithText("Open storage").performClick()
            compose.onNodeWithText("Go to Home").assertIsDisplayed()
            download = DownloadState.NotDownloaded
            compose.onNodeWithText("Download the speech model").assertIsDisplayed()
            compose.onNodeWithText("Try again").performClick()
            compose.onNodeWithText("Go to Home").assertIsDisplayed()
            download = DownloadState.Verifying
            compose.onNodeWithText("Almost ready").assertIsDisplayed()
            download = DownloadState.Queued(wifiOnly = false)
            compose.onNodeWithText("Waiting for a connection").assertIsDisplayed()

            setup = VERIFIED
            download = DownloadState.Ready
            loaded = true
            compose.onNodeWithText(ALL_SET).assertIsDisplayed()
            compose.onNodeWithText("Done").performClick()
            compose.runOnIdle {
                assertThat(calls).containsExactly("next", "useMobileData", "retryDownload", "retryDownload", "openStorage", "retryDownload", "next")
                    .inOrder()
            }
        }

        // The progress bar has four steps, and TalkBack reads it as one line with the step's name.
        @Test
        fun everyStepShowsTheProgress() {
            var step by mutableStateOf(WelcomeStep.WELCOME)
            compose.setContent { AppTheme { WelcomeScreen(step, VERIFIED, null, false, actions(CopyOnWriteArrayList())) } }

            for ((each, name) in WelcomeStep.entries.zip(listOf(READY_STEP, TRY_STEP, SERVICE_STEP, FINISH_STEP))) {
                step = each
                compose.onNodeWithContentDescription(name).assertExists()
            }
        }

        // A yellow bubble is always the real one: in every welcome state and on Home, the only bubble on screen is the
        // try's own control, on the first two steps once there is one; never a picture of it.
        @Test
        fun noBubbleButTheTrysOwn() {
            var case by mutableStateOf(FIT_CASES.first())
            var home by mutableStateOf<SetupState?>(null)
            compose.setContent {
                AppTheme {
                    val shown = home
                    if (shown != null) HomeTab(shown, {})
                    else key(case) { Fit(case, actions(CopyOnWriteArrayList(), Replay(case.trial, case.words))) }
                }
            }

            for (each in FIT_CASES) {
                case = each
                settle()
                val expected = if (each.step == WelcomeStep.TRY || each.step == WelcomeStep.WELCOME && each.chosen != null) 1 else 0
                assertWithMessage(each.name).that(onMain { bubbleViews().size }).isEqualTo(expected)
            }
            for (each in listOf(NOTHING, SetupState(true, true, ModelStatus.MISSING), VERIFIED)) {
                home = each
                compose.waitForIdle()
                assertWithMessage("Home").that(onMain { bubbleViews().size }).isEqualTo(0)
            }
        }

        // No step scrolls at the default font size. On a 1080 x 2400 phone (411 x 914 dp), the design's 360 x 800 dp and a
        // small one (360 x 640 dp, what a 360 x 740 dp screen leaves under its bars), in every state, nothing can scroll,
        // the words and every button are whole and inside the screen, and the pictures show. At 200% font on the larger
        // phone nothing but the bubble step scrolls and no word is cut off: the card drops its frame, the pictures shrink;
        // on the small phone the words may scroll, and every word and button is still whole and on the screen.
        @Test
        fun stepsFitWithoutScrollingAtTheDefaultFontSize() {
            var scale by mutableStateOf(1f)
            var size by mutableStateOf(PHONE)
            var case by mutableStateOf(FIT_CASES.first())
            compose.setContent {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, scale)) {
                    AppTheme {
                        Box(Modifier.requiredSize(size).testTag("phone")) {
                            key(case) { Fit(case, actions(CopyOnWriteArrayList(), Replay(case.trial, case.words))) }
                        }
                    }
                }
            }

            val dropped = mutableListOf<String>()
            for ((phoneSize, fontScale) in listOf(PHONE to 1f, REFERENCE_PHONE to 1f, SMALL_PHONE to 1f, PHONE to 2f, SMALL_PHONE to 2f)) for (each in FIT_CASES) {
                size = phoneSize
                scale = fontScale
                case = each
                settle()
                val where = "${fontScale}x on ${phoneSize.width.value.toInt()} x ${phoneSize.height.value.toInt()} dp in ${each.name}"
                val phone = compose.onNodeWithTag("phone").getBoundsInRoot()
                val overflow = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
                    .fetchSemanticsNodes().maxOfOrNull { it.config[SemanticsProperties.VerticalScrollAxisRange].maxValue() } ?: 0f
                // At 200% the words may scroll on the smaller phones, and on the bubble step, whose disclosure stays word
                // for word; every word is still reachable.
                val mayScroll = fontScale > 1f && (phoneSize != PHONE || each.step == WelcomeStep.SERVICE)
                if (!mayScroll) assertWithMessage("scrolls at $where by $overflow px").that(overflow).isEqualTo(0f)
                for (text in each.texts) {
                    val inMiddle = compose.onAllNodes(hasText(text) and hasAnyAncestor(hasScrollAction())).fetchSemanticsNodes().isNotEmpty()
                    val node = compose.onAllNodesWithText(text).onFirst()
                    if (inMiddle) node.performScrollTo() // a no-op unless the middle scrolls
                    val bounds = node.assertIsDisplayed().getBoundsInRoot()
                    assertWithMessage("$text at $where").that(bounds.top).isAtLeast(phone.top)
                    assertWithMessage("$text at $where").that(bounds.bottom).isAtMost(phone.bottom)
                    val layout = mutableListOf<TextLayoutResult>()
                    node.fetchSemanticsNode().config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layout)
                    // Lines past the bottom of the room it got: a wrapped text is never cut at the side.
                    assertWithMessage("$text cut off at $where").that(layout.singleOrNull()?.didOverflowHeight ?: false).isFalse()
                }
                val bottom = with(compose.density) { phone.bottom.toPx() }
                if (fontScale == 1f && (phoneSize == PHONE || each.smallPhonePictures)) for (picture in each.pictures) {
                    if (picture == CARD && phoneSize == SMALL_PHONE) continue // a short screen's compact card: the reply and bubble only
                    val shown = compose.onAllNodesWithContentDescription(picture).fetchSemanticsNodes()
                        .any { it.layoutInfo.isPlaced && it.boundsInRoot.height > 0f && it.boundsInRoot.bottom <= bottom + 1f }
                    if (!shown) dropped += "$picture at $where"
                }
            }
            assertThat(dropped).isEmpty()
        }

        // The big button has the same place on every step and in every state, whatever its label: its slot and the quiet
        // one under it are always there.
        @Test
        fun bigButtonKeepsItsPlaceOnEveryStep() {
            var case by mutableStateOf(FIT_CASES.first())
            compose.setContent {
                AppTheme {
                    Box(Modifier.requiredSize(REFERENCE_PHONE)) {
                        key(case) { Fit(case, actions(CopyOnWriteArrayList(), Replay(case.trial, case.words))) }
                    }
                }
            }

            val places = mutableMapOf<String, androidx.compose.ui.unit.DpRect>()
            for (each in FIT_CASES) {
                case = each
                settle()
                compose.onAllNodesWithTag(PRIMARY_BUTTON).fetchSemanticsNodes().singleOrNull()?.let {
                    places[each.name] = compose.onNodeWithTag(PRIMARY_BUTTON).getBoundsInRoot()
                }
            }
            assertThat(places.keys).containsAtLeast("all set", "bubble", "try, microphone off", "get ready, stopped")
            assertWithMessage("$places").that(places.values.toSet()).hasSize(1)
        }

        // The first two steps are one screen: from the wait to the try, and as the try goes, Sam's card and the bubble keep
        // their places; only the words under them change.
        @Test
        fun theCardKeepsItsPlaceFromTheWaitToTheTry() {
            val link = FakeTrialLink()
            var step by mutableStateOf(WelcomeStep.WELCOME)
            var loaded by mutableStateOf(false)
            compose.setContent {
                AppTheme {
                    Box(Modifier.requiredSize(REFERENCE_PHONE)) {
                        WelcomeScreen(step, VERIFIED, null, false, actions(CopyOnWriteArrayList(), link), chosen = ENGLISH, download = DownloadState.Ready, loaded = loaded)
                    }
                }
            }
            fun places() = listOf(compose.onNodeWithTag(TRY_BUBBLE).getBoundsInRoot(), compose.onNodeWithContentDescription(CARD).getBoundsInRoot())

            compose.onNodeWithText("Almost ready").assertIsDisplayed()
            val before = places()
            loaded = true
            step = WelcomeStep.TRY
            compose.onNodeWithText("Tap the yellow bubble.").assertIsDisplayed()
            assertThat(places()).isEqualTo(before)
            link.render(BubbleUi.Arming, BubbleUi.Recording(0.8f, locked = true, 200))
            compose.onNodeWithText("Speak now.").assertIsDisplayed()
            link.render(BubbleUi.Processing(false, 0, null))
            link.words("Yes, see you at seven")
            link.render(BubbleUi.Idle)
            compose.onNodeWithText("Continue").assertIsDisplayed()
            assertThat(places()).isEqualTo(before)
        }

        // Home's setup card and its rows name the chosen model in plain words.
        @Test
        fun setupRowNamesTheChosenModel() {
            compose.setContent {
                AppTheme { HomeTab(SetupState(true, true, ModelStatus.MISSING, chosen = Catalog.PARAKEET_TDT_V3_Q8), {}) }
            }

            compose.onNodeWithText("Multilingual speech model").assertIsDisplayed()
            compose.onNodeWithText("Not downloaded · 740 MB").assertIsDisplayed()
            compose.onNodeWithText("English speech model").assertDoesNotExist()
        }

        // Home's setup card shows the chosen model's download on its row: the percentage with no button while it runs, Use
        // mobile data while it waits for Wi-Fi, the reason and Try again once it failed.
        @Test
        fun setupCardShowsTheDownload() {
            var download by mutableStateOf<DownloadState>(DownloadState.Downloading(365_678_784, 731_357_568))
            var opened = 0
            compose.setContent { AppTheme { HomeTab(SetupState(true, true, ModelStatus.MISSING), { fixed += it }, download, onOpenModels = { opened++ }) } }

            compose.onNodeWithText("Downloading · 50%").assertIsDisplayed().performClick() // the row opens the speech models
            compose.runOnIdle { assertThat(opened).isEqualTo(1) }
            compose.onNodeWithText("Get it").assertDoesNotExist()
            download = DownloadState.Queued(wifiOnly = true)
            compose.onNodeWithText("Waiting for Wi-Fi").assertIsDisplayed()
            compose.onNodeWithText("Use mobile data").performClick() // MainActivity starts it without Wi-Fi only
            download = DownloadState.Failed(FailReason.NOT_ENOUGH_SPACE)
            compose.onNodeWithText("Not enough space").assertIsDisplayed()
            compose.onNodeWithText("Try again").performClick()
            compose.runOnIdle { assertThat(fixed).containsExactly(SetupItem.MODEL, SetupItem.MODEL) }
        }

        /** A case's screen at rest: past the time a wait for Wi-Fi must last before the first step says it. */
        private fun settle() {
            compose.waitForIdle()
            compose.mainClock.advanceTimeBy(2_000)
            compose.waitForIdle()
        }

        private fun actions(calls: MutableList<String>, trial: TrialLink? = null, next: () -> Unit = {}) = WelcomeActions(
            next = { calls += "next"; next() }, back = { calls += "back" }, close = null,
            chooseLanguage = { calls += "language ${it.fileName}" }, change = { calls += "change" }, loadModel = { calls += "loadModel" },
            allowMic = { calls += "allowMic" }, openAppSettings = { calls += "openAppSettings" },
            openAccessibility = { calls += "openAccessibility" }, turnOnBubble = { calls += "turnOnBubble" }, trial = trial,
            useMobileData = { calls += "useMobileData" }, retryDownload = { calls += "retryDownload" },
            openStorage = { calls += "openStorage" }, allowMicAndListen = { calls += "allowMicAndListen" },
        )

        /** A toggle's TalkBack state description and the label of its tap. */
        private fun disclosure(state: String, action: String) = SemanticsMatcher("$state, tap: $action") {
            it.config.getOrNull(SemanticsProperties.StateDescription) == state &&
                it.config.getOrNull(SemanticsActions.OnClick)?.label == action
        }

        @Composable
        private fun settings(
            setup: SetupState, onChoose: (ModelFile) -> Unit = {}, onRetention: (Retention) -> Unit = {},
            downloads: Map<ModelFile, DownloadState> = emptyMap(), onLink: (String) -> Unit = {},
            wordCount: Int = 0, onDictionary: () -> Unit = {},
            bubble: BubbleStyle = BubbleStyle.RECOMMENDED, onBubble: (BubbleStyle) -> Unit = {},
            placed: Boolean = false, onSnap: (Boolean) -> Unit = {}, onResetPosition: () -> Unit = {},
        ) = SettingsScreen(
            setup, onFix = {}, onWelcome = {}, onChoose = onChoose, onManageModels = {}, downloads = downloads,
            bubbleStyle = bubble, onBubbleStyle = onBubble,
            bubblePlaced = placed, bubbleSnap = false, onBubbleSnap = onSnap, onResetBubblePosition = onResetPosition,
            wordCount = wordCount, onDictionary = onDictionary,
            retention = Retention(maxDays = null, maxTakes = 200), storageBytes = 0, onRetention = onRetention,
            showRetention = false, onRetentionShown = {}, version = "0.1", onWebsite = {}, onLink = onLink,
        )

        @Composable
        private fun history(
            rows: List<Dictation>, retention: Retention = Retention(maxDays = null, maxTakes = 200),
            onClearAll: () -> Unit = {}, onRetention: () -> Unit = {}, onHome: () -> Unit = {},
        ) = HistoryScreen(rows, emptyMap(), retention, 0, {}, {}, {}, onClearAll, onRetention, onHome)

        private fun row(id: Long, status: Status, text: String) =
            Dictation(id, "s$id", System.currentTimeMillis() - id, 1_000, "recordings/s$id.wav", "m", status, null, text, text, null, 0, null, null, false)
    }

    @RunWith(AndroidJUnit4::class)
    class Main {
        @get:Rule(order = 0) val graph = TestGraph()   // the doubles are in AppGraph before MainActivity starts
        @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()
        private val app = InstrumentationRegistry.getInstrumentation().targetContext

        @Test
        fun contentBelowStatusBar() {
            waitForText("ThumbFree")
            val top = compose.onNodeWithText("ThumbFree").getBoundsInRoot().top
            val inset = compose.runOnIdle {
                compose.activity.window.decorView.rootWindowInsets.getInsets(WindowInsets.Type.statusBars()).top
            }
            assertThat(inset).isGreaterThan(0)
            assertThat(with(compose.density) { top.toPx() }).isAtLeast(inset.toFloat())
        }

        // Four tabs, Home first, and no others.
        @Test
        fun fourTabsHomeFirst() {
            waitForText("Finish setup")
            for (tab in listOf("Home", "History", "Dictionary", "Settings")) compose.onNode(hasText(tab) and hasClickAction()).assertExists()
            compose.onNode(hasText("Try") and hasClickAction()).assertDoesNotExist()
            compose.onNode(hasText("Home") and hasClickAction()).assertIsSelected()
            tab("Settings")
            compose.onNode(hasText("Settings") and hasClickAction()).assertIsSelected()
        }

        // The Dictionary card: at the top of Home on the next visit after a take that typed text, not in the visit it
        // came in; Not now puts it away for good.
        @Test
        fun dictionaryHintShowsOnTheNextVisit() {
            waitForText("Finish setup")
            row("typed", startedAt = 1, Status.INSERTED, text = "Hello there")
            compose.onNodeWithText(HINT).assertDoesNotExist()
            resume()
            waitForText(HINT)
            compose.onNodeWithText("Not now").performClick()

            compose.onNodeWithText(HINT).assertDoesNotExist()
            assertThat(AppGraph.settings.dictionaryHintDone).isTrue()
            resume()
            waitForText("Finish setup")
            compose.onNodeWithText(HINT).assertDoesNotExist()
        }

        // At most once ever: left alone, it is gone after the visit and never comes back.
        @Test
        fun dictionaryHintNeverShowsTwice() {
            row("typed", startedAt = 1, Status.INSERTED, text = "Hello there")
            resume()
            waitForText(HINT)
            resume()
            waitForText("Finish setup")
            compose.onNodeWithText(HINT).assertDoesNotExist()
        }

        // Only a take that typed text leads to it (not one with no speech, not a failed one), and never once the Dictionary
        // was opened.
        @Test
        fun dictionaryHintNeedsATypedTakeAndAnUnopenedDictionary() {
            row("quiet", startedAt = 1, Status.NO_SPEECH)
            row("failed", startedAt = 2, Status.FAILED, text = "Partial words")
            resume()
            waitForText("Finish setup")
            compose.onNodeWithText(HINT).assertDoesNotExist()

            tab("Dictionary")
            waitForText("No words yet")
            row("typed", startedAt = 3, Status.INSERTED, text = "Hello there")
            resume()
            tab("Home")
            waitForText("Finish setup")
            compose.onNodeWithText(HINT).assertDoesNotExist()
        }

        @Test
        fun dictionaryHintOpensTheDictionary() {
            row("typed", startedAt = 1, Status.INSERTED, text = "Hello there")
            resume()
            waitForText(HINT)
            compose.onNodeWithText("Open Dictionary").performClick()

            compose.onNode(hasText("Dictionary") and hasClickAction()).assertIsSelected()
            waitForText("No words yet")
            assertThat(AppGraph.settings.dictionaryHintDone).isTrue()
        }

        @Test
        fun historyNewestFirstWithStatus() {
            row("older", startedAt = 1, Status.INSERTED, text = "Older text")
            row("newer", startedAt = 2, Status.NEEDS_REVIEW, text = "Newer text")
            resume()
            tab("History")
            waitForText("Newer text")
            val rows = compose.onAllNodesWithTag("history_row")
            rows[0].assert(hasText("Newer text")).assert(hasText("May already be in the field"))
            rows[1].assert(hasText("Older text")).assert(hasText("Typed"))
        }

        @Test
        fun copyWritesClipboard() {
            row("older", startedAt = 1, Status.INSERTED, text = "Older text")
            row("newer", startedAt = 2, Status.NEEDS_REVIEW, text = "Newer text")
            resume()
            tab("History")
            waitForText("Newer text")
            compose.onAllNodesWithText("Copy")[0].performClick()
            compose.onNodeWithText("Copied.").assertIsDisplayed()
            assertThat(clip()).isEqualTo("Newer text")
        }

        @Test
        fun deleteRemovesRowAndFile() {
            val id = "p1-19-delete"
            val wav = wav(id)
            row(id, startedAt = 1, Status.INSERTED, text = "Delete me")
            resume()
            tab("History")
            waitForText("Delete me")
            compose.onNodeWithContentDescription("Delete").performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithText("Delete me").fetchSemanticsNodes().isEmpty() }
            assertThat(wav.exists()).isFalse()
            assertThat(graph.history.get(id)).isNull()
        }

        // Clear all deletes every ended take with its WAV, after the confirm; a take in progress stays.
        @Test
        fun clearAllKeepsATakeInProgress() {
            val ended = listOf("c1", "c2").onEach { row(it, startedAt = 1, Status.INSERTED, text = "Text $it") }.map(::wav)
            graph.history.create("live", 3, "recordings/live.wav", "m", null) // RECORDING
            resume()
            tab("History")
            waitForText("Text c1")

            compose.onNodeWithText("Clear all").performClick()
            compose.onNodeWithText("Delete all").performClick()

            compose.waitUntil(5_000) { graph.history.list(10).size == 1 }
            assertThat(graph.history.list(10).single().sessionId).isEqualTo("live")
            assertThat(ended.filter { it.exists() }).isEmpty()
        }

        @Test
        fun interruptedRowOffersTranscribe() {
            row("cut", startedAt = 1, Status.INTERRUPTED)
            resume()
            tab("History")
            waitForText("Interrupted")
            compose.onNodeWithText("Transcribe again").performClick()
            compose.runOnIdle { assertThat(graph.calls).containsExactly("retranscribe(cut)") }
        }

        @Test
        fun partialTextIsShown() {
            row("half", startedAt = 1, Status.FAILED, partial = "half a sent")
            resume()
            tab("History")
            waitForText("half a sent (partial)")
            compose.onNodeWithText("Copy").performClick()
            assertThat(clip()).isEqualTo("half a sent")
        }

        // History's retention line opens its setting; a rule that would delete takes asks first, then deletes them.
        @Test
        fun stricterRetentionAsksThenDeletes() {
            AppGraph.settings.retention = Retention(maxDays = null, maxTakes = 200) // no day limit before the change
            val old = System.currentTimeMillis() - 10 * 86_400_000L
            for (id in listOf("o1", "o2")) { wav(id); row(id, startedAt = old, Status.INSERTED, text = "Old $id") }
            row("fresh", startedAt = System.currentTimeMillis(), Status.INSERTED, text = "Fresh")
            resume()
            tab("History")
            waitForText("Old o1")

            compose.onNodeWithText("Change").performClick()
            compose.onNodeWithText("7 days").performScrollTo().performClick()
            compose.onNodeWithText("The new rule deletes 2 takes and their recordings now. It can't be undone.").assertIsDisplayed()
            compose.onNodeWithText("Delete").performClick()

            compose.waitUntil(5_000) { graph.history.list(10).map { it.sessionId } == listOf("fresh") }
            assertThat(AppGraph.settings.retention).isEqualTo(Retention(maxDays = 7, maxTakes = 200))
            assertThat(listOf("o1", "o2").map { File(app.filesDir, "recordings/$it.wav") }.filter { it.exists() }).isEmpty()
        }

        // Right after a push the first status is a SHA-256 of all 731 MB, which takes seconds: the setup rows show at once,
        // and the model row says so until the status comes.
        @Test
        fun setupShowsWhileTheModelIsChecked() {
            val model = Catalog.PARAKEET_UNIFIED_Q8
            val dir = File(app.cacheDir, "checking-model").apply { mkdirs() }
            val hashing = CountDownLatch(1)
            try {
                RandomAccessFile(File(dir, model.fileName), "rw").use { it.setLength(model.sizeBytes) } // sparse: no real space
                AppGraph.modelStore = ModelStore(dir) { hashing.await(10, TimeUnit.SECONDS); model.sha256 }
                compose.activityRule.scenario.recreate() // a fresh screen, whose first load checks this model
                tab("Settings")

                // The hash is still running: the rows are there already, and the model row says why it waits.
                waitForText("Microphone")
                compose.onNodeWithText("Checking the speech model").assertIsDisplayed()

                hashing.countDown()
                waitForAny("Ready", "All set") // All set once nothing is missing on this device
            } finally {
                hashing.countDown()
                dir.deleteRecursively()
            }
        }

        // The model can finish verifying while the Models screen is open (a download completing, say); the setup rows
        // must not keep showing what they said before that screen, once the user comes back.
        @Test
        fun modelsBackRefreshesSetup() {
            val model = Catalog.PARAKEET_UNIFIED_Q8
            val dir = File(app.cacheDir, "models-back-refresh").apply { mkdirs() }
            AppGraph.modelStore = ModelStore(dir) { model.sha256 } // a stand-in hash: the sparse file below checks out
            try {
                resume()
                tab("Settings")
                waitForText("Not downloaded · 731 MB")

                compose.onNodeWithText("Download or delete models").performScrollTo().performClick()
                waitForText("731 MB")

                RandomAccessFile(File(dir, model.fileName), "rw").use { it.setLength(model.sizeBytes) }
                compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }

                waitForAny("Ready", "All set")
            } finally {
                dir.deleteRecursively()
            }
        }

        // When the microphone goes missing later (an "Only this time" grant ends when the app leaves the screen), Home's
        // setup card asks again: its Allow brings back Android's question. Runs only while the permission is off: revoke it
        // with adb first, since revoking it from here would end this process. It answers While using the app, so the
        // grant is back on afterwards.
        @Test
        fun homeCardAsksForTheMicAgain() {
            assumeTrue("the mic is allowed here", app.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            waitForText("Needed to hear you")
            compose.onNode(hasText("Allow") and hasClickAction()).performClick()

            val answer = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).wait(Until.findObject(By.text("While using the app")), 5_000)
            assertThat(answer).isNotNull()
            answer.click()
            compose.waitUntil(5_000) { app.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED }
        }

        // Home in the real activity: the Finish setup card with the one thing this phone needs next (here at least the
        // speech model, as the test graph's model folder is empty), and no example or field while a take can't run.
        @Test
        fun homeShowsTheCardWithWhatIsNeededNext() {
            waitForText("Finish setup")
            compose.onNode(hasText("Allow") or hasText("Turn on") or hasText("Get it")).assertIsDisplayed()
            compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        }

        // Settings > Setup opens the welcome screens again, and Close returns to Settings.
        @Test
        fun welcomeReopensFromSettings() {
            resume()
            tab("Settings")
            compose.onNodeWithText("Show the welcome screens").performScrollTo().performClick()
            waitForText(QUESTION)
            compose.onNodeWithContentDescription("Close").performClick()
            waitForText("Show the welcome screens")
            assertThat(AppGraph.settings.welcomeDone).isTrue()
        }

        // The Dictation bubble row's Turn on, from Home and from Settings > Setup: the disclosure first (Google
        // Play wants it shown right before every request for the setting, not only the welcome flow's first one), and
        // never the setting itself. Not now returns to the tab it was opened from.
        @Test
        fun turnOnShowsTheDisclosureBeforeTheSetting() {
            val before = secure("enabled_accessibility_services")
            val withoutService = enabledServices().filter { it != SERVICE }.joinToString(":").ifEmpty { null }
            val seen = CopyOnWriteArrayList<Intent>()
            val monitor = object : Instrumentation.ActivityMonitor() {
                override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                    if (intent.action?.startsWith("android.settings.") != true) return null
                    seen += intent
                    return Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
                }
            }
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            instrumentation.addMonitor(monitor)
            try {
                putSecure("enabled_accessibility_services", withoutService)
                resume()
                waitForText("Finish setup")

                // Once from Home's setup card.
                compose.onNodeWithText("Turn on").performClick()
                waitForText(SERVICE_LINE)
                compose.onNodeWithText("Not now").performClick()
                waitForText("Finish setup")

                // Once from Settings > Setup.
                tab("Settings")
                waitForText("Turn on")
                compose.onNodeWithText("Turn on").performClick()
                waitForText(SERVICE_LINE)
                compose.onNodeWithText("Not now").performClick()
                waitForText("Show the welcome screens")

                assertThat(seen).isEmpty() // never Android's accessibility settings, from either tab
            } finally {
                putSecure("enabled_accessibility_services", before)
                instrumentation.removeMonitor(monitor)
            }
        }

        // Back from Android's settings with the service on, from Settings > Setup's Turn on: it lands on Settings, not
        // Home, since discloseOnly's whole visit was the disclosure (see finishWelcome). The settings screen is stopped
        // here and Android delivers that answer with a pause and a resume, which is the return; so the switch is set
        // first, as the user's tap there would, and put back after.
        @Test
        fun turnOnFromSettingsReturningWithTheServiceOnLandsOnSettings() {
            val before = secure("enabled_accessibility_services")
            val withoutService = enabledServices().filter { it != SERVICE }.joinToString(":").ifEmpty { null }
            val monitor = object : Instrumentation.ActivityMonitor() {
                override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? =
                    if (intent.action == android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
                    else null
            }
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            instrumentation.addMonitor(monitor)
            try {
                putSecure("enabled_accessibility_services", withoutService)
                resume()
                tab("Settings")
                waitForText("Turn on")
                compose.onNodeWithText("Turn on").performClick()
                waitForText(SERVICE_LINE)

                shell("settings put secure enabled_accessibility_services $SERVICE")
                compose.onNodeWithText("Agree and open settings").performClick()

                waitForText("Show the welcome screens") // Settings' own content, not Home's
                compose.onNode(hasText("Settings") and hasClickAction()).assertIsSelected()
                compose.onNodeWithText("The bubble is still off.").assertDoesNotExist()
            } finally {
                putSecure("enabled_accessibility_services", before)
                instrumentation.removeMonitor(monitor)
            }
        }

        // Back with the service still off, from Home's Turn on: the disclosure says the bubble is still off, with Open
        // settings again and the restricted-settings help, same as the first run's step, and it is still the screen, not
        // Home.
        @Test
        fun turnOnFromHomeReturningWithTheServiceOffSaysItIsStillOff() {
            val before = secure("enabled_accessibility_services")
            val withoutService = enabledServices().filter { it != SERVICE }.joinToString(":").ifEmpty { null }
            val monitor = object : Instrumentation.ActivityMonitor() {
                override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? =
                    if (intent.action == android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
                    else null
            }
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            instrumentation.addMonitor(monitor)
            try {
                putSecure("enabled_accessibility_services", withoutService)
                resume()
                waitForText("Finish setup")
                compose.onNodeWithText("Turn on").performClick()
                waitForText(SERVICE_LINE)

                compose.onNodeWithText("Agree and open settings").performClick()

                waitForText("The bubble is still off.")
                compose.onNodeWithText("Open settings again").assertIsDisplayed()
                compose.onNodeWithText("Switch greyed out?").assertIsDisplayed()
                compose.onNodeWithText(SERVICE_LINE).assertIsDisplayed() // still the disclosure, not back at Home
            } finally {
                putSecure("enabled_accessibility_services", before)
                instrumentation.removeMonitor(monitor)
            }
        }

        private fun row(id: String, startedAt: Long, status: Status, text: String? = null, partial: String? = null) {
            graph.history.create(id, startedAt, "recordings/$id.wav", "m", null)
            partial?.let { graph.history.saveChunk(id, 1, it) }
            text?.let { graph.history.stage(id, it, it, 1_000) }
            graph.history.finish(id, status)
        }

        /** Writes a header-only WAV for [id] and returns it. */
        private fun wav(id: String) = File(app.filesDir, "recordings/$id.wav").apply { parentFile!!.mkdirs(); writeBytes(ByteArray(44)) }

        private fun tab(name: String) = compose.onNode(hasText(name) and hasClickAction()).performClick()

        // Stop and start again: onResume reloads the grants and the history.
        private fun resume() {
            compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED).moveToState(Lifecycle.State.RESUMED)
        }

        private fun waitForText(text: String, substring: Boolean = false) =
            compose.waitUntil(5_000) { compose.onAllNodesWithText(text, substring).fetchSemanticsNodes().isNotEmpty() }

        private fun waitForAny(vararg texts: String) = compose.waitUntil(5_000) {
            texts.any { compose.onAllNodesWithText(it).fetchSemanticsNodes().isNotEmpty() }
        }

        private fun clip(): String? =
            app.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.text?.toString()
    }
    /** A first launch left at the accessibility step: the welcome screens resume there, before the tabs. */
    @RunWith(AndroidJUnit4::class)
    class FirstLaunch {
        @get:Rule(order = 0) val graph = TestGraph(welcomeAt = WelcomeStep.SERVICE)
        @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()
        private val app = InstrumentationRegistry.getInstrumentation().targetContext

        // Not now (or Continue) goes on to the last step, which asks for what is still off (Not now finishes anyway), says
        // what the download needs (Go to Home) or that all is set (Done). Either way Home opens, and the welcome screens
        // never open by themselves again.
        @Test
        fun welcomeResumesThenEndsOnHome() {
            waitForText("Turn on the bubble.")
            compose.onNode(hasText("Not now") or hasText("Continue")).performClick() // Continue if this device has it on

            compose.waitForFinish()
            assertThat(AppGraph.settings.welcomeScreen).isEqualTo("READY")
            compose.leaveFinish()
            waitForText("Finish setup")
            compose.onNode(hasText("Home") and hasClickAction()).assertIsSelected()
            assertThat(AppGraph.settings.welcomeDone).isTrue()
        }

        // A rotation keeps the step on screen.
        @Test
        fun welcomeStepSurvivesRotation() {
            waitForText("Turn on the bubble.")
            compose.activityRule.scenario.recreate()
            waitForText("Turn on the bubble.")
            compose.onNodeWithText("Finish setup").assertDoesNotExist()
        }

        // Agree opens Android's accessibility list, told to scroll to the service. The list is stopped here, so the test
        // never leaves the app.
        @Test
        fun agreeOpensTheListScrolledToTheService() {
            waitForText("Turn on the bubble.")
            assumeTrue("the service is on here", compose.onAllNodesWithText("Agree and open settings").fetchSemanticsNodes().isNotEmpty())
            val seen = CopyOnWriteArrayList<Intent>()
            val monitor = object : Instrumentation.ActivityMonitor() {
                override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                    if (intent.action?.startsWith("android.settings.") != true) return null
                    seen += intent
                    return Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
                }
            }
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            instrumentation.addMonitor(monitor)
            try {
                compose.onNodeWithText("Agree and open settings").performClick()
                compose.waitUntil(5_000) { seen.isNotEmpty() }

                val service = ComponentName(app, DictationAccessibilityService::class.java).flattenToString()
                assertThat(seen.single().action).isEqualTo(LIST)
                assertThat(seen.single().getStringExtra(":settings:fragment_args_key")).isEqualTo(service)
                assertThat(seen.single().getBundleExtra(":settings:show_fragment_args")?.getString(":settings:fragment_args_key"))
                    .isEqualTo(service)
            } finally {
                instrumentation.removeMonitor(monitor)
            }
        }

        // On a phone without the accessibility list (here the start is made to fail as it would there), Agree opens App
        // info instead, and the app goes on.
        @Test
        fun withoutTheListAgreeOpensAppInfo() {
            waitForText("Turn on the bubble.")
            assumeTrue("the service is on here", compose.onAllNodesWithText("Agree and open settings").fetchSemanticsNodes().isNotEmpty())
            val seen = CopyOnWriteArrayList<Intent>()
            val monitor = object : Instrumentation.ActivityMonitor() {
                override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? = when (intent.action) {
                    LIST -> throw ActivityNotFoundException("no accessibility list on this phone")
                    APP_INFO -> Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null).also { seen += intent }
                    else -> null
                }
            }
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            instrumentation.addMonitor(monitor)
            try {
                compose.onNodeWithText("Agree and open settings").performClick()
                compose.waitUntil(5_000) { seen.isNotEmpty() }

                assertThat(seen.single().data).isEqualTo(Uri.fromParts("package", app.packageName, null))
                compose.onNodeWithText(SERVICE_LINE).assertExists() // no crash, still on the step
            } finally {
                instrumentation.removeMonitor(monitor)
            }
        }

        // Back from Android's settings with the service on, the step is done: the last step opens by itself, with no
        // still-off line. The settings screen is stopped here, and Android delivers that answer with a pause and a resume,
        // which is the return; so the switch is set first, as the user's tap there would, and put back after.
        @Test
        fun returningWithTheServiceOnGoesToTheLastStep() {
            waitForText("Turn on the bubble.")
            assumeTrue("the service is on here", compose.onAllNodesWithText("Agree and open settings").fetchSemanticsNodes().isNotEmpty())
            val monitor = object : Instrumentation.ActivityMonitor() {
                override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? =
                    if (intent.action == LIST) Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null) else null
            }
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            instrumentation.addMonitor(monitor)
            val before = secure("enabled_accessibility_services")
            try {
                shell("settings put secure enabled_accessibility_services $SERVICE")
                compose.onNodeWithText("Agree and open settings").performClick()

                compose.waitForFinish()
                compose.onNodeWithText("Open settings again").assertDoesNotExist()
                assertThat(AppGraph.settings.welcomeScreen).isEqualTo("READY")
            } finally {
                putSecure("enabled_accessibility_services", before)
                instrumentation.removeMonitor(monitor)
            }
        }

        // Automatic return. Agree opens Android's real accessibility settings and keeps the wait; the switch turning on
        // there (here from the shell, as the user's taps on Use ThumbFree and Allow would) connects the service, which
        // brings the app back to the front by itself: the same activity, resumed, on the next step, with the wait over.
        // This checks the flow. Under instrumentation the test harness may allow the background start itself, so
        // Android's own allowance for a service it binds needs the check by hand that ui/AGENTS.md asks for.
        @Test
        fun turningTheServiceOnBringsTheAppBackOnTheNextStep() {
            waitForText("Turn on the bubble.")
            val services = secure("enabled_accessibility_services")
            val enabled = secure("accessibility_enabled")
            val others = enabledServices().filter { it != SERVICE }
            try {
                putSecure("enabled_accessibility_services", others.joinToString(":").ifEmpty { null })
                assertThat(waitFor(5_000) { DictationAccessibilityService.instance == null }).isTrue()
                compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED).moveToState(Lifecycle.State.RESUMED)
                waitForText("Agree and open settings")

                compose.onNodeWithText("Agree and open settings").performClick()
                assertThat(waitFor(10_000) { compose.activityRule.scenario.state == Lifecycle.State.CREATED }).isTrue() // behind Settings
                assertThat(AppGraph.settings.accessibilityWait).isNotNull()
                putSecure("enabled_accessibility_services", (others + SERVICE).joinToString(":"))
                shell("settings put secure accessibility_enabled 1")

                compose.waitForFinish()
                assertThat(compose.activityRule.scenario.state).isEqualTo(Lifecycle.State.RESUMED)
                assertThat(shell("dumpsys activity activities").lines().first { "topResumedActivity" in it }).contains(".ui.MainActivity")
                assertThat(AppGraph.settings.accessibilityWait).isNull()
                assertThat(AppGraph.settings.welcomeScreen).isEqualTo("READY")
                compose.onNodeWithText("Open settings again").assertDoesNotExist()
            } finally {
                putSecure("enabled_accessibility_services", services)
                putSecure("accessibility_enabled", enabled)
            }
        }

        // Not now with the bubble off: the last step says the bubble is still off, and its big button leads back to the
        // bubble step, disclosure and all.
        @Test
        fun readyWithTheBubbleOffLeadsBackToIt() {
            assumeTrue("the mic is off here", app.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
            waitForText("Turn on the bubble.")
            val before = secure("enabled_accessibility_services")
            try {
                putSecure("enabled_accessibility_services", enabledServices().filter { it != SERVICE }.joinToString(":").ifEmpty { null })
                compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED).moveToState(Lifecycle.State.RESUMED)
                waitForText("Not now")
                compose.onNodeWithText("Not now").performClick()

                waitForText("The bubble is still off.")
                compose.onNode(hasText("Turn on the bubble") and hasClickAction()).performClick()
                waitForText(SERVICE_LINE)
                compose.onNodeWithContentDescription(SERVICE_STEP).assertExists()
                assertThat(AppGraph.settings.welcomeScreen).isEqualTo("SERVICE")
            } finally {
                putSecure("enabled_accessibility_services", before)
            }
        }

        // If Android refuses the service's start (here an ActivityMonitor swallows it; a refusal leaves nothing behind
        // either), the user's own return still moves the step on, as it always has: the wait stays for that resume.
        @Test
        fun aRefusedReturnStillMovesOnWhenTheUserComesBack() {
            waitForText("Turn on the bubble.")
            val services = secure("enabled_accessibility_services")
            val enabled = secure("accessibility_enabled")
            val others = enabledServices().filter { it != SERVICE }
            val refused = CountDownLatch(1)
            val monitor = object : Instrumentation.ActivityMonitor() {
                override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? =
                    if (intent.getBooleanExtra(AndroidPorts.EXTRA_BACK_FROM_ACCESSIBILITY, false)) {
                        refused.countDown()
                        Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
                    } else null
            }
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            instrumentation.addMonitor(monitor)
            try {
                putSecure("enabled_accessibility_services", others.joinToString(":").ifEmpty { null })
                assertThat(waitFor(5_000) { DictationAccessibilityService.instance == null }).isTrue()
                compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED).moveToState(Lifecycle.State.RESUMED)
                waitForText("Agree and open settings")

                compose.onNodeWithText("Agree and open settings").performClick()
                assertThat(waitFor(10_000) { compose.activityRule.scenario.state == Lifecycle.State.CREATED }).isTrue() // behind Settings
                putSecure("enabled_accessibility_services", (others + SERVICE).joinToString(":"))
                shell("settings put secure accessibility_enabled 1")
                assertThat(refused.await(10, TimeUnit.SECONDS)).isTrue() // the service asked, and nothing started
                SystemClock.sleep(1_000)
                assertThat(compose.activityRule.scenario.state).isEqualTo(Lifecycle.State.CREATED)
                assertThat(AppGraph.settings.accessibilityWait).isNotNull()

                shell("input keyevent KEYCODE_BACK") // the user comes back by hand, from Android's list
                compose.waitForFinish()
                assertThat(AppGraph.settings.welcomeScreen).isEqualTo("READY")
                assertThat(AppGraph.settings.accessibilityWait).isNull()
            } finally {
                putSecure("enabled_accessibility_services", services)
                putSecure("accessibility_enabled", enabled)
                instrumentation.removeMonitor(monitor)
            }
        }

        // A wait over 10 minutes old is not this visit's (an Agree long ago, never come back from): a new activity ends
        // it without counting a return, so the bubble step doesn't say the bubble is still off.
        @Test
        fun aStaleWaitIsNotAReturn() {
            waitForText("Turn on the bubble.")
            val before = secure("enabled_accessibility_services")
            try {
                putSecure("enabled_accessibility_services", enabledServices().filter { it != SERVICE }.joinToString(":").ifEmpty { null })
                AppGraph.settings.accessibilityWait = System.currentTimeMillis() - 11 * 60_000L

                compose.activityRule.scenario.recreate()

                compose.waitUntil(5_000) { AppGraph.settings.accessibilityWait == null }
                waitForText("Agree and open settings")
                SystemClock.sleep(1_000) // the setup read that would say so
                compose.onNodeWithText("The bubble is still off.").assertDoesNotExist()
            } finally {
                putSecure("enabled_accessibility_services", before)
            }
        }

        // The wait is kept in Settings, so the return still counts in a new activity, as after Android ended the process
        // while the user was in its settings: back with the switch still off, the step says the bubble is still off.
        @Test
        fun aWaitKeptInSettingsStillCountsInANewActivity() {
            waitForText("Turn on the bubble.")
            val before = secure("enabled_accessibility_services")
            try {
                putSecure("enabled_accessibility_services", enabledServices().filter { it != SERVICE }.joinToString(":").ifEmpty { null })
                AppGraph.settings.accessibilityWait = System.currentTimeMillis() // as the Agree of the process that ended left it

                compose.activityRule.scenario.recreate()

                waitForText("The bubble is still off.")
                compose.onNodeWithText(SERVICE_LINE).assertIsDisplayed()
                assertThat(AppGraph.settings.accessibilityWait).isNull()
            } finally {
                putSecure("enabled_accessibility_services", before)
            }
        }

        private fun waitForText(text: String) =
            compose.waitUntil(5_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }

        private fun waitForAny(vararg texts: String) = compose.waitUntil(5_000) {
            texts.any { compose.onAllNodesWithText(it).fetchSemanticsNodes().isNotEmpty() }
        }

        private companion object {
            const val LIST = android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS
            const val APP_INFO = android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS
        }
    }

    /**
     * A first launch from the start. Downloads run through the real worker with a downloader that only waits for its
     * cancel: no network, and nothing written to the models folder. The engine the first step loads the model into is a
     * fake one that takes 1.5 s.
     */
    @RunWith(AndroidJUnit4::class)
    class FirstRun {
        @get:Rule(order = 0) val graph = TestGraph(welcomeAt = WelcomeStep.WELCOME)
        @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()
        private val app = InstrumentationRegistry.getInstrumentation().targetContext
        private val started = CopyOnWriteArrayList<String>()
        private val realDownloader = DownloadWorker.downloaderFactory
        private val engine = SlowEngine(1_500)
        private lateinit var realQueue: TranscriptionQueue

        @Before
        fun fakes() {
            DownloadWorker.downloaderFactory = { dir ->
                object : Downloader(dir, usableSpace = { Long.MAX_VALUE }) {
                    override fun download(
                        model: ModelFile, url: String, isCancelled: () -> Boolean, onProgress: (DownloadProgress) -> Unit,
                    ): DownloadResult {
                        started += model.fileName
                        onProgress(DownloadProgress(model.sizeBytes * 425 / 1000, model.sizeBytes)) // held at 42%
                        while (!isCancelled()) Thread.sleep(50)
                        return DownloadResult.Failed(DownloadResult.Reason.CANCELLED, "cancelled")
                    }
                }
            }
            realQueue = AppGraph.queue
            AppGraph.queue = TranscriptionQueue(
                engine, CoroutineScope(Dispatchers.IO), modelPath = { AppGraph.modelStore.verifiedPath(AppGraph.settings.model)?.path },
                threads = { 4 }, listener = NoQueueListener,
            )
        }

        @After
        fun reals() {
            for (model in listOf(ENGLISH, MULTILINGUAL)) ModelDownloads.cancel(app, model)
            DownloadWorker.downloaderFactory = realDownloader
            AppGraph.queue = realQueue
        }

        // The language's tap chooses the model and starts its download at once (its reaching the downloader means its
        // foreground service started, with no notification permission), and the step waits there: the grey bubble and the
        // percentage. Once the file is on the phone and checked, "Almost ready" while the engine loads it; loaded, the
        // try opens by itself, its bubble yellow.
        @Test
        fun theLanguageTapDownloadsChecksLoadsThenOpensTheTry() {
            assumeTrue("no model: run android/tools/push-test-model.sh", TestModels.find(TestModels.PARAKEET_Q8) != null)
            waitForText(QUESTION)
            compose.onNodeWithTag(LANGUAGE_ENGLISH).performClick()
            compose.waitUntil(20_000) { ENGLISH.fileName in started }
            waitForPercent()
            compose.onNodeWithText(GETTING_READY).assertIsDisplayed()
            assertThat(grey()).isEqualTo(Grey(Grey.Badge.DOWNLOAD, 0.42f))
            assertThat(AppGraph.settings.model).isEqualTo(ENGLISH)
            assertThat(app.checkSelfPermission("android.permission.POST_NOTIFICATIONS")).isEqualTo(PackageManager.PERMISSION_DENIED)

            // The download done: the model pushed for the device tests, as the worker leaves a finished, checked file.
            ModelDownloads.cancel(app, ENGLISH)
            AppGraph.modelStore = ModelStore(File(app.filesDir, "models"))
            ModelDownloads.refresh()
            waitForText("Almost ready", 20_000)
            assertThat(grey()).isEqualTo(Grey.PREPARING)
            compose.waitUntil(10_000) { engine.loads.isNotEmpty() }
            compose.waitForTry()
            assertThat(grey()).isNull()
            assertThat(AppGraph.settings.welcomeScreen).isEqualTo("TRY")
        }

        // Change goes back to the choice while the download runs; Other languages then stops the English download and
        // starts the multilingual one, which the speech model's line names.
        @Test
        fun changeGoesBackToTheChoiceAndSwitchesTheDownload() {
            waitForText(QUESTION)
            compose.onNodeWithTag(LANGUAGE_ENGLISH).performClick()
            compose.waitUntil(20_000) { ENGLISH.fileName in started }
            waitForText("Change")
            compose.onNodeWithText("Change").performClick()
            waitForText(QUESTION)
            compose.onNodeWithTag(LANGUAGE_OTHER).performClick()
            compose.waitUntil(20_000) { MULTILINGUAL.fileName in started }
            waitForText("Speech model: 25 languages · 740 MB")
            assertThat(AppGraph.settings.model).isEqualTo(MULTILINGUAL)
            compose.waitUntil(10_000) { ModelDownloads.states(app).value[ENGLISH] == DownloadState.NotDownloaded }
        }

        // Back from the try shows the language choice; Back again leaves the welcome, rather than going on to the try once
        // more, as the loaded choice would.
        @Test
        fun backFromTheTryThenBackAgainLeavesTheWelcome() {
            assumeTrue("no model: run android/tools/push-test-model.sh", TestModels.find(TestModels.PARAKEET_Q8) != null)
            AppGraph.modelStore = ModelStore(File(app.filesDir, "models")) // the model pushed for the device tests
            ModelDownloads.refresh()
            waitForText(QUESTION)
            compose.onNodeWithTag(LANGUAGE_ENGLISH).performClick()
            compose.waitForTry()
            compose.onNodeWithContentDescription("Back").performClick()
            waitForText(QUESTION)
            UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).pressBack()
            assertThat(waitFor(5_000) { compose.activityRule.scenario.state == Lifecycle.State.DESTROYED }).isTrue()
        }

        // The language tapped again asks the engine afresh rather than trusting the earlier load: here the engine unloaded
        // the model after its idle time, so the try waits for a second load.
        @Test
        fun theLanguageTappedAgainAsksTheEngineAfresh() {
            assumeTrue("no model: run android/tools/push-test-model.sh", TestModels.find(TestModels.PARAKEET_Q8) != null)
            AppGraph.queue = TranscriptionQueue(
                engine, CoroutineScope(Dispatchers.IO), modelPath = { AppGraph.modelStore.verifiedPath(AppGraph.settings.model)?.path },
                threads = { 4 }, listener = NoQueueListener, unloadAfterIdleMs = 500,
            )
            AppGraph.modelStore = ModelStore(File(app.filesDir, "models")) // the model pushed for the device tests
            ModelDownloads.refresh()
            waitForText(QUESTION)
            compose.onNodeWithTag(LANGUAGE_ENGLISH).performClick()
            compose.waitForTry()
            assertThat(engine.loads).hasSize(1)
            assertThat(waitFor(10_000) { engine.unloads > 0 }).isTrue() // after 0.5 s idle

            compose.onNodeWithContentDescription("Back").performClick()
            waitForText(QUESTION)
            compose.onNodeWithTag(LANGUAGE_ENGLISH).performClick()
            compose.waitForTry()
            assertThat(engine.loads).hasSize(2)
        }

        // A model the engine won't load: the first step says so, its bubble grey and never yellow, with Try again, which
        // loads it again; loaded, the try opens by itself.
        @Test
        fun aLoadThatFailsSaysSoAndTryAgainLoadsIt() {
            assumeTrue("no model: run android/tools/push-test-model.sh", TestModels.find(TestModels.PARAKEET_Q8) != null)
            engine.status = 1
            AppGraph.modelStore = ModelStore(File(app.filesDir, "models")) // the model pushed for the device tests
            ModelDownloads.refresh()
            waitForText(QUESTION)
            compose.onNodeWithTag(LANGUAGE_ENGLISH).performClick()
            waitForText(LOAD_FAILED, 20_000)
            compose.onNodeWithText(LOAD_FAILED_LINE).assertIsDisplayed()
            assertThat(grey()).isEqualTo(Grey(Grey.Badge.LOAD_FAILED, 1f))
            assertThat(started).isEmpty() // here already: nothing downloads

            engine.status = 0
            compose.onNodeWithText("Try again").performClick()
            compose.waitForTry()
            assertThat(grey()).isNull()
            assertThat(engine.loads).hasSize(2)
        }

        // Leaving mid-download is fine: the download goes on, and the step comes back to the wait, after the app goes to
        // the background and after Android makes the activity again.
        @Test
        fun leavingMidDownloadComesBackToTheWait() {
            waitForText(QUESTION)
            compose.onNodeWithTag(LANGUAGE_ENGLISH).performClick()
            waitForPercent(20_000)
            compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED).moveToState(Lifecycle.State.RESUMED)
            waitForPercent()
            compose.activityRule.scenario.recreate()
            waitForPercent()
            compose.onNodeWithText(QUESTION).assertDoesNotExist()
            assertThat(started).containsExactly(ENGLISH.fileName)
        }

        private fun waitForText(text: String, ms: Long = 5_000) =
            compose.waitUntil(ms) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }

        /** Waits for the download's 42%, which TalkBack is given as 40%. */
        private fun waitForPercent(ms: Long = 5_000) =
            compose.waitUntil(ms) { compose.onAllNodesWithContentDescription("40%").fetchSemanticsNodes().isNotEmpty() }
    }

    /** A first launch whose welcome screens an earlier build left at its How it works step, kept by that build's index. */
    @RunWith(AndroidJUnit4::class)
    class OldBookmark {
        @get:Rule(order = 0) val graph = TestGraph(oldIndex = 1)
        @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

        // The old index maps onto today's order: How it works came before the download's start, which the first step asks.
        @Test
        fun anEarlierBuildsBookmarkResumesOnTheMatchingStep() {
            compose.waitUntil(5_000) { compose.onAllNodesWithText(QUESTION).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription(READY_STEP).assertExists()
        }
    }

    /** A first launch an earlier build left at its microphone step, which the try now asks in its place. */
    @RunWith(AndroidJUnit4::class)
    class MicBookmark {
        @get:Rule(order = 0) val graph = TestGraph(savedName = "MIC")
        @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

        // That step came after the download started: it resumes on the first step, which shows the download (here the
        // choice, as nothing was chosen).
        @Test
        fun aBookmarkAtTheMicrophoneResumesOnTheFirstStep() {
            compose.waitUntil(5_000) { compose.onAllNodesWithText(QUESTION).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription(READY_STEP).assertExists()
        }
    }

    /** A first launch left at the try, with no model on the phone (the test graph's model folder is empty). */
    @RunWith(AndroidJUnit4::class)
    class TryBookmarkWithoutAModel {
        @get:Rule(order = 0) val graph = TestGraph(welcomeAt = WelcomeStep.TRY)
        @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

        // The try needs a model that makes words: without one, the first step waits for it.
        @Test
        fun theTryWithoutAModelGoesBackToTheFirstStep() {
            compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription(READY_STEP).fetchSemanticsNodes().isNotEmpty() }
            assertThat(AppGraph.settings.welcomeScreen).isEqualTo("WELCOME")
        }
    }
}

/** No take listens to a queue that only loads the first step's model. */
internal object NoQueueListener : TranscriptionQueue.Listener {
    override fun onLoading(sessionId: String) = Unit
    override fun onLoaded(sessionId: String) = Unit
    override fun onChunkDone(sessionId: String, index: Int, text: String, rawText: String) = Unit
    override fun onDone(sessionId: String, texts: List<String>, rawTexts: List<String>, speech: Boolean, language: String?) = Unit
    override fun onFailed(sessionId: String, code: Code) = Unit
}

/**
 * An engine for the first step's load only: each load takes [loadMs] and answers [status] (0: loaded), and [unloads]
 * counts its unloads; it transcribes nothing.
 */
internal class SlowEngine(private val loadMs: Long) : Engine {
    val loads = CopyOnWriteArrayList<String>()
    @Volatile var status = 0
    @Volatile var unloads = 0
    override val isAlive = true

    override suspend fun load(modelPath: String, threads: Int): Int {
        loads += modelPath
        delay(loadMs)
        return status
    }

    override suspend fun transcribe(
        wavPath: String, fromSample: Long, toSample: Long, language: String?, allowRetry: Boolean, speechCheck: Boolean, token: Long,
    ): EngineResult = error("this engine only loads")

    override fun abort(token: Long) = Unit
    override suspend fun unload() {
        unloads++
    }
}

/**
 * Puts test doubles in AppGraph for one test, then puts back what was there, so later classes see the real graph. Its
 * settings start empty with the welcome screens done, so MainActivity opens on the tabs; [welcomeAt] instead leaves them
 * unfinished at that step, as a first launch left midway, [oldIndex] at an earlier build's index for one, and
 * [savedName] at a step name an earlier order kept.
 */
class TestGraph(private val welcomeAt: WelcomeStep? = null, private val oldIndex: Int? = null, private val savedName: String? = null) : ExternalResource() {
    private val app = InstrumentationRegistry.getInstrumentation().targetContext
    val history = HistoryDb(app, null)
    val calls = CopyOnWriteArrayList<String>()   // DictationPorts calls as "name(args)"
    private var saved: List<Any>? = null

    override fun before() {
        saved = runCatching { listOf(AppGraph.controller, AppGraph.history, AppGraph.modelStore, AppGraph.settings) }.getOrNull()
        val ports = Proxy.newProxyInstance(DictationPorts::class.java.classLoader, arrayOf(DictationPorts::class.java)) { _, method, args ->
            calls += "${method.name}(${args.orEmpty().joinToString()})"
            null
        } as DictationPorts
        AppGraph.controller = DictationController(ports, { 0L })
        AppGraph.history = history
        AppGraph.modelStore = ModelStore(File(app.cacheDir, "no-models"))
        ModelDownloads.refresh() // the shared download state reads the empty folder, not what an earlier class left
        welcomeLoaded = null // and no model the welcome loaded in an earlier test
        val prefs = app.getSharedPreferences("test-settings", Context.MODE_PRIVATE).apply { edit().clear().commit() }
        AppGraph.settings = Settings(prefs).apply {
            welcomeDone = welcomeAt == null && oldIndex == null && savedName == null
            welcomeScreen = welcomeAt?.name ?: savedName
        }
        oldIndex?.let { prefs.edit().putInt("welcome_step", it).commit() }
    }

    override fun after() {
        saved?.let { (controller, history, models, settings) ->
            AppGraph.controller = controller as DictationController
            AppGraph.history = history as HistoryDb
            AppGraph.modelStore = models as ModelStore
            AppGraph.settings = settings as Settings
        }
        ModelDownloads.refresh()
        history.close()
    }
}
