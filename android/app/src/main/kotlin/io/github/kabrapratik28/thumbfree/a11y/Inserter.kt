package io.github.kabrapratik28.thumbfree.a11y

import io.github.kabrapratik28.thumbfree.core.insert.InsertionJudge
import io.github.kabrapratik28.thumbfree.core.insert.Surrounding
import io.github.kabrapratik28.thumbfree.core.insert.Verdict
import io.github.kabrapratik28.thumbfree.core.session.Code
import io.github.kabrapratik28.thumbfree.core.session.Outcome
import io.github.kabrapratik28.thumbfree.core.text.CursorFormatter
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/** How an insert ended. [insertedText] is the exact text written to the field, null when nothing was written. */
data class InsertResult(val outcome: Outcome, val insertedText: String?, val code: Code?)

/**
 * Types a transcript at the cursor with one write, then checks once whether it landed. A miss is never written again:
 * the caller's chip offers Copy and Insert here. Every [EditorPort] call runs on [io], never on main.
 */
class Inserter(
    private val port: EditorPort,
    private val io: CoroutineDispatcher,
    private val waitMs: Long = 1_000,
    private val log: (String) -> Unit = { android.util.Log.i("ThumbFree", it) },   // tests pass a recorder
) {
    /** The field before the write, and the payload formatted against it. */
    private class Before(val payload: String, val surrounding: Surrounding?, val nodeText: String?)

    /** At most one automatic write: commitText. */
    suspend fun insert(text: String, pin: Pin?, autoInsert: Boolean, trailingSpace: Boolean = false): InsertResult =
        withContext(io) {
            val start = System.nanoTime()
            fun refuse(code: Code, refused: String? = null) =
                finish(start, pin, "none", "none", notInserted(code), refused)
            if (!autoInsert) return@withContext refuse(Code.HELD_BACK)
            if (pin == null) return@withContext refuse(Code.NO_TARGET)
            if (!port.samePin(pin)) return@withContext refuse(Code.TARGET_CHANGED)
            if (port.isPasswordTarget()) return@withContext refuse(Code.PASSWORD_TARGET)
            // The reads and the write go through the pinned field's own connection, so a field change cannot redirect
            // them. Each read can block for seconds, so the pin is checked again to report such a change.
            val old = port.readSurrounding(pin, text.length + 64, 64)
            if (!port.samePin(pin)) return@withContext refuse(Code.TARGET_CHANGED)
            val before = prepare(pin, text, old, trailingSpace)
            if (!port.samePin(pin)) return@withContext refuse(Code.TARGET_CHANGED)
            // A focus event comes up to 100 ms after its move: the pinned node, read again, narrows that to one read.
            if (!port.stillFocused(pin)) return@withContext refuse(Code.TARGET_CHANGED, "focus")
            if (!port.commit(pin, before.payload)) return@withContext finish(start, pin, "commit", "none", notInserted(unsent(pin)))
            val (result, evidence) = verify(before, pin)
            finish(start, pin, "commit", evidence, result)
        }

    /** Explicit user action: commit into the field focused now, else (no session) clipboard then paste. One write. */
    suspend fun insertHere(text: String): InsertResult = withContext(io) {
        val start = System.nanoTime()
        val pin = port.cachedPin()                       // the focused field's session: the reads and the write stay on it
        if (port.isPasswordTarget()) return@withContext finish(start, pin, "none", "none", notInserted(Code.PASSWORD_TARGET))
        val before = prepare(pin, text, pin?.let { port.readSurrounding(it, text.length + 64, 64) }, trailingSpace = false)
        val route = when {
            // The field focused at the tap, read again just before the write: a move during the read writes nothing.
            pin != null && !port.stillFocused(pin) ->
                return@withContext finish(start, pin, "commit", "none", notInserted(Code.TARGET_CHANGED), "focus")
            pin != null && port.commit(pin, before.payload) -> "commit"
            // Focus moved during the read (the chip offers Insert here again), or the session has no connection.
            pin != null -> return@withContext finish(start, pin, "commit", "none", notInserted(unsent(pin)))
            // No input session: write a sensitive clip and paste it. A clip that reads back different is never pasted.
            !port.copyToClipboard(before.payload) -> return@withContext finish(start, pin, "paste", "none", notInserted(Code.COPY_FAILED))
            // paste() refuses a password node itself, and focus can reach one after the check above.
            !port.paste() -> {
                val code = if (port.isPasswordTarget()) Code.PASSWORD_TARGET else Code.NO_TARGET
                return@withContext finish(start, pin, "paste", "none", notInserted(code))
            }
            else -> "paste"
        }
        val (result, evidence) = verify(before, pin)
        finish(start, pin, route, evidence, result)
    }

    fun copy(text: String): Boolean = port.copyToClipboard(text)

    /** Formats the payload against the [old] read, and reads the node text the fallback check compares against. */
    private fun prepare(pin: Pin?, text: String, old: Surrounding?, trailingSpace: Boolean): Before {
        val caps = pin?.let { port.capsExpected(it) }
        val payload = CursorFormatter.payload(text, old?.before, old?.after, caps, port.inputType(), trailingSpace)
        return Before(payload, old, port.readNodeText())
    }

    /** Why a commit through [pin]'s connection sent nothing: the field changed, or its session has no connection. */
    private fun unsent(pin: Pin) = if (port.samePin(pin)) Code.NO_SESSION else Code.TARGET_CHANGED

    /** Waits for onUpdateSelection (at most waitMs), reads once, and judges the read unless [pin]'s field changed. */
    private suspend fun verify(before: Before, pin: Pin?): Pair<InsertResult, String> {
        port.awaitSelectionChange(waitMs)
        val read = readAfter(before, pin)
        // After a field change the read may be another field's, and judging it a MISS would offer Insert here: a duplicate.
        val (verdict, evidence) = if (pin != null && !port.samePin(pin)) Verdict.UNREADABLE to "none" else read
        val result = when (verdict) {
            Verdict.VERIFIED -> InsertResult(Outcome.INSERTED, before.payload, null)
            Verdict.MISS -> InsertResult(Outcome.NOT_INSERTED, before.payload, Code.MAY_NOT_HAVE_LANDED)
            Verdict.UNREADABLE -> InsertResult(Outcome.UNVERIFIED, before.payload, Code.MAY_NOT_HAVE_LANDED)
        }
        return result to evidence
    }

    /** The one read after the write: surrounding text through [pin]'s connection, or node text when that is null. */
    private fun readAfter(before: Before, pin: Pin?): Pair<Verdict, String> {
        val requested = before.payload.length + 64
        val after = pin?.let { port.readSurrounding(it, requested, 64) }
        if (after != null) return InsertionJudge.judgeCommit(before.surrounding, after, before.payload, requested) to "surrounding"
        val node = port.readNodeText()
        return InsertionJudge.judgeNodeText(before.nodeText, node, before.payload) to (if (node != null) "node" else "none")
    }

    /**
     * Logs the one line every attempt ends with. It carries no text from the field or the transcript. A TARGET_CHANGED
     * names the check that refused: [refused], or else whether the pin's input session ended or another of its nodes
     * took focus.
     */
    private fun finish(
        startNs: Long, pin: Pin?, route: String, evidence: String, result: InsertResult, refused: String? = null,
    ): InsertResult {
        val ms = (System.nanoTime() - startNs) / 1_000_000
        val check = if (result.code == Code.TARGET_CHANGED && pin != null) " refused=${refused ?: changed(pin)}" else ""
        val pkg = pin?.packageName ?: "none"
        log("insert route=$route evidence=$evidence outcome=${result.outcome}$check ms=$ms pkg=$pkg")
        return result
    }

    /**
     * No IPC: the editor connected now is of another input session than [pin]'s, or it is the same one. Input that
     * stopped needs no tag of its own: InputMethod clears inputStarted only in doFinishInput, after onFinishInput has
     * moved the generation, so it always reads as generation.
     */
    private fun changed(pin: Pin) = if (port.cachedPin()?.generation == pin.generation) "node" else "generation"

    private fun notInserted(code: Code) = InsertResult(Outcome.NOT_INSERTED, null, code)
}
