package io.github.kabrapratik28.thumbfree.ui

import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteDiskIOException
import com.google.common.truth.Truth.assertThat
import io.github.kabrapratik28.thumbfree.app.DbThread
import io.github.kabrapratik28.thumbfree.data.HistoryDb
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class ReadHistoryTest {
    private val app = RuntimeEnvironment.getApplication()

    // The home screen's refresh reads the history in a coroutine nothing guards, where an SQLite error would end the
    // app. It reads as null (the screen says so) instead; a readable history reads as usual.
    @Test
    fun unreadableHistoryIsNullNotThrown() = runBlocking<Unit> {
        val db = DbThread()
        val broken = object : HistoryDb(app, null) {
            override fun db(): SQLiteDatabase = throw SQLiteDiskIOException("disk I/O error")
        }

        assertThat(readHistory(db, broken)).isNull()
        HistoryDb(app, null).use { history ->
            history.create("s1", 0, "recordings/s1.wav", "m", null)
            assertThat(readHistory(db, history)!!.map { it.sessionId }).containsExactly("s1")
        }
    }
}
