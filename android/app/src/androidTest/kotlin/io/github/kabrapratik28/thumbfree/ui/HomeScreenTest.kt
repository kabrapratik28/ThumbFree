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
import android.view.WindowInsets
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.github.kabrapratik28.thumbfree.a11y.DictationAccessibilityService
import io.github.kabrapratik28.thumbfree.app.AppGraph
import io.github.kabrapratik28.thumbfree.app.DictationController
import io.github.kabrapratik28.thumbfree.app.DictationPorts
import io.github.kabrapratik28.thumbfree.core.models.Catalog
import io.github.kabrapratik28.thumbfree.core.models.DownloadProgress
import io.github.kabrapratik28.thumbfree.core.models.DownloadResult
import io.github.kabrapratik28.thumbfree.core.models.Downloader
import io.github.kabrapratik28.thumbfree.core.models.ModelFile
import io.github.kabrapratik28.thumbfree.core.models.ModelStatus
import io.github.kabrapratik28.thumbfree.core.models.ModelStore
import io.github.kabrapratik28.thumbfree.core.session.BubbleStyle
import io.github.kabrapratik28.thumbfree.data.Dictation
import io.github.kabrapratik28.thumbfree.data.HistoryDb
import io.github.kabrapratik28.thumbfree.data.Retention
import io.github.kabrapratik28.thumbfree.data.Settings
import io.github.kabrapratik28.thumbfree.data.Status
import io.github.kabrapratik28.thumbfree.models.DownloadState
import io.github.kabrapratik28.thumbfree.models.DownloadWorker
import io.github.kabrapratik28.thumbfree.models.FailReason
import io.github.kabrapratik28.thumbfree.models.ModelDownloads
import io.github.kabrapratik28.thumbfree.testing.SERVICE
import io.github.kabrapratik28.thumbfree.testing.enabledServices
import io.github.kabrapratik28.thumbfree.testing.putSecure
import io.github.kabrapratik28.thumbfree.testing.secure
import io.github.kabrapratik28.thumbfree.testing.shell
import java.io.File
import java.io.RandomAccessFile
import java.lang.reflect.Proxy
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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
 * FirstLaunch starts it with the welcome screens left at their last step, and FirstRun at their first.
 */
/** The Try tab's one-time Dictionary card. */
private const val HINT = "Add names ThumbFree should spell your way: family, friends, work terms."

/** Each welcome step's one sentence, and what its picture tells TalkBack. */
private const val WELCOME_LINE = "Tap the bubble in any app, speak, and your words appear. Everything stays on your phone."
private const val MIC_LINE = "The mic is on only while the bubble is red."
private const val SERVICE_LINE =
    "ThumbFree uses Android accessibility only to show its bubble and type your words. It reads only the field you're typing in, and nothing leaves your phone."
private const val WELCOME_PICTURE =
    "Picture: the bubble beside a message box is tapped, turns red while it listens, and the words appear in the box."
private const val MIC_PICTURE =
    "Picture of Android's microphone question, with its answers While using the app, Only this time and Don't allow. Choose While using the app."
private const val WALK_PICTURE =
    "Picture of three steps in Android's settings. 1: tap ThumbFree. 2: turn on Use ThumbFree, and leave ThumbFree shortcut off. 3: tap Allow."

/** One welcome step in one state, and the words and buttons that must show whole in it. */
private class FitCase(val name: String, val step: WelcomeStep, val setup: SetupState, val refusal: MicRefusal?, val texts: List<String>)

private val FIT_CASES = listOf(
    FitCase(
        "welcome", WelcomeStep.WELCOME, SetupState(false, false, ModelStatus.MISSING), null,
        listOf("Talk instead of typing", WELCOME_LINE, "English model · 731 MB · on Wi-Fi", "Other language?", "Get started"),
    ),
    FitCase(
        "welcome, multilingual", WelcomeStep.WELCOME, SetupState(false, false, ModelStatus.MISSING, chosen = Catalog.PARAKEET_TDT_V3_Q8), null,
        listOf("Talk instead of typing", WELCOME_LINE, "Multilingual model · 740 MB · on Wi-Fi", "Use English", "Get started"),
    ),
    FitCase(
        "microphone", WelcomeStep.MIC, SetupState(false, false, null), null,
        listOf("Let ThumbFree hear you", MIC_LINE, "Allow microphone", "Not now"),
    ),
    FitCase(
        "microphone, denied", WelcomeStep.MIC, SetupState(false, false, null), MicRefusal.DENIED,
        listOf("Let ThumbFree hear you", MIC_LINE, "Dictation needs the mic. You can allow it later too.", "Allow microphone", "Not now"),
    ),
    FitCase(
        "microphone, blocked", WelcomeStep.MIC, SetupState(false, false, null), MicRefusal.BLOCKED,
        listOf("Let ThumbFree hear you", MIC_LINE, "The mic is blocked. Allow it in App info.", "Open app settings", "Not now"),
    ),
    FitCase(
        "microphone, allowed", WelcomeStep.MIC, SetupState(true, false, null), null,
        listOf("Let ThumbFree hear you", MIC_LINE, "Microphone allowed", "Continue"),
    ),
    FitCase(
        "bubble", WelcomeStep.SERVICE, SetupState(true, false, null), null,
        listOf("Turn on the bubble", SERVICE_LINE, "Agree and open settings", "Not now"),
    ),
    FitCase(
        "bubble, on", WelcomeStep.SERVICE, SetupState(true, true, null), null,
        listOf("Turn on the bubble", "The bubble is on", "Continue"),
    ),
)

