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

/** Actions a chip can offer; which ones depend on the [Code]. */
enum class ChipAction { COPY, INSERT_HERE, UNDO, RETRY, DISMISS, CANCEL }

/** What the accessibility bubble renders. */
sealed interface BubbleUi {
    data object Hidden : BubbleUi
    data object Idle : BubbleUi
    data object Arming : BubbleUi
    data class Recording(val level: Float, val locked: Boolean, val elapsedMs: Long) : BubbleUi
    data class Processing(val loadingModel: Boolean, val done: Int, val total: Int?) : BubbleUi
    data class Chip(val code: Code, val actions: List<ChipAction>) : BubbleUi
}
