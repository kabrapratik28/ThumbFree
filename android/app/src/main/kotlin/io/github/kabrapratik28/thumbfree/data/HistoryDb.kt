package io.github.kabrapratik28.thumbfree.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteOpenHelper
import java.io.File
import java.io.IOException

enum class Status {
    RECORDING, TRANSCRIBING, STAGED, INSERTING,
    INSERTED, UNVERIFIED, NOT_INSERTED, NO_SPEECH, CANCELLED, FAILED, INTERRUPTED, NEEDS_REVIEW;
    val terminal: Boolean get() = this !in setOf(RECORDING, TRANSCRIBING, STAGED, INSERTING)
}

data class Dictation(
    val id: Long, val sessionId: String, val startedAt: Long, val durationMs: Long, val audioFile: String,
    val modelId: String, val status: Status, val error: String?, val rawText: String?, val text: String?,
    val partialText: String?, val chunksDone: Int, val insertedText: String?, val targetPackage: String?, val starred: Boolean,
    /** Transcribed again from history: [text] is that new transcript, which was not typed anywhere. */
    val retranscribed: Boolean = false,
)

class HistoryWriteException(cause: Throwable) : Exception(cause)

/**
 * How long history keeps takes: at most [maxTakes] of them, none older than [maxDays] days; null is no limit. Both apply,
 * so a take either rule removes goes.
 */
data class Retention(val maxDays: Int?, val maxTakes: Int?) {
    companion object {
        val DAYS = listOf(7, 30, 90, null)
        val TAKES = listOf(50, 200, 1_000, null)
    }
}

private const val TABLE = "dictation"

/** The non-terminal statuses as an SQL list: 'RECORDING', 'TRANSCRIBING', 'STAGED', 'INSERTING'. */
private val LIVE = Status.entries.filterNot { it.terminal }.joinToString { "'$it'" }

/** A take whose text was typed into an app, or may be in its field. */
private val TYPED = setOf(Status.INSERTED, Status.UNVERIFIED, Status.NEEDS_REVIEW)

/**
 * Table "dictation", user_version 2 (2 added `retranscribed`). Every write throws HistoryWriteException on an SQLite error,
 * and an update also throws when the session has no row, so a lost row never reads as saved text.
 * A delete also throws when the WAV cannot be deleted, and that row stays; retention skips such a row instead.
 * [Dictation.audioFile] is relative to filesDir.
 * Blocking. Call on the database thread, never main.
 */
