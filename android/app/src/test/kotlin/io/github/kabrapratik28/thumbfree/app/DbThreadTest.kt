package io.github.kabrapratik28.thumbfree.app

import com.google.common.truth.Truth.assertThat
import io.github.kabrapratik28.thumbfree.data.HistoryWriteException
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// Robolectric only for android.util.Log, which DbThread writes a failed block to.
@RunWith(RobolectricTestRunner::class)
class DbThreadTest {
    private val db = DbThread()

    @Test
    fun barrierWaitsForEarlierWrites() {
        var done = false // barrier() is also what makes the write visible here
        db.write {
            Thread.sleep(200)
            done = true
        }

        runBlocking { db.barrier() }

        assertThat(done).isTrue()
    }

    @Test
    fun writeAndWaitTimesOut() {
        val start = System.nanoTime()

        val result = db.writeAndWait(500) { Thread.sleep(1_000); "late" }

        assertThat(result).isNull()
        assertThat((System.nanoTime() - start) / 1_000_000).isLessThan(700)
    }

    @Test
    fun writeAndWaitReturnsNullOnException() {
        val result = db.writeAndWait<Unit>(500) { throw HistoryWriteException(IllegalStateException("no row")) }

        assertThat(result).isNull()
    }
}
