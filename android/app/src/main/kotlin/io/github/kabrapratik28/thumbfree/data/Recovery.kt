package io.github.kabrapratik28.thumbfree.data

import io.github.kabrapratik28.thumbfree.core.audio.WavWriter
import io.github.kabrapratik28.thumbfree.core.session.Code
import java.io.File

object Recovery {
    /**
     * RECORDING, TRANSCRIBING -> INTERRUPTED (WAV repaired, duration set); STAGED -> NOT_INSERTED; INSERTING -> NEEDS_REVIEW; missing WAV -> FAILED("AUDIO_MISSING"). Never retries.
     * A RECORDING or TRANSCRIBING row died before its speech decision: its text, which no one confirmed, is cleared in the
     * write that ends the row. STAGED and INSERTING rows keep theirs: the user's transcript, saved before insertion for this.
     * Also deletes the WAVs in recordings that no row names.
     * Run once per process on the same serial thread as create(), before the first take.
     * A row that fails is left as it was for the next start; the other rows are still recovered, then the first failure is rethrown.
     */
    fun run(db: HistoryDb, filesDir: File): List<Dictation> {
        // A take discarded before its writer thread made the file leaves a WAV that no row names. No take runs yet, so
        // such a WAV is an orphan. A WAV with a row stays, whatever the row's status.
        val named = db.list(Int.MAX_VALUE).map { File(filesDir, it.audioFile) }.toSet()
        File(filesDir, "recordings").listFiles { f -> f.name.endsWith(".wav") && f !in named }?.forEach { it.delete() }
        val recovered = mutableListOf<Dictation>()
        var failure: Exception? = null
        for (row in db.nonTerminal()) {
            val wav = File(filesDir, row.audioFile)
            // length() is 0 for a missing file. A file shorter than the header lost it to a crash before it reached disk.
            val missing = wav.length() < WavWriter.HEADER_BYTES
            val decided = row.status == Status.STAGED || row.status == Status.INSERTING
            try {
                when {
                    !decided && missing -> db.recoverUnconfirmed(row.sessionId, Status.FAILED, Code.AUDIO_MISSING.name)
                    !decided -> db.recoverUnconfirmed(
                        row.sessionId, Status.INTERRUPTED, durationMs = WavWriter.repair(wav) * 1000 / 16_000,
                    )
                    missing -> db.finish(row.sessionId, Status.FAILED, Code.AUDIO_MISSING.name)
                    row.status == Status.STAGED -> db.finish(row.sessionId, Status.NOT_INSERTED)
                    else -> db.finish(row.sessionId, Status.NEEDS_REVIEW)
                }
                recovered += db.get(row.sessionId)!!
            } catch (e: Exception) {
                if (failure == null) failure = e else failure.addSuppressed(e)
            }
        }
        failure?.let { throw it }
        return recovered
    }
}
