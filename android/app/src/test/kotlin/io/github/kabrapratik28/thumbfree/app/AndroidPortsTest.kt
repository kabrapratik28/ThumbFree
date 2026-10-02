package io.github.kabrapratik28.thumbfree.app

import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteFullException
import android.graphics.Rect
import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.text.InputType
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.view.Gravity
import android.view.WindowManager
import android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
import android.view.accessibility.AccessibilityWindowInfo
import com.google.common.truth.Truth.assertThat
import io.github.kabrapratik28.thumbfree.a11y.AccessibilityEditorPort
import io.github.kabrapratik28.thumbfree.a11y.BubbleView
import io.github.kabrapratik28.thumbfree.a11y.DictationAccessibilityService
import io.github.kabrapratik28.thumbfree.a11y.FocusTracker
import io.github.kabrapratik28.thumbfree.a11y.EditorPort
import io.github.kabrapratik28.thumbfree.a11y.Pin
import io.github.kabrapratik28.thumbfree.audio.CaptureException
import io.github.kabrapratik28.thumbfree.audio.FakeAudioSource
import io.github.kabrapratik28.thumbfree.audio.ForegroundHooks
import io.github.kabrapratik28.thumbfree.audio.RecordingService
import io.github.kabrapratik28.thumbfree.audio.bursts
import io.github.kabrapratik28.thumbfree.core.audio.Chunk
import io.github.kabrapratik28.thumbfree.core.audio.WavWriter
import io.github.kabrapratik28.thumbfree.core.models.Catalog
import io.github.kabrapratik28.thumbfree.core.models.ModelFile
import io.github.kabrapratik28.thumbfree.core.models.ModelLeases
import io.github.kabrapratik28.thumbfree.core.models.ModelStore
import io.github.kabrapratik28.thumbfree.core.session.BubblePlacement.Spot
import io.github.kabrapratik28.thumbfree.core.session.BubbleStyle
import io.github.kabrapratik28.thumbfree.core.session.BubbleUi
import io.github.kabrapratik28.thumbfree.core.session.ChipAction
import io.github.kabrapratik28.thumbfree.core.session.Code
import io.github.kabrapratik28.thumbfree.core.session.Event
import io.github.kabrapratik28.thumbfree.core.session.Grey
import io.github.kabrapratik28.thumbfree.core.session.HapticKind
import io.github.kabrapratik28.thumbfree.core.session.Outcome
import io.github.kabrapratik28.thumbfree.core.session.Session
import io.github.kabrapratik28.thumbfree.core.session.State
import io.github.kabrapratik28.thumbfree.core.session.TouchOutput
import io.github.kabrapratik28.thumbfree.data.HistoryDb
import io.github.kabrapratik28.thumbfree.data.Retention
import io.github.kabrapratik28.thumbfree.data.Settings
import io.github.kabrapratik28.thumbfree.data.Status
import io.github.kabrapratik28.thumbfree.engine.FakeEngine
import io.github.kabrapratik28.thumbfree.engine.FakeEngine.Reply
import io.github.kabrapratik28.thumbfree.engine.TranscriptionQueue
import io.github.kabrapratik28.thumbfree.models.DownloadState
import io.github.kabrapratik28.thumbfree.ui.copyText
import io.github.kabrapratik28.thumbfree.ui.finalText
import java.io.File
import java.io.RandomAccessFile
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowLog
import org.robolectric.shadows.ShadowWindowManagerImpl

@RunWith(RobolectricTestRunner::class)
class AndroidPortsTest {
    private val app = RuntimeEnvironment.getApplication()
    private val ports = AndroidPorts(app)
    private val scheduler = TestCoroutineScheduler() // runs the queue's worker only when a test advances it
    private val graph = AppGraphSnapshot() // the tests replace AppGraph's history, queue, controller and audio source

    // No idle unload: advanceUntilIdle would reach it after every take.
    private fun queueOver(engine: FakeEngine, modelPath: (String) -> String? = { "m" }) {
        val worker = CoroutineScope(StandardTestDispatcher(scheduler))
        AppGraph.queue = TranscriptionQueue(
            engine, worker, modelPath, { 4 }, ports, language = ports::language, unloadAfterIdleMs = Long.MAX_VALUE,
        )
    }

    /** Both catalog models, verified: sparse files of the pinned sizes (no real space) whose hash is the pinned one. */
    private fun verifiedModels(): ModelStore {
        val dir = File(app.cacheDir, "models").apply { mkdirs() }
        for (model in Catalog.all) RandomAccessFile(File(dir, model.fileName), "rw").use { it.setLength(model.sizeBytes) }
        return ModelStore(dir) { file -> Catalog.all.first { it.fileName == file.name }.sha256 }
    }

    /** What the controller's press does for the queue: the row, then the load. */
    private fun press(id: String) {
        assertThat(ports.createRow(id)).isTrue()
        AppGraph.queue.ensureLoaded(id)
    }

    /** One speech chunk and the stop, then the queue runs. */
    private fun speakAndStop(id: String) {
        AppGraph.queue.submit(id, wav(id).path, Chunk(0, 16_000, true))
        AppGraph.queue.finish(id, 1)
        scheduler.advanceUntilIdle()
    }

    private fun wav(id: String) = File(app.filesDir, "recordings/$id.wav").apply { parentFile!!.mkdirs() }

    private val textField = FocusTracker.Field("com.example", 1, InputType.TYPE_CLASS_TEXT, isPassword = false, editable = true)

    private fun px(dp: Int) = (dp * app.resources.displayMetrics.density).toInt()

    /** A service connected to the ports, whose window list holds a keyboard with its top edge at [imeTop]. */
    private fun serviceWithKeyboard(imeTop: Int): DictationAccessibilityService {
        val service = Robolectric.setupService(DictationAccessibilityService::class.java)
        val keyboard = AccessibilityWindowInfo.obtain()
        shadowOf(keyboard).setType(AccessibilityWindowInfo.TYPE_INPUT_METHOD)
        shadowOf(keyboard).setBoundsInScreen(Rect(0, imeTop, 320, 470))
        shadowOf(service).setWindows(listOf(keyboard))
        ports.onServiceConnected(service)
        return service
    }

    private fun waitUntil(condition: () -> Boolean) {
        val end = System.nanoTime() + 5_000_000_000
        while (!condition()) {
            check(System.nanoTime() < end) { "timed out" }
            Thread.sleep(10)
        }
    }

