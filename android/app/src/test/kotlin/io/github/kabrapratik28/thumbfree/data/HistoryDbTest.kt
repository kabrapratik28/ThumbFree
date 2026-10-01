package io.github.kabrapratik28.thumbfree.data

import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteFullException
import com.google.common.truth.Truth.assertThat
import io.github.kabrapratik28.thumbfree.data.Status.FAILED
import io.github.kabrapratik28.thumbfree.data.Status.INSERTED
import io.github.kabrapratik28.thumbfree.data.Status.INSERTING
import io.github.kabrapratik28.thumbfree.data.Status.NO_SPEECH
import io.github.kabrapratik28.thumbfree.data.Status.RECORDING
import io.github.kabrapratik28.thumbfree.data.Status.STAGED
import io.github.kabrapratik28.thumbfree.data.Status.TRANSCRIBING
import java.io.File
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class HistoryDbTest {
    @get:Rule val tmp = TemporaryFolder()
    private val context = RuntimeEnvironment.getApplication()
    private val db = HistoryDb(context, null)

    @After
    fun close() = db.close()

    // A retranscription's stage names the model that made its text; a stage without one keeps the row's.
    @Test
    fun stageCanNameTheModelThatMadeTheText() {
        db.create("s1", 1000, "recordings/s1.wav", "parakeet", null)

        db.stage("s1", "Raw.", "Text.", 1500)
        assertThat(db.get("s1")!!.modelId).isEqualTo("parakeet")
        db.stage("s1", "Raw.", "Text.", 1500, modelId = "canary")
        assertThat(db.get("s1")!!.modelId).isEqualTo("canary")
    }

    @Test
    fun lifecycle() {
        db.create("s1", 1000, "recordings/s1.wav", "m", "com.x")
        assertThat(db.get("s1")!!.status).isEqualTo(RECORDING)

        db.stage("s1", "Raw.", "Text.", 1500)
        val staged = db.get("s1")!!
        assertThat(staged.status).isEqualTo(STAGED)
        assertThat(staged.rawText).isEqualTo("Raw.")
        assertThat(staged.text).isEqualTo("Text.")
        assertThat(staged.durationMs).isEqualTo(1500)

        db.markInserting("s1")
        assertThat(db.get("s1")!!.status).isEqualTo(INSERTING)

        db.finish("s1", INSERTED, insertedText = " Text.")
        val done = db.get("s1")!!
        assertThat(done.status).isEqualTo(INSERTED)
        assertThat(done.insertedText).isEqualTo(" Text.")
    }

    // A take that failed or was cancelled never reaches stage(), so finish can carry its length; null keeps the row's.
    @Test
    fun finishWritesTheDurationOnlyWhenGiven() {
        db.create("s1", 1000, "recordings/s1.wav", "m", null)

        db.finish("s1", FAILED, "NO_MODEL", durationMs = 10_000)
        assertThat(db.get("s1")!!.durationMs).isEqualTo(10_000)

        db.finish("s1", FAILED, "NO_MODEL")
        assertThat(db.get("s1")!!.durationMs).isEqualTo(10_000)
    }

    @Test
    fun saveChunkAccumulates() {
        db.create("s1", 1000, "recordings/s1.wav", "m", null)

        db.saveChunk("s1", 1, "a")
        db.saveChunk("s1", 2, "a b")

        val row = db.get("s1")!!
        assertThat(row.chunksDone).isEqualTo(2)
        assertThat(row.partialText).isEqualTo("a b")
    }

    // A take that ends NO_SPEECH keeps no text. Its chunks can bring words before the take ends, and a Retry of a
    // staged take can end NO_SPEECH too. The status and the clearing are one UPDATE; the WAV stays for Retry.
    @Test
    fun noSpeechClearsTheText() {
        db.create("s1", 1000, "recordings/s1.wav", "m", null)
        db.saveChunk("s1", 1, "Yeah.")
        db.stage("s1", "yeah", "Yeah.", 1500)

        db.finish("s1", NO_SPEECH)

        val row = db.get("s1")!!
        assertThat(row.status).isEqualTo(NO_SPEECH)
        assertThat(listOf(row.partialText, row.rawText, row.text)).containsExactly(null, null, null)
        assertThat(row.audioFile).isEqualTo("recordings/s1.wav")
        assertThat(row.chunksDone).isEqualTo(1)
    }

    @Test
    fun duplicateSessionIdFails() {
        db.create("s1", 1000, "recordings/s1.wav", "m", null)

        assertThrows(HistoryWriteException::class.java) { db.create("s1", 2000, "recordings/s1.wav", "m", null) }
    }

    @Test
    fun updateOfMissingRowFails() {
        // The controller reads a failed stage as "the text is not durable" and turns automatic insertion off.
        assertThrows(HistoryWriteException::class.java) { db.stage("gone", "Raw.", "Text.", 1500) }
    }

    @Test
    fun listNewestFirst() {
        // Out of insertion order, so the order comes from started_at and not the row id.
        for (t in listOf(1L, 3L, 2L)) db.create("s$t", t, "recordings/s$t.wav", "m", null)

        assertThat(db.list(2).map { it.startedAt }).isEqualTo(listOf(3L, 2L))
    }

    @Test
    fun retentionKeepsNewestUnstarred() {
        for (t in 1L..152L) row("s$t", t, INSERTED)
        db.writableDatabase.execSQL("UPDATE dictation SET starred = 1 WHERE session_id = 's1'")

        assertThat(db.applyRetention(Retention(maxDays = null, maxTakes = 150), NOW, tmp.root)).isEqualTo(1)

        assertThat(db.get("s2")).isNull()
        assertThat(wav("s2").exists()).isFalse()
        val kept = (152L downTo 3L) + 1L
        assertThat(db.list(200).map { it.startedAt }).isEqualTo(kept)
        assertThat(kept.filterNot { wav("s$it").exists() }).isEmpty()
    }

    // A take recording, transcribing, staged or inserting is never deleted, however old, and whatever the count.
    @Test
    fun retentionNeverDeletesLiveRows() {
        val live = listOf(RECORDING, TRANSCRIBING, STAGED, INSERTING)
        live.forEachIndexed { i, status -> row("live$i", NOW - 400 * DAY, status) }
        for (t in 3L downTo 1L) row("s$t", NOW - t, INSERTED) // s1, the newest, is made last

        assertThat(db.applyRetention(Retention(maxDays = 7, maxTakes = 1), NOW, tmp.root)).isEqualTo(2)

        assertThat(live.indices.map { db.get("live$it")!!.status }).isEqualTo(live)
        assertThat(live.indices.filterNot { wav("live$it").exists() }).isEmpty()
        assertThat(db.get("s1")).isNotNull() // the newest terminal take
    }

    @Test
    fun retentionSurvivesBackwardClockJump() {
        for (t in 1L..150L) row("s$t", 1000 + t, INSERTED)
        row("new", 1, INSERTED) // the clock jumped back before this take started

        assertThat(db.applyRetention(Retention(maxDays = null, maxTakes = 150), NOW, tmp.root)).isEqualTo(1)

        assertThat(db.get("new")).isNotNull()
        assertThat(wav("new").exists()).isTrue()
        assertThat(db.get("s1")).isNull()
    }

    @Test
    fun retentionSkipsAWavItCannotDelete() {
        // saveOutcome runs finish and retention in one history write: a throw here would hide the saved outcome.
        for (t in 1L..152L) row("s$t", t, INSERTED)
        wav("s2").delete()
        File(wav("s2"), "x").apply { parentFile!!.mkdirs() }.writeBytes(ByteArray(1)) // cannot be unlinked

        assertThat(db.applyRetention(Retention(maxDays = null, maxTakes = 150), NOW, tmp.root)).isEqualTo(1)

        assertThat(db.get("s2")).isNotNull() // stays for the next pass
        assertThat(db.get("s1")).isNull() // the pass went on past it
        assertThat(wav("s1").exists()).isFalse()
    }

    // The age rule deletes a take, row and WAV, once it started more than maxDays before now.
    @Test
    fun retentionByAge() {
        row("old", NOW - 7 * DAY - 1, INSERTED)
        row("edge", NOW - 7 * DAY, NO_SPEECH)
        row("new", NOW - DAY, FAILED)

        assertThat(db.applyRetention(Retention(maxDays = 7, maxTakes = null), NOW, tmp.root)).isEqualTo(1)

        assertThat(db.get("old")).isNull()
        assertThat(wav("old").exists()).isFalse()
        assertThat(db.list(10).map { it.sessionId }).containsExactly("new", "edge").inOrder()
    }

    // Both rules apply: a take either one removes goes, so the stricter rule decides.
    @Test
    fun retentionAppliesBothRules() {
        fun fresh() {
            db.writableDatabase.execSQL("DELETE FROM dictation")
            for (d in 5L downTo 1L) row("d$d", NOW - d * DAY - 1, INSERTED) // oldest first, so ids grow as takes get newer
        }

        fresh()
        assertThat(db.applyRetention(Retention(maxDays = 3, maxTakes = 4), NOW, tmp.root)).isEqualTo(3) // days remove more
        assertThat(db.list(10).map { it.sessionId }).containsExactly("d1", "d2").inOrder()

        fresh()
        assertThat(db.applyRetention(Retention(maxDays = 3, maxTakes = 1), NOW, tmp.root)).isEqualTo(4) // the count removes more
        assertThat(db.list(10).map { it.sessionId }).containsExactly("d1")

        fresh()
        assertThat(db.applyRetention(Retention(maxDays = null, maxTakes = null), NOW, tmp.root)).isEqualTo(0) // forever, unlimited
        assertThat(db.list(10)).hasSize(5)
    }

    // What Settings shows before a stricter rule applies: the takes it would delete, with nothing deleted yet.
    @Test
    fun overRetentionOnlyCounts() {
        for (d in 5L downTo 1L) row("d$d", NOW - d * DAY - 1, INSERTED)

        assertThat(db.overRetention(Retention(maxDays = 3, maxTakes = null), NOW).map { it.sessionId })
            .containsExactly("d3", "d4", "d5").inOrder()
        assertThat(db.list(10)).hasSize(5)
        assertThat((1..5).filterNot { wav("d$it").exists() }).isEmpty()
    }

    @Test
    fun userVersionIsTwo() {
        assertThat(db.readableDatabase.version).isEqualTo(2)
    }

    @Test
    fun deleteRemovesWavThenRow() {
        row("s1", 1, INSERTED)
        db.create("s2", 2, "recordings/s2.wav", "m", null) // no WAV, like an AUDIO_MISSING row

        db.delete("s1", tmp.root)
        db.delete("s2", tmp.root)
        db.delete("gone", tmp.root) // a missing row is fine

        assertThat(wav("s1").exists()).isFalse()
        assertThat(db.get("s1")).isNull()
        assertThat(db.get("s2")).isNull()
    }

    @Test
    fun undeletableWavKeepsItsRow() {
        db.create("s1", 1, "recordings/s1.wav", "m", null)
        // A non-empty directory where the WAV should be: File.delete() returns false, as it does on an I/O error.
        File(wav("s1"), "x").apply { parentFile!!.mkdirs() }.writeBytes(ByteArray(1))

        assertThrows(HistoryWriteException::class.java) { db.delete("s1", tmp.root) }

        assertThat(db.get("s1")).isNotNull() // the user can still find the recording and delete it again
        assertThat(wav("s1").exists()).isTrue()
    }

    @Test
    fun writeFailureSurfaces() {
        val full = object : HistoryDb(context, null) {
            override fun db(): SQLiteDatabase = throw SQLiteFullException("database or disk is full")
        }

        assertThrows(HistoryWriteException::class.java) { full.create("s1", 1000, "recordings/s1.wav", "m", null) }
    }

    @Test
    fun rawFinalAndPartialSurviveReopen() {
        HistoryDb(context, "t.db").apply {
            create("s1", 1000, "recordings/s1.wav", "m", null)
            saveChunk("s1", 1, "Um so we")
            stage("s1", "um so we go", "so we go.", 1500)
            close()
        }

        val row = HistoryDb(context, "t.db").use { it.get("s1")!! }
        assertThat(row.rawText).isEqualTo("um so we go")
        assertThat(row.text).isEqualTo("so we go.")
        assertThat(row.partialText).isEqualTo("Um so we")
    }

    @Test
    fun truncatedPartialSurvivesReopen() {
        HistoryDb(context, "t.db").apply {
            create("s2", 1000, "recordings/s2.wav", "m", null)
            saveChunk("s2", 1, "half a sent")
            finish("s2", FAILED, "TRUNCATED")
            close()
        }

        val row = HistoryDb(context, "t.db").use { it.get("s2")!! }
        assertThat(row.status).isEqualTo(FAILED)
        assertThat(row.error).isEqualTo("TRUNCATED")
        assertThat(row.partialText).isEqualTo("half a sent")
        assertThat(row.text).isNull()
    }

    private fun wav(id: String) = File(tmp.root, "recordings/$id.wav")

    private companion object {
        const val DAY = 86_400_000L
        const val NOW = 1_000 * DAY // a fixed clock
    }

    // Transcribe again from history: the new text is saved in one write. A take typed into an app (or that may be in its
    // field) keeps that status and what was typed, and shows the new text marked as transcribed again; any other take is
    // Not inserted with the new text. A later stage (a Retry that types its text) clears the mark.
    @Test
    fun aRetranscriptionKeepsATypedTakesStatus() {
        for ((id, before) in listOf("typed" to INSERTED, "check" to Status.UNVERIFIED, "maybe" to Status.NEEDS_REVIEW, "failed" to FAILED)) {
            db.create(id, 1, "recordings/$id.wav", "m", "com.example")
            db.stage(id, "old", "Old text.", 1_000)
            db.finish(id, before, insertedText = if (before == FAILED) null else "Old text.")
        }

        for (id in listOf("typed", "check", "maybe", "failed")) db.saveRetranscription(id, "new", "New text.", "m2")

        val typed = db.get("typed")!!
        assertThat(typed.status).isEqualTo(INSERTED)
        assertThat(typed.insertedText).isEqualTo("Old text.")
        assertThat(listOf(typed.text, typed.rawText, typed.modelId, typed.retranscribed)).containsExactly("New text.", "new", "m2", true).inOrder()
        assertThat(db.get("check")!!.status).isEqualTo(Status.UNVERIFIED)
        assertThat(db.get("maybe")!!.status).isEqualTo(Status.NEEDS_REVIEW)
        val failed = db.get("failed")!!
        assertThat(listOf(failed.status, failed.text, failed.retranscribed)).containsExactly(Status.NOT_INSERTED, "New text.", true).inOrder()

        db.stage("typed", "again", "Again.", 1_000)
        assertThat(db.get("typed")!!.retranscribed).isFalse()
    }

    // No speech on the second try: a typed take stays exactly as it was; any other take ends No speech, its text gone.
    @Test
    fun aBlankRetranscriptionLeavesATypedTakeAlone() {
        db.create("typed", 1, "recordings/typed.wav", "m", "com.example")
        db.stage("typed", "old", "Old text.", 1_000)
        db.finish("typed", INSERTED, insertedText = "Old text.")
        db.create("failed", 2, "recordings/failed.wav", "m", null)
        db.finish("failed", FAILED, "ENGINE_CRASHED")
        val before = db.get("typed")

        db.saveRetranscription("typed", "", "", "m2")
        db.saveRetranscription("failed", "", "", "m2")

        assertThat(db.get("typed")).isEqualTo(before)
        assertThat(db.get("failed")!!.status).isEqualTo(NO_SPEECH)
        assertThrows(HistoryWriteException::class.java) { db.saveRetranscription("gone", "x", "X.", null) }
    }

    // user_version 1 had no retranscribed column: opening it as version 2 adds it, every old row unmarked.
    @Test
    fun version1HistoryGainsTheRetranscribedMark() {
        val file = tmp.newFile("old.db").apply { delete() }
        SQLiteDatabase.openOrCreateDatabase(file, null).use {
            it.execSQL(
                "CREATE TABLE dictation(id INTEGER PRIMARY KEY AUTOINCREMENT, session_id TEXT NOT NULL UNIQUE, " +
                    "started_at INTEGER NOT NULL, duration_ms INTEGER NOT NULL DEFAULT 0, audio_file TEXT NOT NULL, " +
                    "model_id TEXT NOT NULL, status TEXT NOT NULL, error TEXT, raw_text TEXT, text TEXT, partial_text TEXT, " +
                    "chunks_done INTEGER NOT NULL DEFAULT 0, inserted_text TEXT, target_package TEXT, starred INTEGER NOT NULL DEFAULT 0)",
            )
            it.execSQL("INSERT INTO dictation(session_id, started_at, audio_file, model_id, status, text, inserted_text) " +
                "VALUES ('old', 1, 'recordings/old.wav', 'm', 'INSERTED', 'Hi.', 'Hi.')")
            it.version = 1
        }

        val upgraded = HistoryDb(context, file.path)
        try {
            assertThat(upgraded.get("old")!!.retranscribed).isFalse()
            upgraded.saveRetranscription("old", "hey", "Hey.", null)
            assertThat(upgraded.get("old")!!.retranscribed).isTrue()
        } finally {
            upgraded.close()
        }
    }

    private fun row(id: String, startedAt: Long, status: Status) {
        db.create(id, startedAt, "recordings/$id.wav", "m", null)
        db.setStatus(id, status)
        wav(id).apply { parentFile!!.mkdirs() }.writeBytes(ByteArray(44))
    }
}
