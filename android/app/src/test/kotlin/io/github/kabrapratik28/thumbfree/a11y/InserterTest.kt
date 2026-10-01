package io.github.kabrapratik28.thumbfree.a11y

import com.google.common.truth.Truth.assertThat
import io.github.kabrapratik28.thumbfree.core.insert.Surrounding
import io.github.kabrapratik28.thumbfree.core.session.Code
import io.github.kabrapratik28.thumbfree.core.session.Outcome
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test

class InserterTest {
    private val executor = Executors.newSingleThreadExecutor { Thread(it, "insert-io") }
    private val pin = Pin("com.example", 1, "node", 1)
    private val port = FakeEditorPort()
    private val logs = mutableListOf<String>()
    private val inserter = Inserter(port, executor.asCoroutineDispatcher(), log = { logs += it })

    @After
    fun stopIo() = executor.shutdown()

    @Test
    fun commitVerifiedBySurroundingText() = runBlocking<Unit> {
        port.surroundings += listOf(Surrounding("Hello ", "", 0), Surrounding("Hello world", "", 0))

        val result = inserter.insert("world", pin, true)

        assertThat(result).isEqualTo(InsertResult(Outcome.INSERTED, "world", null))
        assertThat(port.count("commit")).isEqualTo(1)
        assertThat(port.count("readSurrounding")).isEqualTo(2)
    }

    // getSurroundingText and getCursorCapsMode block for up to 2 s; the test thread stands in for main.
    @Test
    fun readsHappenOffMain() = runBlocking<Unit> {
        port.surroundings += listOf(Surrounding("Hi", "", 0), null)
        port.nodeTexts += listOf("Hi", "Hi there")

        inserter.insert("there", pin, true)

        assertThat(port.readThreads).isNotEmpty()
        assertThat(port.readThreads.filterNot { it.startsWith("insert-io") }).isEmpty()
    }

    @Test
    fun oneReadAfterTheWait() = runBlocking<Unit> {
        port.selectionChanges = false
        port.surroundings += listOf(Surrounding("Hello ", "", 0), Surrounding("Hello world", "", 0))

        inserter.insert("world", pin, true)

        assertThat(port.awaitedMs).isEqualTo(1_000)
        assertThat(port.calls.dropWhile { it != "commit" })
            .containsExactly("commit", "awaitSelectionChange", "readSurrounding", "samePin").inOrder()
    }

    @Test
    fun nullSurroundingFallsBackToNodeText() = runBlocking<Unit> {
        port.surroundings += listOf(Surrounding("Hi", "", 0), null)
        port.nodeTexts += listOf("Hi", "Hi there")

        val result = inserter.insert("there", pin, true)

        assertThat(result).isEqualTo(InsertResult(Outcome.INSERTED, " there", null))
    }

    @Test
    fun unreadableIsUnverified() = runBlocking<Unit> {
        port.surroundings += listOf(Surrounding("Hi", "", 0), null)
        port.nodeTexts += listOf("Hi", null)

        val result = inserter.insert("there", pin, true)

        assertThat(result).isEqualTo(InsertResult(Outcome.UNVERIFIED, " there", Code.MAY_NOT_HAVE_LANDED))
        assertThat(port.count("commit")).isEqualTo(1)
    }

    @Test
    fun missIsNotInsertedAndNeverRetries() = runBlocking<Unit> {
        val unchanged = Surrounding("Hello ", "", 0)
        port.surroundings += listOf(unchanged, unchanged)

        val result = inserter.insert("world", pin, true)

        assertThat(result.outcome).isEqualTo(Outcome.NOT_INSERTED)
        assertThat(result.code).isEqualTo(Code.MAY_NOT_HAVE_LANDED)
        assertThat(port.count("commit")).isEqualTo(1)
        assertThat(port.count("paste")).isEqualTo(0)
        assertThat(port.count("copyToClipboard")).isEqualTo(0)
    }

    @Test
    fun targetChangedDoesNotWrite() = runBlocking<Unit> {
        port.samePinAnswers += false

        val result = inserter.insert("x", pin, true)

        assertThat(result).isEqualTo(InsertResult(Outcome.NOT_INSERTED, null, Code.TARGET_CHANGED))
        assertThat(port.count("commit")).isEqualTo(0)
    }

    // commit() writes to whichever field holds the session when it runs, and the reads before it can block for seconds.
    @Test
    fun targetChangedDuringReadsDoesNotWrite() = runBlocking<Unit> {
        port.samePinAnswers += listOf(true, false)

        val result = inserter.insert("x", pin, true)

        assertThat(result).isEqualTo(InsertResult(Outcome.NOT_INSERTED, null, Code.TARGET_CHANGED))
        assertThat(port.calls).containsExactly("samePin", "readSurrounding", "samePin").inOrder()
        assertThat(port.count("commit")).isEqualTo(0)
    }

