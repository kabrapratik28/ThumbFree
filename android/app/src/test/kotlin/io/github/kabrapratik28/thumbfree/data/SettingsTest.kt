package io.github.kabrapratik28.thumbfree.data

import android.content.Context
import com.google.common.truth.Truth.assertThat
import io.github.kabrapratik28.thumbfree.core.models.Catalog
import io.github.kabrapratik28.thumbfree.core.session.BubblePlacement.Spot
import io.github.kabrapratik28.thumbfree.core.session.BubbleStyle
import io.github.kabrapratik28.thumbfree.core.session.PreviewPlace
import java.io.File
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class SettingsTest {
    private val prefs = RuntimeEnvironment.getApplication().getSharedPreferences("settings", Context.MODE_PRIVATE)

    @Test
    fun parakeetByDefault() {
        assertThat(Settings(prefs).selectedModelId).isNull()
        assertThat(Settings(prefs).model).isEqualTo(Catalog.PARAKEET_UNIFIED_Q8)
    }

    @Test
    fun choiceIsKept() {
        Settings(prefs).selectedModelId = Catalog.CANARY_180M_FLASH_Q8.id

        val reopened = Settings(prefs)
        assertThat(reopened.selectedModelId).isEqualTo(Catalog.CANARY_180M_FLASH_Q8.id)
        assertThat(reopened.model).isEqualTo(Catalog.CANARY_180M_FLASH_Q8)
    }

    // The multilingual model is a choice like the others, kept as its id.
    @Test
    fun multilingualChoiceIsKept() {
        Settings(prefs).selectedModelId = Catalog.PARAKEET_TDT_V3_Q8.id

        assertThat(Settings(prefs).model).isEqualTo(Catalog.PARAKEET_TDT_V3_Q8)
    }

    // After a restart the choice is read back from the file: it must be in the file, and a SharedPreferences loaded
    // from a copy of that file (not from this process's memory) must give it back.
    @Test
    fun choiceSurvivesARestart() {
        val app = RuntimeEnvironment.getApplication()
        Settings(prefs).selectedModelId = Catalog.CANARY_180M_FLASH_Q8.id
        prefs.edit().commit() // returns once the file holds every change so far
        val dir = File(app.dataDir, "shared_prefs")
        assertThat(File(dir, "settings.xml").readText()).contains(Catalog.CANARY_180M_FLASH_Q8.id)
        File(dir, "settings.xml").copyTo(File(dir, "restarted.xml"))

        val restarted = Settings(app.getSharedPreferences("restarted", Context.MODE_PRIVATE))
        assertThat(restarted.model).isEqualTo(Catalog.CANARY_180M_FLASH_Q8)
    }

    // Model downloads wait for Wi-Fi until the owner turns that off, and the choice is kept.
    @Test
    fun wifiOnlyUntilTurnedOff() {
        assertThat(Settings(prefs).wifiOnly).isTrue()

        Settings(prefs).wifiOnly = false

        assertThat(Settings(prefs).wifiOnly).isFalse()
    }

    @Test
    fun customWordsAreKeptInOrder() {
        assertThat(Settings(prefs).customWords).isEmpty()

        Settings(prefs).customWords = listOf("GitHub", "MacBook Pro", "R&D")

        assertThat(Settings(prefs).customWords).containsExactly("GitHub", "MacBook Pro", "R&D").inOrder()
    }

    // Whatever is stored reads back in CustomWords.parse's shape: trimmed, no case repeats, no entry over 60 characters.
    @Test
    fun customWordsReadBackParsed() {
        prefs.edit().putString("custom_words", " GitHub \ngithub\nMacBook   Pro\n${"x".repeat(61)}\na, b").commit()

        assertThat(Settings(prefs).customWords).containsExactly("GitHub", "MacBook Pro", "a", "b").inOrder()
    }

    // A model a later build drops from the catalog falls back to the default instead of failing every take.
    @Test
    fun unknownModelIsParakeet() {
        prefs.edit().putString("selected_model", "gone/model.gguf").commit()

        assertThat(Settings(prefs).model).isEqualTo(Catalog.PARAKEET_UNIFIED_Q8)
    }

    // History keeps 200 takes, with no age limit, until the owner picks other limits; a pick is kept.
    @Test
    fun retentionDefaultsAndIsKept() {
        assertThat(Settings(prefs).retention).isEqualTo(Retention(maxDays = 7, maxTakes = 200))

        Settings(prefs).retention = Retention(maxDays = 30, maxTakes = null)

        assertThat(Settings(prefs).retention).isEqualTo(Retention(maxDays = 30, maxTakes = null))
    }

    // The welcome screens start at the first step and resume at the step reached when the app was left.
    @Test
    fun welcomeResumesWhereItStopped() {
        assertThat(Settings(prefs).welcomeStep).isEqualTo(0)
        assertThat(Settings(prefs).welcomeDone).isFalse()

        Settings(prefs).welcomeStep = 2
        Settings(prefs).welcomeDone = true

        assertThat(Settings(prefs).welcomeStep).isEqualTo(2)
        assertThat(Settings(prefs).welcomeDone).isTrue()
    }

    // The bubble starts medium and 85% opaque while idle; a pick is kept; a stored opacity outside 30 to 100 is clamped
    // and a size a later build dropped falls back to medium.
    @Test
    fun bubbleStyleDefaultsClampsAndIsKept() {
        assertThat(Settings(prefs).bubbleStyle).isEqualTo(BubbleStyle(BubbleStyle.Size.LARGE, 50)) // until the owner picks

        Settings(prefs).bubbleStyle = BubbleStyle(BubbleStyle.Size.LARGE, 50)
        assertThat(Settings(prefs).bubbleStyle).isEqualTo(BubbleStyle(BubbleStyle.Size.LARGE, 50))

        prefs.edit().putInt("bubble_opacity", 5).putString("bubble_size", "HUGE").commit()
        assertThat(Settings(prefs).bubbleStyle).isEqualTo(BubbleStyle(BubbleStyle.Size.LARGE, 30))
        prefs.edit().putInt("bubble_opacity", 250).commit()
        assertThat(Settings(prefs).bubbleStyle.opacity).isEqualTo(100)
        Settings(prefs).bubbleStyle = BubbleStyle(BubbleStyle.Size.SMALL, 7) // written clamped too
        assertThat(prefs.getInt("bubble_opacity", 0)).isEqualTo(30)
    }

    // The Try tab's Dictionary card shows once: dismissed or used, it stays retired.
    @Test
    fun dictionaryHintIsOneTime() {
        assertThat(Settings(prefs).dictionaryHintDone).isFalse()
        Settings(prefs).dictionaryHintDone = true
        assertThat(Settings(prefs).dictionaryHintDone).isTrue()
    }

    // Where the owner dropped the bubble: none until a drag (the automatic spot), kept, read back within the screen, and
    // gone again after Reset position. Snap to screen edge is off until turned on.
    @Test
    fun bubbleSpotAndSnapAreKept() {
        assertThat(Settings(prefs).bubbleSpot).isNull()
        assertThat(Settings(prefs).bubbleSnap).isFalse()

        Settings(prefs).bubbleSpot = Spot(0.25f, 0.75f)
        Settings(prefs).bubbleSnap = true
        assertThat(Settings(prefs).bubbleSpot).isEqualTo(Spot(0.25f, 0.75f))
        assertThat(Settings(prefs).bubbleSnap).isTrue()

        prefs.edit().putFloat("bubble_x", 7f).commit()
        assertThat(Settings(prefs).bubbleSpot).isEqualTo(Spot(1f, 0.75f))
        Settings(prefs).bubbleSpot = null
        assertThat(Settings(prefs).bubbleSpot).isNull()
    }

    // Live preview (experimental): off, and the old settings row's "live_preview" turns nothing on; a test or
    // developer's choice is kept across a restart.
    @Test
    fun livePreviewIsOffByDefaultAndKept() {
        prefs.edit().putBoolean("live_preview", true).commit()
        assertThat(Settings(prefs).livePreview).isEqualTo(Settings.LIVE_PREVIEW_DEFAULT)
        assertThat(Settings.LIVE_PREVIEW_DEFAULT).isFalse()

        Settings(prefs).livePreview = true

        assertThat(Settings(prefs).livePreview).isTrue()
    }

    @Test
    fun wherePreviewShowsIsNextToTheBubbleByDefaultAndKept() {
        assertThat(Settings(prefs).livePreviewPlace).isEqualTo(PreviewPlace.BUBBLE)

        Settings(prefs).livePreviewPlace = PreviewPlace.TOP
        assertThat(Settings(prefs).livePreviewPlace).isEqualTo(PreviewPlace.TOP)

        prefs.edit().putString("live_preview_place", "SIDEWAYS").commit() // a value from a later version
        assertThat(Settings(prefs).livePreviewPlace).isEqualTo(PreviewPlace.BUBBLE)
    }

    @Test
    fun onlyParakeetUnifiedShowsThePreview() {
        assertThat(Catalog.all.filter { it.livePreview }).containsExactly(Catalog.PARAKEET_UNIFIED_Q8)
    }
}
