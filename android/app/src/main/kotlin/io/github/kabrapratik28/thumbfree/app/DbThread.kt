package io.github.kabrapratik28.thumbfree.app

import android.util.Log
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred

/** One thread for every history write, in order. [onCommit] runs on it after each write that did not throw. */
class DbThread(private val onCommit: () -> Unit = {}) {
    private val thread = Executors.newSingleThreadExecutor { Thread(it, "history-db").apply { isDaemon = true } }

    /** Fire and forget; an exception is logged. */
    fun write(block: () -> Unit) {
        thread.execute { commit(block) }
    }

    /** Null on an exception or after [timeoutMs]. A write that timed out still runs, in its turn. */
    fun <T> writeAndWait(timeoutMs: Long, block: () -> T): T? = try {
        thread.submit(Callable { commit(block) }).get(timeoutMs, TimeUnit.MILLISECONDS)
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        null
    } catch (e: Exception) { // TimeoutException, or an Error the block threw (ExecutionException)
        null
    }

    /** Returns once every earlier write has finished. */
    suspend fun barrier() {
        val done = CompletableDeferred<Unit>()
        thread.execute { done.complete(Unit) }
        done.await()
    }

    private fun <T> commit(block: () -> T): T? = try {
        block().also { onCommit() }
    } catch (e: Exception) {
        Log.w("ThumbFree", "history write failed", e)
        null
    }
}
