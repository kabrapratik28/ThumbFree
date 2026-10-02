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

    /**
     * Key "welcome_screen": the welcome screen to show next, by its name, so a flow left midway resumes there; null until
     * one is left. A name from an earlier order is mapped onto this one (ui.resumeAt); earlier builds kept an index in
     * their own order instead ([welcomeStepBefore]).
     */
    var welcomeScreen: String?
        get() = prefs.getString("welcome_screen", null)
        set(value) = prefs.edit().putString("welcome_screen", value).apply()

    /** Key "welcome_step", only read: where an earlier build's welcome screens were left, as an index in its order. */
    val welcomeStepBefore: Int get() = prefs.getInt("welcome_step", 0)

    /**
     * Key "accessibility_wait": when the owner tapped Agree and open settings on the bubble step (wall clock, ms), until
     * the app is back on screen (MainActivity ends it on its next resume); null otherwise. Kept here rather than in
     * memory, so the return still counts when Android ends the process while the owner is in its settings.
     */
    var accessibilityWait: Long?
        get() = prefs.getLong("accessibility_wait", 0L).takeIf { it > 0L }
        set(value) = prefs.edit().apply { if (value == null) remove("accessibility_wait") else putLong("accessibility_wait", value) }.apply()

    /**
     * Key "mic_asked": Android answered a microphone request before (any answer). After that, a refusal Android gives
     * without asking (no rationale) is final, "don't ask again" or a device policy, and only App info can grant it; the
     * first one may be a question dismissed with a tap outside, which can be asked again.
     */
    var micAsked: Boolean
        get() = prefs.getBoolean("mic_asked", false)
        set(value) = prefs.edit().putBoolean("mic_asked", value).apply()

    /** Key "welcome_done": the welcome screens were finished; they never open by themselves again. */
    var welcomeDone: Boolean
        get() = prefs.getBoolean("welcome_done", false)
        set(value) = prefs.edit().putBoolean("welcome_done", value).apply()

    /**
     * Keys "bubble_size" (a BubbleStyle.Size name) and "bubble_opacity" (percent while idle), BubbleStyle.RECOMMENDED
     * until the owner picks. A size a later build dropped reads as the recommended one; an opacity is kept between 30 and 100.
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

    /** Key "dictionary_hint_done": Home's one-time card about the Dictionary was dismissed or used. */
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

        /** How long after Agree and open settings the service still brings the app back: 10 minutes. */
        const val ACCESSIBILITY_WAIT_MS = 10 * 60_000L

        /**
         * Whether an [accessibilityWait] set at [since] is still recent at [now]: at most [ACCESSIBILITY_WAIT_MS] old.
         * A later connect is not that trip's (a reboot, say), and a clock set back reads as stale.
         */
        fun recentWait(since: Long, now: Long): Boolean = now - since in 0..ACCESSIBILITY_WAIT_MS
    }
}
