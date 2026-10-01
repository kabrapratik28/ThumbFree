package io.github.kabrapratik28.thumbfree.data

import android.content.SharedPreferences
import io.github.kabrapratik28.thumbfree.core.models.Catalog
import io.github.kabrapratik28.thumbfree.core.models.ModelFile
import io.github.kabrapratik28.thumbfree.core.session.BubblePlacement
import io.github.kabrapratik28.thumbfree.core.session.BubbleStyle
import io.github.kabrapratik28.thumbfree.core.session.PreviewPlace
import io.github.kabrapratik28.thumbfree.core.text.CustomWords

/** SharedPreferences "settings", one key per setting. */
class Settings(private val prefs: SharedPreferences) {
    /** Key "selected_model", a [ModelFile.id]; null until the owner picks a model on the home screen. */
    var selectedModelId: String?
        get() = prefs.getString("selected_model", null)
        set(value) = prefs.edit().putString("selected_model", value).apply()

    /** The model takes use: the selected one, else Parakeet, also for an id the catalog no longer has. */
    val model: ModelFile get() = selectedModelId?.let(Catalog::byId) ?: Catalog.PARAKEET_UNIFIED_Q8

    /** Key "wifi_only": model downloads wait for Wi-Fi (an unmetered network). On until the owner turns it off. */
    var wifiOnly: Boolean
        get() = prefs.getBoolean("wifi_only", true)
        set(value) = prefs.edit().putBoolean("wifi_only", value).apply()

    /**
     * Key "custom_words", one entry per line: the words each take's text is corrected with (CustomWords). Always read
     * back through CustomWords.parse, so it has parse's shape whatever was stored; an entry with a comma reads back as two.
     */
    var customWords: List<String>
        get() = CustomWords.parse(prefs.getString("custom_words", null).orEmpty())
        set(value) = prefs.edit().putString("custom_words", value.joinToString("\n")).apply()

    /**
     * Keys "keep_days" and "keep_takes", 0 for no limit. By default 7 days and at most 200 takes, whichever comes first:
     * each take keeps its recording (about 1.9 MB a minute), so a short default keeps storage small.
     */
    var retention: Retention
        get() = Retention(prefs.getInt("keep_days", 7).takeIf { it > 0 }, prefs.getInt("keep_takes", 200).takeIf { it > 0 })
        set(value) = prefs.edit().putInt("keep_days", value.maxDays ?: 0).putInt("keep_takes", value.maxTakes ?: 0).apply()

    /** Key "welcome_step": the welcome screen to show next, so a flow left midway resumes there. */
    var welcomeStep: Int
        get() = prefs.getInt("welcome_step", 0)
        set(value) = prefs.edit().putInt("welcome_step", value).apply()

    /** Key "welcome_done": the welcome screens were finished; they never open by themselves again. */
    var welcomeDone: Boolean
        get() = prefs.getBoolean("welcome_done", false)
        set(value) = prefs.edit().putBoolean("welcome_done", value).apply()

    /**
     * Keys "bubble_size" (a BubbleStyle.Size name) and "bubble_opacity" (percent while idle), BubbleStyle.RECOMMENDED
     * until the owner picks. A size a later build dropped reads as medium; an opacity is kept between 30 and 100.
     */
    var bubbleStyle: BubbleStyle
        get() = BubbleStyle(
            BubbleStyle.Size.entries.firstOrNull { it.name == prefs.getString("bubble_size", null) } ?: BubbleStyle.RECOMMENDED.size,
            clampOpacity(prefs.getInt("bubble_opacity", BubbleStyle.RECOMMENDED.opacity)),
        )
        set(value) = prefs.edit().putString("bubble_size", value.size.name).putInt("bubble_opacity", clampOpacity(value.opacity)).apply()

    private fun clampOpacity(percent: Int) = percent.coerceIn(BubbleStyle.MIN_OPACITY, BubbleStyle.MAX_OPACITY)

    /**
     * Keys "bubble_x" and "bubble_y": where the owner last dropped the bubble, as BubblePlacement.Spot fractions, read
     * back between 0 and 1. Null until the first drag and after Reset position: the automatic spot beside the keyboard.
     */
    var bubbleSpot: BubblePlacement.Spot?
        get() {
            val x = prefs.getFloat("bubble_x", Float.NaN)
            val y = prefs.getFloat("bubble_y", Float.NaN)
            return if (x.isNaN() || y.isNaN()) null else BubblePlacement.Spot(x.coerceIn(0f, 1f), y.coerceIn(0f, 1f))
        }
        set(value) = prefs.edit().apply {
            if (value == null) remove("bubble_x").remove("bubble_y") else putFloat("bubble_x", value.x).putFloat("bubble_y", value.y)
        }.apply()

    /** Key "dictionary_hint_done": the Try tab's one-time card about the Dictionary was dismissed or used. */
    var dictionaryHintDone: Boolean
        get() = prefs.getBoolean("dictionary_hint_done", false)
        set(value) = prefs.edit().putBoolean("dictionary_hint_done", value).apply()

    /** Key "bubble_snap": after a drag the bubble goes to the nearer side edge. Off: it stays where it is dropped. */
    var bubbleSnap: Boolean
        get() = prefs.getBoolean("bubble_snap", false)
        set(value) = prefs.edit().putBoolean("bubble_snap", value).apply()

    /**
     * Key "experimental_live_preview" (the live preview, experimental): while a take records, the words being
     * recognized show in a panel by the bubble; the text typed at the stop is the same either way. Read at each take's
     * start. The app offers no switch for it; tests and developers set it. A new key, so a "live_preview" = true left
     * by the settings row of an earlier build turns nothing on.
     */
    var livePreview: Boolean
        get() = prefs.getBoolean("experimental_live_preview", LIVE_PREVIEW_DEFAULT)
        set(value) = prefs.edit().putBoolean("experimental_live_preview", value).apply()

    /**
     * Key "live_preview_place": where the live preview shows, next to the bubble (the default) or in a strip at the top
     * of the screen. No settings row while the preview is experimental.
     */
    var livePreviewPlace: PreviewPlace
        get() = PreviewPlace.entries.firstOrNull { it.name == prefs.getString("live_preview_place", null) } ?: PreviewPlace.BUBBLE
        set(value) = prefs.edit().putString("live_preview_place", value.name).apply()

    companion object {
        /**
         * Off: an experimental feature (since 2026-09-27). The code and its tests stay; the app doesn't offer it, since
         * the preview lags the speech and costs about 3 times the energy while dictating
         * (android/tools/live-phone-check.sh).
         */
        const val LIVE_PREVIEW_DEFAULT = false
    }
}
