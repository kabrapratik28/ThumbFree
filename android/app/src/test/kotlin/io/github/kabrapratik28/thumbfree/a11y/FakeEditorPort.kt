package io.github.kabrapratik28.thumbfree.a11y

import io.github.kabrapratik28.thumbfree.core.insert.Surrounding

/** Scripted [EditorPort]: each answer comes from the fields below, and every call is recorded in order. */
class FakeEditorPort : EditorPort {
    var pin: Pin? = null
    val samePinAnswers = ArrayDeque<Boolean>()           // one answer per samePin, then whether the pin's field still holds the session
    var password = false
    val passwordAnswers = ArrayDeque<Boolean>()          // one answer per isPasswordTarget, then password
    val stillFocusedAnswers = ArrayDeque<Boolean>()      // one answer per stillFocused, then whether the pin's field still holds the session
    var type = 0x1                                       // TYPE_CLASS_TEXT
    var caps: Boolean? = null
    val surroundings = ArrayDeque<Surrounding?>()        // one answer per readSurrounding, null once used up
    val nodeTexts = ArrayDeque<String?>()                // one answer per readNodeText, null once used up
    var commits = true
    var selectionChanges = true
    var copies = true
    var pastes = true
    var field = 1                                        // the generation of the field that holds the session
    var switchOn: String? = null                         // focus moves to a new field as this call starts (once)
    val replaceAnswers = ArrayDeque<Replaced>()          // one answer per replaceBeforeCursor, then DONE

    val calls = mutableListOf<String>()
    val committed = mutableListOf<String>()
    val received = mutableMapOf<Int, String>()           // field to the text that reached it
    val copied = mutableListOf<String>()
    val replaced = mutableListOf<Pair<String, String>>() // (old, new) per replaceBeforeCursor
    val readThreads = mutableListOf<String>()
    var awaitedMs = -1L

    fun count(call: String) = calls.count { it == call }

    override fun cachedPin(): Pin? = pin
    override fun isPasswordTarget(): Boolean = passwordAnswers.removeFirstOrNull() ?: password
    override fun inputType(): Int = type

    override fun samePin(pin: Pin): Boolean {
        call("samePin")
        return samePinAnswers.removeFirstOrNull() ?: (pin.generation == field)
    }

    override fun stillFocused(pin: Pin): Boolean {
        read("stillFocused")
        return stillFocusedAnswers.removeFirstOrNull() ?: (pin.generation == field)
    }

    // A pinned call uses the pin's own connection: once another field holds the session it reads and writes nothing.
    override fun capsExpected(pin: Pin): Boolean? {
        read("capsExpected")
        return caps.takeIf { pin.generation == field }
    }

    override fun readSurrounding(pin: Pin, before: Int, after: Int): Surrounding? {
        read("readSurrounding")
        return surroundings.removeFirstOrNull()?.takeIf { pin.generation == field }
    }

    override fun readNodeText(): String? {
        read("readNodeText")
        return nodeTexts.removeFirstOrNull()
    }

    override fun commit(pin: Pin, text: String): Boolean {
        call("commit")
        committed += text
        if (!commits || pin.generation != field) return false
        received[field] = text
        return true
    }

    override suspend fun awaitSelectionChange(timeoutMs: Long): Boolean {
        call("awaitSelectionChange")
        awaitedMs = timeoutMs
        return selectionChanges
    }

    override fun copyToClipboard(text: String): Boolean {
        call("copyToClipboard")
        copied += text
        return copies
    }

    override fun paste(): Boolean {
        call("paste")
        return pastes
    }

    var cursorEnds = ArrayDeque<Int?>()                  // one answer per cursorEnd, then 100
    val ends = mutableListOf<Int>()                      // the end each replaceBeforeCursor was asked for

    override fun cursorEnd(pin: Pin, take: String): Int? {
        read("cursorEnd")
        if (pin.generation != field) return null
        return if (cursorEnds.isEmpty()) 100 else cursorEnds.removeFirst()
    }

    override fun replaceBeforeCursor(pin: Pin, old: String, new: String, end: Int, live: () -> Boolean): Replaced {
        read("replaceBeforeCursor")
        if (pin.generation != field || !live()) return Replaced.UNCHANGED
        replaced += old to new
        ends += end
        return replaceAnswers.removeFirstOrNull() ?: Replaced.DONE
    }

    private fun call(name: String) {
        if (name == switchOn) {
            field++
            switchOn = null
        }
        calls += name
    }

    private fun read(name: String) {
        call(name)
        readThreads += Thread.currentThread().name
    }
}
