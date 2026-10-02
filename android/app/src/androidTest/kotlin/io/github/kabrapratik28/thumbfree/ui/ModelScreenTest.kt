package io.github.kabrapratik28.thumbfree.ui

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import io.github.kabrapratik28.thumbfree.core.models.Catalog
import io.github.kabrapratik28.thumbfree.core.models.ModelFile
import io.github.kabrapratik28.thumbfree.models.DownloadState
import io.github.kabrapratik28.thumbfree.models.FailReason
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the stateless ModelScreen directly, the way HomeScreenTest.Checklist exercises HomeScreen. */
@RunWith(AndroidJUnit4::class)
class ModelScreenTest {
    @get:Rule val compose = createComposeRule()
    private val model = Catalog.PARAKEET_UNIFIED_Q8

    // On an unmetered network the direct tap pins the transfer to Wi-Fi too: CONNECTED would let it carry on over
    // cellular the moment the phone leaves Wi-Fi, with no ask at all.
    @Test
    fun showsSizeBeforeDownload() {
        var downloaded: Pair<ModelFile, Boolean>? = null
        compose.setContent {
            ModelScreen(
                rows = listOf(ModelRow(model, DownloadState.NotDownloaded)),
                metered = false,
                onDownload = { m, wifiOnly -> downloaded = m to wifiOnly },
                onCancel = {},
                onDelete = {},
            )
        }
        compose.onNodeWithText("731 MB").assertIsDisplayed()

        compose.onNodeWithText("Download").performClick()

        compose.runOnIdle { assertThat(downloaded).isEqualTo(model to true) }
    }

    @Test
    fun meteredAsksFirst() {
        var downloaded: Pair<ModelFile, Boolean>? = null
        compose.setContent {
            ModelScreen(
                rows = listOf(ModelRow(model, DownloadState.NotDownloaded)),
                metered = true,
                onDownload = { m, wifiOnly -> downloaded = m to wifiOnly },
                onCancel = {},
                onDelete = {},
            )
        }
        compose.onNodeWithText("Download").performClick()
        compose.onNodeWithText("Download now").assertIsDisplayed()
        compose.runOnIdle { assertThat(downloaded).isNull() }

        compose.onNodeWithText("Wait for Wi-Fi").performClick()

        compose.runOnIdle { assertThat(downloaded).isEqualTo(model to true) }
    }

    // The speech models name the one takes use (In use), so the bubble's Open lands on a screen that says which; another
    // one already on the phone offers Use this model, and a download with no room offers Open storage beside Try again.
    @Test
    fun namesTheModelInUseAndOffersEveryRemedy() {
        val picked = mutableListOf<ModelFile>()
        var storage = 0
        compose.setContent {
            ModelScreen(
                rows = listOf(
                    ModelRow(model, DownloadState.Failed(FailReason.NOT_ENOUGH_SPACE)), ModelRow(Catalog.PARAKEET_TDT_V3_Q8, DownloadState.Ready),
                ),
                metered = false, onDownload = { _, _ -> }, onCancel = {}, onDelete = {},
                chosen = model, onChoose = { picked += it }, onOpenStorage = { storage++ },
            )
        }

        compose.onNodeWithText("In use").assertIsDisplayed()
        compose.onNodeWithText("Try again").assertIsDisplayed()
        compose.onNodeWithText("Open storage").performClick()
        compose.onNodeWithText("Use this model").performClick()
        compose.runOnIdle {
            assertThat(picked).containsExactly(Catalog.PARAKEET_TDT_V3_Q8)
            assertThat(storage).isEqualTo(1)
        }
    }

    // Every model but the one in use offers Use this model, downloading or not yet on the phone too; the one in use never.
    @Test
    fun everyModelNotInUseOffersUseThisModel() {
        val picked = mutableListOf<ModelFile>()
        compose.setContent {
            ModelScreen(
                rows = listOf(
                    ModelRow(model, DownloadState.Downloading(310_000_000, 731_357_568)),
                    ModelRow(Catalog.PARAKEET_TDT_V3_Q8, DownloadState.NotDownloaded),
                    ModelRow(Catalog.CANARY_180M_FLASH_Q8, DownloadState.Downloading(100_000_000, 218_447_552)),
                ),
                metered = false, onDownload = { _, _ -> }, onCancel = {}, onDelete = {},
                chosen = model, onChoose = { picked += it },
            )
        }

        compose.onAllNodesWithText("Use this model").assertCountEquals(2)
        compose.onAllNodesWithText("Use this model")[0].performClick()
        compose.onAllNodesWithText("Use this model")[1].performScrollTo().performClick()
        compose.runOnIdle { assertThat(picked).containsExactly(Catalog.PARAKEET_TDT_V3_Q8, Catalog.CANARY_180M_FLASH_Q8).inOrder() }
    }

    @Test
    fun progressCapsAt100() {
        compose.setContent {
            ModelScreen(
                rows = listOf(ModelRow(model, DownloadState.Downloading(1_200, 1_000))),
                metered = false,
                onDownload = { _, _ -> },
                onCancel = {},
                onDelete = {},
            )
        }

        compose.onNodeWithText("100%").assertIsDisplayed()
    }

