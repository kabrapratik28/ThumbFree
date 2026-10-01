package io.github.kabrapratik28.thumbfree.a11y

import androidx.annotation.StringRes
import io.github.kabrapratik28.thumbfree.R
import io.github.kabrapratik28.thumbfree.core.session.Code

/** User-facing text for each [Code] (strings_codes.xml). */
object CodeMessages {
    @StringRes
    fun of(code: Code): Int = when (code) {
        Code.MIC_PERMISSION -> R.string.code_mic_permission
        Code.MIC_UNAVAILABLE -> R.string.code_mic_unavailable
        Code.MIC_NOT_READY -> R.string.code_mic_not_ready
        Code.MIC_SILENCED -> R.string.code_mic_silenced
        Code.MIC_SILENT -> R.string.code_mic_silent
        Code.FORMAT_UNSUPPORTED -> R.string.code_format_unsupported
        Code.DEVICE_LOST -> R.string.code_device_lost
        Code.CAPTURE_STALLED -> R.string.code_capture_stalled
        Code.CAPTURE_OVERFLOW -> R.string.code_capture_overflow
        Code.STORAGE_FULL -> R.string.code_storage_full
        Code.HISTORY_WRITE_FAILED -> R.string.code_history_write_failed
        Code.NO_MODEL -> R.string.code_no_model
        Code.LOAD_FAILED -> R.string.code_load_failed
        Code.NO_MEMORY -> R.string.code_no_memory
        Code.ENGINE_CRASHED -> R.string.code_engine_crashed
        Code.TRUNCATED -> R.string.code_truncated
        Code.INPUT_TOO_LONG -> R.string.code_input_too_long
        Code.NO_SPEECH -> R.string.code_no_speech
        Code.NO_TARGET -> R.string.code_no_target
        Code.TARGET_CHANGED -> R.string.code_target_changed
        Code.PASSWORD_TARGET -> R.string.code_password_target
        Code.NO_SESSION -> R.string.code_no_session
        Code.MAY_NOT_HAVE_LANDED -> R.string.code_may_not_have_landed
        Code.FOREGROUND_DENIED -> R.string.code_foreground_denied
        Code.FOREGROUND_STOPPED -> R.string.code_foreground_stopped
        Code.TAKE_LIMIT -> R.string.code_take_limit
        Code.TAKE_ENDS_SOON -> R.string.code_take_ends_soon
        Code.LOCKED_SILENCE -> R.string.code_locked_silence
        Code.CALL -> R.string.code_call
        Code.AUDIO_MISSING -> R.string.code_audio_missing
        Code.CANCELLED -> R.string.code_cancelled
        Code.COPIED -> R.string.code_copied
        Code.COPY_FAILED -> R.string.code_copy_failed
        Code.HELD_BACK -> R.string.code_held_back
    }
}
