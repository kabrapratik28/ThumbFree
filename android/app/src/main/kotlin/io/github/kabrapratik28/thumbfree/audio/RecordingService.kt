package io.github.kabrapratik28.thumbfree.audio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import io.github.kabrapratik28.thumbfree.R

interface ForegroundListener {
    fun onForegroundStarted()                 // startForeground succeeded: the microphone may open now
    fun onForegroundDenied()                  // startForegroundService or startForeground threw
    fun onForegroundStopped()                 // a service that had reached the foreground was destroyed while a take was active
}

object ForegroundHooks {
    @Volatile var listener: ForegroundListener? = null
    @Volatile var isForeground: Boolean = false
    @Volatile var takeActive: Boolean = false
    /** Test seams; default to Context.startForegroundService(intent) and Service.startForeground(id, notification, type). */
    var startServiceFn: (Context, Intent) -> Unit =
        { context, intent -> check(context.startForegroundService(intent) != null) { "RecordingService not found" } }
    var startForegroundFn: (Service, Int, Notification, Int) -> Unit =
        { service, id, notification, type -> service.startForeground(id, notification, type) }
}

/**
 * The microphone foreground service, a gate for each take: a background app without it records silence, and
 * Android 14 and later can refuse to start it. The mic opens only after [ForegroundListener.onForegroundStarted].
 */
class RecordingService : Service() {
    companion object {
        const val CHANNEL_ID = "recording"
        const val NOTIFICATION_ID = 1

        // Main thread. Stopping the service before its onStartCommand has called startForeground crashes the app
        // (ForegroundServiceDidNotStartInTimeException), so a stop that comes while a start is pending waits for it.
        // Internal only so tests can reset them.
        internal var pendingStarts = 0
        internal var stopWhenStarted = false

        /** False, after listener.onForegroundDenied(), when startForegroundService throws. */
        fun start(context: Context): Boolean = try {
            ForegroundHooks.startServiceFn(context, Intent(context, RecordingService::class.java))
            pendingStarts++
            stopWhenStarted = false
            true
        } catch (e: Exception) {
            Log.w("ThumbFree", "foreground refused", e)
            ForegroundHooks.listener?.onForegroundDenied()
            false
        }

        fun stop(context: Context) {
            if (pendingStarts > 0) stopWhenStarted = true
            else context.stopService(Intent(context, RecordingService::class.java))
        }
    }

    // Only an instance that reached the foreground can stop a take when it goes; a refused one never does.
    private var reachedForeground = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (pendingStarts > 0) pendingStarts--
        // A stop came before this command ran: that take is gone, so no callback may reach a newer take.
        val discarded = stopWhenStarted && pendingStarts == 0
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.recording_channel), NotificationManager.IMPORTANCE_LOW),
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.brand_mark)
            .setContentTitle(getString(R.string.recording_title))
            .setUsesChronometer(true)
            .setWhen(System.currentTimeMillis())
            .build()
        // Called for a discarded start too: stopping before startForeground crashes the app.
        val started = try {
            ForegroundHooks.startForegroundFn(this, NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            true
        } catch (e: Exception) {
            Log.w("ThumbFree", "foreground refused", e)
            false
        }
        when {
            discarded -> {
                stopWhenStarted = false
                stopSelf()
            }
            started -> {
                ForegroundHooks.isForeground = true
                reachedForeground = true
                ForegroundHooks.listener?.onForegroundStarted()
            }
            else -> {
                ForegroundHooks.listener?.onForegroundDenied()
                stopSelf()
            }
        }
        // Never restarted after the process dies: without a live take there is nothing to record.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        ForegroundHooks.isForeground = false
        if (reachedForeground && ForegroundHooks.takeActive) ForegroundHooks.listener?.onForegroundStopped()
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder? = null
}