    @Test
    fun targetChangedBeforeCommitDoesNotWrite() = runBlocking<Unit> {
        port.samePinAnswers += listOf(true, true, false)

        val result = inserter.insert("x", pin, true)

        assertThat(result).isEqualTo(InsertResult(Outcome.NOT_INSERTED, null, Code.TARGET_CHANGED))
        assertThat(port.calls.last()).isEqualTo("samePin")
        assertThat(port.count("commit")).isEqualTo(0)
    }

    // The last pin check can pass just before focus moves on. The write must go through the pinned field's own
    // connection, which the app has deactivated by then, so it lands nowhere instead of in the new field.
    @Test
    fun switchAfterTheLastCheckWritesNothing() = runBlocking<Unit> {
        port.surroundings += Surrounding("Hello ", "", 0)
        port.switchOn = "commit"

        val result = inserter.insert("world", pin, true)

        assertThat(port.received).isEmpty()
        assertThat(result).isEqualTo(InsertResult(Outcome.NOT_INSERTED, null, Code.TARGET_CHANGED))
    }

    // A focus event comes up to 100 ms after the move it reports. Just before the write the pinned node is read again
    // past the node cache: no longer focused, or a password field now, nothing is written.
    @Test
    fun pinnedNodeNoLongerFocusedIsNotWritten() = runBlocking<Unit> {
        port.surroundings += Surrounding("Hello ", "", 0)
        port.stillFocusedAnswers += false

        val result = inserter.insert("world", pin, true)

        assertThat(result).isEqualTo(InsertResult(Outcome.NOT_INSERTED, null, Code.TARGET_CHANGED))
        assertThat(port.count("commit")).isEqualTo(0)
        assertThat(port.calls.last()).isEqualTo("stillFocused")
    }

    @Test
    fun insertHereNodeNoLongerFocusedIsNotWritten() = runBlocking<Unit> {
        port.pin = pin
        port.surroundings += Surrounding("", "", 0)
        port.stillFocusedAnswers += false

        val result = inserter.insertHere("x")

        assertThat(result).isEqualTo(InsertResult(Outcome.NOT_INSERTED, null, Code.TARGET_CHANGED))
        assertThat(port.calls).containsNoneOf("commit", "copyToClipboard", "paste")
    }

    // After a switch the read may be the new field's, and a MISS there would offer Insert here: a duplicate.
    @Test
    fun switchAfterCommitIsUnverified() = runBlocking<Unit> {
        port.samePinAnswers += listOf(true, true, true, false)
        port.surroundings += listOf(Surrounding("Hello ", "", 0), Surrounding("Other field", "", 0))

        val result = inserter.insert("world", pin, true)

        assertThat(result).isEqualTo(InsertResult(Outcome.UNVERIFIED, "world", Code.MAY_NOT_HAVE_LANDED))
    }

    @Test
    fun insertHereSwitchAfterCommitIsUnverified() = runBlocking<Unit> {
        port.pin = pin
        port.samePinAnswers += false
        port.surroundings += listOf(Surrounding("", "", 0), Surrounding("Other field", "", 0))

        val result = inserter.insertHere("x")

        assertThat(result).isEqualTo(InsertResult(Outcome.UNVERIFIED, "x", Code.MAY_NOT_HAVE_LANDED))
    }

    // Insert here's read can block for seconds. A field change meanwhile writes nothing and offers Insert here again.
    @Test
    fun insertHereSwitchDuringReadWritesNothing() = runBlocking<Unit> {
        port.pin = pin
        port.surroundings += Surrounding("", "", 0)
        port.switchOn = "readSurrounding"

        val result = inserter.insertHere("x")

        assertThat(port.received).isEmpty()
        assertThat(port.calls).containsNoneOf("copyToClipboard", "paste")
        assertThat(result).isEqualTo(InsertResult(Outcome.NOT_INSERTED, null, Code.TARGET_CHANGED))
    }

    @Test
    fun noPinDoesNotWrite() = runBlocking<Unit> {
        val result = inserter.insert("x", null, true)

        assertThat(result).isEqualTo(InsertResult(Outcome.NOT_INSERTED, null, Code.NO_TARGET))
        assertThat(port.count("commit")).isEqualTo(0)
    }

    @Test
    fun passwordTargetRefused() = runBlocking<Unit> {
        port.password = true

        val result = inserter.insert("x", pin, true)

        assertThat(result).isEqualTo(InsertResult(Outcome.NOT_INSERTED, null, Code.PASSWORD_TARGET))
        assertThat(port.count("commit")).isEqualTo(0)
    }

    @Test
    fun heldBackDoesNotWrite() = runBlocking<Unit> {
        val result = inserter.insert("x", pin, false)

        assertThat(result).isEqualTo(InsertResult(Outcome.NOT_INSERTED, null, Code.HELD_BACK))
        assertThat(port.count("commit")).isEqualTo(0)
    }

    @Test
    fun noConnectionDoesNotPaste() = runBlocking<Unit> {
        port.commits = false

        val result = inserter.insert("x", pin, true)

        assertThat(result).isEqualTo(InsertResult(Outcome.NOT_INSERTED, null, Code.NO_SESSION))
        assertThat(port.count("paste")).isEqualTo(0)
        assertThat(port.count("copyToClipboard")).isEqualTo(0)
    }

