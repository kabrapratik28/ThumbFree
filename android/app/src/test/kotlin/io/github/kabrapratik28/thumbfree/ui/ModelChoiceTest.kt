package io.github.kabrapratik28.thumbfree.ui

import com.google.common.truth.Truth.assertThat
import io.github.kabrapratik28.thumbfree.core.models.Catalog
import io.github.kabrapratik28.thumbfree.core.models.ModelStatus
import io.github.kabrapratik28.thumbfree.models.DownloadState
import io.github.kabrapratik28.thumbfree.models.FailReason
import org.junit.Test

// What Settings > Speech model and the welcome screen show and offer.
class ModelChoiceTest {
    // "See supported languages" names the multilingual model's languages from the catalog, in the catalog's order.
    @Test
    fun multilingualLanguagesByName() {
        assertThat(languageNames(Catalog.PARAKEET_TDT_V3_Q8)).containsExactly(
            "Bulgarian", "Croatian", "Czech", "Danish", "Dutch", "English", "Estonian", "Finnish", "French", "German", "Greek",
            "Hungarian", "Italian", "Latvian", "Lithuanian", "Maltese", "Polish", "Portuguese", "Romanian", "Russian", "Slovak",
            "Slovenian", "Spanish", "Swedish", "Ukrainian",
        ).inOrder()
        assertThat(languageNames(Catalog.PARAKEET_UNIFIED_Q8)).containsExactly("English")
    }

    // The welcome screen offers the recommended English model, or the multilingual one once it is the choice.
    @Test
    fun welcomeOffersEnglishUnlessMultilingualIsChosen() {
        assertThat(welcomeModel(Catalog.PARAKEET_UNIFIED_Q8)).isEqualTo(RECOMMENDED_MODEL)
        assertThat(welcomeModel(Catalog.PARAKEET_TDT_V3_Q8)).isEqualTo(Catalog.PARAKEET_TDT_V3_Q8)
        assertThat(welcomeModel(Catalog.CANARY_180M_FLASH_Q8)).isEqualTo(RECOMMENDED_MODEL)
        assertThat(RECOMMENDED_MODEL).isEqualTo(Catalog.PARAKEET_UNIFIED_Q8)
    }

    // A switch keeps a status only where one is known: the chosen model's own, or Verified for an offered one. The
    // multilingual model chosen before its download is checked again, never shown as the old model's Ready.
    @Test
    fun aSwitchNeverKeepsTheOldModelsStatus() {
        val setup = SetupState(true, true, ModelStatus.VERIFIED, offered = listOf(Catalog.PARAKEET_UNIFIED_Q8, Catalog.CANARY_180M_FLASH_Q8))

        assertThat(setup.choosing(Catalog.PARAKEET_TDT_V3_Q8)).isEqualTo(setup.copy(chosen = Catalog.PARAKEET_TDT_V3_Q8, model = null))
        assertThat(setup.choosing(Catalog.CANARY_180M_FLASH_Q8).model).isEqualTo(ModelStatus.VERIFIED)
        assertThat(setup.copy(model = ModelStatus.CORRUPT).choosing(Catalog.PARAKEET_UNIFIED_Q8).model).isEqualTo(ModelStatus.CORRUPT)
    }

    // Get started downloads the model the welcome screen shows, English or multilingual, and never one that is on the
    // phone already or under way, which a second start would begin again. A failed download starts again.
    @Test
    fun getStartedDownloadsTheShownModelOnlyWhenNeeded() {
        val english = Catalog.PARAKEET_UNIFIED_Q8
        val multilingual = Catalog.PARAKEET_TDT_V3_Q8
        val nothing = SetupState(true, true, ModelStatus.MISSING)

        assertThat(welcomeDownload(welcomeModel(english), nothing, DownloadState.NotDownloaded)).isEqualTo(english)
        assertThat(welcomeDownload(welcomeModel(multilingual), nothing.choosing(multilingual), null)).isEqualTo(multilingual)
        assertThat(welcomeDownload(english, null, DownloadState.Failed(FailReason.INTERRUPTED))).isEqualTo(english)
        // Only the shown model's own state counts: the multilingual model on the phone doesn't make English ready.
        assertThat(welcomeDownload(english, nothing.copy(offered = listOf(multilingual)), null)).isEqualTo(english)

        assertThat(welcomeDownload(english, nothing, DownloadState.Ready)).isNull()
        assertThat(welcomeDownload(english, nothing.copy(offered = listOf(english)), null)).isNull()
        for (running in listOf(DownloadState.Queued(wifiOnly = true), DownloadState.Downloading(1, 2), DownloadState.Verifying)) {
            assertThat(welcomeDownload(english, nothing, running)).isNull()
        }
    }
}
