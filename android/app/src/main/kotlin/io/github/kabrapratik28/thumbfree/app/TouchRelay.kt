package io.github.kabrapratik28.thumbfree.app

import io.github.kabrapratik28.thumbfree.core.session.Gesture
import io.github.kabrapratik28.thumbfree.core.session.TouchOutput

/**
 * Feeds bubble touches to the classifier and its outputs on, leaving out None. Whether a touch is in progress is the
 * classifier's own phase, so [hide] needs no state here.
 */
class TouchRelay(private val gesture: Gesture, private val onOutput: (TouchOutput) -> Unit) {
    fun down(x: Float, y: Float, atMs: Long) = forward(gesture.down(x, y, atMs))

    fun move(x: Float, y: Float, atMs: Long) = forward(gesture.move(x, y, atMs))

    fun up(atMs: Long) = forward(gesture.up(atMs))

    /**
     * The bubble is being hidden, or the system took the touch: a touch in progress is cancelled through the
     * classifier (gesture.cancel()), since its UP will never come.
     */
    fun hide() = forward(gesture.cancel())

    private fun forward(output: TouchOutput) {
        if (output != TouchOutput.None) onOutput(output)
    }
}
