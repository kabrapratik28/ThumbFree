package io.github.kabrapratik28.thumbfree.ui

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

// The welcome screen's download actions run in the order they were asked for, and none is dropped.
@OptIn(ExperimentalCoroutinesApi::class)
class ModelQueueTest {
    // A switch from A whose cancel is still running, a Download of B, and a switch straight back to A: B's download ends
    // cancelled, where the drop-if-busy guard this replaces skipped B's cancel.
    @Test
    fun aQuickSecondSwitchStillCancelsTheDownloadItLeaves() = runTest {
        val queue = ModelQueue(this)
        val downloads = mutableMapOf<String, String>() // what ModelDownloads holds for each model
        val shown = mutableListOf("A") // the model the step shows
        val slowCancel = CompletableDeferred<Unit>()
        fun start(model: String) = queue.enqueue { downloads[model] = "downloading" }
        fun cancel(model: String) = queue.enqueue {
            if (model == "A") slowCancel.await()
            downloads[model] = "cancelled"
        }
        fun choose(model: String) = queue.enqueue { shown += model }

        cancel("A") // the switch from A to B: its cancel waits
        choose("B")
        start("B") // Download B
        cancel("B") // and straight back to A
        choose("A")
        runCurrent()
        assertThat(queue.pending).isEqualTo(5) // nothing ran ahead of the waiting cancel, and nothing was dropped
        assertThat(shown).containsExactly("A")

        slowCancel.complete(Unit)
        advanceUntilIdle()
        assertThat(downloads).containsExactly("A", "cancelled", "B", "cancelled")
        assertThat(shown).containsExactly("A", "B", "A").inOrder()
        assertThat(queue.pending).isEqualTo(0)
    }
}
