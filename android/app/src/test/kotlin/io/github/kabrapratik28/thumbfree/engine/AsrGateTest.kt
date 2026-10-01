package io.github.kabrapratik28.thumbfree.engine

import com.google.common.truth.Truth.assertThat
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.junit.Test

/** Live preview: the asr thread's admission. Preview work never gets in ahead of an offline call that came. */
class AsrGateTest {
    @Test
    fun aPreviewCallThatLostTheRaceToAnOfflineCallGivesWay() {
        // The preview call has passed its first check (no offline call yet) and is about to take the lock; the offline
        // call then raises its count and is held just before the lock, so the preview call gets the lock first.
        val previewChecked = CountDownLatch(1)
        val previewGo = CountDownLatch(1)
        val offlineWaiting = CountDownLatch(1)
        val offlineGo = CountDownLatch(1)
        val gate = AsrGate(
            checked = { previewChecked.countDown(); previewGo.await() },
            waiting = { offlineWaiting.countDown(); offlineGo.await() },
        )
        var previewRan = false
        var previewResult = ""
        val preview = thread { previewResult = gate.preview({ "busy" }) { previewRan = true; "ran" } }
        assertThat(previewChecked.await(5, TimeUnit.SECONDS)).isTrue()
        var offlineRan = false
        val offline = thread { gate.offline { offlineRan = true } }
        assertThat(offlineWaiting.await(5, TimeUnit.SECONDS)).isTrue()

        previewGo.countDown()
        preview.join(5_000)

        assertThat(previewResult).isEqualTo("busy") // it took the lock but found the offline call and did nothing
        assertThat(previewRan).isFalse()
        offlineGo.countDown()
        offline.join(5_000)
        assertThat(offlineRan).isTrue()
    }

    @Test
    fun aPreviewCallWhileAnOfflineCallWaitsOrRunsIsBusyAtOnce() {
        val running = CountDownLatch(1)
        val release = CountDownLatch(1)
        val gate = AsrGate()
        val offline = thread { gate.offline { running.countDown(); release.await() } }
        assertThat(running.await(5, TimeUnit.SECONDS)).isTrue()

        val start = System.nanoTime()
        val result = gate.preview({ "busy" }) { "ran" }

        assertThat(result).isEqualTo("busy")
        assertThat((System.nanoTime() - start) / 1_000_000).isLessThan(100) // never waited for the lock
        release.countDown()
        offline.join(5_000)
        assertThat(gate.preview({ "busy" }) { "ran" }).isEqualTo("ran")
    }

    @Test
    fun anOfflineCallWaitsOnlyForPreviewWorkAdmittedBeforeIt() {
        val inside = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val gate = AsrGate()
        val order = mutableListOf<String>()
        val preview = thread { gate.preview({ order += "busy" }) { inside.countDown(); finish.await(); synchronized(order) { order += "chunk" } } }
        assertThat(inside.await(5, TimeUnit.SECONDS)).isTrue()
        val offline = thread { gate.offline { synchronized(order) { order += "offline" } } }
        Thread.sleep(50) // the offline call is waiting for the lock now
        assertThat(gate.preview({ "busy" }) { "ran" }).isEqualTo("busy") // nothing more gets in ahead of it

        finish.countDown()
        preview.join(5_000)
        offline.join(5_000)

        assertThat(order).containsExactly("chunk", "offline").inOrder()
    }
}