@RunWith(Enclosed::class)
class HomeScreenTest {
    @RunWith(AndroidJUnit4::class)
    class Screens {
        @get:Rule val compose = createComposeRule()
        private val fixed = CopyOnWriteArrayList<SetupItem>()

        @Test
        fun setupCardShowsWhatIsMissing() {
            compose.setContent { AppTheme { TryScreen(SetupState(false, false, ModelStatus.MISSING), { fixed += it }) } }

            compose.onNodeWithText("Finish setup").assertIsDisplayed()
            compose.onNodeWithText("0 of 3 done").assertIsDisplayed()
            compose.onNodeWithText("English speech model").assertIsDisplayed()
            compose.onNodeWithText("Needed to hear you").assertIsDisplayed()
            compose.onNodeWithText("Off in Accessibility settings").assertIsDisplayed()
            compose.onNodeWithText("Not downloaded · 731 MB").assertIsDisplayed()
            compose.onNodeWithText("Turn on").performClick()
            compose.onNodeWithText("Allow").performClick()
            compose.onNodeWithText("Get it").performClick()
            compose.runOnIdle { assertThat(fixed).containsExactly(SetupItem.SERVICE, SetupItem.MIC, SetupItem.MODEL).inOrder() }
        }

        // Once a take can run, the card folds into one line.
        @Test
        fun readySetupIsOneLine() {
            compose.setContent { AppTheme { TryScreen(SetupState(true, true, ModelStatus.VERIFIED), {}) } }

            compose.onNodeWithText("Ready · works offline").assertIsDisplayed()
            compose.onNodeWithText("Finish setup").assertDoesNotExist()
            compose.onNode(hasText("Try it here") and hasSetTextAction()).assertExists()
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

        // The Try tab's one-time card: Open Dictionary goes there and retires it, and so does Not now.
        @Test
        fun tryHintOpensTheDictionaryOrRetires() {
            val calls = CopyOnWriteArrayList<String>()
            compose.setContent {
                AppTheme {
                    TryScreen(
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

        // Settings > Bubble: a size tile, the transparency slider (TalkBack hears "50 percent", not the thumb's place in the
        // range), and Reset to recommended, which is off while the style is the recommended one.
        @Test
        fun bubbleSectionSetsSizeOpacityAndResets() {
            val picked = CopyOnWriteArrayList<BubbleStyle>()
            var style by mutableStateOf(BubbleStyle.RECOMMENDED)
            compose.setContent {
                AppTheme { settings(SetupState(true, true, ModelStatus.VERIFIED), bubble = style, onBubble = { picked += it; style = it }) }
            }
            val slider = compose.onNodeWithContentDescription("Transparency while idle")
            slider.performScrollTo().assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "50 percent"))
            compose.onNode(hasText("Large\nRecommended") and hasClickAction()).assertIsSelected()
            compose.onNodeWithText("50% is recommended", substring = true).assertExists()
            compose.onNodeWithText("Reset to recommended").performScrollTo().assertIsNotEnabled()

            compose.onNodeWithText("Small").performScrollTo().performClick()
            slider.performSemanticsAction(SemanticsActions.SetProgress) { it(40f) } // 40% transparent is 60% opaque
            slider.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "40 percent"))
            compose.onNodeWithText("40%").assertExists()
            compose.onNodeWithText("Reset to recommended").performScrollTo().assertIsEnabled().performClick()

            compose.runOnIdle {
                assertThat(picked).containsExactly(
                    BubbleStyle(BubbleStyle.Size.SMALL, 50), BubbleStyle(BubbleStyle.Size.SMALL, 60), BubbleStyle.RECOMMENDED,
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

        @Test
        fun historyRetentionLineAndEmptyState() {
            var changed = 0
            var tried = 0
            compose.setContent {
                AppTheme { history(emptyList(), retention = Retention(maxDays = 30, maxTakes = 1_000), onRetention = { changed++ }, onTry = { tried++ }) }
            }

            compose.onNodeWithText("Keeps takes for 30 days, up to 1,000").assertIsDisplayed() // no size for an empty history
            compose.onNodeWithText("No takes yet").assertIsDisplayed()
            compose.onNodeWithText("Clear all").assertDoesNotExist()
            compose.onNodeWithText("Change").performClick()
            compose.onNode(hasText("Try it") and hasClickAction()).performClick()
            compose.runOnIdle { assertThat(changed to tried).isEqualTo(1 to 1) }
        }

        // The first screen: a short title, one sentence, and a chip naming the speech model Get started downloads (one
        // recommended model under a plain name, no model names). Get started goes on at once.
        @Test
        fun welcomeSaysWhatGetStartedDownloads() {
            val calls = CopyOnWriteArrayList<String>()
            compose.setContent {
                AppTheme { WelcomeScreen(WelcomeStep.WELCOME, SetupState(false, false, ModelStatus.MISSING), null, false, actions(calls), DownloadState.NotDownloaded) }
            }

            compose.onNodeWithContentDescription("Step 1 of 3").assertExists()
            compose.onNodeWithText("Talk instead of typing").assertIsDisplayed()
            compose.onNodeWithText(WELCOME_LINE).assertIsDisplayed()
            compose.onNodeWithText("English model · 731 MB · on Wi-Fi").assertIsDisplayed()
            compose.onNodeWithText("Parakeet", substring = true).assertDoesNotExist()
            compose.onNodeWithText("Get started").performClick()
            compose.runOnIdle { assertThat(calls).containsExactly("getStarted") }
        }

        // "Other language?" switches to the multilingual model before Get started, and "Use English" switches back. A
        // switch cancels the shown model's download first, in case one runs.
        @Test
        fun welcomeSwitchesToTheMultilingualModel() {
            val calls = CopyOnWriteArrayList<String>()
            var chosen by mutableStateOf(Catalog.PARAKEET_UNIFIED_Q8)
            compose.setContent {
                AppTheme {
                    WelcomeScreen(
                        WelcomeStep.WELCOME, SetupState(false, false, ModelStatus.MISSING, chosen = chosen), null, false,
                        actions(calls), DownloadState.NotDownloaded, model = welcomeModel(chosen),
                    )
                }
            }

            compose.onNodeWithText("Other language?").performClick()
            compose.runOnIdle { assertThat(calls).containsExactly("cancelModel", "choose parakeet-tdt-0.6b-v3-Q8_0.gguf").inOrder() }
            chosen = Catalog.PARAKEET_TDT_V3_Q8
            compose.onNodeWithText("Multilingual model · 740 MB · on Wi-Fi").assertIsDisplayed()
            compose.onNodeWithText("Use English").performClick()
            compose.onNodeWithText("Get started").performClick()
            compose.runOnIdle {
                assertThat(calls).containsExactly(
                    "cancelModel", "choose parakeet-tdt-0.6b-v3-Q8_0.gguf",
                    "cancelModel", "choose parakeet-unified-en-0.6b-Q8_0.gguf", "getStarted",
                ).inOrder()
            }
        }

        // Back on the first screen while the model downloads (Get started, then Back): the switch is still there, and
        // cancels that download before it chooses the other model.
        @Test
        fun welcomeSwitchCancelsARunningDownload() {
            val calls = CopyOnWriteArrayList<String>()
            compose.setContent {
                AppTheme {
                    WelcomeScreen(
                        WelcomeStep.WELCOME, SetupState(true, false, ModelStatus.MISSING), null, false, actions(calls),
                        DownloadState.Downloading(307_000_000, 731_357_568),
                    )
                }
            }

            compose.onNodeWithText("Other language?").performClick()
            compose.runOnIdle { assertThat(calls).containsExactly("cancelModel", "choose parakeet-tdt-0.6b-v3-Q8_0.gguf").inOrder() }
        }

        // The chip follows the Wi-Fi setting (Settings > Download or delete models): with it off, no Wi-Fi promise.
        @Test
        fun welcomeChipFollowsWifiOnly() {
            compose.setContent {
                AppTheme {
                    WelcomeScreen(
                        WelcomeStep.WELCOME, SetupState(false, false, ModelStatus.MISSING), null, false,
                        actions(CopyOnWriteArrayList()), DownloadState.NotDownloaded, wifiOnly = false,
                    )
                }
            }

            compose.onNodeWithText("English model · 731 MB").assertIsDisplayed()
        }

        // The chip is the shown model's own state. A ready multilingual model keeps the way back, a ready English model
        // keeps the other language, and a switch from a ready model cancels nothing.
        @Test
        fun welcomeChipOnceReady() {
            val calls = CopyOnWriteArrayList<String>()
            var chosen by mutableStateOf(Catalog.PARAKEET_TDT_V3_Q8)
            compose.setContent {
                AppTheme {
                    WelcomeScreen(
                        WelcomeStep.WELCOME, SetupState(true, true, ModelStatus.VERIFIED, chosen = chosen, offered = listOf(chosen)),
                        null, false, actions(calls), DownloadState.Ready, model = welcomeModel(chosen),
                    )
                }
            }

            compose.onNodeWithText("Multilingual model · ready").assertIsDisplayed()
            compose.onNodeWithText("Use English").performClick()
            chosen = Catalog.PARAKEET_UNIFIED_Q8
            compose.onNodeWithText("English model · ready").assertIsDisplayed()
            compose.onNodeWithText("Other language?").assertIsDisplayed()
            compose.runOnIdle { assertThat(calls).containsExactly("choose parakeet-unified-en-0.6b-Q8_0.gguf") }
        }

        // With Canary chosen (and on the phone), the screen shows the English model and English's own state.
        @Test
        fun welcomeWithCanaryChosenShowsEnglishAndItsState() {
            compose.setContent {
                AppTheme {
                    WelcomeScreen(
                        WelcomeStep.WELCOME,
                        SetupState(
                            true, true, ModelStatus.VERIFIED, chosen = Catalog.CANARY_180M_FLASH_Q8,
                            offered = listOf(Catalog.CANARY_180M_FLASH_Q8),
                        ),
                        null, false, actions(CopyOnWriteArrayList()), DownloadState.NotDownloaded,
                        model = welcomeModel(Catalog.CANARY_180M_FLASH_Q8),
                    )
                }
            }

            compose.onNodeWithText("English model · 731 MB · on Wi-Fi").assertIsDisplayed()
            compose.onNodeWithText("English model · ready").assertDoesNotExist()
        }

        // While an earlier download action still runs (busy), the switch waits, so no tap can land on a model a pending
        // switch is leaving; Get started still goes on (MainActivity starts the model the screen shows by then).
        @Test
        fun welcomeSwitchWaitsWhileBusy() {
            compose.setContent {
                AppTheme {
                    WelcomeScreen(
                        WelcomeStep.WELCOME, SetupState(false, false, ModelStatus.MISSING), null, false,
                        actions(CopyOnWriteArrayList()), DownloadState.NotDownloaded, busy = true,
                    )
                }
            }

            compose.onNodeWithText("Other language?").assertIsNotEnabled()
            compose.onNodeWithText("Get started").assertIsEnabled()
        }

        // The microphone step asks until it is granted; after "don't ask again" it sends the user to App info. One short
        // sentence, and nothing about notifications.
        @Test
        fun micStepFollowsTheAnswer() {
            val calls = CopyOnWriteArrayList<String>()
            var refusal by mutableStateOf<MicRefusal?>(null)
            var granted by mutableStateOf(false)
            compose.setContent {
                AppTheme { WelcomeScreen(WelcomeStep.MIC, SetupState(granted, false, null), refusal, false, actions(calls)) }
            }

            compose.onNodeWithText("Let ThumbFree hear you").assertIsDisplayed()
            compose.onNodeWithText(MIC_LINE).assertIsDisplayed()
            compose.onNodeWithText("notification", substring = true, ignoreCase = true).assertDoesNotExist()
            compose.onNodeWithText("Allow microphone").performClick()
            refusal = MicRefusal.DENIED
            compose.onNodeWithText("Dictation needs the mic. You can allow it later too.").assertIsDisplayed()
            refusal = MicRefusal.BLOCKED
            compose.onNodeWithText("The mic is blocked. Allow it in App info.").assertIsDisplayed()
            compose.onNodeWithText("Open app settings").performClick()
            granted = true
            compose.onNodeWithText("Microphone allowed").assertIsDisplayed()
            compose.onNodeWithText("Continue").performClick()
            compose.onNodeWithContentDescription("Step 2 of 3").assertExists()
            compose.runOnIdle { assertThat(calls).containsExactly("allowMic", "openAppSettings", "next").inOrder() }
        }

        // The accessibility step is the prominent disclosure: one sentence says what the service accesses and why, before
        // the user agrees and leaves for the setting. Not now goes on (to the Try tab: it is the last step).
        @Test
        fun serviceStepDisclosesThenOpensSettings() {
            val calls = CopyOnWriteArrayList<String>()
            compose.setContent {
                AppTheme { WelcomeScreen(WelcomeStep.SERVICE, SetupState(true, false, null), null, false, actions(calls)) }
            }

            compose.onNodeWithContentDescription("Step 3 of 3").assertExists()
            compose.onNodeWithText("Turn on the bubble").assertIsDisplayed()
            compose.onNodeWithText(SERVICE_LINE).assertIsDisplayed()
            compose.onNodeWithText("Agree and open settings").performClick()
            compose.onNodeWithText("Not now").performClick()
            compose.runOnIdle { assertThat(calls).containsExactly("openAccessibility", "next").inOrder() }
        }

        // Back from settings with the switch still off, a dialog explains restricted settings, with a way to App info.
        @Test
        fun serviceStepHelpsWithRestrictedSettings() {
            val calls = CopyOnWriteArrayList<String>()
            compose.setContent {
                AppTheme { WelcomeScreen(WelcomeStep.SERVICE, SetupState(true, false, null), null, true, actions(calls)) }
            }

            compose.onNodeWithText("Switch greyed out?").assertIsDisplayed()
            compose.onNodeWithText("Open App info").performClick()
            compose.runOnIdle { assertThat(calls).containsExactly("closeHelp", "openAppSettings").inOrder() }
        }

        // Once the service is on, nothing is asked: a line says so, and Continue goes on.
        @Test
        fun serviceStepOnceOn() {
            val calls = CopyOnWriteArrayList<String>()
            compose.setContent {
                AppTheme { WelcomeScreen(WelcomeStep.SERVICE, SetupState(true, true, null), null, false, actions(calls)) }
            }

            compose.onNodeWithText("The bubble is on").assertIsDisplayed()
            compose.onNodeWithText(SERVICE_LINE).assertDoesNotExist()
            compose.onNodeWithText("Not now").assertDoesNotExist()
            compose.onNodeWithText("Continue").performClick()
            compose.runOnIdle { assertThat(calls).containsExactly("next") }
        }

        // Each step's picture tells TalkBack what it shows.
        @Test
        fun picturesHaveDescriptions() {
            var step by mutableStateOf(WelcomeStep.WELCOME)
            compose.setContent {
                AppTheme { WelcomeScreen(step, SetupState(false, false, ModelStatus.MISSING), null, false, actions(CopyOnWriteArrayList())) }
            }

            compose.onNodeWithContentDescription(WELCOME_PICTURE).assertIsDisplayed()
            step = WelcomeStep.MIC
            compose.onNodeWithContentDescription(MIC_PICTURE).assertIsDisplayed()
            step = WelcomeStep.SERVICE
            compose.onNodeWithContentDescription(WALK_PICTURE).assertIsDisplayed()
        }

        // With animations off, the walkthrough shows its three frames one under another; animated, one at a time.
        @Test
        fun stillWalkthroughShowsEveryFrame() {
            var still by mutableStateOf(false)
            compose.setContent { AppTheme { WalkthroughPicture(still) } }

            val one = compose.onNodeWithContentDescription(WALK_PICTURE).getBoundsInRoot().height
            still = true
            val three = compose.onNodeWithContentDescription(WALK_PICTURE).getBoundsInRoot().height
            assertThat(three).isGreaterThan(one * 2.5f)
        }

        // No step ever scrolls. On a 1080 x 2400 phone (411 x 914 dp) at 100% and 200% font, in every state, the page has
        // no scroll, and its title, its sentence and every button are whole and inside the screen. The picture takes
        // what is left, and at 200% it is smaller or gone.
        @Test
        fun stepsFitWithoutScrollingAtAnyFontSize() {
            var scale by mutableStateOf(1f)
            var case by mutableStateOf(FIT_CASES.first())
            compose.setContent {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, scale)) {
                    AppTheme {
                        Box(Modifier.requiredSize(411.dp, 914.dp).testTag("phone")) {
                            WelcomeScreen(
                                case.step, case.setup, case.refusal, false, actions(CopyOnWriteArrayList()),
                                DownloadState.NotDownloaded, model = welcomeModel(case.setup.chosen),
                            )
                        }
                    }
                }
            }

            for (fontScale in listOf(1f, 2f)) for (each in FIT_CASES) {
                scale = fontScale
                case = each
                compose.waitForIdle()
                val phone = compose.onNodeWithTag("phone").getBoundsInRoot()
                compose.onAllNodes(hasScrollAction()).assertCountEquals(0)
                for (text in each.texts) {
                    val node = compose.onNodeWithText(text)
                    val bounds = node.assertIsDisplayed().getBoundsInRoot()
                    assertWithMessage("$text at ${fontScale}x in ${each.name}").that(bounds.top).isAtLeast(phone.top)
                    assertWithMessage("$text at ${fontScale}x in ${each.name}").that(bounds.bottom).isAtMost(phone.bottom)
                    val layout = mutableListOf<TextLayoutResult>()
                    node.fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action?.invoke(layout)
                    // Lines past the bottom of the room it got: a wrapped text is never cut at the side.
                    assertWithMessage("$text cut off at ${fontScale}x in ${each.name}").that(layout.single().didOverflowHeight).isFalse()
                }
            }
        }

        // The Try card and the setup rows name the chosen model in plain words.
        @Test
        fun setupRowNamesTheChosenModel() {
            compose.setContent {
                AppTheme { TryScreen(SetupState(true, true, ModelStatus.MISSING, chosen = Catalog.PARAKEET_TDT_V3_Q8), {}) }
            }

            compose.onNodeWithText("Multilingual speech model").assertIsDisplayed()
            compose.onNodeWithText("Not downloaded · 740 MB").assertIsDisplayed()
            compose.onNodeWithText("English speech model").assertDoesNotExist()
        }

        // The Try card shows the chosen model's download on its row: the percentage with no button while it runs, Use mobile
        // data while it waits for Wi-Fi, the reason and Try again once it failed.
        @Test
        fun setupCardShowsTheDownload() {
            var download by mutableStateOf<DownloadState>(DownloadState.Downloading(365_678_784, 731_357_568))
            compose.setContent { AppTheme { TryScreen(SetupState(true, true, ModelStatus.MISSING), { fixed += it }, download) } }

            compose.onNodeWithText("Downloading · 50%").assertIsDisplayed()
            compose.onNodeWithText("Get it").assertDoesNotExist()
            download = DownloadState.Queued(wifiOnly = true)
            compose.onNodeWithText("Waiting for Wi-Fi").assertIsDisplayed()
            compose.onNodeWithText("Use mobile data").performClick() // MainActivity starts it without Wi-Fi only
            download = DownloadState.Failed(FailReason.NOT_ENOUGH_SPACE)
            compose.onNodeWithText("Not enough space").assertIsDisplayed()
            compose.onNodeWithText("Try again").performClick()
            compose.runOnIdle { assertThat(fixed).containsExactly(SetupItem.MODEL, SetupItem.MODEL) }
        }

        private fun actions(calls: MutableList<String>) = WelcomeActions(
            next = { calls += "next" }, back = { calls += "back" }, close = null,
            getStarted = { calls += "getStarted" }, cancelModel = { calls += "cancelModel" },
            chooseModel = { calls += "choose ${it.fileName}" },
            allowMic = { calls += "allowMic" }, openAppSettings = { calls += "openAppSettings" },
            openAccessibility = { calls += "openAccessibility" }, closeHelp = { calls += "closeHelp" },
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
            onClearAll: () -> Unit = {}, onRetention: () -> Unit = {}, onTry: () -> Unit = {},
        ) = HistoryScreen(rows, emptyMap(), retention, 0, {}, {}, {}, onClearAll, onRetention, onTry)

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
            waitForText("Try it")
            val top = compose.onNodeWithText("Try it").getBoundsInRoot().top
            val inset = compose.runOnIdle {
                compose.activity.window.decorView.rootWindowInsets.getInsets(WindowInsets.Type.statusBars()).top
            }
            assertThat(inset).isGreaterThan(0)
            assertThat(with(compose.density) { top.toPx() }).isAtLeast(inset.toFloat())
        }

        // Four tabs, Try first, and no others.
        @Test
        fun fourTabsTryFirst() {
            waitForText("Try it here")
            for (tab in listOf("Try", "History", "Dictionary", "Settings")) compose.onNode(hasText(tab) and hasClickAction()).assertExists()
            compose.onNode(hasText("Try") and hasClickAction()).assertIsSelected()
            tab("Settings")
            compose.onNode(hasText("Settings") and hasClickAction()).assertIsSelected()
        }

        // The Dictionary card: at the top of Try on the next visit after a take that typed text, not in the visit it
        // came in; Not now puts it away for good.
        @Test
        fun dictionaryHintShowsOnTheNextVisit() {
            waitForText("Try it here")
            row("typed", startedAt = 1, Status.INSERTED, text = "Hello there")
            compose.onNodeWithText(HINT).assertDoesNotExist()
            resume()
            waitForText(HINT)
            compose.onNodeWithText("Not now").performClick()

            compose.onNodeWithText(HINT).assertDoesNotExist()
            assertThat(AppGraph.settings.dictionaryHintDone).isTrue()
            resume()
            waitForText("Try it here")
            compose.onNodeWithText(HINT).assertDoesNotExist()
        }

        // At most once ever: left alone, it is gone after the visit and never comes back.
        @Test
        fun dictionaryHintNeverShowsTwice() {
            row("typed", startedAt = 1, Status.INSERTED, text = "Hello there")
            resume()
            waitForText(HINT)
            resume()
            waitForText("Try it here")
            compose.onNodeWithText(HINT).assertDoesNotExist()
        }

        // Only a take that typed text leads to it (not one with no speech, not a failed one), and never once the Dictionary
        // was opened.
        @Test
        fun dictionaryHintNeedsATypedTakeAndAnUnopenedDictionary() {
            row("quiet", startedAt = 1, Status.NO_SPEECH)
            row("failed", startedAt = 2, Status.FAILED, text = "Partial words")
            resume()
            waitForText("Try it here")
            compose.onNodeWithText(HINT).assertDoesNotExist()

            tab("Dictionary")
            waitForText("No words yet")
            row("typed", startedAt = 3, Status.INSERTED, text = "Hello there")
            resume()
            tab("Try")
            waitForText("Try it here")
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

        // When the microphone goes missing later (an "Only this time" grant ends when the app leaves the screen), the Try
        // card asks again: its Allow brings back Android's question. Runs only while the permission is off: revoke it
        // with adb first, since revoking it from here would end this process. It answers While using the app, so the
        // grant is back on afterwards.
        @Test
        fun tryCardAsksForTheMicAgain() {
            assumeTrue("the mic is allowed here", app.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            waitForText("Needed to hear you")
            compose.onNode(hasText("Allow") and hasClickAction()).performClick()

            val answer = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).wait(Until.findObject(By.text("While using the app")), 5_000)
            assertThat(answer).isNotNull()
            answer.click()
            compose.waitUntil(5_000) { app.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED }
        }

        @Test
        fun practiceFieldPresent() {
            waitForText("Try it here")
            compose.onNode(hasText("Try it here") and hasSetTextAction()).assertExists()
        }

        // Settings > Setup opens the welcome screens again, and Close returns to Settings.
        @Test
        fun welcomeReopensFromSettings() {
            resume()
            tab("Settings")
            compose.onNodeWithText("Show the welcome screens").performScrollTo().performClick()
            waitForText("Get started")
            compose.onNodeWithContentDescription("Close").performClick()
            waitForText("Show the welcome screens")
            assertThat(AppGraph.settings.welcomeDone).isTrue()
        }

        // The Dictation bubble row's Turn on, from the Try tab and from Settings > Setup: the disclosure first (Google
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
                waitForText("Try it here")

                // Once from the Try tab's setup card.
                compose.onNodeWithText("Turn on").performClick()
                waitForText(SERVICE_LINE)
                compose.onNodeWithText("Not now").performClick()
                waitForText("Try it here")

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
        // Try, since discloseOnly's whole visit was the disclosure (see finishWelcome). The settings screen is stopped
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

                waitForText("Show the welcome screens") // Settings' own content, not Try's
                compose.onNode(hasText("Settings") and hasClickAction()).assertIsSelected()
                compose.onNodeWithText("Switch greyed out?").assertDoesNotExist()
            } finally {
                putSecure("enabled_accessibility_services", before)
                instrumentation.removeMonitor(monitor)
            }
        }

