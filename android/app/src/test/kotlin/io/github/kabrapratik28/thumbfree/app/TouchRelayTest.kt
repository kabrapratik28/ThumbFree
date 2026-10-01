package io.github.kabrapratik28.thumbfree.app

import com.google.common.truth.Truth.assertThat
import io.github.kabrapratik28.thumbfree.core.session.Gesture
import io.github.kabrapratik28.thumbfree.core.session.TouchOutput
import org.junit.Test

class TouchRelayTest {
    private val outputs = mutableListOf<TouchOutput>()
    private val relay = TouchRelay(Gesture(slopPx = 10f)) { outputs += it }

    @Test
    fun hideDuringTouchCancelsIt() {
        relay.down(0f, 0f, 0)
        assertThat(outputs).containsExactly(TouchOutput.Press)

        relay.hide()
        assertThat(outputs.last()).isEqualTo(TouchOutput.Cancelled)
        relay.up(500)
        assertThat(outputs).hasSize(2)

        outputs.clear()
        relay.down(0f, 0f, 1_000)
        relay.move(15f, 0f, 1_100)
        relay.hide()
        assertThat(outputs.last()).isEqualTo(TouchOutput.DragEnd(cancelled = true))
    }

    @Test
    fun hideWithoutTouchSendsNothing() {
        relay.hide()
        assertThat(outputs).isEmpty()

        relay.down(0f, 0f, 0)
        relay.up(100)
        outputs.clear()
        relay.hide()
        assertThat(outputs).isEmpty()
    }
}
