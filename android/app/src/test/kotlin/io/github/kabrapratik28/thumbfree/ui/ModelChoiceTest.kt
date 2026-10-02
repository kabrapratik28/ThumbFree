package io.github.kabrapratik28.thumbfree.ui

import com.google.common.truth.Truth.assertThat
import io.github.kabrapratik28.thumbfree.core.models.Catalog
import io.github.kabrapratik28.thumbfree.core.models.ModelFile
import io.github.kabrapratik28.thumbfree.core.models.ModelStatus
import io.github.kabrapratik28.thumbfree.core.session.Grey
import io.github.kabrapratik28.thumbfree.core.session.SpeechWait
import io.github.kabrapratik28.thumbfree.models.DownloadState
import io.github.kabrapratik28.thumbfree.models.FailReason
import java.util.Locale
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

    // The first step fills English, or Other languages once the multilingual model is the choice.
    @Test
    fun welcomeOffersEnglishUnlessMultilingualIsChosen() {
        assertThat(welcomeModel(Catalog.PARAKEET_UNIFIED_Q8)).isEqualTo(RECOMMENDED_MODEL)
        assertThat(welcomeModel(Catalog.PARAKEET_TDT_V3_Q8)).isEqualTo(Catalog.PARAKEET_TDT_V3_Q8)
        assertThat(welcomeModel(Catalog.CANARY_180M_FLASH_Q8)).isEqualTo(RECOMMENDED_MODEL)
        assertThat(RECOMMENDED_MODEL).isEqualTo(Catalog.PARAKEET_UNIFIED_Q8)
    }

    // The smart default: with no choice yet the phone's languages decide which button is filled, all of them: Other
    // languages when one is among the multilingual model's languages other than English (Hindi is not), else English.
    // A choice, even of English, wins over them.
    @Test
    fun welcomeOffersMultilingualForAPhoneLanguageItKnows() {
        fun offered(vararg tags: String, chosen: ModelFile? = null) = welcomeModel(chosen) { tags.map(Locale::forLanguageTag) }
        val multilingual = Catalog.PARAKEET_TDT_V3_Q8

        assertThat(offered("en-US")).isEqualTo(RECOMMENDED_MODEL)
        assertThat(offered("de-DE")).isEqualTo(multilingual)
        assertThat(offered("en-US", "es-ES")).isEqualTo(multilingual)
        assertThat(offered("en-US", "hi-IN")).isEqualTo(RECOMMENDED_MODEL)
        assertThat(offered("de-DE", chosen = Catalog.PARAKEET_UNIFIED_Q8)).isEqualTo(RECOMMENDED_MODEL)
        assertThat(offered("en-US", chosen = multilingual)).isEqualTo(multilingual)
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

    // A switch away from a model stops its download and removes its partial file while it waits, runs, or failed (its
    // partial file, up to the model's size, would stay otherwise); a model that is ready, or not started, is left alone.
    @Test
    fun aSwitchStopsTheDownloadItLeavesFailedOnesToo() {
        for (download in listOf(
            DownloadState.Queued(wifiOnly = true), DownloadState.Queued(wifiOnly = true, retrying = true), DownloadState.Downloading(1, 2),
            DownloadState.Failed(FailReason.NO_INTERNET), DownloadState.Failed(FailReason.INTERRUPTED), DownloadState.Failed(FailReason.NOT_ENOUGH_SPACE),
        )) assertThat(stopsOnSwitch(download)).isTrue()
        for (download in listOf(DownloadState.Ready, DownloadState.Verifying, DownloadState.NotDownloaded, null)) assertThat(stopsOnSwitch(download)).isFalse()
    }

    // The first step's two buttons: English is the English model, Other languages the multilingual one, which knows
    // English too and 24 more ("Spanish, French, German and 21 more").
    @Test
    fun theLanguageButtonsChooseTheirModels() {
        assertThat(RECOMMENDED_MODEL).isEqualTo(Catalog.PARAKEET_UNIFIED_Q8)
        assertThat(OTHER_LANGUAGES_MODEL).isEqualTo(Catalog.PARAKEET_TDT_V3_Q8)
        assertThat(OTHER_LANGUAGES_MODEL.languages).containsAtLeast("en", "es", "fr", "de")
        assertThat(OTHER_LANGUAGES_MODEL.languages.size - 4).isEqualTo(21)
    }

    // The first step waits through its phases for the chosen model: the choice until a language is tapped, then the
    // download (a state not known yet, a start not under way yet, and Queued without Wi-Fi all count as downloading),
    // a wait for Wi-Fi, a stop it picks up from, a damaged file it starts over, no space, the check and the engine's load
    // once it is here, a load that failed, and loaded. A failed load says so only while the model is here.
    @Test
    fun theFirstStepWaitsThroughEachPhase() {
        val english = Catalog.PARAKEET_UNIFIED_Q8
        fun phase(
            download: DownloadState?, verified: Boolean = false, loaded: Boolean = false, chosen: ModelFile? = english, loadFailed: Boolean = false,
        ) = readyPhase(chosen, download, verified, loaded, loadFailed)

        assertThat(phase(DownloadState.Downloading(1, 2), chosen = null)).isEqualTo(ReadyPhase.CHOOSE)
        for (downloading in listOf(null, DownloadState.NotDownloaded, DownloadState.Downloading(1, 2), DownloadState.Queued(wifiOnly = false))) {
            assertThat(phase(downloading)).isEqualTo(ReadyPhase.DOWNLOADING)
        }
        assertThat(phase(DownloadState.Queued(wifiOnly = true))).isEqualTo(ReadyPhase.WIFI)
        assertThat(phase(DownloadState.Queued(wifiOnly = true, retrying = true))).isEqualTo(ReadyPhase.RETRYING) // never "Wi-Fi"
        assertThat(phase(DownloadState.Failed(FailReason.NO_INTERNET))).isEqualTo(ReadyPhase.STOPPED)
        assertThat(phase(DownloadState.Failed(FailReason.INTERRUPTED))).isEqualTo(ReadyPhase.STOPPED)
        assertThat(phase(DownloadState.Failed(FailReason.FILE_CHECK_FAILED))).isEqualTo(ReadyPhase.FAILED)
        assertThat(phase(DownloadState.Failed(FailReason.NOT_ENOUGH_SPACE))).isEqualTo(ReadyPhase.NO_SPACE)
        assertThat(phase(DownloadState.Verifying)).isEqualTo(ReadyPhase.ALMOST)
        assertThat(phase(DownloadState.Ready)).isEqualTo(ReadyPhase.ALMOST)
        assertThat(phase(null, verified = true)).isEqualTo(ReadyPhase.ALMOST)
        assertThat(phase(DownloadState.Ready, loaded = true)).isEqualTo(ReadyPhase.LOADED)
        assertThat(phase(DownloadState.Ready, loadFailed = true)).isEqualTo(ReadyPhase.LOAD_FAILED)
        assertThat(phase(null, verified = true, loadFailed = true)).isEqualTo(ReadyPhase.LOAD_FAILED)
        assertThat(phase(DownloadState.Downloading(1, 2), loadFailed = true)).isEqualTo(ReadyPhase.DOWNLOADING)
        assertThat(phase(DownloadState.Ready, loaded = true, loadFailed = true)).isEqualTo(ReadyPhase.LOADED)
        // Loaded counts only while the file is here and checked: deleted since, it downloads again.
        assertThat(phase(DownloadState.NotDownloaded, loaded = true)).isEqualTo(ReadyPhase.DOWNLOADING)
        assertThat(phase(DownloadState.Verifying, loaded = true)).isEqualTo(ReadyPhase.ALMOST)
        assertThat(phase(null, verified = true, loaded = true)).isEqualTo(ReadyPhase.LOADED)
    }

    // The grey bubble in each phase: none before the choice, the download's ring and badge, Wi-Fi, a retry's pause, a
    // stop, the full turning ring while the model is checked or loaded, the full ring with its own badge once its load
    // failed (never yellow), and yellow once loaded.
    @Test
    fun theFirstStepsBubbleIsGreyUntilLoaded() {
        assertThat(readyGrey(ReadyPhase.CHOOSE, 0)).isNull()
        assertThat(readyGrey(ReadyPhase.DOWNLOADING, 42)).isEqualTo(Grey(Grey.Badge.DOWNLOAD, 0.42f))
        assertThat(readyGrey(ReadyPhase.WIFI, 0)!!.badge).isEqualTo(Grey.Badge.WIFI)
        assertThat(readyGrey(ReadyPhase.STOPPED, 10)!!.badge).isEqualTo(Grey.Badge.STOPPED)
        assertThat(readyGrey(ReadyPhase.NO_SPACE, 10)!!.badge).isEqualTo(Grey.Badge.STOPPED)
        assertThat(readyGrey(ReadyPhase.FAILED, 0)).isEqualTo(Grey(Grey.Badge.STOPPED, 0f))
        assertThat(readyGrey(ReadyPhase.RETRYING, 42)).isEqualTo(Grey(Grey.Badge.RETRYING, 0.42f))
        assertThat(readyGrey(ReadyPhase.LOAD_FAILED, 100)).isEqualTo(Grey(Grey.Badge.LOAD_FAILED, 1f))
        assertThat(readyGrey(ReadyPhase.ALMOST, 100)).isEqualTo(Grey.PREPARING)
        assertThat(readyGrey(ReadyPhase.LOADED, 100)).isNull()
    }

    // The last step's speech picture says what its words say: a pause only for a retry's pause, the stop mark for a stop
    // that Try again picks up from ("Download stopped", as the grey bubble's) and for a damaged file.
    @Test
    fun theLastStepsPictureBadgeSaysWhatItsWordsSay() {
        assertThat(packBadge(SpeechWait.DOWNLOADING)).isSameInstanceAs(AppIcons.Download)
        assertThat(packBadge(SpeechWait.WIFI)).isSameInstanceAs(AppIcons.Wifi)
        assertThat(packBadge(SpeechWait.RETRYING)).isSameInstanceAs(AppIcons.Pause)
        assertThat(packBadge(SpeechWait.PAUSED)).isSameInstanceAs(AppIcons.Warning)
        assertThat(packBadge(SpeechWait.CHECK_FAILED)).isSameInstanceAs(AppIcons.Warning)
        assertThat(packBadge(SpeechWait.NO_SPACE)).isSameInstanceAs(AppIcons.Storage)
        for (none in listOf(SpeechWait.CONNECTION, SpeechWait.NOT_STARTED, SpeechWait.PREPARING)) assertThat(packBadge(none)).isNull()
    }
}