        // Back with the service still off, from the Try tab's Turn on: the restricted-settings help explains why the
        // switch looks grey, same as the first run's analogous step, and the disclosure is still the screen underneath
        // it, not Try.
        @Test
        fun turnOnFromTryReturningWithTheServiceOffHelpsWithRestrictedSettings() {
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
                waitForText("Try it here")
                compose.onNodeWithText("Turn on").performClick()
                waitForText(SERVICE_LINE)

                compose.onNodeWithText("Agree and open settings").performClick()

                waitForText("Switch greyed out?")
                compose.onNodeWithText(SERVICE_LINE).assertIsDisplayed() // still the disclosure, not back at Try
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

        // It is the last step: finishing it opens the Try tab, and the welcome screens never open by themselves again.
        @Test
        fun welcomeResumesThenOpensTry() {
            waitForText("Turn on the bubble")
            compose.onNode(hasText("Not now") or hasText("Continue")).performClick() // Continue if this device has it on

            waitForText("Try it here")
            compose.onNode(hasText("Try") and hasClickAction()).assertIsSelected()
            assertThat(AppGraph.settings.welcomeDone).isTrue()
        }

        // A rotation keeps the step on screen.
        @Test
        fun welcomeStepSurvivesRotation() {
            waitForText("Turn on the bubble")
            compose.activityRule.scenario.recreate()
            waitForText("Turn on the bubble")
            compose.onNodeWithText("Try it here").assertDoesNotExist()
        }

        // Agree opens Android's accessibility list, told to scroll to the service. The list is stopped here, so the test
        // never leaves the app.
        @Test
        fun agreeOpensTheListScrolledToTheService() {
            waitForText("Turn on the bubble")
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
            waitForText("Turn on the bubble")
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
                compose.onNodeWithText("Turn on the bubble").assertExists() // no crash, still on the step
            } finally {
                instrumentation.removeMonitor(monitor)
            }
        }

