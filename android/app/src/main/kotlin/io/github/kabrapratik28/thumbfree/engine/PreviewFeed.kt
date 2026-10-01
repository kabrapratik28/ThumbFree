package io.github.kabrapratik28.thumbfree.engine

import io.github.kabrapratik28.thumbfree.audio.PcmRing
import io.github.kabrapratik28.thumbfree.core.audio.PreviewGate
import io.github.kabrapratik28.thumbfree.core.session.Preview
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Live preview: feeds one take at a time to :engine's preview stream and reports what it says. The Recorder's writer
 * thread puts all of the take's audio in the take's [PcmRing] until the stop; a worker on [scope] opens the stream once
 * :engine has the take's model loaded, then sends the audio as it comes, whole Silero windows at a time: :engine's
 * Silero gate picks the speech (PreviewGate) and feeds the stream at most one chunk a call, giving way to the offline
 * path. While the engine loads, only the newest [WAIT_KEEP_MS] of audio waits. It gives up for the rest of the take
 * with [Preview.Event.Behind] when audio piles up past Preview.BEHIND_MS, here or in :engine, or the ring fills; and
 * with [Preview.Event.Failed] when the engine is gone, Silero fails or a feed fails. The take's own recording and text
 * never depend on it. [events] runs on the worker: post from it.
 */
class PreviewFeed(
    private val engine: Engine,
    private val scope: CoroutineScope, // one worker
    private val events: (Preview.Event) -> Unit,
) {
    private var job: Job? = null
    private var token = 0L // the running take's stream in :engine
    private var lastToken = 0L
    @Volatile private var ending: Job? = null // the last end sent to :engine, done once it is freed there

    /** Starts feeding [take], whose model file is [modelFile], from [ring], ending any take still fed. Main thread. */
    fun start(take: String, modelFile: String, ring: PcmRing) {
        stop()
        val token = ++lastToken
        this.token = token
        job = scope.launch {
            try {
                feed(take, modelFile, token, ring)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) { // anything unexpected ends the preview, never the take
                events(Preview.Event.Failed(take))
            }
        }
    }

    /**
     * Stops feeding and ends the stream in :engine, where a chunk in progress stops within a node; [awaitEnded] returns
     * once :engine has freed it and no call of the take can make anything there again. The first end goes at once,
     * alongside the feed's call in flight (on the IO pool), and stops it; that call is then waited for, and a second end
     * frees whatever it could still have made (a begin in flight, say). Main thread; again is a no-op.
     */
    fun stop() {
        val job = job ?: return
        this.job = null
        job.cancel()
        val token = token
        ending = scope.launch {
            engine.streamEnd(token)
            job.join()
            engine.streamEnd(token) // idempotent: nothing is left to free unless the call in flight made it
        }
    }

    /** Returns once the last stream [stop] ended is freed in :engine (at once when none is ending). */
    suspend fun awaitEnded() {
        ending?.join()
    }

    private suspend fun CoroutineScope.feed(take: String, modelFile: String, token: Long, ring: PcmRing) {
        val batch = ShortArray(MAX_BATCH)
        // The stream opens once :engine has this take's model: the queue loads it at the press, so a cold start or a
        // model switch waits here.
        while (true) {
            val status = engine.streamBegin(token, modelFile)
            if (status == 0) break
            if (status != NO_MODEL && status != StreamUpdate.BUSY && status != StreamUpdate.NO_ENGINE) {
                return events(Preview.Event.Failed(take)) // a model that cannot stream, Silero, or an engine error
            }
            if (!isActive) return
            while (ring.available > WAIT_KEEP_SAMPLES) { // only the newest audio waits
                ring.read(batch, 0, minOf(batch.size.toLong(), ring.available - WAIT_KEEP_SAMPLES).toInt())
            }
            if (ring.overflowed) return events(Preview.Event.Behind(take))
            delay(WAIT_POLL_MS)
        }
        var committed = ""
        var tentative = ""
        while (isActive) {
            if (ring.overflowed || ring.available > BEHIND_SAMPLES) return events(Preview.Event.Behind(take))
            val n = (minOf(ring.available, MAX_BATCH.toLong()) / PreviewGate.WINDOW * PreviewGate.WINDOW).toInt()
            if (n < MIN_BATCH) {
                delay(POLL_MS)
                continue
            }
            ring.read(batch, 0, n)
            val update = engine.streamFeed(token, batch, n) ?: return events(Preview.Event.Failed(take))
            when (update.status) {
                0, StreamUpdate.BUSY -> if (update.committed != committed || update.tentative != tentative) {
                    committed = update.committed
                    tentative = update.tentative
                    events(Preview.Event.Text(take, committed, tentative))
                }
                StreamUpdate.BEHIND -> return events(Preview.Event.Behind(take))
                else -> return events(Preview.Event.Failed(take)) // stale, aborted, Silero, or an engine error
            }
        }
    }

    companion object {
        private const val NO_MODEL = 1 // from streamBegin: the take's model is not the loaded one yet
        const val WAIT_KEEP_MS = 3_000
        private const val WAIT_POLL_MS = 100L
        private const val POLL_MS = 20L // the Recorder's read
        const val MIN_BATCH = 3 * PreviewGate.WINDOW // 96 ms: a call at most every 96 ms while audio flows
        const val MAX_BATCH = 32 * PreviewGate.WINDOW // about 1 s, to catch up after a wait
        private const val BEHIND_SAMPLES = Preview.BEHIND_MS * 16L
        private const val WAIT_KEEP_SAMPLES = WAIT_KEEP_MS * 16L

        /** The preview ring's size: the most a stream can be behind, and the engine's load, with room to spare. */
        const val RING_SAMPLES = 10 * 16_000
    }
}
