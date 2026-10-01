package io.github.kabrapratik28.thumbfree.app

import android.content.Context
import com.google.common.truth.Truth.assertThat
import io.github.kabrapratik28.thumbfree.a11y.DictationAccessibilityService
import io.github.kabrapratik28.thumbfree.audio.ForegroundHooks
import io.github.kabrapratik28.thumbfree.data.HistoryDb
import io.github.kabrapratik28.thumbfree.data.Retention
import io.github.kabrapratik28.thumbfree.data.Settings
import io.github.kabrapratik28.thumbfree.data.Status
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class AppGraphTest {
    private val graph = AppGraphSnapshot()

    // init points these process-wide hooks at its ports, and fills AppGraph; later tests must not inherit either.
    @After
    fun tearDown() {
        DictationAccessibilityService.listener = null
        ForegroundHooks.listener = null
        if (AppGraph.initialized) AppGraph.history.close()
        graph.restore()
    }

    @Test
    fun mainProcessOnly() {
        assertThat(ThumbFreeApp.isMainProcess("io.github.kabrapratik28.thumbfree", "io.github.kabrapratik28.thumbfree")).isTrue()
        assertThat(ThumbFreeApp.isMainProcess("io.github.kabrapratik28.thumbfree:engine", "io.github.kabrapratik28.thumbfree")).isFalse()
    }

    @Test
    fun recoveryRunsAtInit() {
        val app = RuntimeEnvironment.getApplication()
        File(app.filesDir, "recordings/s1.wav").apply { parentFile!!.mkdirs() }.writeBytes(ByteArray(44 + 3_200))
        HistoryDb(app).use { it.create("s1", System.currentTimeMillis(), "recordings/s1.wav", "m", null) } // a take the process died in

        AppGraph.init(app)
        runBlocking { AppGraph.ports.db.barrier() }

        assertThat(AppGraph.history.get("s1")!!.status).isEqualTo(Status.INTERRUPTED)
    }

    // Retention also runs at start, after Recovery: a take past the owner's limit goes even before the next take ends.
    @Test
    fun retentionRunsAtInit() {
        val app = RuntimeEnvironment.getApplication()
        Settings(app.getSharedPreferences("settings", Context.MODE_PRIVATE)).retention = Retention(maxDays = 7, maxTakes = null)
        val now = System.currentTimeMillis()
        HistoryDb(app).use { db ->
            for ((id, startedAt) in listOf("old" to now - 8 * 86_400_000L, "new" to now)) {
                File(app.filesDir, "recordings/$id.wav").apply { parentFile!!.mkdirs() }.writeBytes(ByteArray(44))
                db.create(id, startedAt, "recordings/$id.wav", "m", null)
                db.finish(id, Status.INSERTED)
            }
        }

        AppGraph.init(app)
        runBlocking { AppGraph.ports.db.barrier() }

        assertThat(AppGraph.history.get("old")).isNull()
        assertThat(File(app.filesDir, "recordings/old.wav").exists()).isFalse()
        assertThat(AppGraph.history.get("new")).isNotNull()
    }
}
