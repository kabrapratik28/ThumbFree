package io.github.kabrapratik28.thumbfree.app

import io.github.kabrapratik28.thumbfree.core.session.BubbleUi
import io.github.kabrapratik28.thumbfree.core.session.Code
import io.github.kabrapratik28.thumbfree.core.session.HapticKind
import io.github.kabrapratik28.thumbfree.core.session.Outcome

/** Records every port call as a string, for example "createRow(s1)". Session ids are s1, s2, and so on. */
class FakePorts : DictationPorts {
    val calls = mutableListOf<String>()
    var createRowResult = true
    var saveStagedResult = true
    var saveRetranscriptionResult = true
    var copyResult = true
    var msSinceLastSpeech = 0L
    /** Runs inside startForeground. Real ports never call back from inside a port call; one test needs it. */
    var onStartForeground: () -> Unit = {}
    private var nextId = 1

    private fun <T> call(text: String, result: T): T {
        calls += text
        return result
    }

    override fun newSessionId() = call("newSessionId", "s${nextId++}")
    override fun createRow(id: String) = call("createRow($id)", createRowResult)
    override fun deleteSession(id: String) = call("deleteSession($id)", Unit)
    override fun startForeground(id: String) = call("startForeground($id)", Unit).also { onStartForeground() }
    override fun stopForeground(id: String) = call("stopForeground($id)", Unit)
    override fun startCapture(id: String) = call("startCapture($id)", Unit)
    override fun startTail(id: String) = call("startTail($id)", Unit)
    override fun stopCapture(id: String, keepAudio: Boolean) = call("stopCapture($id,$keepAudio)", Unit)
    override fun msSinceLastSpeech(id: String) = call("msSinceLastSpeech($id)", msSinceLastSpeech)
    override fun requestAudioFocus() = call("requestAudioFocus", Unit)
    override fun abandonAudioFocus() = call("abandonAudioFocus", Unit)
    override fun ensureEngineLoaded(id: String) = call("ensureEngineLoaded($id)", Unit)
    override fun finishTranscription(id: String) = call("finishTranscription($id)", Unit)
    override fun abortEngine(id: String) = call("abortEngine($id)", Unit)
    override fun pinTarget(id: String) = call("pinTarget($id)", Unit)
    override fun saveStaged(id: String, raw: String, text: String) = call("saveStaged($id,$raw,$text)", saveStagedResult)
    override fun saveRetranscription(id: String, raw: String, text: String) = call("saveRetranscription($id,$raw,$text)", saveRetranscriptionResult)
    override fun markInserting(id: String) = call("markInserting($id)", Unit)
    override fun insert(id: String, text: String, autoInsert: Boolean) = call("insert($id,$text,$autoInsert)", Unit)
    override fun saveOutcome(id: String, outcome: Outcome, code: Code?) = call("saveOutcome($id,$outcome,$code)", Unit)
    override fun haptic(kind: HapticKind) = call("haptic($kind)", Unit)
    override fun render(ui: BubbleUi) = call("render($ui)", Unit)
    override fun schedule(id: String, kind: TimerKind, delayMs: Long) = call("schedule($id,$kind,$delayMs)", Unit)
    override fun cancelTimers(id: String) = call("cancelTimers($id)", Unit)
    override fun copy(text: String) = call("copy($text)", copyResult)
    override fun insertHere(id: String, text: String) = call("insertHere($id,$text)", Unit)
    override fun retranscribe(id: String) = call("retranscribe($id)", Unit)
}