    @Test
    fun verifiedShowsInstalled() {
        var deleted: ModelFile? = null
        compose.setContent {
            ModelScreen(
                rows = listOf(ModelRow(model, DownloadState.Ready)),
                metered = false,
                onDownload = { _, _ -> },
                onCancel = {},
                onDelete = { deleted = it },
            )
        }
        compose.onNodeWithText("Installed").assertIsDisplayed()
        compose.onNodeWithText("Download").assertDoesNotExist()

        // Delete asks first: Cancel keeps the model, "Delete model" removes it.
        compose.onNodeWithText("Delete").performClick()
        compose.onNodeWithText("Delete this speech model?").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle { assertThat(deleted).isNull() }

        compose.onNodeWithText("Delete").performClick()
        compose.onNodeWithText("Delete model").performClick()

        compose.runOnIdle { assertThat(deleted).isEqualTo(model) }
    }

    // A Wi-Fi-only request sits ENQUEUED with no progress data for as long as the wait lasts; the row must not fall
    // back to a tappable Download in the meantime, or a second tap would need the enqueue policy just to register (see
    // DownloadWorkerTest.downloadNowReplacesWaitingWifiOnly for that half).
    @Test
    fun waitingForWifiShowsCancelNotDownload() {
        compose.setContent {
            ModelScreen(
                rows = listOf(ModelRow(model, DownloadState.Queued(wifiOnly = true))),
                metered = false,
                onDownload = { _, _ -> },
                onCancel = {},
                onDelete = {},
            )
        }
        compose.onNodeWithText("Waiting for Wi-Fi").assertIsDisplayed()
        compose.onNodeWithText("Cancel").assertIsDisplayed()
        compose.onNodeWithText("Download").assertDoesNotExist()
    }

    // A Wi-Fi wait can use mobile data now: a start without Wi-Fi only replaces the wait.
    @Test
    fun waitingForWifiOffersMobileData() {
        var downloaded: Pair<ModelFile, Boolean>? = null
        compose.setContent {
            ModelScreen(
                rows = listOf(ModelRow(model, DownloadState.Queued(wifiOnly = true))),
                metered = true,
                onDownload = { m, wifiOnly -> downloaded = m to wifiOnly },
                onCancel = {},
                onDelete = {},
            )
        }

        compose.onNodeWithText("Use mobile data").performClick()

        compose.runOnIdle { assertThat(downloaded).isEqualTo(model to false) }
    }

    // RUNNING has no progress yet before the first bytes land; same requirement.
    @Test
    fun runningWithNoProgressYetShowsCancelNotDownload() {
        compose.setContent {
            ModelScreen(
                rows = listOf(ModelRow(model, DownloadState.Downloading(0, model.sizeBytes))),
                metered = false,
                onDownload = { _, _ -> },
                onCancel = {},
                onDelete = {},
            )
        }
        compose.onNodeWithText("0%").assertIsDisplayed()
        compose.onNodeWithText("Cancel").assertIsDisplayed()
        compose.onNodeWithText("Download").assertDoesNotExist()
    }

    // The hash after the last byte takes seconds on a 731 MB file: it says so, and Cancel stays.
    @Test
    fun verifyingShowsCheckingWithCancel() {
        compose.setContent {
            ModelScreen(rows = listOf(ModelRow(model, DownloadState.Verifying)), metered = false, onDownload = { _, _ -> }, onCancel = {}, onDelete = {})
        }
        compose.onNodeWithText("Checking the file").assertIsDisplayed()
        compose.onNodeWithText("Cancel").assertIsDisplayed()
    }

    // A failure says why in plain words, and Try again goes on from the partial file.
    @Test
    fun failedShowsTheReasonAndTryAgain() {
        var downloaded: Pair<ModelFile, Boolean>? = null
        compose.setContent {
            ModelScreen(
                rows = listOf(ModelRow(model, DownloadState.Failed(FailReason.NO_INTERNET))),
                metered = false,
                onDownload = { m, wifiOnly -> downloaded = m to wifiOnly },
                onCancel = {},
                onDelete = {},
            )
        }
        compose.onNodeWithText("No internet connection").assertIsDisplayed()

        compose.onNodeWithText("Try again").performClick()

        compose.runOnIdle { assertThat(downloaded).isEqualTo(model to true) }
    }

    // With Wi-Fi only turned off, a tap on mobile data starts at once, and the row's wait names any connection.
    @Test
    fun mobileDataAllowedStartsWithoutAsking() {
        var downloaded: Pair<ModelFile, Boolean>? = null
        compose.setContent {
            ModelScreen(
                rows = listOf(ModelRow(model, DownloadState.NotDownloaded), ModelRow(Catalog.CANARY_180M_FLASH_Q8, DownloadState.Queued(wifiOnly = false))),
                metered = true,
                onDownload = { m, wifiOnly -> downloaded = m to wifiOnly },
                onCancel = {},
                onDelete = {},
                wifiOnly = false,
            )
        }
        compose.onNodeWithText("Waiting for a connection").assertIsDisplayed()

        compose.onNodeWithText("Download").performClick()

        compose.runOnIdle { assertThat(downloaded).isEqualTo(model to false) }
    }
}