    @Before
    fun setUp() {
        AppGraph.history = HistoryDb(app, null)
        AppGraph.settings = Settings(app.getSharedPreferences("settings", Context.MODE_PRIVATE))
        AppGraph.settings.retention = Retention(maxDays = null, maxTakes = 200) // rows here start at 0: no day limit
        AppGraph.leases = ModelLeases() // each test's takes hold their own leases
        ports.downloadStates = { MutableStateFlow(Catalog.all.associateWith { DownloadState.Ready }) } // every model usable: a tap starts a take
        // The bubble's fades run on the frame clock, which a paused looper's idle() never moves on, so they would never
        // end here: animations off, as the fades have their own test (BubbleViewTest).
        android.provider.Settings.Global.putFloat(app.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
    }

    @After
    fun tearDown() {
        AppGraph.history.close()
        graph.restore()
    }

    // A take keeps the model chosen at its press, even when the switch comes before the queue reaches the take (its
    // load can wait behind a history Transcribe). The next take gets the new model through the queue's unload and load,
    // and each row records the model that transcribed it.
    @Test
    fun switchDuringATakeAppliesAtTheNextTake() {
        val store = verifiedModels()
        AppGraph.modelStore = store
        AppGraph.controller = DictationController(FakePorts(), { 0L }) // the queue's reports for s1 and s2 go nowhere
        val engine = FakeEngine(Reply("one"), Reply("two"))
        queueOver(engine, ports::modelPath)

        press("s1")
        AppGraph.settings.selectedModelId = Catalog.CANARY_180M_FLASH_Q8.id // s1 records; its load has not run yet
        speakAndStop("s1")
        press("s2")
        speakAndStop("s2")

        val parakeet = store.file(Catalog.PARAKEET_UNIFIED_Q8).path
        val canary = store.file(Catalog.CANARY_180M_FLASH_Q8).path
        assertThat(engine.calls).containsExactly(
            "load $parakeet 4", "transcribe 0-16000", "unload", "load $canary 4", "transcribe 0-16000",
        ).inOrder()
        assertThat(AppGraph.history.get("s1")!!.modelId).isEqualTo(Catalog.PARAKEET_UNIFIED_Q8.id)
        assertThat(AppGraph.history.get("s2")!!.modelId).isEqualTo(Catalog.CANARY_180M_FLASH_Q8.id)
    }

    // A take holds a lease on the model it started with, from its row until its queue result or its end, so a delete
    // cannot get that model meanwhile. A switch does not move the lease.
    @Test
    fun aTakeHoldsItsModelUntilItEnds() {
        AppGraph.modelStore = verifiedModels()
        AppGraph.controller = DictationController(FakePorts(), { 0L })
        queueOver(FakeEngine(Reply("one")), ports::modelPath)
        val parakeet = Catalog.PARAKEET_UNIFIED_Q8
        val canary = Catalog.CANARY_180M_FLASH_Q8

        press("s1")
        AppGraph.settings.selectedModelId = canary.id

        assertThat(AppGraph.leases.tryDelete(parakeet)).isEqualTo(0L)
        AppGraph.leases.tryDelete(canary).also { assertThat(it).isNotEqualTo(0L) }.let { AppGraph.leases.deleted(canary, it) }
        ports.saveOutcome("s1", Outcome.INSERTED, null)
        runBlocking { ports.db.barrier() }
        AppGraph.leases.tryDelete(parakeet).also { assertThat(it).isNotEqualTo(0L) }.let { AppGraph.leases.deleted(parakeet, it) }
        assertThat(ports.heldTakes()).doesNotContain("s1")
    }

    // A take that starts while a delete has its model gets no lease, and so no model path, though the file is still
    // there: the queue's existing NO_MODEL, with no engine load.
    @Test
    fun aTakeStartingDuringADeleteGetsNoModel() {
        AppGraph.modelStore = verifiedModels()
        AppGraph.controller = DictationController(FakePorts(), { 0L })
        val engine = FakeEngine(Reply("one"))
        queueOver(engine, ports::modelPath)
        val model = Catalog.PARAKEET_UNIFIED_Q8
        val reservation = AppGraph.leases.tryDelete(model)

        press("s1")
        speakAndStop("s1")

        assertThat(ports.modelPath("s1")).isNull()
        assertThat(engine.calls).isEmpty()
        AppGraph.leases.deleted(model, reservation)
    }

    // A history Transcribe asked for while a delete has its model fails the same way (NO_MODEL), before its WAV is
    // planned or any queue work starts.
    @Test
    fun aHistoryTranscribeDuringADeleteFailsAsNoModel() {
        AppGraph.modelStore = verifiedModels()
        val engine = FakeEngine(Reply("one"))
        queueOver(engine, ports::modelPath)
        val controller = DictationController(ports, { 0L })
        AppGraph.controller = controller
        AppGraph.history.create("h1", 0, "recordings/h1.wav", Catalog.PARAKEET_UNIFIED_Q8.id, null)
        AppGraph.history.finish("h1", Status.FAILED, "ENGINE_CRASHED")
        ports.plan = { error("no planning while the model is being deleted") }
        val reservation = AppGraph.leases.tryDelete(Catalog.PARAKEET_UNIFIED_Q8)

        controller.startRetranscribe("h1", insertAfter = false)
        waitUntil {
            shadowOf(Looper.getMainLooper()).idle()
            scheduler.advanceUntilIdle()
            AppGraph.history.get("h1")!!.error == Code.NO_MODEL.name
        }

        assertThat(engine.calls).isEmpty()
        assertThat(ports.heldTakes()).doesNotContain("h1")
        AppGraph.leases.deleted(Catalog.PARAKEET_UNIFIED_Q8, reservation)
    }

    // A history Transcribe whose text could not be saved (its chip keeps the text) still gives its model back at the
    // queue's result, so a delete of that model is not refused for the rest of the process.
    @Test
    fun aHistoryTranscribeWhoseSaveFailedReleasesItsModel() {
        var failures = 0
        AppGraph.history.close()
        AppGraph.history = object : HistoryDb(app, null) {
            override fun db(): SQLiteDatabase = if (failures-- > 0) throw SQLiteFullException("full") else super.db()
        }
        AppGraph.modelStore = verifiedModels()
        val engine = FakeEngine(Reply("Hello there."))
        queueOver(engine, ports::modelPath)
        val controller = DictationController(ports, { 0L })
        AppGraph.controller = controller
        ports.db.writeAndWait(1_000) {
            AppGraph.history.create("h1", 0, "recordings/h1.wav", Catalog.PARAKEET_UNIFIED_Q8.id, null)
            AppGraph.history.finish("h1", Status.FAILED, "ENGINE_CRASHED")
        }
        WavWriter.create(wav("h1")).use {
            it.append(bursts(1_000))
            it.finish()
        }
        ports.plan = { listOf(Chunk(0, 16_000, true)) }

        controller.startRetranscribe("h1", insertAfter = false)
        failures = 1 // the stage, the next history write
        var reservation = 0L
        waitUntil {
            shadowOf(Looper.getMainLooper()).idle()
            scheduler.advanceUntilIdle()
            reservation = AppGraph.leases.tryDelete(Catalog.PARAKEET_UNIFIED_Q8)
            reservation != 0L
        }

        AppGraph.leases.deleted(Catalog.PARAKEET_UNIFIED_Q8, reservation)
        assertThat(engine.calls).contains("transcribe 0-16000")
        assertThat(AppGraph.history.get("h1")!!.status).isEqualTo(Status.FAILED) // the save failed: the row is as it was
    }

    // A switch while no take runs does nothing until the next press, which frees the old model before it loads the new
    // one, so :engine never holds both.
    @Test
    fun switchWhileIdleLoadsTheNewModelAtTheNextPress() {
        val store = verifiedModels()
        AppGraph.modelStore = store
        AppGraph.controller = DictationController(FakePorts(), { 0L })
        val engine = FakeEngine(Reply("one"))
        queueOver(engine, ports::modelPath)
        press("s1")
        speakAndStop("s1")

        AppGraph.settings.selectedModelId = Catalog.CANARY_180M_FLASH_Q8.id
        scheduler.advanceUntilIdle()
        assertThat(engine.calls).hasSize(2) // s1's load and chunk: the switch itself sends nothing
        press("s2")
        scheduler.advanceUntilIdle()

        assertThat(engine.calls.drop(2))
            .containsExactly("unload", "load ${store.file(Catalog.CANARY_180M_FLASH_Q8).path} 4").inOrder()
    }

    // A switch between the chunks of a take leaves the take on its model; the next take gets the new one.
    @Test
    fun selectionChangeDuringAMultiChunkTakeKeepsItsModel() {
        val store = verifiedModels()
        AppGraph.modelStore = store
        AppGraph.controller = DictationController(FakePorts(), { 0L })
        val engine = FakeEngine(Reply("one"), Reply("two"), Reply("three"))
        queueOver(engine, ports::modelPath)

        press("s1")
        AppGraph.queue.submit("s1", wav("s1").path, Chunk(0, 16_000, true))
        scheduler.advanceUntilIdle() // chunk 0 done on Parakeet
        AppGraph.settings.selectedModelId = Catalog.CANARY_180M_FLASH_Q8.id
        AppGraph.queue.submit("s1", wav("s1").path, Chunk(16_000, 32_000, true))
        AppGraph.queue.finish("s1", 2)
        scheduler.advanceUntilIdle()
        press("s2")
        speakAndStop("s2")

        val parakeet = store.file(Catalog.PARAKEET_UNIFIED_Q8).path
        val canary = store.file(Catalog.CANARY_180M_FLASH_Q8).path
        assertThat(engine.calls).containsExactly(
            "load $parakeet 4", "transcribe 0-16000", "transcribe 16000-32000", "unload", "load $canary 4",
            "transcribe 0-16000",
        ).inOrder()
    }

    // Canary gets "en" as Parakeet does. For Canary that is source en and target en with punctuation on
    // (canary/model.cpp run()), so it transcribes English and never translates.
    @Test
    fun everyModelTranscribesEnglish() {
        AppGraph.modelStore = verifiedModels()
        AppGraph.controller = DictationController(FakePorts(), { 0L })
        val engine = FakeEngine(Reply("one"), Reply("two"))
        queueOver(engine, ports::modelPath)

        press("s1")
        speakAndStop("s1")
        AppGraph.settings.selectedModelId = Catalog.CANARY_180M_FLASH_Q8.id
        press("s2")
        speakAndStop("s2")

        assertThat(engine.calls.filter { it.startsWith("load") }).hasSize(2) // one take on each model
        assertThat(engine.languages).containsExactly("en", "en").inOrder()
    }

    // A take on the multilingual model tells the engine no language, so the model hears it; the takes before and after
    // it on Parakeet Unified still say "en".
    @Test
    fun theMultilingualModelGetsNoLanguageHint() {
        AppGraph.modelStore = verifiedModels()
        AppGraph.controller = DictationController(FakePorts(), { 0L })
        val engine = FakeEngine(Reply("one"), Reply("zwei"), Reply("three"))
        queueOver(engine, ports::modelPath)

        press("s1")
        speakAndStop("s1")
        AppGraph.settings.selectedModelId = Catalog.PARAKEET_TDT_V3_Q8.id
        press("s2")
        speakAndStop("s2")
        AppGraph.settings.selectedModelId = Catalog.PARAKEET_UNIFIED_Q8.id
        press("s3")
        speakAndStop("s3")

        assertThat(engine.languages).containsExactly("en", null, "en").inOrder()
        assertThat(engine.calls.filter { it.startsWith("load") }).hasSize(3)
    }

    // A Retry or history Transcribe runs on the model chosen when it was asked for, even when the owner switches before
    // the queue reaches it, and the row then names the model that made its new text.
    @Test
    fun retranscriptionKeepsTheModelChosenWhenAskedAndTheRowNamesIt() {
        val store = verifiedModels()
        AppGraph.modelStore = store
        val engine = FakeEngine(Reply("Hello there."))
        queueOver(engine, ports::modelPath)
        val controller = DictationController(ports, { 0L })
        AppGraph.controller = controller
        AppGraph.history.create("s1", 0, "recordings/s1.wav", Catalog.PARAKEET_UNIFIED_Q8.id, null) // an old Parakeet take
        AppGraph.history.finish("s1", Status.FAILED, "ENGINE_CRASHED")
        WavWriter.create(wav("s1")).use {
            it.append(bursts(1_000))
            it.finish()
        }
        ports.plan = { listOf(Chunk(0, 16_000, true)) }
        AppGraph.settings.selectedModelId = Catalog.CANARY_180M_FLASH_Q8.id

        controller.startRetranscribe("s1", insertAfter = false)
        AppGraph.settings.selectedModelId = Catalog.PARAKEET_UNIFIED_Q8.id // before the queue reaches the request
        waitUntil {
            shadowOf(Looper.getMainLooper()).idle()
            scheduler.advanceUntilIdle()
            AppGraph.history.get("s1")!!.status == Status.NOT_INSERTED
        }

        assertThat(engine.calls)
            .containsExactly("load ${store.file(Catalog.CANARY_180M_FLASH_Q8).path} 4", "transcribe 0-16000").inOrder()
        assertThat(AppGraph.history.get("s1")!!.modelId).isEqualTo(Catalog.CANARY_180M_FLASH_Q8.id)
        assertThat(ports.heldTakes()).doesNotContain("s1")
    }

    // The custom words follow the model that makes the text, here a history Transcribe's (the model chosen when it was
    // asked for), not the model the take first used: exact matches only on the multilingual model, near misses too on
    // Parakeet Unified.
    @Test
    fun transcribeAgainMatchesCustomWordsAsItsModelDoes() {
        AppGraph.modelStore = verifiedModels()
        val engine = FakeEngine(Reply("grazie, push kubernetis to github"), Reply("grazie, push kubernetis to github"))
        queueOver(engine, ports::modelPath)
        val controller = DictationController(ports, { 0L }, customWords = { listOf("Grazia", "Kubernetes", "GitHub") })
        AppGraph.controller = controller
        ports.plan = { listOf(Chunk(0, 16_000, true)) }
        for ((id, model) in listOf("s1" to Catalog.PARAKEET_TDT_V3_Q8, "s2" to Catalog.PARAKEET_UNIFIED_Q8)) {
            // A take the other model made, failed; the owner picks this model and taps Transcribe.
            val first = if (model == Catalog.PARAKEET_TDT_V3_Q8) Catalog.PARAKEET_UNIFIED_Q8 else Catalog.PARAKEET_TDT_V3_Q8
            AppGraph.history.create(id, 0, "recordings/$id.wav", first.id, null)
            AppGraph.history.finish(id, Status.FAILED, "ENGINE_CRASHED")
            WavWriter.create(wav(id)).use {
                it.append(bursts(1_000))
                it.finish()
            }
            AppGraph.settings.selectedModelId = model.id
            controller.startRetranscribe(id, insertAfter = false)
            waitUntil {
                shadowOf(Looper.getMainLooper()).idle()
                scheduler.advanceUntilIdle()
                AppGraph.history.get(id)!!.status == Status.NOT_INSERTED
            }
        }

        assertThat(AppGraph.history.get("s1")!!.text).isEqualTo("grazie, push kubernetis to GitHub")
        assertThat(AppGraph.history.get("s2")!!.text).isEqualTo("Grazia, push Kubernetes to GitHub")
    }

    // Deleting a row while its history Transcribe runs stops that work and leaves nothing of it behind.
    @Test
    fun deleteDuringHistoryTranscribeStopsItAndForgetsTheSession() {
        val engine = FakeEngine(Reply("one", delayMs = 1_000), Reply("two"))
        queueOver(engine)
        val controller = DictationController(ports, { 0L })
        AppGraph.controller = controller
        AppGraph.history.create("h1", 0, "recordings/h1.wav", "m", null)
        AppGraph.history.finish("h1", Status.INTERRUPTED)
        WavWriter.create(wav("h1")).use {
            it.append(bursts(1_000))
            it.finish()
        }
        ports.plan = { listOf(Chunk(0, 16_000, true), Chunk(16_000, 32_000, true)) }

        controller.startRetranscribe("h1", insertAfter = false)
        waitUntil {
            shadowOf(Looper.getMainLooper()).idle() // the planned request is queued from main
            scheduler.runCurrent()
            engine.calls.contains("transcribe 0-16000") // chunk 0 runs until 1,000 ms
        }
        AppGraph.history.delete("h1", app.filesDir) // what MainActivity does, and after it:
        controller.forget("h1")
        scheduler.advanceUntilIdle()
        shadowOf(Looper.getMainLooper()).idle()

        assertThat(engine.calls).containsExactly("load m 4", "transcribe 0-16000", "abort 1").inOrder()
        assertThat(ports.heldTakes()).doesNotContain("h1")
        assertThat(AppGraph.history.get("h1")).isNull()
    }

    // A FAILED or INTERRUPTED row stays terminal while its history Transcribe runs, so chunk text saved on it would
    // show and copy before the Transcribe ends. That text waits until the end.
    @Test
    fun historyTranscribeShowsNoTextBeforeItEnds() {
        for (status in listOf(Status.FAILED, Status.INTERRUPTED)) {
            val id = "h-$status"
            val engine = FakeEngine(Reply("one"), Reply("two", delayMs = 60_000))
            queueOver(engine)
            val controller = DictationController(ports, { 0L })
            AppGraph.controller = controller
            AppGraph.history.create(id, 0, "recordings/$id.wav", "m", null)
            AppGraph.history.finish(id, status)
            WavWriter.create(wav(id)).use {
                it.append(bursts(2_000))
                it.finish()
            }
            ports.plan = { listOf(Chunk(0, 16_000, true), Chunk(16_000, 32_000, true)) }

            controller.startRetranscribe(id, insertAfter = false)
            waitUntil {
                shadowOf(Looper.getMainLooper()).idle() // the planned request, then chunk 0's result, go through main
                scheduler.runCurrent()
                engine.calls.contains("transcribe 16000-32000") // chunk 0 is done; chunk 1 runs until 60,000 ms
            }
            shadowOf(Looper.getMainLooper()).idle()
            ports.db.writeAndWait(5_000) {} // every history write so far has landed

            val running = AppGraph.history.get(id)!!
            assertThat(running.partialText).isNull()
            assertThat(running.finalText).isNull()
            assertThat(running.copyText).isNull()

            scheduler.advanceUntilIdle()
            waitUntil {
                shadowOf(Looper.getMainLooper()).idle()
                AppGraph.history.get(id)!!.status == Status.NOT_INSERTED
            }
            assertThat(AppGraph.history.get(id)!!.copyText).isNotNull() // once it ends, its text shows
        }
    }

    // A history Transcribe goes through Silero's speech check as a take does. Steady noise over -55 dBFS, which the
    // gate hears no speech in: its chunk reaches the engine with the check, the check refuses it, so whatever the model
    // would have typed, the row ends NO_SPEECH with no text.
    @Test
    fun historyTranscribeGoesThroughTheSpeechCheck() {
        val engine = FakeEngine(Reply("Yeah.", vadRejected = true))
        queueOver(engine)
        val controller = DictationController(ports, { 0L })
        AppGraph.controller = controller
        AppGraph.history.create("h", 0, "recordings/h.wav", "m", null)
        AppGraph.history.finish("h", Status.FAILED, Code.ENGINE_CRASHED.name)
        val random = java.util.Random(7)
        WavWriter.create(wav("h")).use {
            it.append(ShortArray(32_000) { (random.nextGaussian() * 100).toInt().toShort() }) // about -50 dBFS
            it.finish()
        }

        controller.startRetranscribe("h", insertAfter = false)
        waitUntil {
            shadowOf(Looper.getMainLooper()).idle()
            scheduler.advanceUntilIdle()
            AppGraph.history.get("h")!!.status == Status.NO_SPEECH
        }

        assertThat(engine.speechChecks).containsExactly(true)
        val row = AppGraph.history.get("h")!!
        assertThat(listOf(row.partialText, row.rawText, row.text, row.copyText)).containsExactly(null, null, null, null)
    }

    // A delete while the history Transcribe still plans the WAV (before any queue session exists) starts nothing when
    // the planning ends.
    @Test
    fun deleteWhileTheWavIsPlannedStartsNothing() {
        val engine = FakeEngine(Reply("one"))
        queueOver(engine)
        val controller = DictationController(ports, { 0L })
        AppGraph.controller = controller
        AppGraph.history.create("h2", 0, "recordings/h2.wav", "m", null)
        AppGraph.history.finish("h2", Status.INTERRUPTED)
        WavWriter.create(wav("h2")).use {
            it.append(bursts(1_000))
            it.finish()
        }
        val planning = CountDownLatch(1)
        val release = CountDownLatch(1)
        ports.plan = {
            planning.countDown()
            release.await(5, TimeUnit.SECONDS)
            listOf(Chunk(0, 16_000, true))
        }

        controller.startRetranscribe("h2", insertAfter = false)
        assertThat(planning.await(5, TimeUnit.SECONDS)).isTrue()
        AppGraph.history.delete("h2", app.filesDir) // what MainActivity does, and after it:
        controller.forget("h2")
        release.countDown()
        repeat(50) { // the planning coroutine ends on its own thread within this
            shadowOf(Looper.getMainLooper()).idle()
            scheduler.advanceUntilIdle()
            Thread.sleep(10)
        }

        assertThat(engine.calls).isEmpty()
        assertThat(ports.heldTakes()).doesNotContain("h2")
    }

    @Test
    fun outcomeMapsToStatus() {
        for (outcome in Outcome.entries) assertThat(AndroidPorts.statusOf(outcome).name).isEqualTo(outcome.name)
    }

    @Test
    fun hapticConstants() {
        assertThat(AndroidPorts.hapticConstant(HapticKind.TICK)).isEqualTo(HapticFeedbackConstants.CLOCK_TICK)
        assertThat(AndroidPorts.hapticConstant(HapticKind.STOP)).isEqualTo(HapticFeedbackConstants.CLOCK_TICK) // the same tick
        assertThat(AndroidPorts.hapticConstant(HapticKind.CONFIRM)).isEqualTo(HapticFeedbackConstants.CONFIRM)
        assertThat(AndroidPorts.hapticConstant(HapticKind.REJECT)).isEqualTo(HapticFeedbackConstants.REJECT)
    }

    // Every stop logs how long its stop tail ran and why it ended; no audio or text.
    @Test
    fun stopLogsHowTheTailEnded() {
        queueOver(FakeEngine(Reply("hi")))
        AppGraph.controller = DictationController(FakePorts(), { 0L }) // the take's reports go nowhere
        // Speech, then the stop and silence, paced like a real mic by what the writer has put in the WAV.
        val source = FakeAudioSource(
            listOf(bursts(1_000)),
            mayDeliver = { wav("s1").length() >= WavWriter.HEADER_BYTES + 2 * it },
            onScriptEnd = { ports.startTail("s1") },
        )
        AppGraph.audioSourceFactory = { source }

        ports.startCapture("s1")

        val tail = { ShadowLog.getLogsForTag("ThumbFree").map { it.msg }.filter { it.startsWith("take_tail") } }
        waitUntil {
            shadowOf(Looper.getMainLooper()).idle()
            tail().isNotEmpty()
        }
        assertThat(tail().single()).matches("take_tail ms=\\d+ end=hangover")
    }

    // A submit after queue.cancel starts a fresh queue session, so a chunk that comes after StopCapture is dropped.
    @Test
    fun chunkAfterStopCaptureIsDropped() {
        val engine = FakeEngine(Reply("late"))
        queueOver(engine)
        // A controller of its own with s1 in Stopping: s1's TailDone then shows that the recorder's last calls arrived.
        val controller = DictationController(FakePorts(), { 0L })
        AppGraph.controller = controller
        controller.onTouch(TouchOutput.Press)
        controller.onForegroundStarted()
        controller.onEvent(Event.FirstBuffer("s1"))
        controller.onTouch(TouchOutput.Release(400))
        val speaking = AtomicBoolean(true)
        val source = FakeAudioSource(listOf(bursts(2_000)), mayDeliver = { it < 32_000 || !speaking.get() })
        AppGraph.audioSourceFactory = { source }

        ports.startCapture("s1")
        waitUntil { source.delivered >= 32_000 }
        ports.stopCapture("s1", keepAudio = true)
        speaking.set(false)
        waitUntil {
            shadowOf(Looper.getMainLooper()).idle()
            controller.state is State.Transcribing
        }
        scheduler.advanceUntilIdle()

        assertThat(engine.calls).isEmpty()
    }

    @Test
    fun deleteSessionEndsTheQueueSession() {
        val engine = FakeEngine(Reply("gone"))
        queueOver(engine)
        AppGraph.history.create("s1", 0, "recordings/s1.wav", "m", null)
        val file = wav("s1").apply { writeBytes(ByteArray(44)) }
        ports.ensureEngineLoaded("s1")
        AppGraph.queue.submit("s1", file.path, Chunk(0, 16_000, true))

        ports.deleteSession("s1")
        runBlocking { ports.db.barrier() }
        scheduler.advanceUntilIdle()

        assertThat(engine.calls).isEmpty()
        assertThat(AppGraph.history.get("s1")).isNull()
        assertThat(file.exists()).isFalse()
    }

    // The failed session still waits for its finish; a Retry that joined it would fail again without transcribing.
    @Test
    fun retranscribeStartsAFreshQueueSession() {
        val engine = FakeEngine(Reply(status = 5), Reply("hello"))
        queueOver(engine)
        AppGraph.controller = DictationController(FakePorts(), { 0L }) // the queue's reports go nowhere
        val file = wav("s9")
        WavWriter.create(file).use {
            it.append(bursts(1_000))
            it.finish()
        }
        AppGraph.queue.submit("s9", file.path, Chunk(0, 16_000, true))
        scheduler.advanceUntilIdle()

        ports.retranscribe("s9")

        waitUntil {
            shadowOf(Looper.getMainLooper()).idle() // the planned request is queued from main
            scheduler.advanceUntilIdle()
            engine.calls.count { it.startsWith("transcribe") } == 2
        }
    }

    // A deliberate manual override, still gated by Silero. Transcribe on a take with no frame above -55 dBFS sends
    // every chunk to the engine instead of refusing it on its level, because the user asked, but only with Silero's
    // speech check. Here Silero refuses it, so whatever the model would type, the row ends NO_SPEECH with no text.
    @Test
    fun historyTranscribeOfAQuietTakeIsAManualOverrideStillGatedBySilero() {
        val engine = FakeEngine(Reply("Yeah.", vadRejected = true))
        queueOver(engine)
        val controller = DictationController(ports, { 0L })
        AppGraph.controller = controller
        AppGraph.history.create("s9", 0, "recordings/s9.wav", "m", null)
        AppGraph.history.finish("s9", Status.INTERRUPTED)
        WavWriter.create(wav("s9")).use {
            it.append(ShortArray(32_000)) // no frame above -55 dBFS
            it.finish()
        }

        controller.startRetranscribe("s9", insertAfter = false)
        waitUntil {
            shadowOf(Looper.getMainLooper()).idle() // the planned request is queued from main
            scheduler.advanceUntilIdle()
            AppGraph.history.get("s9")!!.status == Status.NO_SPEECH
        }

        assertThat(engine.calls).containsExactly("load m 4", "transcribe 0-32000").inOrder() // sent, not refused
        assertThat(engine.speechChecks).containsExactly(true) // but only with the check
        val row = AppGraph.history.get("s9")!!
        assertThat(listOf(row.partialText, row.rawText, row.text, row.copyText)).containsExactly(null, null, null, null)
    }

    // Cancel returns before the recorder has drained and finished the WAV. An Undo right then must plan the finished file,
    // not the prefix on disk: here the read in flight at the cancel still adds a block.
    @Test
    fun retranscribeWaitsForTheCancelledRecorder() {
        val engine = FakeEngine(Reply("hi"))
        queueOver(engine)
        AppGraph.controller = DictationController(FakePorts(), { 0L })
        AppGraph.history.create("s1", 0, "recordings/s1.wav", "m", null)
        val more = AtomicBoolean(false)
        val source = FakeAudioSource(listOf(bursts(3_000)), mayDeliver = { it < 32_000 || more.get() })
        AppGraph.audioSourceFactory = { source }
        val file = wav("s1")

        ports.startCapture("s1")
        waitUntil { file.length() == WavWriter.HEADER_BYTES + 64_000L } // every sample read so far is in the file
        ports.stopCapture("s1", keepAudio = true)
        ports.retranscribe("s1")
        Thread.sleep(200) // time enough for a plan that does not wait
        more.set(true)

        waitUntil {
            shadowOf(Looper.getMainLooper()).idle()
            scheduler.advanceUntilIdle()
            engine.calls.any { it.startsWith("transcribe") }
        }
        assertThat(engine.calls).containsExactly("load m 4", "transcribe 0-32320").inOrder()
    }

    // An Undo that gives up waiting for the recorder plans what is on disk, and says so in the log.
    @Test
    fun undoWaitTimeoutIsLogged() {
        queueOver(FakeEngine(Reply("hi")))
        AppGraph.controller = DictationController(FakePorts(), { 0L })
        val hung = AtomicBoolean(true)
        val source = FakeAudioSource(listOf(bursts(1_000)), mayDeliver = { it < 16_000 || !hung.get() })
        AppGraph.audioSourceFactory = { source }
        ports.startCapture("s1")
        waitUntil { source.delivered >= 16_000 } // the next read hangs, so the recorder cannot finish
        ports.stopCapture("s1", keepAudio = true)

        ports.retranscribe("s1")

        waitUntil { ShadowLog.getLogsForTag("ThumbFree").any { it.msg == "take_undo_wait_timeout" } }
        hung.set(false)
    }

    // An accessibility service's process can live for weeks: a take's texts go once its queue session ends and its
    // outcome is saved.
    @Test
    fun finishedTakesLeaveNoText() {
        val controller = DictationController(FakePorts(), { 0L })
        AppGraph.controller = controller
        for (id in listOf("s1", "s2")) AppGraph.history.create(id, 0, "recordings/$id.wav", "m", null)
        // The queue's results reach the main thread while their take is live, as they do for a real take.
        fun whileLive(results: () -> Unit) {
            controller.onTouch(TouchOutput.Press)
            results()
            shadowOf(Looper.getMainLooper()).idle()
            controller.onEvent(Event.Cancel)
        }

        whileLive {
            ports.onChunkDone("s1", 0, "Hello.", "hello")
            ports.onDone("s1", listOf("Hello."), listOf("hello"), speech = true, language = "en")
        }
        whileLive {
            ports.onChunkDone("s2", 0, "Partial.", "partial") // a truncated chunk comes just before its failure
            ports.onFailed("s2", Code.TRUNCATED)
        }
        ports.insertedText["s1"] = " Hello."
        ports.saveOutcome("s1", Outcome.INSERTED, null)
        runBlocking { ports.db.barrier() }

        assertThat(AppGraph.history.get("s2")!!.partialText).isEqualTo("Partial.")
        assertThat(AppGraph.history.get("s1")!!.insertedText).isEqualTo(" Hello.")
        assertThat(ports.chunkTexts).isEmpty()
        assertThat(ports.insertedText).isEmpty()
    }

    // After Agree and open settings (the wait set), the service connecting brings the app back by itself: its launcher
    // activity, into its own task, handing the intent to the activity already there, with the extra MainActivity reads
    // as the return. Once per wait: a second connect (the switch off and on again) brings nothing more. The wait stays,
    // since Android can refuse the start without a word; MainActivity ends it on its next resume.
    @Test
    fun serviceConnectingAfterAgreeBringsTheAppBack() {
        AppGraph.controller = DictationController(FakePorts(), { 0L }) // for callbacks an earlier test left on main
        val since = System.currentTimeMillis() - 60_000L
        AppGraph.settings.accessibilityWait = since

        ports.onServiceConnected(Robolectric.setupService(DictationAccessibilityService::class.java))

        val started = shadowOf(app).nextStartedActivity
        assertThat(started.component?.className).isEqualTo("io.github.kabrapratik28.thumbfree.ui.MainActivity")
        val flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        assertThat(started.flags and flags).isEqualTo(flags)
        assertThat(started.getBooleanExtra(AndroidPorts.EXTRA_BACK_FROM_ACCESSIBILITY, false)).isTrue()
        assertThat(AppGraph.settings.accessibilityWait).isEqualTo(since)

        ports.onServiceConnected(Robolectric.setupService(DictationAccessibilityService::class.java))
        assertThat(shadowOf(app).nextStartedActivity).isNull()
    }

    // A wait over 10 minutes old is an earlier trip's, and nothing comes to the front. Nor does anything without a wait
    // (a reboot, an update, the switch turned on from elsewhere).
    @Test
    fun aStaleOrMissingWaitBringsNothingBack() {
        AppGraph.controller = DictationController(FakePorts(), { 0L }) // for callbacks an earlier test left on main
        AppGraph.settings.accessibilityWait = System.currentTimeMillis() - 11 * 60_000L

        ports.onServiceConnected(Robolectric.setupService(DictationAccessibilityService::class.java))
        assertThat(shadowOf(app).nextStartedActivity).isNull()

        AppGraph.settings.accessibilityWait = null
        ports.onServiceConnected(Robolectric.setupService(DictationAccessibilityService::class.java))
        assertThat(shadowOf(app).nextStartedActivity).isNull()
    }

    // A warning chip 14 minutes into a hands-free take must not let the screen dim: the flag follows the take, not what
    // the bubble draws.
    @Test
    fun screenStaysOnWhileATakeRecords() {
        val service = Robolectric.setupService(DictationAccessibilityService::class.java)
        ports.onServiceConnected(service)
        val controller = DictationController(FakePorts(), { 0L })
        AppGraph.controller = controller
        fun screenOn() = (service.bubble!!.params.flags and FLAG_KEEP_SCREEN_ON) != 0
        controller.onTouch(TouchOutput.Press)
        controller.onForegroundStarted()
        controller.onEvent(Event.FirstBuffer("s1"))

        ports.render(BubbleUi.Recording(0.5f, locked = true, elapsedMs = 840_000))
        ports.render(BubbleUi.Chip(Code.TAKE_ENDS_SOON, listOf(ChipAction.DISMISS)))
        assertThat(screenOn()).isTrue()

        controller.onChip(ChipAction.CANCEL)
        ports.render(BubbleUi.Chip(Code.CANCELLED, listOf(ChipAction.UNDO)))
        assertThat(screenOn()).isFalse()
    }

    // A chat app's input bar sits on the keyboard, up to 64 dp tall with Send at its right end: the circle clears it.
    @Test
    fun bubbleClearsAChatInputBar() {
        AppGraph.controller = DictationController(FakePorts(), { 0L })
        val service = serviceWithKeyboard(imeTop = 300)

        ports.onFieldChanged(textField)

        val bottom = service.bubble!!.params.y + service.bubble!!.sizePx
        assertThat(300 - bottom).isEqualTo(px(72))
    }

    // A new size in Settings reaches a bubble already showing: its window takes the size and is placed again for it,
    // still clear of the chat input bar, with no restart.
    @Test
    fun bubbleStyleChangeRestylesAndPlacesAgain() {
        AppGraph.controller = DictationController(FakePorts(), { 0L })
        val service = serviceWithKeyboard(imeTop = 600)
        ports.onFieldChanged(textField)
        assertThat(service.bubble!!.sizePx).isEqualTo(px(60)) // Large, recommended

        AppGraph.settings.bubbleStyle = BubbleStyle(BubbleStyle.Size.EXTRA_LARGE, 60)
        ports.bubbleSettingsChanged()

        val bubble = service.bubble!!
        assertThat(bubble.sizePx).isEqualTo(px(72))
        assertThat(600 - (bubble.params.y + px(72))).isEqualTo(px(72)) // the same gap above the keyboard, for the new size
        assertThat(bubble.style).isEqualTo(BubbleStyle(BubbleStyle.Size.EXTRA_LARGE, 60))
    }

    /** The circle's top-left on screen from the window's params: under END gravity x counts from the right edge. */
    /** Every visible text under [root], buttons' too. */
    private fun texts(root: View): List<String> = when {
        root.visibility != View.VISIBLE -> emptyList()
        root is TextView -> listOf(root.text.toString())
        root is ViewGroup -> (0 until root.childCount).flatMap { texts(root.getChildAt(it)) }
        else -> emptyList()
    }

    private fun clickButton(root: View, text: String) {
        fun find(view: View): View? = when {
            view is Button && view.text.toString() == text -> view
            view is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { find(view.getChildAt(it)) }
            else -> null
        }
        checkNotNull(find(root)) { "no $text button" }.performClick()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun circleAt(service: DictationAccessibilityService): Pair<Int, Int> {
        val bubble = service.bubble!!
        val width = service.getSystemService(WindowManager::class.java).currentWindowMetrics.bounds.width()
        val fromRight = (bubble.params.gravity and Gravity.END) == Gravity.END
        return (if (fromRight) width - bubble.params.x - bubble.sizePx else bubble.params.x) to bubble.params.y
    }

    // A yellow bubble always listens, so the floating bubble is grey until a tap can: with the microphone off (its
    // badge, no ring), then while the chosen model downloads (its ring and badge), and yellow once it is usable. It is
    // read again as the download changes and whenever the bubble shows.
    @Test
    fun theFloatingBubbleIsGreyUntilATapCanListen() {
        AppGraph.controller = DictationController(FakePorts(), { 0L })
        val model = AppGraph.settings.model
        val downloads = MutableStateFlow<Map<ModelFile, DownloadState>>(mapOf(model to DownloadState.Downloading(model.sizeBytes * 42 / 100 + 1, model.sizeBytes)))
        ports.downloadStates = { downloads }
        val service = serviceWithKeyboard(imeTop = 300)
        ports.onFieldChanged(textField)
        shadowOf(Looper.getMainLooper()).idle()
        val view = Shadow.extract<ShadowWindowManagerImpl>(service.getSystemService(WindowManager::class.java)).views.single() as BubbleView
        assertThat(view.grey).isEqualTo(Grey.MIC_OFF)

        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        ports.onFieldChanged(textField)
        shadowOf(Looper.getMainLooper()).idle()
        assertThat(view.grey).isEqualTo(Grey(Grey.Badge.DOWNLOAD, 0.42f))

        downloads.value = mapOf(model to DownloadState.Verifying)
        shadowOf(Looper.getMainLooper()).idle()
        assertThat(view.grey).isEqualTo(Grey.PREPARING)
        downloads.value = mapOf(model to DownloadState.Ready)
        shadowOf(Looper.getMainLooper()).idle()
        assertThat(view.grey).isNull()
    }

    // Before the chosen model is usable a tap on the bubble never listens: a panel by it says how far the download is,
    // and nothing starts. The panel follows the download only in 10% steps, at most once a second (TalkBack reads it as
    // it comes), and goes once the model is ready; a drag still only moves the bubble; a second tap puts it away, as does
    // a tap outside it; Open brings the app up on its speech models. A state not known yet (the first seconds after the
    // process starts) reads as the model being prepared: the panel says so, and a take starts only once it is usable.
    @Test
    fun aTapBeforeTheModelIsUsableShowsThePanelAndStartsNothing() {
        val fake = FakePorts()
        val controller = DictationController(fake, { 0L })
        AppGraph.controller = controller
        val model = AppGraph.settings.model
        fun at(percent: Int): Map<ModelFile, DownloadState> = mapOf(model to DownloadState.Downloading(model.sizeBytes * percent / 100 + 1, model.sizeBytes))
        val downloads = MutableStateFlow(at(42))
        ports.downloadStates = { downloads }
        val service = serviceWithKeyboard(imeTop = 300)
        AppGraph.settings.bubbleSpot = Spot(0f, 0.5f) // the left half: Robolectric puts a window by its x, not END gravity
        ports.onFieldChanged(textField)
        shadowOf(Looper.getMainLooper()).idle()
        val (x, y) = circleAt(service)
        val view = Shadow.extract<ShadowWindowManagerImpl>(service.getSystemService(WindowManager::class.java)).views.single()
        fun touch(action: Int, atMs: Long, dx: Int = 0, dy: Int = 0) =
            view.dispatchTouchEvent(MotionEvent.obtain(0, atMs, action, (x + 24 + dx).toFloat(), (y + 24 + dy).toFloat(), 0))
        fun tap(atMs: Long) {
            touch(MotionEvent.ACTION_DOWN, atMs)
            touch(MotionEvent.ACTION_UP, atMs + 80)
            shadowOf(Looper.getMainLooper()).idle()
        }
        fun shown(): List<String> = texts(view)
        fun set(now: Map<ModelFile, DownloadState>) {
            downloads.value = now
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1_100)) // past the panel's once-a-second and its fade
        }
        fun settle() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200)) // the panel's fade out

        tap(0)
        assertThat(shown()).containsAtLeast("Your speech model is still downloading (42%).", "Open ThumbFree")
        assertThat(controller.state).isEqualTo(State.Idle)
        assertThat(fake.calls).isEmpty() // no row, no service, no microphone
        set(at(47))
        assertThat(shown()).contains("Your speech model is still downloading (42%).") // the same 10% step: drawn as it was
        set(at(51))
        assertThat(shown()).contains("Your speech model is still downloading (51%).")
        set(mapOf(model to DownloadState.Ready))
        assertThat(shown().filter { it.startsWith("Your speech model") }).isEmpty()

        set(mapOf(model to DownloadState.Queued(wifiOnly = true)))
        touch(MotionEvent.ACTION_DOWN, 1_000)
        touch(MotionEvent.ACTION_MOVE, 1_050, 100, -100) // past the slop before the hold threshold: a drag
        touch(MotionEvent.ACTION_UP, 1_100, 110, -90)
        shadowOf(Looper.getMainLooper()).idle()
        assertThat(shown()).doesNotContain("Your speech model is waiting for Wi-Fi.")
        assertThat(circleAt(service)).isEqualTo(x + 110 to y - 90)

        tap(2_000)
        assertThat(shown()).contains("Your speech model is waiting for Wi-Fi.")
        tap(3_000)
        settle()
        assertThat(shown()).doesNotContain("Your speech model is waiting for Wi-Fi.")
        tap(4_000)
        assertThat(shown()).contains("Your speech model is waiting for Wi-Fi.")
        view.dispatchTouchEvent(MotionEvent.obtain(0, 4_500, MotionEvent.ACTION_OUTSIDE, 0f, 0f, 0))
        settle()
        assertThat(shown()).doesNotContain("Your speech model is waiting for Wi-Fi.")
        tap(5_000)
        assertThat(shown()).contains("Your speech model is waiting for Wi-Fi.")
        clickButton(view, "Open ThumbFree")
        settle()
        val started = shadowOf(app).nextStartedActivity
        assertThat(started.component?.className).isEqualTo("io.github.kabrapratik28.thumbfree.ui.MainActivity")
        assertThat(started.getBooleanExtra(AndroidPorts.EXTRA_OPEN_SPEECH_MODELS, false)).isTrue()
        assertThat(shown()).doesNotContain("Your speech model is waiting for Wi-Fi.")
        assertThat(controller.state).isEqualTo(State.Idle)

        set(emptyMap())
        tap(6_000)
        assertThat(shown()).contains("Your speech model is almost ready.") // not known yet: being prepared
        assertThat(controller.state).isEqualTo(State.Idle)
        assertThat(fake.calls).isEmpty()
        set(mapOf(model to DownloadState.Ready))
        assertThat(shown().filter { it.startsWith("Your speech model") }).isEmpty() // usable: the panel goes by itself
        tap(7_000)
        assertThat(controller.state).isNotEqualTo(State.Idle) // and a tap starts the take
    }

    // A drag leaves the bubble where it is let go, the middle included, and the next placement (another report, a new
    // field) keeps it there. Settings > Bubble hears of the move for its Reset position.
    @Test
    fun dragLeavesTheBubbleWhereItIsDropped() {
        val controller = DictationController(FakePorts(), { 0L })
        AppGraph.controller = controller
        val service = serviceWithKeyboard(imeTop = 300)
        AppGraph.settings.bubbleSpot = Spot(0f, 0.5f) // the left half: Robolectric puts a window by its x, not END gravity
        ports.onFieldChanged(textField)
        shadowOf(Looper.getMainLooper()).idle() // the window attaches and lays out
        val (x, y) = circleAt(service)
        val moves = AppGraph.bubbleMoves.value
        val view = Shadow.extract<ShadowWindowManagerImpl>(service.getSystemService(WindowManager::class.java)).views.single()
        fun touch(action: Int, atMs: Long, dx: Int, dy: Int) =
            view.dispatchTouchEvent(MotionEvent.obtain(0, atMs, action, (x + 24 + dx).toFloat(), (y + 24 + dy).toFloat(), 0))

        touch(MotionEvent.ACTION_DOWN, 0, 0, 0)
        touch(MotionEvent.ACTION_MOVE, 50, 100, -100) // past the slop before the hold threshold: a drag
        touch(MotionEvent.ACTION_UP, 100, 110, -90) // where the finger lifts, not the last MOVE, which can be resampled
        ports.onFieldChanged(textField)
        shadowOf(Looper.getMainLooper()).idle()

        assertThat(circleAt(service)).isEqualTo(x + 110 to y - 90)
        assertThat(AppGraph.settings.bubbleSpot).isNotNull()
        assertThat(AppGraph.bubbleMoves.value).isEqualTo(moves + 1)
        assertThat(controller.state).isEqualTo(State.Idle) // a drag starts no take
    }

    // A remembered spot places the bubble there, kept above the keyboard; Reset position (no spot) brings back the
    // automatic one at the right edge above the keyboard.
    @Test
    fun spotPlacesTheBubbleAndResetGoesBack() {
        AppGraph.controller = DictationController(FakePorts(), { 0L })
        val service = serviceWithKeyboard(imeTop = 300)
        ports.onFieldChanged(textField)
        val automatic = circleAt(service)

        AppGraph.settings.bubbleSpot = Spot(0.5f, 0.5f)
        ports.bubbleSettingsChanged()
        assertThat(circleAt(service)).isEqualTo(130 to 205) // the middle of 320 x 470, less the 60 px (Large) bubble

        AppGraph.settings.bubbleSpot = Spot(0f, 1f)
        ports.bubbleSettingsChanged()
        assertThat(circleAt(service)).isEqualTo(0 to 300 - 60) // at the bottom: above the keyboard

        AppGraph.settings.bubbleSpot = null
        ports.bubbleSettingsChanged()
        assertThat(circleAt(service)).isEqualTo(automatic)
    }

    // The bubble shows only while a text field has focus, a remembered spot included. It hides when focus leaves and
    // comes back at the spot on the next field.
    @Test
    fun rememberedSpotShowsOnlyWithAField() {
        AppGraph.controller = DictationController(FakePorts(), { 0L })
        val service = serviceWithKeyboard(imeTop = 300)
        val windows = Shadow.extract<ShadowWindowManagerImpl>(service.getSystemService(WindowManager::class.java))
        AppGraph.settings.bubbleSpot = Spot(0.5f, 0.5f)

        ports.onFieldChanged(null)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        assertThat(windows.views).isEmpty()

        ports.onFieldChanged(textField)
        assertThat(windows.views).hasSize(1)
        assertThat(circleAt(service)).isEqualTo(130 to 205)

        ports.onFieldChanged(null) // focus leaves: gone after the 400 ms grace
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        assertThat(windows.views).isEmpty()

        ports.onFieldChanged(textField.copy(windowId = 2)) // the next field
        assertThat(windows.views).hasSize(1)
        assertThat(circleAt(service)).isEqualTo(130 to 205)
    }

    // A take can end after its field lost focus. A chip still shows, but the idle bubble must not stay behind: a tap on
    // it would record with no field to type into.
    @Test
    fun idleRenderHidesABubbleWhoseFieldIsGone() {
        val controller = DictationController(FakePorts(), { 0L })
        AppGraph.controller = controller
        val service = serviceWithKeyboard(imeTop = 300)
        val windows = Shadow.extract<ShadowWindowManagerImpl>(service.getSystemService(WindowManager::class.java))
        ports.onFieldChanged(textField)
        controller.onTouch(TouchOutput.Press) // s1 starts...
        ports.onFieldChanged(null) // ...and focus leaves: the bubble stays for the take, past the 400 ms grace
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        assertThat(windows.views).hasSize(1)

        controller.onEvent(Event.ArmingTimeout("s1")) // the take ends with a chip, which stays
        ports.render(BubbleUi.Chip(Code.MIC_NOT_READY, listOf(ChipAction.DISMISS)))
        shadowOf(Looper.getMainLooper()).idle()
        assertThat(windows.views).hasSize(1)

        ports.render(BubbleUi.Idle) // the chip goes
        shadowOf(Looper.getMainLooper()).idle()
        assertThat(windows.views).isEmpty()
    }

    // The other half: while an eligible field keeps focus, the Idle render's placement leaves the window alone, with no
    // hide and show (a flicker) and no move.
    @Test
    fun idleRenderKeepsTheBubbleOverAnEligibleField() {
        AppGraph.controller = DictationController(FakePorts(), { 0L })
        val service = serviceWithKeyboard(imeTop = 300)
        ports.onFieldChanged(textField)
        shadowOf(Looper.getMainLooper()).idle() // the window attaches
        val windowManager = service.getSystemService(WindowManager::class.java)
        val view = Shadow.extract<ShadowWindowManagerImpl>(windowManager).views.single()
        fun params() = service.bubble!!.params.let { listOf(it.gravity, it.x, it.y, it.flags) }
        val before = params()
        var detached = 0
        view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit
            override fun onViewDetachedFromWindow(v: View) {
                detached++
            }
        })

        ports.render(BubbleUi.Idle)
        shadowOf(Looper.getMainLooper()).idle()

        assertThat(detached).isEqualTo(0)
        assertThat(view.isAttachedToWindow).isTrue()
        assertThat(params()).isEqualTo(before)
    }

    // The system can drop the bubble's window while the service stays connected. A relayout then finds it gone, and the
    // next focus report adds it again, although its spot has not changed.
    @Test
    fun droppedBubbleComesBackAtTheNextReport() {
        val controller = DictationController(FakePorts(), { 0L })
        AppGraph.controller = controller
        val service = serviceWithKeyboard(imeTop = 300)
        val windowManager = service.getSystemService(WindowManager::class.java)
        val windows = Shadow.extract<ShadowWindowManagerImpl>(windowManager)
        ports.onFieldChanged(textField)
        windowManager.removeViewImmediate(windows.views.single()) // dropped behind the bubble's back
        controller.onTouch(TouchOutput.Press)
        ports.render(BubbleUi.Arming) // keeping the screen on relays out the dropped window
        assertThat(service.bubble!!.shown).isFalse()

        ports.onFieldChanged(textField)

        assertThat(windows.views).hasSize(1)
    }

    // A relayout can find the window gone while a finger is on it. That finger's UP never comes, so the touch ends the way
    // a hide ends it, and the next report adds the window again.
    @Test
    fun windowDroppedMidTouchEndsTheTouch() {
        val controller = DictationController(FakePorts(), { 0L })
        AppGraph.controller = controller
        val service = serviceWithKeyboard(imeTop = 300)
        val windowManager = service.getSystemService(WindowManager::class.java)
        val windows = Shadow.extract<ShadowWindowManagerImpl>(windowManager)
        ports.onFieldChanged(textField)
        val view = windows.views.single()
        view.dispatchTouchEvent(MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 1f, 1f, 0)) // a press: s1 arms
        windowManager.removeViewImmediate(view) // dropped under the finger
        ports.render(BubbleUi.Arming) // keeping the screen on relays out the dropped window

        ports.onFieldChanged(textField)

        assertThat(controller.state).isEqualTo(State.Idle) // the cancelled touch discards the take in Arming
        assertThat(windows.views).hasSize(1)
    }

    // The system can drop the window right under a press, before the Arming render's relayout finds it gone. No focus
    // event need follow: the touch ends at once, so a hold that records by then stops instead of running to the limit.
    @Test
    fun droppedWindowEndsAHoldAtOnce() {
        val controller = DictationController(FakePorts(), { 0L })
        AppGraph.controller = controller
        val service = serviceWithKeyboard(imeTop = 300)
        val windowManager = service.getSystemService(WindowManager::class.java)
        ports.onFieldChanged(textField)
        val view = Shadow.extract<ShadowWindowManagerImpl>(windowManager).views.single()
        view.dispatchTouchEvent(MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 1f, 1f, 0)) // a hold: s1 arms
        windowManager.removeViewImmediate(view)
        ports.render(BubbleUi.Arming) // keeping the screen on relays out the dropped window
        controller.onEvent(Event.FirstBuffer("s1")) // the mic is on: s1 records, held

        shadowOf(Looper.getMainLooper()).idle() // what the window posted, and nothing else

        assertThat(controller.state).isEqualTo(State.Stopping("s1", autoInsert = false))
    }

    // A press 10 ms after a release is debounced and starts nothing. A dropped window found during it ends that touch
    // quietly, and the bubble comes back.
    @Test
    fun droppedWindowEndsADebouncedPressQuietly() {
        val fake = FakePorts()
        val controller = DictationController(fake, { 0L })
        AppGraph.controller = controller
        val service = serviceWithKeyboard(imeTop = 300)
        val windowManager = service.getSystemService(WindowManager::class.java)
        val windows = Shadow.extract<ShadowWindowManagerImpl>(windowManager)
        ports.onFieldChanged(textField)
        val view = windows.views.single()
        fun touch(action: Int, atMs: Long) = view.dispatchTouchEvent(MotionEvent.obtain(0, atMs, action, 1f, 1f, 0))
        touch(MotionEvent.ACTION_DOWN, 0) // a tap: s1 arms, locked on
        ports.render(BubbleUi.Arming)
        touch(MotionEvent.ACTION_UP, 100)
        touch(MotionEvent.ACTION_DOWN, 110) // debounced
        windowManager.removeViewImmediate(view)
        controller.onChip(ChipAction.CANCEL)
        val calls = fake.calls.size
        ports.render(BubbleUi.Chip(Code.CANCELLED, listOf(ChipAction.UNDO))) // the screen may dim: a relayout finds it gone

        shadowOf(Looper.getMainLooper()).idle()

        assertThat(fake.calls.drop(calls)).isEmpty() // the controller heard nothing more
        assertThat(controller.state).isEqualTo(State.Idle)
        assertThat(windows.views).hasSize(1)
    }

    // A chip's dismissal timer belongs to its window. Once the service goes, it must not dismiss a later chip and so
    // drop the text that chip's Copy or Insert here needs.
    @Test
    fun goneServiceChipCannotDismissALaterChip() {
        val fake = FakePorts()
        AppGraph.controller = DictationController(fake, { 0L })
        ports.onServiceConnected(Robolectric.setupService(DictationAccessibilityService::class.java))
        ports.render(BubbleUi.Chip(Code.HELD_BACK, listOf(ChipAction.COPY))) // dismissed after 300 s

        ports.onServiceGone()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(301))

        assertThat(fake.calls).doesNotContain("render(Idle)")
    }

    // Through the ports: Transcribe again from history on a take typed into an app keeps its status and what was typed,
    // shows the new text marked transcribed again, and ends the request, so nothing is held for it.
    @Test
    fun historyTranscribeKeepsATypedTakesStatus() {
        val history = AppGraph.history
        ports.db.writeAndWait(1_000) {
            history.create("s1", 0, "recordings/s1.wav", "m", "com.example")
            history.stage("s1", "old", "Old text.", 1_000)
            history.finish("s1", Status.INSERTED, insertedText = "Old text.")
        }

        assertThat(ports.saveRetranscription("s1", "new", "New text.")).isTrue()

        val row = history.get("s1")!!
        assertThat(listOf(row.status, row.insertedText, row.text, row.retranscribed))
            .containsExactly(Status.INSERTED, "Old text.", "New text.", true).inOrder()
        assertThat(ports.heldTakes()).isEmpty()
    }

    // A stage that timed out still runs in its turn. Landing late, it must not leave its row live (no Delete, no
    // Transcribe) until the next start. One that failed leaves the row as it was, so the take can be transcribed again.
    @Test
    fun lateStageEndsItsRowAndAFailedOneLeavesIt() {
        var failures = 0
        AppGraph.history.close()
        AppGraph.history = object : HistoryDb(app, null) {
            override fun db(): SQLiteDatabase = if (failures-- > 0) throw SQLiteFullException("full") else super.db()
        }
        val history = AppGraph.history
        ports.db.writeAndWait(1_000) {
            for (id in listOf("s1", "s2")) {
                history.create(id, 0, "recordings/$id.wav", "m", null)
                history.finish(id, Status.FAILED, Code.ENGINE_CRASHED.name)
            }
        }
        val release = CountDownLatch(1)
        ports.db.write { release.await() } // the history thread is busy past the 500 ms wait

        assertThat(ports.saveStaged("s1", "hi", "Hi.")).isFalse()
        release.countDown()
        runBlocking { ports.db.barrier() }
        failures = 1 // the next write fails
        assertThat(ports.saveStaged("s2", "hi", "Hi.")).isFalse()
        runBlocking { ports.db.barrier() }

        val late = history.get("s1")!!
        assertThat(listOf(late.status, late.text, late.error)).containsExactly(Status.NOT_INSERTED, "Hi.", "ENGINE_CRASHED")
        val failed = history.get("s2")!!
        assertThat(listOf(failed.status, failed.text, failed.error)).containsExactly(Status.FAILED, null, "ENGINE_CRASHED")
    }

    // A locked take has no touch for the relay to cancel, so the service's end reaches the controller itself, and the
    // take stops instead of recording, unseen, until the silence check or the limit.
    @Test
    fun goneServiceStopsALockedTake() {
        val fake = FakePorts()
        val controller = DictationController(fake, { 0L })
        AppGraph.controller = controller
        ports.onServiceConnected(Robolectric.setupService(DictationAccessibilityService::class.java))
        controller.onTouch(TouchOutput.Press)
        controller.onForegroundStarted()
        controller.onTouch(TouchOutput.Release(120))
        controller.onEvent(Event.FirstBuffer("s1")) // locked

        ports.onServiceGone()
        shadowOf(Looper.getMainLooper()).idle()

        assertThat(controller.state).isEqualTo(State.Stopping("s1", autoInsert = false))
        assertThat(fake.calls).contains("startTail(s1)")
    }

    // The queue answers a silent chunk at once, without the engine. On a device that answer can reach the main thread
    // after the take has ended (the recorder posts its last chunk and onStopped back to back): it must not bring the
    // take's chunk texts back. One main task at a time here, with the queue's worker moving in between.
    @Test
    fun lateChunkResultOfAnEndedTakeLeavesNoText() {
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        queueOver(FakeEngine())
        val controller = DictationController(ports, { 0L })
        AppGraph.controller = controller
        val looper = shadowOf(Looper.getMainLooper())
        try {
            for (samples in listOf(16_000, 8_000)) { // a kept silent take (AbortEngine) and a dropped one (DeleteSession)
                AppGraph.audioSourceFactory = { FakeAudioSource(listOf(ShortArray(samples), CaptureException(Code.MIC_UNAVAILABLE))) }
                controller.onTouch(TouchOutput.Press)
                controller.onForegroundStarted()
                waitUntil {
                    if (!looper.isIdle) looper.runOneTask()
                    scheduler.advanceUntilIdle()
                    controller.state == State.Idle && looper.isIdle
                }
            }
            runBlocking { ports.db.barrier() }

            assertThat(ports.chunkTexts).isEmpty()
        } finally {
            RecordingService.pendingStarts = 0
            RecordingService.stopWhenStarted = false
            ForegroundHooks.takeActive = false
        }
    }

    // A take that ends without a transcript keeps its length in history: after the stop from its sample count, after a
    // Cancel while it recorded from the recorder once that has stopped.
    @Test
    fun failedAndCancelledTakesKeepTheirDuration() {
        queueOver(FakeEngine())
        val controller = DictationController(FakePorts(), { 0L })
        AppGraph.controller = controller
        for (id in listOf("s1", "s2")) AppGraph.history.create(id, 0, "recordings/$id.wav", "m", null)
        controller.onTouch(TouchOutput.Press)
        controller.onForegroundStarted()
        controller.onEvent(Event.FirstBuffer("s1"))

        // s1 records 1 s until the mic fails, stops, and then fails in the engine.
        AppGraph.audioSourceFactory = { FakeAudioSource(listOf(bursts(1_000), CaptureException(Code.MIC_UNAVAILABLE))) }
        ports.startCapture("s1")
        waitUntil {
            shadowOf(Looper.getMainLooper()).idle()
            controller.state is State.Transcribing
        }
        ports.saveOutcome("s1", Outcome.FAILED, Code.NO_MODEL)
        runBlocking { ports.db.barrier() }
        assertThat(AppGraph.history.get("s1")!!.durationMs).isEqualTo(1_000L)

        // s2 is cancelled while it records: its outcome is saved before the recorder knows its length.
        val more = AtomicBoolean(false)
        AppGraph.audioSourceFactory = { FakeAudioSource(listOf(bursts(2_000)), mayDeliver = { it < 16_000 || more.get() }) }
        ports.startCapture("s2")
        waitUntil { wav("s2").length() == WavWriter.HEADER_BYTES + 32_000L }
        ports.stopCapture("s2", keepAudio = true)
        ports.saveOutcome("s2", Outcome.CANCELLED, null)
        more.set(true) // the read in flight adds one block: 16,320 samples

        waitUntil {
            shadowOf(Looper.getMainLooper()).idle()
            runBlocking { ports.db.barrier() }
            AppGraph.history.get("s2")!!.durationMs > 0
        }
        assertThat(AppGraph.history.get("s2")!!.durationMs).isEqualTo(1_020L)
        assertThat(AppGraph.history.get("s2")!!.status).isEqualTo(Status.CANCELLED)
    }

    // An accessibility service's process lives for weeks. Twenty takes through the real controller leave no per-take
    // entry behind but the pin of the last chip's take, which an Undo or Retry on that chip still inserts through.
    @Test
    fun finishedTakesLeaveNoBookkeeping() {
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        queueOver(FakeEngine(*Array(10) { if (it % 2 == 0) Reply("Hello there.") else Reply(status = 5) }))
        val controller = DictationController(ports, { 0L })
        AppGraph.controller = controller
        var last = ""
        try {
            repeat(20) { i ->
                val kind = i % 4 // 0 NOT_INSERTED (no field), 1 NO_SPEECH, 2 FAILED, 3 CANCELLED
                val audio = if (kind == 1) ShortArray(16_000) else bursts(1_000)
                // The mic fails after 1 s, which stops the take: no tail, so no waiting on the clock.
                AppGraph.audioSourceFactory = { FakeAudioSource(listOf(audio, CaptureException(Code.MIC_UNAVAILABLE))) }
                controller.onTouch(TouchOutput.Press)
                last = Session.sessionId(controller.state)!!
                controller.onForegroundStarted()
                waitUntil {
                    shadowOf(Looper.getMainLooper()).idle()
                    if (kind != 3) scheduler.advanceUntilIdle()
                    else if (controller.state is State.Transcribing) controller.onChip(ChipAction.CANCEL)
                    controller.state == State.Idle
                }
            }
            runBlocking { ports.db.barrier() }

            assertThat(ports.heldTakes()).containsExactly(last)
        } finally {
            RecordingService.pendingStarts = 0
            RecordingService.stopWhenStarted = false
            ForegroundHooks.takeActive = false
        }
    }

    // The welcome's try runs a real take through the same machine, microphone and model, and keeps nothing: no History
    // row, no recording once it ends, no text left in the take machine. Its words go to the try, never into a field, and
    // the bubble's drawing and buzzes go to the try's own bubble.
    @Test
    fun aTrialTakeKeepsNothing() {
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        queueOver(FakeEngine(Reply("Yes, see you at seven.")))
        val controller = DictationController(ports, { 0L })
        AppGraph.controller = controller
        val words = mutableListOf<String>()
        val drawn = mutableListOf<BubbleUi>()
        val buzzes = mutableListOf<HapticKind>()
        val host = object : TrialHost {
            override fun render(ui: BubbleUi) { drawn += ui }
            override fun haptic(kind: HapticKind) { buzzes += kind }
            override fun words(text: String) { words += text }
        }
        ports.attachTrial(host)
        try {
            // The mic fails after 1 s, which stops the take: no tail, so no waiting on the clock.
            AppGraph.audioSourceFactory = { FakeAudioSource(listOf(bursts(1_000), CaptureException(Code.MIC_UNAVAILABLE))) }
            ports.trialTouch(TouchOutput.Press)
            val id = Session.sessionId(controller.state)!!
            controller.onForegroundStarted()
            waitUntil {
                shadowOf(Looper.getMainLooper()).idle()
                scheduler.advanceUntilIdle()
                controller.state == State.Idle
            }
            runBlocking { ports.db.barrier() }

            assertThat(words.single()).contains("see you at seven")
            assertThat(drawn.any { it is BubbleUi.Recording }).isTrue()
            assertThat(buzzes).containsAtLeast(HapticKind.TICK, HapticKind.CONFIRM)
            assertThat(AppGraph.history.list(10)).isEmpty()
            assertThat(File(app.filesDir, "trial").listFiles().orEmpty().toList()).isEmpty()
            assertThat(File(app.filesDir, "recordings/$id.wav").exists()).isFalse()
            assertThat(ports.heldTakes()).isEmpty()
            assertThat(controller.staged).isEmpty()
        } finally {
            ports.detachTrial(host)
            RecordingService.pendingStarts = 0
            RecordingService.stopWhenStarted = false
            ForegroundHooks.takeActive = false
        }
    }

    // While a take from the floating bubble runs (a locked take in another app), the try's bubble does nothing to it;
    // leaving the try cancels a take the try started, and its recording goes.
    @Test
    fun theTryNeverStopsAnotherTakeAndLeavingItCancelsItsOwn() {
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        queueOver(FakeEngine())
        val controller = DictationController(ports, { 0L })
        AppGraph.controller = controller
        val host = object : TrialHost {
            override fun render(ui: BubbleUi) = Unit
            override fun haptic(kind: HapticKind) = Unit
            override fun words(text: String) = Unit
        }
        ports.attachTrial(host)
        try {
            controller.onTouch(TouchOutput.Press) // the floating bubble's take
            val floating = Session.sessionId(controller.state)
            ports.trialTouch(TouchOutput.Press)
            ports.trialTouch(TouchOutput.Release(80))
            assertThat(controller.state).isEqualTo(State.Arming(floating!!))
            controller.onEvent(Event.Cancel)

            ports.trialTouch(TouchOutput.Press) // the try's own take
            assertThat(controller.state).isInstanceOf(State.Arming::class.java)
            ports.detachTrial(host)
            assertThat(controller.state).isEqualTo(State.Idle)
            runBlocking { ports.db.barrier() }
            assertThat(ports.heldTakes()).isEmpty()
            assertThat(File(app.filesDir, "trial").listFiles().orEmpty().toList()).isEmpty()
        } finally {
            RecordingService.pendingStarts = 0
            RecordingService.stopWhenStarted = false
            ForegroundHooks.takeActive = false
        }
    }

    // The Undo (CANCELLED) or Retry (FAILED) on a take's chip inserts through that take's pin, even after a history
    // Transcribe of the same take saved another outcome. The next take replaces the chip and drops the pin, unless an
    // Undo or Retry is running: that pin stays until a take starts after its result.
    @Test
    fun endedTakeKeepsItsPinUntilTheNextTake() {
        queueOver(FakeEngine())
        AppGraph.controller = DictationController(FakePorts(), { 0L })
        ports.pinTarget("s1")
        ports.saveOutcome("s1", Outcome.FAILED, Code.ENGINE_CRASHED)
        ports.retranscribe("s1") // a history Transcribe of the same take
        ports.saveOutcome("s1", Outcome.NOT_INSERTED, null)
        assertThat(ports.pins.keys).containsExactly("s1") // the Retry on its chip still inserts through it
        ports.pinTarget("s2")
        assertThat(ports.pins.keys).containsExactly("s2")
        ports.saveOutcome("s2", Outcome.CANCELLED, null)

        ports.retranscribe("s2") // the Undo runs
        ports.pinTarget("s3")
        assertThat(ports.pins.keys).containsExactly("s2", "s3")
        ports.saveOutcome("s2", Outcome.INSERTED, null)
        ports.saveOutcome("s3", Outcome.INSERTED, null)
        ports.pinTarget("s4")
        assertThat(ports.heldTakes()).containsExactly("s4")
    }

    // An exception inside an insert ends the take on the chip instead of killing the process. Before a write nothing can
    // have reached the field; after one the text may have, so the chip offers Copy only: Insert here would write it a
    // second time. The log names the exception's class, never its message.
    @Test
    fun insertThatThrowsEndsTheTakeOnTheChip() {
        val fake = FakePorts()
        AppGraph.controller = DictationController(fake, { 0L })
        val beforeWrite = AndroidPorts(app, object : EditorPort by AccessibilityEditorPort(app) {
            override fun cachedPin(): Pin? = throw IllegalStateException("text from the field")
        })
        val afterWrite = AndroidPorts(app, object : EditorPort by AccessibilityEditorPort(app) {
            override fun paste(): Boolean = throw IllegalStateException("text from the field") // after the clipboard write
        })
        fun chipAfterInsertHere(ports: AndroidPorts, id: String): String {
            ports.insertHere(id, "Hello there.")
            waitUntil {
                shadowOf(Looper.getMainLooper()).idle()
                fake.calls.any { it.startsWith("saveOutcome($id,") }
            }
            return fake.calls.last { it.startsWith("render(") }
        }

        assertThat(chipAfterInsertHere(beforeWrite, "s1"))
            .isEqualTo("render(${BubbleUi.Chip(Code.NO_SESSION, listOf(ChipAction.COPY, ChipAction.INSERT_HERE))})")
        assertThat(chipAfterInsertHere(afterWrite, "s2"))
            .isEqualTo("render(${BubbleUi.Chip(Code.MAY_NOT_HAVE_LANDED, listOf(ChipAction.COPY))})")
        assertThat(fake.calls).containsAtLeast(
            "saveOutcome(s1,NOT_INSERTED,NO_SESSION)", "saveOutcome(s2,UNVERIFIED,MAY_NOT_HAVE_LANDED)",
        ).inOrder()
        val logs = ShadowLog.getLogsForTag("ThumbFree").map { it.msg }
        assertThat(logs.filter { it.startsWith("take_insert_error") })
            .containsExactly("take_insert_error java.lang.IllegalStateException", "take_insert_error java.lang.IllegalStateException")
        assertThat(logs.filter { "text from the field" in it }).isEmpty()
    }

    // A retranscription that throws fails its take on the chip, with Retry, instead of killing the process.
    @Test
    fun retranscribeThatThrowsFailsTheTake() {
        queueOver(FakeEngine())
        val fake = FakePorts()
        val controller = DictationController(fake, { 0L })
        AppGraph.controller = controller
        WavWriter.create(wav("s9")).use {
            it.append(bursts(1_000))
            it.finish()
        }
        ports.plan = { throw IllegalStateException("text from the take") }
        controller.startRetranscribe("s9", insertAfter = true) // the controller now waits for s9's result

        ports.retranscribe("s9")

        waitUntil {
            shadowOf(Looper.getMainLooper()).idle()
            "saveOutcome(s9,FAILED,AUDIO_MISSING)" in fake.calls
        }
        assertThat(ShadowLog.getLogsForTag("ThumbFree").map { it.msg })
            .contains("take_retranscribe_error java.lang.IllegalStateException")
    }

    // A take's end applies the owner's retention rule (here 1 take) in the write that saves its outcome.
    @Test
    fun takeEndAppliesTheRetentionSetting() {
        AppGraph.settings.retention = Retention(maxDays = null, maxTakes = 1)
        for (id in listOf("old", "s1")) {
            wav(id).writeBytes(ByteArray(44))
            AppGraph.history.create(id, 0, "recordings/$id.wav", "m", null)
        }
        AppGraph.history.finish("old", Status.INSERTED)

        ports.saveOutcome("s1", Outcome.INSERTED, null)
        runBlocking { ports.db.barrier() }

        assertThat(AppGraph.history.get("s1")!!.status).isEqualTo(Status.INSERTED)
        assertThat(AppGraph.history.get("old")).isNull()
        assertThat(wav("old").exists()).isFalse()
    }

    @Test
    fun saveOutcomeKeepsTheRowsError() {
        AppGraph.history.create("s1", 0, "recordings/s1.wav", "m", null)
        AppGraph.history.finish("s1", Status.FAILED, "TRUNCATED")

        ports.saveOutcome("s1", Outcome.NOT_INSERTED, null)
        runBlocking { ports.db.barrier() }
        assertThat(AppGraph.history.get("s1")!!.status).isEqualTo(Status.NOT_INSERTED)
        assertThat(AppGraph.history.get("s1")!!.error).isEqualTo("TRUNCATED")

        ports.saveOutcome("s1", Outcome.FAILED, Code.NO_MODEL)
        runBlocking { ports.db.barrier() }
        assertThat(AppGraph.history.get("s1")!!.error).isEqualTo("NO_MODEL")
    }
}
