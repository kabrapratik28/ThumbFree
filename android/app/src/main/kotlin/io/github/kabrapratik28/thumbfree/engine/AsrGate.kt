package io.github.kabrapratik28.thumbfree.engine

import java.util.concurrent.atomic.AtomicInteger

/**
 * Who runs on :engine's asr thread next, offline calls or the live preview's. Offline calls (load, transcribe, unload)
 * take [lock] in turn, as they would without the preview. Preview calls take it only while no offline call waits or
 * runs, and check again once they hold it: one that finds an offline call arrived while it waited gives way at once
 * (its busy value). So an offline call waits at most for preview work admitted before it came, one stream chunk, and
 * never for work admitted after. [checked] and [waiting] are test hooks: after a preview call's first check, and after
 * an offline call has raised its count but before it takes the lock.
 */
class AsrGate(
    @PublishedApi internal val checked: () -> Unit = {},
    @PublishedApi internal val waiting: () -> Unit = {},
) {
    val lock = Any()

    @PublishedApi internal val offline = AtomicInteger() // offline calls waiting for or holding lock

    inline fun <T> offline(block: () -> T): T {
        offline.incrementAndGet()
        try {
            waiting()
            synchronized(lock) { return block() }
        } finally {
            offline.decrementAndGet()
        }
    }

    inline fun <T> preview(busy: () -> T, block: () -> T): T {
        if (offline.get() > 0) return busy()
        checked()
        synchronized(lock) {
            if (offline.get() > 0) return busy() // an offline call came while this one waited for the lock
            return block()
        }
    }
}
