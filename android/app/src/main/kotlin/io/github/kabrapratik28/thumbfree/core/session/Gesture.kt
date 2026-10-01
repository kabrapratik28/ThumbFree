package io.github.kabrapratik28.thumbfree.core.session

import kotlin.math.hypot

sealed interface TouchOutput {
    data object None : TouchOutput
    data object Press : TouchOutput
    data class Release(val heldMs: Long) : TouchOutput
    data class DragBy(val dx: Float, val dy: Float, val first: Boolean) : TouchOutput   // offset from the down point
    data class DragEnd(val cancelled: Boolean) : TouchOutput
    data object Cancelled : TouchOutput
}

/** Pure; the caller passes timestamps. A move past [slopPx] before [holdThresholdMs] is a drag; later moves are ignored. */
class Gesture(private val slopPx: Float, private val holdThresholdMs: Long = 300, private val debounceMs: Long = 30) {
    private enum class Phase { IDLE, PRESSED, DRAGGING, IGNORED }

    private var phase = Phase.IDLE
    private var downX = 0f
    private var downY = 0f
    private var downAtMs = 0L
    private var lastUpMs: Long? = null

    fun down(x: Float, y: Float, atMs: Long): TouchOutput {
        val debounced = lastUpMs?.let { atMs - it < debounceMs } == true
        downX = x
        downY = y
        downAtMs = atMs
        phase = if (debounced) Phase.IGNORED else Phase.PRESSED
        return if (debounced) TouchOutput.None else TouchOutput.Press
    }

    fun move(x: Float, y: Float, atMs: Long): TouchOutput {
        if (phase == Phase.DRAGGING) return TouchOutput.DragBy(x - downX, y - downY, first = false)
        if (phase != Phase.PRESSED) return TouchOutput.None
        val pastSlop = hypot(x - downX, y - downY) >= slopPx
        if (!pastSlop || atMs - downAtMs >= holdThresholdMs) return TouchOutput.None
        phase = Phase.DRAGGING
        return TouchOutput.DragBy(x - downX, y - downY, first = true)
    }

    fun up(atMs: Long): TouchOutput = when (phase) {
        Phase.PRESSED -> TouchOutput.Release(atMs - downAtMs).also { lastUpMs = atMs; phase = Phase.IDLE }
        Phase.DRAGGING -> TouchOutput.DragEnd(cancelled = false).also { phase = Phase.IDLE }
        Phase.IGNORED -> TouchOutput.None.also { phase = Phase.IDLE }
        Phase.IDLE -> TouchOutput.None
    }

    fun cancel(): TouchOutput = when (phase) {
        Phase.PRESSED -> TouchOutput.Cancelled.also { phase = Phase.IDLE }
        Phase.DRAGGING -> TouchOutput.DragEnd(cancelled = true).also { phase = Phase.IDLE }
        Phase.IGNORED -> TouchOutput.None.also { phase = Phase.IDLE }
        Phase.IDLE -> TouchOutput.None
    }
}
