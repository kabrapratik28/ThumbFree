package io.github.kabrapratik28.thumbfree.a11y

import io.github.kabrapratik28.thumbfree.core.text.FieldKind

/** Decides whether the bubble shows, from the focus reports of [DictationAccessibilityService]. One thread only. */
class FocusTracker(val hideDelayMs: Long = 400) {
    data class Field(val packageName: String, val windowId: Int, val inputType: Int, val isPassword: Boolean, val editable: Boolean)

    var current: Field? = null
        private set
    private var graceUntilMs = Long.MIN_VALUE

    fun focused(field: Field, nowMs: Long) {
        current = field
        graceUntilMs = Long.MIN_VALUE
    }

    fun lost(nowMs: Long) {
        // Only a showing bubble gets the grace period, so moving between fields does not flicker it.
        if (current?.let(::eligible) == true) graceUntilMs = nowMs + hideDelayMs
        current = null
    }

    /** Eligible field focused, or focus lost less than hideDelayMs ago, or a take is active. */
    fun visible(nowMs: Long, sessionActive: Boolean): Boolean =
        sessionActive || current?.let(::eligible) ?: (nowMs < graceUntilMs)

    companion object {
        /** Editable, and not a password, number, phone or date field: the bubble shows for it. */
        fun eligible(field: Field) = field.editable && FieldKind.isDictatable(field.inputType, field.isPassword)
    }
}