        // Back from Android's settings with the service on, the last step is done: the Try tab opens by itself, with no
        // help dialog. The settings screen is stopped here, and Android delivers that answer with a pause and a resume,
        // which is the return; so the switch is set first, as the user's tap there would, and put back after.
        @Test
        fun returningWithTheServiceOnOpensTry() {
            waitForText("Turn on the bubble")
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

                waitForText("Try it here")
                compose.onNode(hasText("Try") and hasClickAction()).assertIsSelected()
                compose.onNodeWithText("Switch greyed out?").assertDoesNotExist()
                assertThat(AppGraph.settings.welcomeDone).isTrue()
            } finally {
                putSecure("enabled_accessibility_services", before)
                instrumentation.removeMonitor(monitor)
            }
        }

        private fun waitForText(text: String) =
            compose.waitUntil(5_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }

        private companion object {
            const val LIST = android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS
            const val APP_INFO = android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS
        }
    }

    /**
     * A first launch from the start. Downloads run through the real worker with a downloader that only waits for its
     * cancel: no network, and nothing written to the models folder.
     */
    @RunWith(AndroidJUnit4::class)
    class FirstRun {
        @get:Rule(order = 0) val graph = TestGraph(welcomeAt = WelcomeStep.WELCOME)
        @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()
        private val app = InstrumentationRegistry.getInstrumentation().targetContext
        private val started = CopyOnWriteArrayList<String>()
        private val realDownloader = DownloadWorker.downloaderFactory

        @Before
        fun fakeDownloads() {
            DownloadWorker.downloaderFactory = { dir ->
                object : Downloader(dir, usableSpace = { Long.MAX_VALUE }) {
                    override fun download(
                        model: ModelFile, url: String, isCancelled: () -> Boolean, onProgress: (DownloadProgress) -> Unit,
                    ): DownloadResult {
                        started += model.fileName
                        while (!isCancelled()) Thread.sleep(50)
                        return DownloadResult.Failed(DownloadResult.Reason.CANCELLED, "cancelled")
                    }
                }
            }
        }

        @After
        fun realDownloads() {
            for (model in listOf(Catalog.PARAKEET_UNIFIED_Q8, Catalog.PARAKEET_TDT_V3_Q8)) ModelDownloads.cancel(app, model)
            DownloadWorker.downloaderFactory = realDownloader
        }

        // Get started starts the English model's download at once, and the multilingual one's once it is the choice; the
        // download reaching the downloader means its foreground service started, with no notification permission. The
        // steps then go on to the Try tab.
        @Test
        fun getStartedDownloadsTheChosenModelThenTryOpens() {
            waitForText("Talk instead of typing")
            compose.onNodeWithText("Get started").performClick()
            waitForText("Let ThumbFree hear you")
            compose.waitUntil(20_000) { Catalog.PARAKEET_UNIFIED_Q8.fileName in started }
            ModelDownloads.cancel(app, Catalog.PARAKEET_UNIFIED_Q8)

            compose.onNodeWithContentDescription("Back").performClick()
            compose.onNodeWithText("Other language?").performClick()
            waitForText("Multilingual model · 740 MB · on Wi-Fi")
            compose.onNodeWithText("Get started").performClick()
            compose.waitUntil(20_000) { Catalog.PARAKEET_TDT_V3_Q8.fileName in started }
            assertThat(AppGraph.settings.model).isEqualTo(Catalog.PARAKEET_TDT_V3_Q8)
            assertThat(app.checkSelfPermission("android.permission.POST_NOTIFICATIONS")).isEqualTo(PackageManager.PERMISSION_DENIED)

            waitForText("Let ThumbFree hear you")
            compose.onNode(hasText("Continue") or hasText("Not now")).performClick() // Continue where the mic is allowed
            waitForText("Turn on the bubble")
            compose.onNode(hasText("Continue") or hasText("Not now")).performClick()
            waitForText("Try it here")
            assertThat(AppGraph.settings.welcomeDone).isTrue()
        }

        private fun waitForText(text: String) =
            compose.waitUntil(5_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }
}

/**
 * Puts test doubles in AppGraph for one test, then puts back what was there, so later classes see the real graph. Its
 * settings start empty with the welcome screens done, so MainActivity opens on the tabs; [welcomeAt] instead leaves them
 * unfinished at that step, as a first launch left midway.
 */
class TestGraph(private val welcomeAt: WelcomeStep? = null) : ExternalResource() {
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
        val prefs = app.getSharedPreferences("test-settings", Context.MODE_PRIVATE).apply { edit().clear().commit() }
        AppGraph.settings = Settings(prefs).apply {
            welcomeDone = welcomeAt == null
            welcomeStep = welcomeAt?.ordinal ?: 0
        }
    }

    override fun after() {
        saved?.let { (controller, history, models, settings) ->
            AppGraph.controller = controller as DictationController
            AppGraph.history = history as HistoryDb
            AppGraph.modelStore = models as ModelStore
            AppGraph.settings = settings as Settings
        }
        history.close()
    }
}
