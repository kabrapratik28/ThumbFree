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
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Clean up (issue #1): after a take is typed, the sparkle beside the bubble tidies its words with Gemini Nano. A tap uses
 * the default style (Clean writes at once; any other style shows its answer first) and a hold opens the styles, both in
 * CleanupActivity, since ML Kit answers only while a ThumbFree activity is resumed. Undo writes the take back. Only the
 * take's own words change: the same field, the same place (where the take ended, read right after it was typed), and
 * only while they sit right before the cursor. Main thread; field calls run on [io], which the dictation's own writes
 * share, so the two never interleave.
 */
class Cleanup(
    private val app: Application,
    private val port: EditorPort,
    private val enabled: () -> Boolean,
    private val draw: (Sparkle?) -> Unit,
    private val anchor: () -> Rect?,
    private val io: CoroutineDispatcher,
    private val previewFirst: () -> Boolean = { false },
    private val toast: (Int) -> Unit = { Toast.makeText(app, it, Toast.LENGTH_SHORT).show() },
    private val start: (Intent) -> Unit = app::startActivity,
) {
    /**
     * The take on offer: the exact text it typed, where in the field it ends ([end]), and the pin of its field (a new
     * session of the same field once the card cost it its session); [written], the tidied text in its place, until Undo.
     */
    private class Offer(var pin: Pin, val take: String, var end: Int) {
        var written: String? = null
    }

    private val scope = MainScope()
    private var offer: Offer? = null
        set(value) {
            field = value
            live = value
        }

    // The offer as the field calls see it from the io thread: a write goes ahead only while it is still this one.
    @Volatile private var live: Offer? = null
    private var asking: Any? = null // the read of a new take's end, until it answers
    private var job: Job? = null // the write or Undo that runs
    private var card: WeakReference<Activity>? = null
    private var opening = false // the card was asked for and has not closed yet
    private var writing = false
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

    /**
     * A take was typed and verified through [pin]; [take] is the exact text written. Where it ends in the field is read
     * first; then the sparkle offers to tidy it. A field that can't say where (no offset, no node) gets no sparkle.
     */
    fun offer(pin: Pin, take: String) {
        clear()
        if (!enabled() || status == FeatureStatus.UNAVAILABLE || take.isBlank()) return
        val ask = Any()
        asking = ask
        scope.launch {
            val end = try {
                // A field's node can lag the write by a moment: asked again for up to a second.
                withContext(io) {
                    var at: Int? = null
                    for (tries in 0 until 10) {
                        at = port.cursorEnd(pin, take)
                        if (at != null) break
                        delay(100)
                    }
                    at
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "cleanup_offer_error ${e.javaClass.name}")
                null
            }
            if (asking !== ask) return@launch
            asking = null
            if (end == null) {
                Log.i(TAG, "cleanup_no_offer")
                return@launch
            }
            offer = Offer(pin, take, end)
            redraw()
        }
    }

    /** A new take, another field, or the service gone: the sparkle goes, an open card closes, and a write stops. */
    fun clear() {
        close()
        asking = null
        job?.cancel()
        job = null
        writing = false
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
        if (!opening && !writing && pin != null && pin.generation != offer.pin.generation && !sameField(pin, offer.pin)) clear()
    }

    /** [pin] is [of]'s field in a new session: the same app, window and node. A pin that named no node matches only itself. */
    private fun sameField(pin: Pin, of: Pin) =
        of.nodeKey.isNotEmpty() && pin.packageName == of.packageName && pin.windowId == of.windowId && pin.nodeKey == of.nodeKey

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
            else -> open(hold || previewFirst())
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

    /** The card went, however it closed; the answer it handed over, if any, is written now. */
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
     * while another app is in front, and gets them back only then. Only from the card that is up, while it is up.
     */
    fun deliver(activity: Activity, cleaned: String) {
        if (opening && card?.get() === activity) pending = cleaned
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

    /** Writes [cleaned] in place of the take. A field that changed gets nothing and a toast says so; after a write, Undo. */
    fun write(cleaned: String) {
        val offer = offer ?: return
        var payload = ""
        run(offer, "cleanup_write", block = { pin ->
            val around = port.readSurrounding(pin, offer.take.length + 64, 64) ?: return@run refused("read")
            payload = CleanupCheck.replacement(around.before, offer.take, cleaned, around.after, port.inputType())
                ?: return@run refused("moved")
            if (!writable(pin)) Replaced.UNCHANGED else port.replaceBeforeCursor(pin, offer.take, payload, offer.end) { live === offer }
        }) {
            offer.written = payload
            offer.end += payload.length - offer.take.length
        }
    }

    private fun undo(offer: Offer) {
        val written = offer.written ?: return
        run(offer, "cleanup_undo", block = { pin -> port.replaceBeforeCursor(pin, written, offer.take, offer.end) { live === offer } }) {
            offer.written = null
            offer.end += offer.take.length - written.length
        }
    }

    /**
     * Runs one write for [offer] on [io], through its field ([field]); [block] makes it, and [done] follows on main once it
     * landed. The sparkle turns meanwhile; a write that didn't land ends the offer, with a toast.
     */
    private fun run(offer: Offer, log: String, block: (Pin) -> Replaced, done: () -> Unit) {
        writing = true
        redraw()
        job = scope.launch {
            var used: Pin? = null
            val result = try {
                withContext(io) {
                    val pin = field(offer.pin) ?: return@withContext refused("session")
                    used = pin
                    if (live !== offer || !writable(pin)) Replaced.UNCHANGED else block(pin)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "${log}_error ${e.javaClass.name}") // the class only: a message can carry text
                Replaced.UNSURE // a write may have gone out
            }
            writing = false
            Log.i(TAG, "$log result=$result")
            if (this@Cleanup.offer === offer) {
                if (result == Replaced.DONE) {
                    used?.let { offer.pin = it }
                    done()
                } else {
                    fail(result)
                }
            }
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
