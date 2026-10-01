package io.github.kabrapratik28.thumbfree.app

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.graphics.Rect
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Choreographer
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowManager
import android.view.WindowMetrics
import io.github.kabrapratik28.thumbfree.a11y.AccessibilityEditorPort
import io.github.kabrapratik28.thumbfree.a11y.BubbleWindow
import io.github.kabrapratik28.thumbfree.a11y.DictationAccessibilityService
import io.github.kabrapratik28.thumbfree.a11y.EditorPort
import io.github.kabrapratik28.thumbfree.a11y.FocusTracker
import io.github.kabrapratik28.thumbfree.a11y.InsertResult
import io.github.kabrapratik28.thumbfree.a11y.Inserter
import io.github.kabrapratik28.thumbfree.a11y.Pin
import io.github.kabrapratik28.thumbfree.a11y.ServiceListener
import io.github.kabrapratik28.thumbfree.audio.AudioFocus
import io.github.kabrapratik28.thumbfree.audio.ForegroundHooks
import io.github.kabrapratik28.thumbfree.audio.ForegroundListener
import io.github.kabrapratik28.thumbfree.audio.PcmRing
import io.github.kabrapratik28.thumbfree.audio.Recorder
import io.github.kabrapratik28.thumbfree.audio.RecordingResult
import io.github.kabrapratik28.thumbfree.audio.RecordingService
import io.github.kabrapratik28.thumbfree.core.audio.Chunk
import io.github.kabrapratik28.thumbfree.core.audio.WavChunks
import io.github.kabrapratik28.thumbfree.core.audio.WavWriter
import io.github.kabrapratik28.thumbfree.core.models.ModelFile
import io.github.kabrapratik28.thumbfree.core.session.BubblePlacement
import io.github.kabrapratik28.thumbfree.core.session.BubbleUi
import io.github.kabrapratik28.thumbfree.core.session.Code
import io.github.kabrapratik28.thumbfree.core.session.Event
import io.github.kabrapratik28.thumbfree.core.session.Gesture
import io.github.kabrapratik28.thumbfree.core.session.HapticKind
import io.github.kabrapratik28.thumbfree.core.session.Outcome
import io.github.kabrapratik28.thumbfree.core.session.Preview
import io.github.kabrapratik28.thumbfree.core.session.PreviewUi
import io.github.kabrapratik28.thumbfree.core.session.Session
import io.github.kabrapratik28.thumbfree.core.session.State
import io.github.kabrapratik28.thumbfree.core.session.TouchOutput
import io.github.kabrapratik28.thumbfree.core.text.joinChunks
import io.github.kabrapratik28.thumbfree.data.HistoryWriteException
import io.github.kabrapratik28.thumbfree.data.Status
import io.github.kabrapratik28.thumbfree.engine.PreviewFeed
import io.github.kabrapratik28.thumbfree.engine.TranscriptionQueue
import java.io.File
import java.io.IOException
import java.util.SortedMap
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The controller's Android side, and the listener of the queue, the accessibility service and the recording service.
 * Port methods run on the main thread and never call the controller back: every recorder, queue, service and
 * foreground callback is posted to the main thread first, because the controller may be in the middle of an effect
 * batch. The per-take maps are touched on the main thread only.
 */
