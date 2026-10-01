package io.github.kabrapratik28.thumbfree.data

import com.google.common.truth.Truth.assertThat
import io.github.kabrapratik28.thumbfree.core.audio.WavWriter
import io.github.kabrapratik28.thumbfree.data.Status.FAILED
import io.github.kabrapratik28.thumbfree.data.Status.INSERTED
import io.github.kabrapratik28.thumbfree.data.Status.INSERTING
import io.github.kabrapratik28.thumbfree.data.Status.INTERRUPTED
import io.github.kabrapratik28.thumbfree.data.Status.NEEDS_REVIEW
import io.github.kabrapratik28.thumbfree.data.Status.NOT_INSERTED
import io.github.kabrapratik28.thumbfree.data.Status.RECORDING
import io.github.kabrapratik28.thumbfree.data.Status.STAGED
import io.github.kabrapratik28.thumbfree.data.Status.TRANSCRIBING
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Assume.assumeFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class RecoveryTest {
    @get:Rule val tmp = TemporaryFolder()
    private val db = HistoryDb(RuntimeEnvironment.getApplication(), null)

    @After
    fun close() = db.close()

    @Test
    fun mapsEveryLiveState() {
        row("rec", RECORDING)
        row("tra", TRANSCRIBING)
        row("sta", STAGED)
        row("ins", INSERTING)
        row("done", INSERTED)
        db.stage("sta", "Raw.", "Text.", 1000)

        val recovered = Recovery.run(db, tmp.root)

        assertThat(listOf("rec", "tra", "sta", "ins", "done").map { db.get(it)!!.status })
            .containsExactly(INTERRUPTED, INTERRUPTED, NOT_INSERTED, NEEDS_REVIEW, INSERTED).inOrder()
        assertThat(recovered).hasSize(4)
        assertThat(db.get("sta")!!.text).isEqualTo("Text.")
    }

    // A take that died before its speech decision (RECORDING, TRANSCRIBING) holds chunk text nobody confirmed, so the
    // write that ends its row clears partial, raw, final and inserted text, whether its WAV is usable (INTERRUPTED) or
    // missing (FAILED). STAGED and INSERTING rows passed that decision and hold the user's transcript, the reason text
    // is saved before insertion: they keep it. The WAV stays either way.
    @Test
    fun recoveryClearsTextOnlyBeforeTheSpeechDecision() {
        val ends = mapOf(
            (RECORDING to true) to INTERRUPTED, (RECORDING to false) to FAILED,
            (TRANSCRIBING to true) to INTERRUPTED, (TRANSCRIBING to false) to FAILED,
            (STAGED to true) to NOT_INSERTED, (STAGED to false) to FAILED,
            (INSERTING to true) to NEEDS_REVIEW, (INSERTING to false) to FAILED,
        )
        for ((status, withWav) in ends.keys) {
            row("$status-$withWav", status, withWav)
            db.writableDatabase.execSQL(
                "UPDATE dictation SET partial_text = 'p', raw_text = 'r', text = 't', inserted_text = 'i' WHERE session_id = ?",
                arrayOf("$status-$withWav"),
            )
        }

        Recovery.run(db, tmp.root)

        for ((key, end) in ends) {
            val (status, withWav) = key
            val row = db.get("$status-$withWav")!!
            assertThat(row.status).isEqualTo(end)
            assertThat(row.error).isEqualTo(if (withWav) null else "AUDIO_MISSING")
            val kept = status == STAGED || status == INSERTING
            assertThat(listOf(row.partialText, row.rawText, row.text, row.insertedText))
                .isEqualTo(if (kept) listOf("p", "r", "t", null) else listOf(null, null, null, null))
            assertThat(wav("$status-$withWav").exists()).isEqualTo(withWav)
        }
    }

    @Test
    fun repairsWav() {
        db.create("s1", 1000, "recordings/s1.wav", "m", null)
        // Died mid-take: 32,000 bytes of audio on disk, but the header still says 0.
        WavWriter.create(wav("s1").apply { parentFile!!.mkdirs() }).use { it.append(ShortArray(16_000)) }
        assertThat(dataSize(wav("s1"))).isEqualTo(0)

        Recovery.run(db, tmp.root)

        assertThat(dataSize(wav("s1"))).isEqualTo(32_000)
        assertThat(db.get("s1")!!.durationMs).isEqualTo(1000)
    }

    @Test
    fun missingWavIsFailed() {
        row("s1", RECORDING, withWav = false)

        Recovery.run(db, tmp.root)

        val row = db.get("s1")!!
        assertThat(row.status).isEqualTo(FAILED)
        assertThat(row.error).isEqualTo("AUDIO_MISSING")
    }

    @Test
    fun wavWithoutHeaderIsFailed() {
        // Power lost before the header reached disk: the file exists but holds no audio.
        row("s1", RECORDING, withWav = false)
        wav("s1").apply { parentFile!!.mkdirs() }.writeBytes(ByteArray(0))

        Recovery.run(db, tmp.root)

        val row = db.get("s1")!!
        assertThat(row.status).isEqualTo(FAILED)
        assertThat(row.error).isEqualTo("AUDIO_MISSING")
    }

    @Test
    fun oneBadRowDoesNotStopTheRest() {
        // nonTerminal() is newest first, so the bad row is handled before the older one.
        row("old", RECORDING, startedAt = 1000)
        row("bad", RECORDING, startedAt = 2000)
        wav("bad").setWritable(false) // repair() cannot open it
        assumeFalse("root ignores file modes", wav("bad").canWrite())

        val e = assertThrows(IOException::class.java) { Recovery.run(db, tmp.root) }

        assertThat(e).hasMessageThat().contains("bad.wav")
        assertThat(db.get("old")!!.status).isEqualTo(INTERRUPTED)
        assertThat(db.get("bad")!!.status).isEqualTo(RECORDING) // left for the next start
    }

    @Test
    fun deletesOnlyWavsWithoutRows() {
        for (status in Status.entries) row("with-$status", status) // CANCELLED keeps its WAV for Undo, like the rest
        for (t in 1..150) row("new$t", INSERTED) // more rows than the history screen lists
        // A take discarded before its writer thread made the file leaves a WAV that no row names.
        val orphan = wav("orphan").apply { writeBytes(ByteArray(44)) }
        val other = File(tmp.root, "recordings/notes.txt").apply { writeText("not a take") }

        Recovery.run(db, tmp.root)

        assertThat(orphan.exists()).isFalse()
        assertThat(other.exists()).isTrue()
        assertThat(Status.entries.filterNot { wav("with-$it").exists() }).isEmpty()
        assertThat((1..150).filterNot { wav("new$it").exists() }).isEmpty()
    }

    @Test
    fun neverSchedulesWork() {
        for (status in Status.entries) {
            row("with-$status", status)
            row("without-$status", status, withWav = false)
        }

        Recovery.run(db, tmp.root)

        assertThat(db.list().filter { it.status in setOf(RECORDING, TRANSCRIBING, INSERTING) }).isEmpty()
    }

    private fun wav(id: String) = File(tmp.root, "recordings/$id.wav")

    private fun dataSize(file: File) = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN).getInt(40)

    /** A row in [status] with a finished one-second WAV, or no file at all. */
    private fun row(id: String, status: Status, withWav: Boolean = true, startedAt: Long = 1000) {
        db.create(id, startedAt, "recordings/$id.wav", "m", null)
        db.setStatus(id, status)
        if (withWav) WavWriter.create(wav(id).apply { parentFile!!.mkdirs() }).use { it.append(ShortArray(16_000)); it.finish() }
    }
}