open class HistoryDb(context: Context, name: String? = "history.db") : SQLiteOpenHelper(context, name, null, 2) {
    override fun onCreate(db: SQLiteDatabase) = db.execSQL(
        "CREATE TABLE dictation(id INTEGER PRIMARY KEY AUTOINCREMENT, session_id TEXT NOT NULL UNIQUE, " +
            "started_at INTEGER NOT NULL, duration_ms INTEGER NOT NULL DEFAULT 0, audio_file TEXT NOT NULL, " +
            "model_id TEXT NOT NULL, status TEXT NOT NULL, error TEXT, raw_text TEXT, text TEXT, partial_text TEXT, " +
            "chunks_done INTEGER NOT NULL DEFAULT 0, inserted_text TEXT, target_package TEXT, starred INTEGER NOT NULL DEFAULT 0, " +
            "retranscribed INTEGER NOT NULL DEFAULT 0)",
    )

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) db.execSQL("ALTER TABLE $TABLE ADD COLUMN retranscribed INTEGER NOT NULL DEFAULT 0")
    }

    fun create(sessionId: String, startedAt: Long, audioFile: String, modelId: String, targetPackage: String?): Long = write { db ->
        db.insertOrThrow(TABLE, null, ContentValues().apply {
            put("session_id", sessionId)
            put("started_at", startedAt)
            put("audio_file", audioFile)
            put("model_id", modelId)
            put("status", Status.RECORDING.name)
            put("target_package", targetPackage)
        })
    }

    fun setStatus(sessionId: String, status: Status, error: String? = null) = update(sessionId) {
        put("status", status.name)
        put("error", error)
    }

    fun saveChunk(sessionId: String, chunksDone: Int, partialText: String) = update(sessionId) {
        put("chunks_done", chunksDone)
        put("partial_text", partialText)
    }

    /** A non-null [modelId] replaces the row's model, in the same write: the model that made this text. */
    fun stage(sessionId: String, rawText: String, text: String, durationMs: Long, modelId: String? = null) = update(sessionId) {
        put("status", Status.STAGED.name)
        put("raw_text", rawText)
        put("text", text)
        put("retranscribed", 0) // this text is the one a take types
        put("duration_ms", durationMs)
        if (modelId != null) put("model_id", modelId)
    }

    fun markInserting(sessionId: String) = update(sessionId) { put("status", Status.INSERTING.name) }

    /**
     * Transcribe again from history, in one write: nothing is typed. A take whose text was typed into an app, or may be
     * in its field (INSERTED, UNVERIFIED, NEEDS_REVIEW), keeps that status and its inserted text, and takes [text] marked
     * `retranscribed`; any other take ends NOT_INSERTED with [text]. A blank [text] (no speech) leaves a typed take as it
     * was and ends any other NO_SPEECH, its text cleared. A non-null [modelId] names the model that made [text].
     */
    fun saveRetranscription(sessionId: String, rawText: String, text: String, modelId: String?) {
        val typed = get(sessionId)?.status in TYPED
        if (text.isBlank() && typed) return
        update(sessionId) {
            if (text.isBlank()) {
                put("status", Status.NO_SPEECH.name)
                clearText()
                return@update
            }
            if (!typed) put("status", Status.NOT_INSERTED.name)
            put("raw_text", rawText)
            put("text", text)
            put("retranscribed", 1)
            if (modelId != null) put("model_id", modelId)
        }
    }

    /**
     * A null [durationMs] keeps the row's duration. NO_SPEECH clears the row's text in the same write, so words its
     * chunks brought before the take ended never show.
     */
    fun finish(sessionId: String, status: Status, error: String? = null, insertedText: String? = null, durationMs: Long? = null) =
        update(sessionId) {
            put("status", status.name)
            put("error", error)
            put("inserted_text", insertedText)
            if (durationMs != null) put("duration_ms", durationMs)
            if (status == Status.NO_SPEECH) clearText()
        }

    /**
     * For Recovery, a take that died before its speech decision (RECORDING, TRANSCRIBING): [status] and [error], with its
     * text cleared in the same write, since no one confirmed it was speech. The WAV stays for Transcribe. A null
     * [durationMs] keeps the row's duration (the take never reached stage(), which sets it).
     */
    internal fun recoverUnconfirmed(sessionId: String, status: Status, error: String? = null, durationMs: Long? = null) =
        update(sessionId) {
            put("status", status.name)
            put("error", error)
            if (durationMs != null) put("duration_ms", durationMs)
            clearText()
        }

    private fun ContentValues.clearText() {
        putNull("partial_text")
        putNull("raw_text")
        putNull("text")
        putNull("inserted_text")
        put("retranscribed", 0)
    }

    fun get(sessionId: String): Dictation? = query("session_id = ?", sessionId).firstOrNull()

    /** Newest first. */
    fun list(limit: Int = 150): List<Dictation> = query(null, limit = limit)

    fun nonTerminal(): List<Dictation> = query("status IN ($LIVE)")

    /** WAV first, then row. A missing row or WAV is fine; a WAV that cannot be deleted keeps its row and throws. */
    fun delete(sessionId: String, filesDir: File) {
        write { db ->
            get(sessionId)?.let {
                if (!remove(db, it, filesDir)) throw HistoryWriteException(IOException("could not delete ${it.audioFile}"))
            }
        }
    }

    /**
     * The unstarred terminal rows [retention] removes at [now], newest first: those past the newest maxTakes, and those
     * that started more than maxDays before [now]. A live take is never one of them.
     * ponytail: reads every terminal row to filter here; an SQL OFFSET and started_at bound if histories reach many thousands.
     */
    fun overRetention(retention: Retention, now: Long): List<Dictation> {
        val oldest = retention.maxDays?.let { now - it * 86_400_000L }
        // Newest by id, not started_at: AUTOINCREMENT ids only grow, so a backward clock jump cannot make a new take look oldest.
        return query("starred = 0 AND status NOT IN ($LIVE)", orderBy = "id DESC").filterIndexed { i, row ->
            i >= (retention.maxTakes ?: Int.MAX_VALUE) || (oldest != null && row.startedAt < oldest)
        }
    }

    /**
     * Deletes [overRetention]'s rows with their WAVs; returns how many. A row whose WAV cannot be deleted stays for the
     * next pass and never makes this throw.
     */
    fun applyRetention(retention: Retention, now: Long, filesDir: File): Int = write { db ->
        overRetention(retention, now).count { remove(db, it, filesDir) }
    }

    protected open fun db(): SQLiteDatabase = writableDatabase

    private fun <T> write(block: (SQLiteDatabase) -> T): T = try {
        block(db())
    } catch (e: SQLiteException) {
        throw HistoryWriteException(e)
    }

    private fun update(sessionId: String, values: ContentValues.() -> Unit) = write { db ->
        if (db.update(TABLE, ContentValues().apply(values), "session_id = ?", arrayOf(sessionId)) != 1) {
            throw HistoryWriteException(IllegalStateException("no row for session $sessionId"))
        }
    }

    /**
     * WAV first: death between the two steps leaves a row with its audio missing, never an orphan WAV. Returns false,
     * keeping the row, when the WAV is still there after delete(), so the user can find it and delete it again.
     */
    private fun remove(db: SQLiteDatabase, row: Dictation, filesDir: File): Boolean {
        val wav = File(filesDir, row.audioFile)
        if (!wav.delete() && wav.exists()) return false
        db.delete(TABLE, "id = ?", arrayOf(row.id.toString()))
        return true
    }

    private fun query(where: String?, vararg args: String, orderBy: String = "started_at DESC, id DESC", limit: Int? = null): List<Dictation> =
        db().query(TABLE, null, where, args, null, null, orderBy, limit?.toString()).use { c ->
            buildList { while (c.moveToNext()) add(c.toDictation()) }
        }
}

private fun Cursor.toDictation(): Dictation {
    fun col(name: String) = getColumnIndexOrThrow(name)
    fun str(name: String): String? = getString(col(name))
    return Dictation(
        id = getLong(col("id")),
        sessionId = str("session_id")!!,
        startedAt = getLong(col("started_at")),
        durationMs = getLong(col("duration_ms")),
        audioFile = str("audio_file")!!,
        modelId = str("model_id")!!,
        status = Status.valueOf(str("status")!!),
        error = str("error"),
        rawText = str("raw_text"),
        text = str("text"),
        partialText = str("partial_text"),
        chunksDone = getInt(col("chunks_done")),
        insertedText = str("inserted_text"),
        targetPackage = str("target_package"),
        starred = getInt(col("starred")) != 0,
        retranscribed = getInt(col("retranscribed")) != 0,
    )
}
