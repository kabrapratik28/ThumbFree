package io.github.kabrapratik28.thumbfree.core.session

import com.google.common.truth.Truth.assertThat
import org.junit.Test

// slopPx = 10f throughout: a fixed touch slop keeps the drag cases easy to read.
class GestureTest {
    @Test
    fun tapIsShortRelease() {
        val gesture = Gesture(slopPx = 10f)
        assertThat(gesture.down(0f, 0f, 1000)).isEqualTo(TouchOutput.Press)
        assertThat(gesture.up(1120)).isEqualTo(TouchOutput.Release(120))
    }

    @Test
    fun holdIsLongRelease() {
        val gesture = Gesture(slopPx = 10f)
        assertThat(gesture.down(0f, 0f, 1000)).isEqualTo(TouchOutput.Press)
        assertThat(gesture.up(1400)).isEqualTo(TouchOutput.Release(400))
    }

    @Test
    fun moveBeyondSlopBeforeThresholdIsDrag() {
        val gesture = Gesture(slopPx = 10f)
        assertThat(gesture.down(0f, 0f, 0)).isEqualTo(TouchOutput.Press)
        assertThat(gesture.move(15f, 0f, 100)).isEqualTo(TouchOutput.DragBy(15f, 0f, true))
        assertThat(gesture.move(20f, 5f, 150)).isEqualTo(TouchOutput.DragBy(20f, 5f, false))
        assertThat(gesture.up(200)).isEqualTo(TouchOutput.DragEnd(false))
    }

    @Test
    fun moveWithinSlopIsIgnored() {
        val gesture = Gesture(slopPx = 10f)
        assertThat(gesture.down(0f, 0f, 0)).isEqualTo(TouchOutput.Press)
        assertThat(gesture.move(5f, 5f, 100)).isEqualTo(TouchOutput.None)
        assertThat(gesture.up(150)).isEqualTo(TouchOutput.Release(150))
    }

    @Test
    fun moveAfterThresholdIsIgnored() {
        val gesture = Gesture(slopPx = 10f)
        assertThat(gesture.down(0f, 0f, 0)).isEqualTo(TouchOutput.Press)
        assertThat(gesture.move(50f, 0f, 350)).isEqualTo(TouchOutput.None)
        assertThat(gesture.up(500)).isEqualTo(TouchOutput.Release(500))
    }

    @Test
    fun cancelDuringPressIsCancelled() {
        val gesture = Gesture(slopPx = 10f)
        assertThat(gesture.down(0f, 0f, 0)).isEqualTo(TouchOutput.Press)
        assertThat(gesture.cancel()).isEqualTo(TouchOutput.Cancelled)
    }

    @Test
    fun cancelDuringDragEndsDrag() {
        val gesture = Gesture(slopPx = 10f)
        assertThat(gesture.down(0f, 0f, 0)).isEqualTo(TouchOutput.Press)
        assertThat(gesture.move(15f, 0f, 100)).isEqualTo(TouchOutput.DragBy(15f, 0f, true))
        assertThat(gesture.cancel()).isEqualTo(TouchOutput.DragEnd(true))
    }

    @Test
    fun pressesWithin30msAreDebounced() {
        val gesture = Gesture(slopPx = 10f)
        assertThat(gesture.down(0f, 0f, 0)).isEqualTo(TouchOutput.Press)
        assertThat(gesture.up(10)).isEqualTo(TouchOutput.Release(10))
        assertThat(gesture.down(0f, 0f, 25)).isEqualTo(TouchOutput.None)
        assertThat(gesture.up(30)).isEqualTo(TouchOutput.None)
        assertThat(gesture.down(0f, 0f, 45)).isEqualTo(TouchOutput.Press)
    }
}
