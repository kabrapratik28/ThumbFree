package io.github.kabrapratik28.thumbfree.core.models

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.StampedLock

/**
 * Per-model leases: a take or retranscription holds its model while it runs, and a delete must have the model to
 * itself. Stamps rather than locks a thread owns, so a lease taken on one thread may end on another.
 */
class ModelLeases {
    private val locks = ConcurrentHashMap<String, StampedLock>()

    private fun lock(model: ModelFile): StampedLock = locks.computeIfAbsent(model.id) { StampedLock() }

    /** A take's lease on [model], or 0 while a delete has it: the take goes on without one and finds the file gone. */
    fun hold(model: ModelFile): Long = lock(model).tryReadLock()

    fun release(model: ModelFile, lease: Long) {
        if (lease != 0L) lock(model).unlockRead(lease)
    }

    /** [model] reserved for a delete, or 0 while any take holds it. */
    fun tryDelete(model: ModelFile): Long = lock(model).tryWriteLock()

    fun deleted(model: ModelFile, reservation: Long) = lock(model).unlockWrite(reservation)
}