    @Test
    fun payloadUsesTheFormatter() = runBlocking<Unit> {
        port.surroundings += Surrounding("I said.", "", 0)
        port.caps = true

        inserter.insert("hello", pin, true)

        assertThat(port.committed).containsExactly(" Hello")
    }

    @Test
    fun insertHereCommitsWithASession() = runBlocking<Unit> {
        port.pin = pin
        port.surroundings += listOf(Surrounding("", "", 0), Surrounding("x", "", 0))

        val result = inserter.insertHere("x")

        assertThat(result).isEqualTo(InsertResult(Outcome.INSERTED, "x", null))
        assertThat(port.count("commit")).isEqualTo(1)
        assertThat(port.count("copyToClipboard")).isEqualTo(0)
    }

    // A session without a connection takes nothing, and a paste could not be tied to the pinned field.
    @Test
    fun insertHereWithoutConnectionIsNoSession() = runBlocking<Unit> {
        port.pin = pin
        port.commits = false

        val result = inserter.insertHere("x")

        assertThat(result).isEqualTo(InsertResult(Outcome.NOT_INSERTED, null, Code.NO_SESSION))
        assertThat(port.calls).containsNoneOf("copyToClipboard", "paste")
    }

    // No timed clipboard restore: the clip Insert here pasted stays, and nothing writes the clipboard again.
    @Test
    fun insertHerePastesWithoutSession() = runBlocking<Unit> {
        port.nodeTexts += listOf("", "x")

        val result = inserter.insertHere("x")

        assertThat(result).isEqualTo(InsertResult(Outcome.INSERTED, "x", null))
        assertThat(port.calls.filter { it == "copyToClipboard" || it == "paste" })
            .containsExactly("copyToClipboard", "paste").inOrder()
        assertThat(port.copied).containsExactly("x")
    }

    @Test
    fun insertHereCopyFailureNeverPastes() = runBlocking<Unit> {
        port.copies = false

        val result = inserter.insertHere("x")

        assertThat(result).isEqualTo(InsertResult(Outcome.NOT_INSERTED, null, Code.COPY_FAILED))
        assertThat(port.count("paste")).isEqualTo(0)
    }

    @Test
    fun insertHerePasteFailureIsNoTarget() = runBlocking<Unit> {
        port.pastes = false

        val result = inserter.insertHere("x")

        assertThat(result).isEqualTo(InsertResult(Outcome.NOT_INSERTED, null, Code.NO_TARGET))
    }

    // paste() refuses a password node itself, and focus can reach one after the first check: the chip says why.
    @Test
    fun insertHerePasteRefusedForPassword() = runBlocking<Unit> {
        port.passwordAnswers += listOf(false, true)
        port.pastes = false

        val result = inserter.insertHere("x")

        assertThat(result).isEqualTo(InsertResult(Outcome.NOT_INSERTED, null, Code.PASSWORD_TARGET))
    }

    @Test
    fun insertHereRefusesPassword() = runBlocking<Unit> {
        port.password = true

        val result = inserter.insertHere("x")

        assertThat(result).isEqualTo(InsertResult(Outcome.NOT_INSERTED, null, Code.PASSWORD_TARGET))
        assertThat(port.count("commit")).isEqualTo(0)
        assertThat(port.count("copyToClipboard")).isEqualTo(0)
        assertThat(port.count("paste")).isEqualTo(0)
    }

    @Test
    fun logLineHasNoText() = runBlocking<Unit> {
        port.surroundings += listOf(Surrounding("", "", 0), Surrounding("secret words", "", 0))

        inserter.insert("secret words", pin, true)

        assertThat(logs).hasSize(1)
        assertThat(logs[0]).contains("route=commit")
        assertThat(logs[0]).contains("evidence=surrounding")
        assertThat(logs[0]).contains("outcome=INSERTED")
        assertThat(logs[0]).contains("pkg=com.example")
        assertThat(logs[0]).doesNotContain("secret")
    }

    // The one line of a TARGET_CHANGED says which check refused, still with no text: the input session ended
    // (generation), another node of it took focus (node), or the pinned node, read again, did not hold (focus).
    @Test
    fun targetChangedLogsWhichCheckRefused() = runBlocking<Unit> {
        port.samePinAnswers += false
        inserter.insert("x", pin, true) // no editor session now: cachedPin() is null
        port.pin = pin
        port.samePinAnswers += false
        inserter.insert("x", pin, true) // the same session
        port.surroundings += Surrounding("", "", 0)
        port.stillFocusedAnswers += false
        inserter.insert("x", pin, true)

        assertThat(logs.map { it.substringAfter("refused=", "-").substringBefore(' ') })
            .containsExactly("generation", "node", "focus").inOrder()
    }

    @Test
    fun copyReportsFailure() {
        port.copies = false

        assertThat(inserter.copy("x")).isFalse()
    }
}
