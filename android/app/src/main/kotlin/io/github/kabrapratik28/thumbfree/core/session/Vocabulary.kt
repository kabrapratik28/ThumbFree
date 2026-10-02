package io.github.kabrapratik28.thumbfree.core.session

/** Reasons a take ends or a chip shows. `strings_codes.xml` (a11y.CodeMessages) has the user-facing text. */
enum class Code {
    MIC_PERMISSION, MIC_UNAVAILABLE, MIC_NOT_READY, MIC_SILENCED, MIC_SILENT, FORMAT_UNSUPPORTED,
    DEVICE_LOST, CAPTURE_STALLED, CAPTURE_OVERFLOW, STORAGE_FULL, HISTORY_WRITE_FAILED,
    NO_MODEL, LOAD_FAILED, NO_MEMORY, ENGINE_CRASHED, TRUNCATED, INPUT_TOO_LONG,
    NO_SPEECH, NO_TARGET, TARGET_CHANGED, PASSWORD_TARGET, NO_SESSION, MAY_NOT_HAVE_LANDED,
    FOREGROUND_DENIED, FOREGROUND_STOPPED, TAKE_LIMIT, TAKE_ENDS_SOON, LOCKED_SILENCE, CALL,
    AUDIO_MISSING, CANCELLED, COPIED, COPY_FAILED, HELD_BACK,
}

/** How a take's text ended up, for history rows. */
enum class Outcome { INSERTED, UNVERIFIED, NOT_INSERTED, NO_SPEECH, CANCELLED, FAILED }

/** The haptic cues a state change can ask for. */
enum class HapticKind { TICK, STOP, CONFIRM, REJECT }

/**
 * Actions a chip can offer; which ones depend on the [Code]. [OPEN_SPEECH] is the not-ready panel's (BubbleUi.NotReady):
 * it opens the app's speech models, outside any take.
 */
enum class ChipAction { COPY, INSERT_HERE, UNDO, RETRY, DISMISS, CANCEL, OPEN_SPEECH }

/** What the accessibility bubble renders. */
sealed interface BubbleUi {
    data object Hidden : BubbleUi
    data object Idle : BubbleUi
    data object Arming : BubbleUi
    data class Recording(val level: Float, val locked: Boolean, val elapsedMs: Long) : BubbleUi
    data class Processing(val loadingModel: Boolean, val done: Int, val total: Int?) : BubbleUi
    data class Chip(val code: Code, val actions: List<ChipAction>) : BubbleUi
    /**
     * A tap before the speech model is usable: a panel by the bubble says why ([wait]; [percent] while it downloads),
     * with Open, and nothing listens.
     */
    data class NotReady(val wait: SpeechWait, val percent: Int) : BubbleUi
}

/**
 * Why the speech model is not usable yet (models.Readiness), as the welcome's last step, Home and the bubble's panel say
 * it. [RETRYING] is the pause before the download starts again by itself; [PAUSED] a stop it picks up from on Try again.
 */
enum class SpeechWait { DOWNLOADING, WIFI, CONNECTION, PREPARING, RETRYING, PAUSED, NO_SPACE, CHECK_FAILED, NOT_STARTED }

/**
 * The bubble's grey look while it can't listen yet: a yellow bubble always listens, so one that can't is grey, with a
 * [badge] saying why and a ring saying how far: the download's part ([progress], 0 to 1), full and turning while the
 * model is checked or loaded ([turning]), or none ([progress] null, as for the microphone).
 */
data class Grey(val badge: Badge?, val progress: Float?, val turning: Boolean = false) {
    /**
     * Why the bubble can't listen, which its badge shows and TalkBack says: a download under way (its arrow drifts),
     * waiting for Wi-Fi, waiting for any connection (the Wi-Fi mark too), a download not started (the arrow, still), a
     * retry's pause before the download starts again by itself (the pause), a download stopped, a model the engine
     * couldn't load (these two with the stop mark), or the microphone off.
     */
    enum class Badge { DOWNLOAD, WIFI, CONNECTION, NOT_STARTED, RETRYING, STOPPED, LOAD_FAILED, MIC_OFF }

    companion object {
        /** The microphone is off: its badge, and no ring. */
        val MIC_OFF = Grey(Badge.MIC_OFF, null)

        /** The model's file is checked, or loaded into the engine: the full ring turning, no badge. */
        val PREPARING = Grey(null, 1f, turning = true)

        /** The grey look for a model that waits ([wait]; [percent] while it downloads). */
        fun of(wait: SpeechWait, percent: Int): Grey = when (wait) {
            SpeechWait.DOWNLOADING -> Grey(Badge.DOWNLOAD, percent / 100f)
            SpeechWait.WIFI -> Grey(Badge.WIFI, percent / 100f)
            SpeechWait.CONNECTION -> Grey(Badge.CONNECTION, percent / 100f)
            SpeechWait.NOT_STARTED -> Grey(Badge.NOT_STARTED, 0f)
            SpeechWait.PREPARING -> PREPARING
            SpeechWait.RETRYING -> Grey(Badge.RETRYING, percent / 100f)
            SpeechWait.PAUSED, SpeechWait.NO_SPACE, SpeechWait.CHECK_FAILED -> Grey(Badge.STOPPED, percent / 100f)
        }
    }
}