class AndroidPorts(private val app: Application, private val editorPort: EditorPort = AccessibilityEditorPort(app)) :
    DictationPorts, TranscriptionQueue.Listener, ServiceListener, ForegroundListener {
    companion object {
        private const val TAG = "ThumbFree"
        private const val GEOMETRY_MS = 500L // live preview: the geometry's refresh, at most this often

        fun statusOf(outcome: Outcome): Status = Status.valueOf(outcome.name)

        fun hapticConstant(kind: HapticKind): Int = when (kind) {
            HapticKind.TICK -> HapticFeedbackConstants.CLOCK_TICK
            HapticKind.STOP -> HapticFeedbackConstants.CONTEXT_CLICK
            HapticKind.CONFIRM -> HapticFeedbackConstants.CONFIRM
            HapticKind.REJECT -> HapticFeedbackConstants.REJECT
        }
    }

    internal val db = DbThread(AppGraph::historyWritten)
    private val main = Handler(Looper.getMainLooper())
    private val scope = MainScope()
    private val filesDir = app.filesDir // read once, at process start: getFilesDir() touches the disk
    // Field writes sent so far (commit, paste): an insert that throws tells from it whether its text may have landed.
    @Volatile private var writes = 0
    private val inserter = Inserter(object : EditorPort by editorPort {
        override fun commit(pin: Pin, text: String): Boolean {
            writes++ // before the call: a write that throws may still have reached the field
            return editorPort.commit(pin, text)
        }

        override fun paste(): Boolean {
            writes++
            return editorPort.paste()
        }
    }, Dispatchers.IO.limitedParallelism(1))
    private val focus = FocusTracker()
    private val audioFocus = AudioFocus(app.getSystemService(AudioManager::class.java)) {
        post { (controller.state as? State.Recording)?.let { controller.onEvent(Event.StopRequested(it.id, Code.CALL)) } }
    }
    private val controller: DictationController get() = AppGraph.controller
    private val history get() = AppGraph.history
    private val queue get() = AppGraph.queue

    // A take's entries go when its outcome is saved or its session is deleted (see ended). Its pin stays for an Undo
    // or Retry on its chip: until the next take starts, or while that Undo or Retry runs. pins, chunkTexts and
    // insertedText are internal for tests.
    private val recorders = HashMap<String, Recorder>()
    private val finishing = HashMap<String, Recorder>() // stopped by stopCapture, still finishing the WAV until onStopped
    internal val pins = HashMap<String, Pin?>()
    private val downAt = HashMap<String, Long>()
    private val samples = HashMap<String, Long>()
    private val chunkCount = HashMap<String, Int>()
    private val totals = HashMap<String, Int>()
    internal val chunkTexts = HashMap<String, SortedMap<Int, String>>() // final texts by chunk index
    internal val insertedText = HashMap<String, String?>()
    private val markFailed = ConcurrentHashMap.newKeySet<String>() // written on the history thread
    private val redoing = HashSet<String>() // ended takes whose Undo, Retry or history Transcribe still runs
    private var serviceTake: String? = null // the take RecordingService was started for
    // The model of each take and each Retry or history Transcribe, fixed when it starts and read by the queue's worker
    // (modelPath): a switch while it runs, even before the queue reaches it, applies from the next one. The stage writes
    // it to the row with the text it made.
    private val models = ConcurrentHashMap<String, ModelFile>()
    private val leases = ConcurrentHashMap<String, Pair<ModelFile, Long>>() // each one's lease on that model
    private val requests = HashMap<String, Any>() // the Retry or history Transcribe whose WAV is being planned, by take

    // Live preview of the current take (Settings.livePreview and the take's model, read at each start): its state
    // (Preview.reduce), what the panel shows, and the feed that streams its audio to :engine, made at the first preview.
    // Main thread; the feed posts its events here.
    private var preview = Preview.State()
    private var previewUi: PreviewUi = PreviewUi.Hidden
    @Volatile private var feed: PreviewFeed? = null

    private fun feed() = feed ?: PreviewFeed(
        AppGraph.engine, CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1)),
    ) { event -> post { onPreview(event) } }.also { feed = it }

    /**
     * Live preview: returns once the last preview stream that ended is freed in :engine: the queue waits for it before
     * each transcribe, so a take's final chunk never runs behind preview work. At once with the preview off.
     */
    suspend fun awaitPreviewEnded() {
        feed?.awaitEnded()
    }

    /** Live preview: what the panel keeps clear of, in screen pixels: the cursor's line (or field) and the keyboard. */
    class PreviewGeometry(val line: BubblePlacement.Box?, val imeTop: Int?)

    // Live preview: the panel is laid out from this cached geometry only, so a preview update never makes the target
    // app answer on the main thread. A worker refreshes it at most every GEOMETRY_MS while a preview is live, on focus
    // reports and on updates. It belongs to one focus, geometryFocus (the window, node and editor generation, not the
    // field's look: two plain fields in one window look the same): another focus drops it, and the panel hides until
    // the new focus's answer comes.
    @Volatile private var geometry: PreviewGeometry? = null
    private var geometryFocus: DictationAccessibilityService.FocusKey? = null
    private var geometryAskedAt = -GEOMETRY_MS
    private var geometryBusy = false
    private val geometryWorker by lazy { Executors.newSingleThreadExecutor { Thread(it, "preview-geometry").apply { isDaemon = true } } }

    /**
     * Live preview: the geometry the worker reads: the cursor's line, or the whole focused field when the app gives no
     * line (DictationAccessibilityService.cursorLine), and the keyboard's top. Tests replace it; it may block.
     */
    internal var geometrySource: () -> PreviewGeometry? = {
        service?.let { s -> PreviewGeometry(s.cursorLine()?.let { BubblePlacement.Box(it.left, it.top, it.right, it.bottom) }, s.imeBounds()?.top) }
    }

    /**
     * Live preview: asks the worker for fresh geometry, unless it was asked less than GEOMETRY_MS ago or is still busy.
     * The answer counts only for the focus it was asked for; one that comes after the focus moved is dropped, and the
     * new focus's is asked for at once. Main thread.
     */
    private fun refreshGeometry() {
        if (preview.take == null) return
        val now = SystemClock.uptimeMillis()
        if (geometryBusy || now - geometryAskedAt < GEOMETRY_MS) return
        geometryAskedAt = now
        geometryBusy = true
        val source = geometrySource
        val asked = geometryFocus
        geometryWorker.execute {
            val fresh = runCatching { source() }.getOrNull()
            post {
                geometryBusy = false
                if (asked == geometryFocus) geometry = fresh else refreshGeometry()
                if (previewUi != PreviewUi.Hidden) showPreview()
            }
        }
    }

    /** Live preview: the take whose preview is live or finishing (null once it has ended), for tests. */
    internal val previewTake: String? get() = preview.take

    /** Live preview: whether the preview has words to show (the panel shows them once it has geometry), for tests. */
    internal val previewHasWords: Boolean get() = previewUi != PreviewUi.Hidden

    /** Every take id the per-take maps above still hold, the live preview's too, for tests. */
    internal fun heldTakes(): Set<String> = listOf(
        recorders.keys, finishing.keys, pins.keys, downAt.keys, samples.keys, chunkCount.keys, totals.keys,
        chunkTexts.keys, insertedText.keys, markFailed, redoing, models.keys, leases.keys, requests.keys,
        listOfNotNull(preview.take),
    ).flatten().toSet()

    /** The take has ended: nothing reads these entries again. Its pin is up to the caller. */
    private fun ended(id: String) {
        downAt -= id
        samples -= id
        chunkCount -= id
        totals -= id
        chunkTexts -= id
        markFailed -= id
        models -= id
        release(id)
        requests -= id
    }

    /**
     * [id] starts with [model]: it holds that model until its queue result, so a delete can't take it meanwhile.
     * False while a delete has the model: the take or Transcribe then gets no model path.
     */
    private fun use(id: String, model: ModelFile): Boolean {
        models[id] = model
        val lease = AppGraph.leases.hold(model)
        leases.put(id, model to lease)?.let { (old, stamp) -> AppGraph.leases.release(old, stamp) }
        return lease != 0L
    }

    /** Gives [id]'s model back; again is a no-op. At the queue's result, or when the take ends without one. */
    private fun release(id: String) {
        leases.remove(id)?.let { (model, lease) -> AppGraph.leases.release(model, lease) }
    }

    /**
     * For the queue, once per session: the verified file of the model that take, Retry or history Transcribe [id] started
     * with. Null when that model is not verified. May hash.
     */
    fun modelPath(id: String): String? {
        // One that got no lease, because a delete had its model, gets no path even while the file is still there: the
        // queue's existing NO_MODEL, before any engine work.
        if (leases[id]?.second == 0L) return null
        return AppGraph.modelStore.verifiedPath(models[id] ?: AppGraph.settings.model)?.path
    }

    /**
     * The language hint of the model [id] started with (ModelFile.languageHint): what the queue tells the engine, and,
     * through its result, what the controller cleans the text in. Null for the multilingual model, which hears the
     * language itself.
     */
    fun language(id: String): String? = (models[id] ?: AppGraph.settings.model).languageHint


    // The bubble, while the accessibility service is connected.
    private var service: DictationAccessibilityService? = null
    private var windowManager: WindowManager? = null
    private var bubble: BubbleWindow? = null
    private var relay: TouchRelay? = null
    private var shownAt: Pair<Int, Int>? = null // the spot the last placement applied; null while hidden or after a drag
    private var touching = false
    private var dragOrigin: Rect? = null
    private var dragX = 0 // the circle's top-left during a drag
    private var dragY = 0
    // The press context, taken at ACTION_DOWN before the touch reaches the classifier and the controller.
    private var downPin: Pin? = null
    private var downAtMs = 0L
    private val recheck = Runnable { place() }

    private fun post(block: () -> Unit) {
        main.post(block)
    }

    private fun wavOf(id: String) = File(filesDir, "recordings/$id.wav")

    private fun px(dp: Int) = (dp * app.resources.displayMetrics.density).toInt()

    // DictationPorts

    override fun newSessionId(): String = UUID.randomUUID().toString()

    override fun createRow(id: String): Boolean {
        val pkg = focus.current?.packageName
        val model = AppGraph.settings.model
        val created = db.writeAndWait(500) {
            history.create(id, System.currentTimeMillis(), "recordings/$id.wav", model.id, pkg)
        } != null
        if (created) use(id, model) // before ensureEngineLoaded, which the controller sends next
        return created
    }

    // A discarded take, or a take deleted from history (which has deleted the row and WAV already): its queue work stops,
    // a history Transcribe of it included, and nothing it kept stays.
    override fun deleteSession(id: String) {
        onPreview(Preview.Event.Cancel(id))
        queue.cancel(id) // a leftover queue session ends and skips its chunks
        ended(id)
        pins -= id
        redoing -= id
        insertedText -= id
        db.write {
            wavOf(id).delete()
            history.delete(id, filesDir) // a missing row is fine
        }
    }

    override fun startForeground(id: String) {
        // Without the grant, Android 14 and later refuse a microphone service: the user would see FOREGROUND_DENIED.
        if (app.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            post { controller.onEvent(Event.CaptureFailed(id, Code.MIC_PERMISSION)) }
            return
        }
        ForegroundHooks.takeActive = true
        if (RecordingService.start(app)) serviceTake = id // a refusal has already reported onForegroundDenied
    }

    override fun stopForeground(id: String) {
        ForegroundHooks.takeActive = false
        // Once per start. A take can end in Arming before the service's onStartCommand; stop then waits for it.
        if (serviceTake == id) {
            serviceTake = null
            RecordingService.stop(app)
        }
    }

    override fun startCapture(id: String) {
        val source = AppGraph.audioSourceFactory(app)
        val wav = wavOf(id)
        lateinit var recorder: Recorder
        val listener = object : Recorder.Listener {
            override fun onFirstBuffer() { // capture thread, once
                val at = SystemClock.uptimeMillis()
                val fgs = ForegroundHooks.isForeground
                val silenced = source.silenced
                post {
                    // From touch-down, so the service start and the row write count.
                    Log.i(TAG, "take_capture fgs=$fgs silenced=$silenced first_buffer_ms=${at - (downAt[id] ?: at)}")
                    controller.onEvent(Event.FirstBuffer(id))
                }
            }

            override fun onLevel(unit: Float) = post { controller.onLevel(id, unit) }

            override fun onChunk(chunk: Chunk) = post {
                // A chunk after stopCapture is dropped: a submit after queue.cancel(id) would start a fresh session.
                if (recorders[id] !== recorder) return@post
                chunkCount[id] = (chunkCount[id] ?: 0) + 1
                queue.submit(id, wav.path, chunk)
            }

            override fun onSilentMic() = post { controller.onWarning(id, Code.MIC_SILENT) }

            override fun onError(code: Code) = post {
                onPreview(Preview.Event.Stop(id)) // the take stops here too: its stream ends before its last chunk runs
                controller.onEvent(Event.CaptureFailed(id, code))
            }

            override fun onStopped(result: RecordingResult) = post {
                result.tail?.let { Log.i(TAG, "take_tail ms=${result.tailMs} end=${it.name.lowercase()}") }
                if (finishing.remove(id) == null) {
                    if (recorders[id] === recorder) recorders.remove(id) // its last call: the ring buffer can go
                    samples[id] = result.samples
                } else {
                    // Stopped by a Cancel, whose outcome is already saved, or by a discard, whose row is gone: the
                    // length goes to the row, if any, with the rest of it as it is now.
                    val ms = result.samples * 1000 / 16_000
                    db.write { history.get(id)?.let { history.finish(id, it.status, it.error, it.insertedText, ms) } }
                }
                controller.onEvent(Event.TailDone(id, result.hasSpeech, result.samples))
            }
        }
        val open = { WavWriter.create(wav.apply { parentFile!!.mkdirs() }) } // on the writer thread
        // With the live preview on and a model that can show it, the Recorder puts the take's audio in a ring the feed
        // streams to :engine. Off, or on a model without it, nothing of the preview runs.
        val model = models[id]?.takeIf { AppGraph.settings.livePreview && it.livePreview }
        val ring = model?.let { PcmRing(PreviewFeed.RING_SAMPLES) }
        recorder = Recorder(source, open, listener, SystemClock::elapsedRealtime, preview = ring)
        recorders[id] = recorder
        if (model != null && ring != null) {
            feed().start(id, model.fileName, ring)
            onPreview(Preview.Event.Start(id))
        }
        recorder.start()
    }

    override fun startTail(id: String) {
        onPreview(Preview.Event.Stop(id)) // the panel keeps the words so far while today's path transcribes
        recorders[id]?.requestStop()
    }

    override fun stopCapture(id: String, keepAudio: Boolean) {
        onPreview(Preview.Event.Cancel(id))
        val recorder = recorders.remove(id) ?: return // the WAV stays until DeleteSession
        recorder.cancel()
        finishing[id] = recorder
    }

    override fun msSinceLastSpeech(id: String): Long = recorders[id]?.msSinceSpeech ?: 0

    override fun requestAudioFocus() {
        audioFocus.request()
    }

    override fun abandonAudioFocus() = audioFocus.abandon()

    override fun ensureEngineLoaded(id: String) = queue.ensureLoaded(id)

    override fun finishTranscription(id: String) {
        val total = chunkCount[id] ?: 0
        totals[id] = total
        queue.finish(id, total)
    }

    override fun abortEngine(id: String) = queue.cancel(id)

    override fun pinTarget(id: String) {
        pins.keys.retainAll(redoing) // the new take replaces any chip: only a running Undo or Retry still needs its pin
        pins[id] = downPin
        downAt[id] = downAtMs
    }

    override fun saveStaged(id: String, raw: String, text: String): Boolean {
        val durationMs = samples[id]?.let { it * 1000 / 16_000 }
        val model = models[id]?.id // made this text; a retranscription's may differ from the row's
        val saved = db.writeAndWait(500) {
            // A retranscription after a restart has no sample count here: the row keeps its duration.
            history.stage(id, raw, text, durationMs ?: history.get(id)?.durationMs ?: 0, model)
        } != null
        // A stage that timed out still runs. Landing late, it must not leave the row live (no Delete, no Transcribe)
        // until the next start, as a history Transcribe saves no outcome after it: the row ends NOT_INSERTED with its
        // text. A stage that failed leaves the row as it was.
        if (!saved) db.write {
            history.get(id)?.takeIf { it.status == Status.STAGED }?.let { history.finish(id, Status.NOT_INSERTED, it.error) }
        }
        return saved
    }

    // Ends the request as saveOutcome ends a take: its entries go, and so does its model lease.
    override fun saveRetranscription(id: String, raw: String, text: String): Boolean {
        val model = models[id]?.id // made this text; it may differ from the row's
        ended(id)
        redoing -= id
        return db.writeAndWait(500) {
            history.saveRetranscription(id, raw, text, model)
            history.applyRetention(AppGraph.settings.retention, System.currentTimeMillis(), filesDir)
        } != null
    }

    override fun markInserting(id: String) {
        markFailed -= id
        db.write {
            try {
                history.markInserting(id)
            } catch (e: HistoryWriteException) {
                markFailed += id
                throw e
            }
        }
    }

    override fun insert(id: String, text: String, autoInsert: Boolean) = launchInsert(id) {
        db.barrier() // the INSERTING row is on disk before any commit
        // Without that row a crash during the insert could not be told apart later: the text waits on the chip.
        inserter.insert(text, pins[id], autoInsert && id !in markFailed)
    }

    override fun insertHere(id: String, text: String) = launchInsert(id) { inserter.insertHere(text) }

    /**
     * Runs one insert and reports it. An unexpected exception (from an accessibility, clipboard or file call) ends the
     * take on its chip instead of the process. After a write the text may be in the field, so the take ends UNVERIFIED
     * (Copy only), as the Inserter does when it cannot tell: Insert here would write it twice. Before any write it ends
     * NOT_INSERTED.
     */
    private fun launchInsert(id: String, attempt: suspend () -> InsertResult) {
        scope.launch {
            val writesBefore = writes
            val result = try {
                attempt()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "take_insert_error ${e.javaClass.name}") // the class only: a message can carry text
                if (writes != writesBefore) InsertResult(Outcome.UNVERIFIED, null, Code.MAY_NOT_HAVE_LANDED)
                else InsertResult(Outcome.NOT_INSERTED, null, Code.NO_SESSION)
            }
            insertedText[id] = result.insertedText
            controller.onEvent(Event.InsertDone(id, result.outcome, result.code))
        }
    }

    override fun saveOutcome(id: String, outcome: Outcome, code: Code?) {
        val inserted = insertedText.remove(id) // each insert result is saved once, right after its InsertDone
        // Unknown yet after a Cancel while recording: onStopped writes it then.
        val durationMs = samples[id]?.let { it * 1000 / 16_000 }
        // The pin stays: an Undo or Retry on the chip inserts through it, and the next take's pinTarget drops it.
        ended(id)
        redoing -= id
        db.write {
            // A null code keeps the row's error, so a history Transcribe after a restart does not blank it.
            history.finish(id, statusOf(outcome), code?.name ?: history.get(id)?.error, inserted, durationMs)
            history.applyRetention(AppGraph.settings.retention, System.currentTimeMillis(), filesDir)
        }
    }

    override fun haptic(kind: HapticKind) {
        bubble?.haptic(hapticConstant(kind))
    }

    override fun render(ui: BubbleUi) {
        // A take that has ended (typed, a chip, no speech, a failure) takes its live preview panel with it.
        if (controller.state == State.Idle) preview.take?.let { onPreview(Preview.Event.Done(it)) }
        val bubble = bubble ?: return
        bubble.render(ui)
        // Keyed on the take, not on the drawing: a warning chip while it records must not let the screen dim.
        bubble.keepScreenOn(controller.state.let { it is State.Arming || it is State.Recording })
        // A take can end away from an eligible field, and a chip stays until it goes: going idle places the bubble again,
        // which hides it then. Posted, so the placement sees the controller after this effect batch and never calls it
        // back from inside a port call.
        if (ui == BubbleUi.Idle) post(::place)
    }

    // Handler compares tokens by identity; interned, equal ids are the same token.
    override fun schedule(id: String, kind: TimerKind, delayMs: Long) {
        main.postAtTime({ controller.onTimer(id, kind) }, id.intern(), SystemClock.uptimeMillis() + delayMs)
    }

    override fun cancelTimers(id: String) = main.removeCallbacksAndMessages(id.intern())

    override fun copy(text: String): Boolean = editorPort.copyToClipboard(text)

    internal var plan: (File) -> List<Chunk> = WavChunks::plan // tests make it throw

    override fun retranscribe(id: String) {
        val held = use(id, AppGraph.settings.model) // the model chosen when it was asked for, as a take gets at its press
        redoing += id
        queue.cancel(id) // a Retry never joins an old failed session still waiting for its finish
        chunkTexts.remove(id)
        val request = Any()
        requests[id] = request
        // A delete has the model, so the existing model-unavailable path, before any queue work.
        if (!held) {
            post { if (requests[id] === request) controller.onEvent(Event.TranscriptFailed(id, Code.NO_MODEL)) }
            return
        }
        val wav = wavOf(id)
        val recorder = finishing[id]
        scope.launch(Dispatchers.IO) {
            try {
                // An Undo right after Cancel plans the finished WAV, not the prefix on disk now; after 2 s it plans
                // what is there.
                if (recorder?.awaitStopped(2_000) == false) Log.w(TAG, "take_undo_wait_timeout")
                val chunks = try {
                    if (wav.length() < WavWriter.HEADER_BYTES) null else plan(wav)
                } catch (e: IOException) {
                    null
                }
                if (chunks == null) {
                    post { controller.onEvent(Event.TranscriptFailed(id, Code.AUDIO_MISSING)) }
                    return@launch
                }
                // A deliberate manual override, still gated by Silero: the user tapped Transcribe, so when no chunk
                // has a frame above -55 dBFS every chunk still goes to the engine instead of being refused on its
                // level. Silero checks each one like any other chunk, and one it hears no speech in keeps no text.
                val asked = if (chunks.none { it.hasSpeech }) chunks.map { it.copy(hasSpeech = true) } else chunks
                // On main, where deleteSession runs: a request it dropped while the WAV was planned starts nothing.
                post {
                    if (requests[id] !== request) return@post
                    queue.ensureLoaded(id)
                    asked.forEach { queue.submit(id, wav.path, it) }
                    queue.finish(id, asked.size)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) { // unexpected, from a file call: the take fails on its chip instead of the process
                Log.w(TAG, "take_retranscribe_error ${e.javaClass.name}") // the class only: a message can carry text
                post { controller.onEvent(Event.TranscriptFailed(id, Code.AUDIO_MISSING)) }
            }
        }
    }

    // TranscriptionQueue.Listener: called on the queue's worker under its lock, so each one only posts.

    override fun onLoading(sessionId: String) = post { controller.onEvent(Event.ModelLoading(sessionId)) }

    override fun onLoaded(sessionId: String) = post { controller.onModelLoaded(sessionId) }

    override fun onChunkDone(sessionId: String, index: Int, text: String, rawText: String) = post {
        // Only a live take's result, or a retranscription's, counts. A silent chunk is answered at once and can arrive
        // after its take has ended: saving it would bring back chunk texts nothing removes, onto a finished row.
        if (sessionId != Session.sessionId(controller.state) && sessionId !in redoing) return@post
        val texts = chunkTexts.getOrPut(sessionId) { sortedMapOf() }.apply { put(index, text) }
        val count = texts.size
        // A live take's truncated chunk comes here before onFailed, so its partial text is saved too. A Retry's or history
        // Transcribe's is not: its row stays terminal while it runs, and History would show and copy the text before the
        // end. Its text reaches the row when it ends (stage), and a failed one leaves the row as it was.
        if (sessionId !in redoing) {
            val partial = joinChunks(texts.values.toList())
            db.write { history.saveChunk(sessionId, count, partial) }
        }
        controller.onEvent(Event.ChunkDone(sessionId, count, totals[sessionId]))
    }

    // The session has ended, so its chunk texts go: onDone brings all of them, and no chunk follows a failure.
    // Its model is no longer needed after its queue result either: the lease goes now, whatever then happens to the
    // text. A failed history save, say, never reaches saveOutcome or deleteSession.
    override fun onDone(sessionId: String, texts: List<String>, rawTexts: List<String>, speech: Boolean, language: String?) =
        post {
            chunkTexts.remove(sessionId)
            release(sessionId)
            controller.onQueueDone(sessionId, texts, rawTexts, speech, language)
        }

    override fun onFailed(sessionId: String, code: Code) = post {
        chunkTexts.remove(sessionId)
        release(sessionId)
        controller.onEvent(Event.TranscriptFailed(sessionId, code))
    }

    // ForegroundListener

    override fun onForegroundStarted() = post { controller.onForegroundStarted() }

    override fun onForegroundDenied() {
        Log.i(TAG, "take_denied")
        post { controller.onForegroundDenied() }
    }

    override fun onForegroundStopped() = post { controller.onForegroundStopped() }

    // ServiceListener

    override fun onServiceConnected(service: DictationAccessibilityService) {
        this.service = service
        val windows = service.getSystemService(WindowManager::class.java)
        windowManager = windows
        relay = TouchRelay(Gesture(ViewConfiguration.get(service).scaledTouchSlop.toFloat()), ::onTouchOutput)
        bubble = BubbleWindow(service, windows, ::onBubbleTouch) { controller.onChip(it) }.also {
            it.onDropped = ::place // found gone: the touch on it ends at once and the window comes back, with no focus event
            it.restyle(AppGraph.settings.bubbleStyle)
            service.bubble = it
        }
        shownAt = null
        touching = false
    }

    override fun onServiceGone() {
        // The system removed the window with the service: nothing may lay it out again, and a finger on it never sends
        // its UP, so the touch is cancelled once the bubble is gone. Nothing keeps the dead service either.
        bubble?.dispose() // its chip's dismissal timer would otherwise dismiss the next window's chip
        bubble = null
        service = null
        windowManager = null
        shownAt = null
        touching = false
        relay?.hide()
        post { controller.onServiceGone() }
    }

    override fun onFieldChanged(field: FocusTracker.Field?, key: DictationAccessibilityService.FocusKey?) {
        val now = SystemClock.uptimeMillis()
        if (field != null) {
            focus.focused(field, now)
        } else {
            focus.lost(now)
            main.removeCallbacks(recheck)
            main.postDelayed(recheck, focus.hideDelayMs) // when the grace period is over
        }
        if (key != geometryFocus) { // the live preview's geometry was the last focus's
            geometryFocus = key
            geometry = null
            geometryAskedAt = -GEOMETRY_MS
            if (previewUi != PreviewUi.Hidden) showPreview() // hidden until the new focus's answer
        }
        refreshGeometry()
        place()
    }

    /**
     * The owner changed the bubble in Settings (its size, transparency, snap, or Reset position): a bubble on screen
     * takes it at once, placed again for its size and spot. Main thread.
     */
    fun bubbleSettingsChanged() {
        val bubble = bubble ?: return
        bubble.restyle(AppGraph.settings.bubbleStyle)
        shownAt = null // the spot depends on the size
        place()
        showPreview()
    }

    /** Live preview: runs the preview's reducer and its effects. Main thread; never calls the controller. */
    private fun onPreview(event: Preview.Event) {
        val (next, effects) = Preview.reduce(preview, event)
        preview = next
        refreshGeometry() // at a take's start, and then at most every GEOMETRY_MS
        for (effect in effects) when (effect) {
            is Preview.Effect.EndStream -> feed?.stop()
            is Preview.Effect.Show -> {
                previewUi = effect.ui
                showPreview()
            }
        }
    }

    /**
     * Live preview: the panel where the owner chose, from the cached geometry only; hidden until the worker has read it
     * for this field.
     */
    private fun showPreview() {
        val bubble = bubble ?: return
        val metrics = windowManager?.currentWindowMetrics
        val geometry = geometry
        if (previewUi == PreviewUi.Hidden || metrics == null || geometry == null) return bubble.hidePreview()
        bubble.showPreview(previewUi, area(metrics), geometry.imeTop, geometry.line, AppGraph.settings.livePreviewPlace)
    }

    /** The circle's side in pixels: its touch target, at the size the owner picked (48 dp before the service connects). */
    private val bubblePx get() = bubble?.sizePx ?: px(48)

    /**
     * Shows the bubble at its spot for the focused field, or hides it. Every window event reports focus again, our own
     * moves and a keyboard coming or going included, with no new field: so the spot is computed on every report, and
     * only an unchanged spot is a no-op.
     */
    private fun place() {
        val bubble = bubble ?: return
        if (!focus.visible(SystemClock.uptimeMillis(), controller.state != State.Idle)) {
            touching = false
            relay?.hide() // the classifier will never see the UP of a finger on a removed window
            bubble.hide()
            shownAt = null
            return
        }
        // A relayout found the window gone under the finger: that UP never comes, so the touch ends as a hide ends it.
        if (touching && !bubble.shown) {
            touching = false
            relay?.hide()
        }
        if (touching) return // the window stays under the finger; the UP places it again
        val spot = spot() ?: return
        if (spot == shownAt && bubble.shown) return // a window the system dropped is added again
        if (!bubble.show(spot.first, spot.second)) return // tried again at the next focus report
        shownAt = spot
        logShown(spot)
        if (previewUi != PreviewUi.Hidden) showPreview() // the panel follows the bubble
    }

    // ponytail: one fixed gap above the keyboard for every app. 72 dp clears a chat app's input bar (up to 64 dp, with
    // Send at its right end) on the keyboard; remembering the height the user drags the bubble to would lift the limit.
    private val imeGapDp = 72

    /**
     * The circle's top-left in screen pixels. Until the owner drags it: at the right edge, above the keyboard or clear of
     * the bottom inset. After a drag: where it was dropped (Settings.bubbleSpot), above the keyboard, and moved up or down
     * just enough to keep off the line the text cursor is on.
     */
    private fun spot(): Pair<Int, Int>? {
        val metrics = windowManager?.currentWindowMetrics ?: return null
        val area = area(metrics)
        val ime = service?.imeBounds()
        val pinned = AppGraph.settings.bubbleSpot ?: return BubblePlacement.place(
            area.copy(bottom = metrics.bounds.bottom), ime?.let { BubblePlacement.Box(it.left, it.top, it.right, it.bottom) },
            metrics.bounds.bottom - area.bottom, bubblePx, px(imeGapDp), px(96), BubblePlacement.Side.RIGHT,
        )
        val (x, y) = BubblePlacement.at(pinned, area, bubblePx, ime?.top)
        val line = service?.cursorLine() ?: return x to y
        val bottom = minOf(area.bottom, ime?.top ?: area.bottom)
        return x to BubblePlacement.clear(x, y, bubblePx, BubblePlacement.Box(line.left, line.top, line.right, line.bottom), area.top, bottom, px(8))
    }

    /**
     * Where the bubble can go, in screen pixels: the window metrics' bounds (the width BubbleWindow picks a side from)
     * less the system bars and the cutout, so it never sits on a navigation bar, the status bar or a cutout.
     */
    private fun area(metrics: WindowMetrics): BubblePlacement.Box {
        val b = metrics.bounds
        val inset = metrics.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
        return BubblePlacement.Box(b.left + inset.left, b.top + inset.top, b.right - inset.right, b.bottom - inset.bottom)
    }

    /**
     * android/tools/fgs-gate.sh taps the middle of the last such line, so it is written once the window has laid out at
     * [spot], after the next frame's traversal. The numbers are the spot this class passed, never params.x, which
     * counts from the right edge under END gravity.
     */
    private fun logShown(spot: Pair<Int, Int>) {
        Choreographer.getInstance().postFrameCallback {
            main.post {
                when {
                    shownAt != spot -> Unit // hidden or placed again since
                    bubble?.shown != true -> Unit // found gone: the placement that adds it back logs again
                    bubble?.boundsOnScreen() == null -> logShown(spot) // not laid out yet
                    else -> Log.i(TAG, "bubble_shown x=${spot.first} y=${spot.second} w=$bubblePx h=$bubblePx")
                }
            }
        }
    }

    private fun onBubbleTouch(event: MotionEvent): Boolean {
        val relay = relay ?: return true
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touching = true
                // cachedPin() makes no IPC, and a focus change right after touch-down cannot move the target.
                downPin = editorPort.cachedPin()
                downAtMs = event.eventTime
                dragOrigin = bubble?.boundsOnScreen()
                dragX = dragOrigin?.left ?: 0
                dragY = dragOrigin?.top ?: 0
                relay.down(event.rawX, event.rawY, event.eventTime)
            }
            MotionEvent.ACTION_MOVE -> relay.move(event.rawX, event.rawY, event.eventTime)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                touching = false
                if (event.actionMasked == MotionEvent.ACTION_UP) {
                    // A drag ends where the finger lifts: the input system can resample the last MOVE tens of pixels off.
                    relay.move(event.rawX, event.rawY, event.eventTime)
                    relay.up(event.eventTime)
                } else {
                    relay.hide()
                }
                place() // placements were dropped while the finger was down
            }
        }
        return true
    }

    private fun onTouchOutput(output: TouchOutput) {
        when (output) {
            // From where the circle was at ACTION_DOWN: show() ignores a spot while a finger is down, and params.x
            // counts from the right edge under END gravity.
            is TouchOutput.DragBy -> dragOrigin?.let { origin ->
                dragX = origin.left + output.dx.roundToInt()
                dragY = origin.top + output.dy.roundToInt()
                bubble?.move(dragX, dragY)
                shownAt = null
            }
            // It stays where it is let go, kept on screen and above the keyboard, and at the nearer edge with Snap to
            // screen edge; the UP's placement puts it there. Settings > Bubble hears of it for its Reset position.
            is TouchOutput.DragEnd -> windowManager?.let {
                AppGraph.settings.bubbleSpot = BubblePlacement.spotOf(
                    dragX, dragY, area(it.currentWindowMetrics), bubblePx, service?.imeBounds()?.top, AppGraph.settings.bubbleSnap,
                )
                AppGraph.bubbleMoved()
            }
            else -> Unit
        }
        controller.onTouch(output)
    }
}
