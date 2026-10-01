package io.github.kabrapratik28.thumbfree.audio

import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ServiceInfo
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class RecordingServiceTest {
    private val calls = mutableListOf<String>()
    private val defaultStartService = ForegroundHooks.startServiceFn
    private val defaultStartForeground = ForegroundHooks.startForegroundFn

    @Before
    fun setUp() {
        ForegroundHooks.listener = object : ForegroundListener {
            override fun onForegroundStarted() { calls += "started" }
            override fun onForegroundDenied() { calls += "denied" }
            override fun onForegroundStopped() { calls += "stopped" }
        }
    }

    // ForegroundHooks and the start counters are process-wide, so every test leaves them as it found them.
    @After
    fun tearDown() {
        ForegroundHooks.listener = null
        ForegroundHooks.isForeground = false
        ForegroundHooks.takeActive = false
        ForegroundHooks.startServiceFn = defaultStartService
        ForegroundHooks.startForegroundFn = defaultStartForeground
        RecordingService.pendingStarts = 0
        RecordingService.stopWhenStarted = false
    }

    @Test
    fun startsForegroundWithMicrophoneType() {
        val service = Robolectric.buildService(RecordingService::class.java).create().startCommand(0, 1).get()

        assertThat(ForegroundHooks.isForeground).isTrue()
        assertThat(service.foregroundServiceType).isEqualTo(ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        assertThat(calls).containsExactly("started")
    }

    @Test
    fun notificationUsesChronometerAndNoActions() {
        val service = Robolectric.buildService(RecordingService::class.java).create().startCommand(0, 1).get()

        val notification = shadowOf(service).lastForegroundNotification
        assertThat(notification.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER)).isTrue()
        assertThat(notification.actions).isNull()
        assertThat(notification.channelId).isEqualTo("recording")
        val channel = service.getSystemService(NotificationManager::class.java).getNotificationChannel("recording")
        assertThat(channel.importance).isEqualTo(NotificationManager.IMPORTANCE_LOW)
    }

    @Test
    fun startServiceDenialIsReported() {
        val errors = listOf(ForegroundServiceStartNotAllowedException("background"), SecurityException(), IllegalStateException())
        for (error in errors) {
            calls.clear()
            ForegroundHooks.startServiceFn = { _, _ -> throw error }

            assertThat(RecordingService.start(RuntimeEnvironment.getApplication())).isFalse()
            assertThat(calls).containsExactly("denied")
        }
    }

    // The default seam: startForegroundService returns null when the system finds no such service.
    @Test
    fun missingServiceIsADenial() {
        val noService = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun startForegroundService(service: Intent): ComponentName? = null
        }

        assertThat(RecordingService.start(noService)).isFalse()
        assertThat(calls).containsExactly("denied")
    }

    // A refusal fails the take through onForegroundDenied alone; the destroy that follows must not stop it again.
    @Test
    fun startForegroundDenialNeverStopsATake() {
        ForegroundHooks.takeActive = true
        ForegroundHooks.startForegroundFn = { _, _, _, _ -> throw ForegroundServiceStartNotAllowedException("denied") }

        val controller = Robolectric.buildService(RecordingService::class.java).create().startCommand(0, 1)
        controller.destroy()

        assertThat(calls).containsExactly("denied")
        assertThat(ForegroundHooks.isForeground).isFalse()
        assertThat(shadowOf(controller.get()).isStoppedBySelf).isTrue()
    }

    @Test
    fun destroyDuringTakeNotifies() {
        val controller = Robolectric.buildService(RecordingService::class.java).create().startCommand(0, 1)
        ForegroundHooks.takeActive = true
        controller.destroy()
        assertThat(calls).containsExactly("started", "stopped").inOrder()

        calls.clear()
        ForegroundHooks.takeActive = false
        Robolectric.buildService(RecordingService::class.java).create().startCommand(0, 1).destroy()
        assertThat(calls).containsExactly("started")
    }

    // A take discarded in Arming (a drag right after the press) can stop the service before its onStartCommand ran;
    // stopService then would crash the app with ForegroundServiceDidNotStartInTimeException.
    @Test
    fun stopBeforeTheStartCommandWaitsForIt() {
        val app = RuntimeEnvironment.getApplication()
        assertThat(RecordingService.start(app)).isTrue()
        RecordingService.stop(app)
        assertThat(shadowOf(app).nextStoppedService).isNull()

        val controller = Robolectric.buildService(RecordingService::class.java).create().startCommand(0, 1)
        assertThat(shadowOf(controller.get()).isStoppedBySelf).isTrue()
        // Its take is gone: no callback may reach a newer take, even one that started before this destroy.
        assertThat(calls).isEmpty()
        ForegroundHooks.takeActive = true
        controller.destroy()
        assertThat(calls).isEmpty()

        RecordingService.stop(app)
        assertThat(shadowOf(app).nextStoppedService.component?.className).isEqualTo(RecordingService::class.java.name)
    }

    @Test
    fun discardedStartThatIsRefusedStaysSilent() {
        val app = RuntimeEnvironment.getApplication()
        ForegroundHooks.startForegroundFn = { _, _, _, _ -> throw ForegroundServiceStartNotAllowedException("denied") }
        RecordingService.start(app)
        RecordingService.stop(app)

        val controller = Robolectric.buildService(RecordingService::class.java).create().startCommand(0, 1)

        assertThat(calls).isEmpty()
        assertThat(shadowOf(controller.get()).isStoppedBySelf).isTrue()
    }

    // A new press lands before the discarded take's start command runs: the new take keeps the service.
    @Test
    fun startAfterAPendingStopKeepsTheService() {
        val app = RuntimeEnvironment.getApplication()
        RecordingService.start(app)
        RecordingService.stop(app)
        RecordingService.start(app)

        val controller = Robolectric.buildService(RecordingService::class.java).create().startCommand(0, 1).startCommand(0, 2)

        assertThat(shadowOf(controller.get()).isStoppedBySelf).isFalse()
        assertThat(ForegroundHooks.isForeground).isTrue()
    }
}
