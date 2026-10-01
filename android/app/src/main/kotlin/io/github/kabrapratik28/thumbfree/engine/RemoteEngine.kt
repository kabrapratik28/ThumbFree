package io.github.kabrapratik28.thumbfree.engine

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.DeadObjectException
import android.os.IBinder
import android.os.RemoteException
import android.os.SystemClock
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Binds [EngineService] with BIND_AUTO_CREATE on first use, waiting up to 10 s in all. DeadObjectException or
 * binderDied become [EngineDiedException], once per death; the next call rebinds. A death while idle shows at once in
 * [isAlive] and is reported by the next transcribe, so the caller learns the model is gone and loads it again; a load
 * just starts a new :engine. Use one instance per process: :engine ends only when its last binding goes, and a bind
 * waits for any other :engine to exit first.
 */
class RemoteEngine(context: Context) : Engine {
    private val context: Context = context.applicationContext
    private val calls = Mutex() // load, transcribe and unload run one at a time; abort skips it to reach a running call
    private val lock = Any() // guards the fields below; binderDied runs on a Binder thread
    private var service: IEngine? = null
    private var connection: ServiceConnection? = null
    private var died = false // a death that no call has reported yet

    override val isAlive: Boolean get() = synchronized(lock) { !died }

    override suspend fun load(modelPath: String, threads: Int): Int =
        call(reportDeath = false) { it.load(modelPath, threads) }

    override suspend fun transcribe(
        wavPath: String,
        fromSample: Long,
        toSample: Long,
        language: String?,
        allowRetry: Boolean,
        speechCheck: Boolean,
        token: Long,
    ): EngineResult = call { it.transcribe(wavPath, fromSample, toSample, language, allowRetry, speechCheck, token) }

    override fun abort(token: Long) {
        try {
            synchronized(lock) { service }?.abort(token) // oneway: never waits for :engine
        } catch (e: RemoteException) {
            // A dead engine runs nothing.
        }
    }

    override suspend fun unload() {
        calls.withLock {
            withContext(Dispatchers.IO) {
                try {
                    synchronized(lock) { service }?.unload()
                } catch (e: RemoteException) {
                    // A dead engine holds no model.
                }
                synchronized(lock) {
                    drop() // the last unbind ends the :engine process (EngineService.onDestroy)
                    died = false // the caller knows nothing is loaded
                }
            }
        }
    }

    override suspend fun streamBegin(token: Long, modelFile: String): Int =
        stream { it.streamBegin(token, modelFile) } ?: StreamUpdate.NO_ENGINE

    override suspend fun streamFeed(token: Long, pcm: ShortArray, n: Int): StreamUpdate? = stream {
        val bytes = ByteBuffer.allocate(n * 2).order(ByteOrder.LITTLE_ENDIAN)
        bytes.asShortBuffer().put(pcm, 0, n)
        it.streamFeed(token, bytes.array())
    }

    override suspend fun streamEnd(token: Long) {
        stream { it.streamEnd(token) } // a dead engine has no stream
    }

    override suspend fun streamRelease() {
        stream { it.streamRelease() }
    }

    /**
     * The loaded model's key=value line (arch, variant, languages and so on, and the preview stream's state), "" when
     * none is loaded or no engine is up. For benchmarks, tests and diagnostics: it never binds and never waits for a
     * transcribe.
     */
    suspend fun info(): String = stream { it.info() }.orEmpty()

    /**
     * A live preview stream call. It skips [calls], so it never waits for or holds up a load or transcribe, and it
     * never binds: with no engine up yet, or one that died, it returns null. A death it meets is left to linkToDeath,
     * which the queue's next call reports.
     */
    private suspend fun <T> stream(block: (IEngine) -> T): T? = withContext(Dispatchers.IO) {
        val engine = synchronized(lock) { service.takeIf { !died } } ?: return@withContext null
        try {
            block(engine)
        } catch (e: RemoteException) {
            null
        }
    }

    private suspend fun <T> call(reportDeath: Boolean = true, block: (IEngine) -> T): T = calls.withLock {
        withContext(Dispatchers.IO) {
            val engine = synchronized(lock) {
                if (died) {
                    died = false
                    if (reportDeath) throw EngineDiedException() // a load needs no report: it loads the model again
                }
                service
            } ?: bind()
            try {
                block(engine)
            } catch (e: DeadObjectException) {
                synchronized(lock) {
                    if (service === engine) {
                        drop() // binderDied, when it comes, finds the binding gone and reports nothing
                    } else {
                        died = false // binderDied came first; this call reports that death
                    }
                }
                throw EngineDiedException()
            }
        }
    }

    private suspend fun bind(): IEngine {
        val deadline = SystemClock.elapsedRealtime() + BIND_TIMEOUT_MS
        awaitEngineExit()
        val connected = CompletableDeferred<IBinder>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                connected.complete(binder)
            }

            override fun onServiceDisconnected(name: ComponentName) {} // binderDied reports it
        }
        val intent = Intent(context, EngineService::class.java)
        // The callbacks run on the Binder thread that delivers them, so a busy main thread cannot stall the bind.
        val bound = context.bindService(intent, Context.BIND_AUTO_CREATE, Runnable::run, connection)
        val binder = try {
            if (bound) withTimeoutOrNull(deadline - SystemClock.elapsedRealtime()) { connected.await() } else null
        } catch (e: CancellationException) {
            // The caller gave up. A binding left behind would keep :engine alive, and unbinding before the service
            // is up leaves the process started for it running empty. So let the bind finish, then unbind: that ends
            // the process (EngineService.onDestroy).
            withContext(NonCancellable) {
                withTimeoutOrNull(deadline - SystemClock.elapsedRealtime()) { connected.await() }
            }
            context.unbindService(connection)
            throw e
        }
        if (binder == null) {
            context.unbindService(connection)
            throw EngineDiedException()
        }
        val engine = IEngine.Stub.asInterface(binder)
        synchronized(lock) {
            service = engine
            this.connection = connection
        }
        try {
            binder.linkToDeath({
                synchronized(lock) {
                    if (service === engine) {
                        drop()
                        died = true
                    }
                }
            }, 0)
        } catch (e: RemoteException) {
            // Already dead: the first call gets DeadObjectException and reports it.
        }
        return engine
    }

    // Unload and a death drop the binding just before the next bind. A bind that reaches the system before it has
    // seen that :engine exit is sent to the dead process, and the system starts a new one only 4 s later (Android
    // 16 emulator). So wait until the process list shows no :engine, polling every 10 ms for at most 2 s of the
    // bind's 10 s.
    private suspend fun awaitEngineExit() {
        val activityManager = context.getSystemService(ActivityManager::class.java)
        val name = "${context.packageName}:engine"
        withTimeoutOrNull(EXIT_WAIT_MS) {
            while (activityManager.runningAppProcesses.orEmpty().any { it.processName == name }) delay(10)
        }
    }

    // Caller holds lock. Unbinding also keeps the system from restarting a dead :engine in the background.
    private fun drop() {
        connection?.let(context::unbindService)
        connection = null
        service = null
    }

    private companion object {
        const val BIND_TIMEOUT_MS = 10_000L
        const val EXIT_WAIT_MS = 2_000L
    }
}
