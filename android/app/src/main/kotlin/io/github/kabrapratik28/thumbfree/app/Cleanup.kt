package io.github.kabrapratik28.thumbfree.app

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.graphics.Rect
import android.util.Log
import android.widget.Toast
import com.google.mlkit.genai.common.FeatureStatus
import io.github.kabrapratik28.thumbfree.R
import io.github.kabrapratik28.thumbfree.a11y.EditorPort
import io.github.kabrapratik28.thumbfree.a11y.Pin
import io.github.kabrapratik28.thumbfree.a11y.Replaced
import io.github.kabrapratik28.thumbfree.a11y.Sparkle
import io.github.kabrapratik28.thumbfree.core.text.CleanupCheck
import io.github.kabrapratik28.thumbfree.core.text.CleanupStyle
import io.github.kabrapratik28.thumbfree.ui.CleanupActivity
import java.lang.ref.WeakReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Clean up (issue #1): after a take is typed, the sparkle beside the bubble tidies its words with Gemini Nano. A tap uses
 * the default style and a hold opens the styles, both in CleanupActivity, since ML Kit answers only while a ThumbFree
 * activity is resumed. Undo writes the take back. Only the take's own words change, and only while they sit right before
 * the cursor of the field they were typed into. Main thread; field calls run on [io].
 */
class Cleanup(
    private val app: Application,
    private val port: EditorPort,
    private val enabled: () -> Boolean,
    private val draw: (Sparkle?) -> Unit,
    private val anchor: () -> Rect?,
    private val io: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1),
    private val toast: (Int) -> Unit = { Toast.makeText(app, it, Toast.LENGTH_SHORT).show() },
    private val start: (Intent) -> Unit = app::startActivity,
) {
    /**
     * The take on offer: the exact text it typed, and the pin of its field (a new session of the same field once the card
     * cost it its session); [written], the tidied text in its place, until Undo.
     */
    private class Offer(var pin: Pin, val take: String) {
        var written: String? = null
    }

    private val scope = MainScope()
    private var offer: Offer? = null
    private var card: WeakReference<Activity>? = null
    private var opening = false // the card was asked for and has not closed yet
    private var writing = false // a write or an Undo runs
    private var pending: String? = null // the card's answer, written once the card has closed

    /** Gemini Nano's last known FeatureStatus, from Settings or the card; null until checked. UNAVAILABLE hides the sparkle. */
    @Volatile var status: Int? = null

    /** What the sparkle shows now. */
    val sparkle: Sparkle?
        get() = offer?.let {
            when {
                opening || writing -> Sparkle.WORKING
                it.written != null -> Sparkle.UNDO
                else -> Sparkle.OFFER
            }
        }

    private fun redraw() = draw(sparkle)

    /** A take was typed and verified through [pin]; [take] is the exact text written. The sparkle offers to tidy it. */
    fun offer(pin: Pin, take: String) {
        close()
        offer = Offer(pin, take).takeIf { enabled() && status != FeatureStatus.UNAVAILABLE && take.isNotBlank() }
        redraw()
    }

    /** A new take, another field, or the service gone: the sparkle goes, and an open card closes. */
    fun clear() {
        close()
        offer = null
        redraw()
    }

    /** The bubble hid. The card can hide it for its moment, so an open card or a write keeps the offer; else it goes. */
    fun bubbleHidden() {
        if (!opening && !writing) clear()
    }

    /**
     * Focus was reported again: another field ends the offer, unless the card is up or a write runs. No session at all
     * (the field getting its focus back after the card) keeps it.
     */
    fun followFocus(pin: Pin?) {
        val offer = offer ?: return
        if (!opening && !writing && pin != null && !sameField(pin, offer.pin)) clear()
    }

    /**
     * [pin] is [of]'s field, maybe in a new session: the same app, window and node. A pin that named no node (no focus
     * event yet at its press) matches any field of its app; the write still needs the take right before the cursor.
     */
    private fun sameField(pin: Pin, of: Pin) =
        pin.packageName == of.packageName && (of.nodeKey.isEmpty() || (pin.windowId == of.windowId && pin.nodeKey == of.nodeKey))

    /** The sparkle's tap or [hold]: tidy (the card), cancel the card, or Undo. */
    fun tap(hold: Boolean) {
        val offer = offer ?: return
        when {
            writing -> Unit
            opening -> {
                close()
                redraw()
            }
            offer.written != null -> undo(offer)
            else -> open(hold)
        }
    }

    private fun open(styles: Boolean) {
        val intent = Intent(app, CleanupActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            .putExtra(CleanupActivity.EXTRA_STYLES, styles)
            .putExtra(CleanupActivity.EXTRA_ANCHOR, anchor())
        opening = true
        redraw()
        try {
            start(intent)
        } catch (e: RuntimeException) {
            Log.w(TAG, "cleanup_open_failed ${e.javaClass.name}")
            opening = false
            redraw()
        }
    }

    /** The card came up: false when nothing waits for it (the offer went), and it closes at once. */
    fun attach(activity: Activity): Boolean {
        if (!opening || offer == null) return false
        card = WeakReference(activity)
        return true
    }

    /** The card went, however it closed; its answer, if it gave one, is written now. */
    fun detach(activity: Activity) {
        if (card?.get() !== activity) return
        card = null
        opening = false
        val cleaned = pending
        pending = null
        if (cleaned != null) write(cleaned) else redraw()
    }

    /**
     * The card's checked answer, written once the card has closed: a web page's field loses its focus and input session
     * while another app is in front, and gets them back only then.
     */
    fun deliver(cleaned: String) {
        if (opening) pending = cleaned
    }

    private fun close() {
        card?.get()?.finish()
        card = null
        opening = false
        pending = null
    }

    /** The take's words for the model while its card is up; null once the offer went. */
    fun take(): String? = offer?.takeIf { opening }?.take?.trim()

    /** The model's [output] for [style], checked against the take; Rejected once the offer went. */
    fun check(style: CleanupStyle, output: String?): CleanupCheck.Verdict =
        offer?.let { CleanupCheck.check(it.take, output, style) } ?: CleanupCheck.Verdict.Rejected("gone")

    /**
     * Writes [cleaned] in place of the take, then calls [done] with whether it did. A field that changed gets nothing
     * and a toast says so; after a write the sparkle offers Undo.
     */
    fun write(cleaned: String, done: (Boolean) -> Unit = {}) {
        val offer = offer ?: return done(false)
        writing = true
        redraw()
        scope.launch {
            val pin = withContext(io) { field(offer.pin) }
            val (result, payload) = if (pin == null) refused("session") to "" else guarded { replace(pin, offer.take, cleaned) }
            writing = false
            Log.i(TAG, "cleanup_write result=$result")
            if (pin != null && result == Replaced.DONE) offer.pin = pin
            if (this@Cleanup.offer === offer) if (result == Replaced.DONE) offer.written = payload else fail(result)
            redraw()
            done(result == Replaced.DONE)
        }
    }

    private fun undo(offer: Offer) {
        val written = offer.written ?: return
        writing = true
        redraw()
        scope.launch {
            val (result, _) = guarded { put(offer.pin, written, offer.take) to offer.take }
            writing = false
            Log.i(TAG, "cleanup_undo result=$result")
            if (this@Cleanup.offer === offer) if (result == Replaced.DONE) offer.written = null else fail(result)
            redraw()
        }
    }

    /** A write that didn't land ends the offer: the words before the cursor are no longer the ones it knows. */
    private fun fail(result: Replaced) {
        toast(if (result == Replaced.UNSURE) R.string.cleanup_check_text else R.string.cleanup_text_changed)
        offer = null
    }

    /**
     * Off main: the take's field, ready for a write: [pin] while its session lasts, else the session the same field starts
     * again once the card has closed, for up to 2 s; null without one.
     */
    private suspend fun field(pin: Pin): Pin? {
        repeat(20) { tries ->
            val fresh = if (port.samePin(pin)) pin else port.cachedPin()?.takeIf { sameField(it, pin) && port.samePin(it) }
            if (fresh != null) {
                if (tries > 0 || fresh !== pin) Log.i(TAG, "cleanup_field waited_ms=${tries * 100} new_session=${fresh !== pin}")
                return fresh
            }
            delay(100)
        }
        return null
    }

    /** Runs [block] on [io]. An unexpected exception (an accessibility call) reads as UNSURE: a write may have gone out. */
    private suspend fun guarded(block: () -> Pair<Replaced, String>): Pair<Replaced, String> = try {
        withContext(io) { block() }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "cleanup_write_error ${e.javaClass.name}") // the class only: a message can carry text
        Replaced.UNSURE to ""
    }

    /** Off main: [cleaned], formatted against the text before the take as the take was, in the take's place. */
    private fun replace(pin: Pin, take: String, cleaned: String): Pair<Replaced, String> {
        if (!writable(pin)) return Replaced.UNCHANGED to ""
        val around = port.readSurrounding(pin, take.length + 64, 64) ?: return refused("read") to ""
        val payload = CleanupCheck.replacement(around.before, take, cleaned, around.after, port.inputType())
            ?: return refused("moved") to ""
        return put(pin, take, payload) to payload
    }

    private fun put(pin: Pin, old: String, new: String): Replaced =
        if (writable(pin)) port.replaceBeforeCursor(pin, old, new) else Replaced.UNCHANGED

    /** The take's field still holds its session and focus, and is no password field (focus can reach one meanwhile). */
    private fun writable(pin: Pin): Boolean = when {
        !port.samePin(pin) -> refused("pin") == Replaced.DONE
        port.isPasswordTarget() -> refused("password") == Replaced.DONE
        !port.stillFocused(pin) -> refused("focus") == Replaced.DONE
        else -> true
    }

    /** Logs which check refused a write (a code, no text), and reads as nothing written. */
    private fun refused(check: String): Replaced {
        Log.i(TAG, "cleanup_refused check=$check")
        return Replaced.UNCHANGED
    }

    private companion object {
        const val TAG = "ThumbFree"
    }
}
