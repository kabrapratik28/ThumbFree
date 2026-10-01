package io.github.kabrapratik28.thumbfree.models

import androidx.work.Configuration
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkInfo.State.CANCELLED
import androidx.work.WorkInfo.State.ENQUEUED
import androidx.work.WorkInfo.State.FAILED
import androidx.work.WorkInfo.State.RUNNING
import androidx.work.WorkInfo.State.SUCCEEDED
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.work.workDataOf
import com.google.common.truth.Truth.assertThat
import io.github.kabrapratik28.thumbfree.app.AppGraph
import io.github.kabrapratik28.thumbfree.app.AppGraphSnapshot
import io.github.kabrapratik28.thumbfree.core.models.BODY
import io.github.kabrapratik28.thumbfree.core.models.CannedResponse
import io.github.kabrapratik28.thumbfree.core.models.Catalog
import io.github.kabrapratik28.thumbfree.core.models.DownloadProgress
import io.github.kabrapratik28.thumbfree.core.models.DownloadResult
import io.github.kabrapratik28.thumbfree.core.models.Downloader
import io.github.kabrapratik28.thumbfree.core.models.FileCheck
import io.github.kabrapratik28.thumbfree.core.models.ModelFile
import io.github.kabrapratik28.thumbfree.core.models.ModelLeases
import io.github.kabrapratik28.thumbfree.core.models.ModelStatus
import io.github.kabrapratik28.thumbfree.core.models.ModelStore
import io.github.kabrapratik28.thumbfree.core.models.TestHttpServer
import io.github.kabrapratik28.thumbfree.core.models.sha256Hex
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.shadows.ShadowLog

@RunWith(RobolectricTestRunner::class)
class ModelDownloadsTest {
    private val context = RuntimeEnvironment.getApplication()
    private val model = Catalog.CANARY_180M_FLASH_Q8
    private val modelsDir = File(context.filesDir, "models")
    private val graph = AppGraphSnapshot()
    private val server = TestHttpServer()

    /** A 1 MiB model the test server really serves, so a download can end with real matching bytes. */
    private val served = ModelFile(
        id = "t/r/m.gguf",
        fileName = "m.gguf",
        sizeBytes = BODY.size.toLong(),
        sha256 = MessageDigest.getInstance("SHA-256").digest(BODY).joinToString("") { "%02x".format(it) },
        languages = listOf("en"),
        revision = "abc",
    )

    @Before
    fun setUp() {
        AppGraph.modelStore = ModelStore(modelsDir)
        AppGraph.leases = ModelLeases()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().setExecutor(SynchronousExecutor()).build())
        DownloadWorker.downloaderFactory = { Downloader(it, usableSpace = { Long.MAX_VALUE }) }
    }

    @After
    fun tearDown() {
        gateOpen.set(true) // never leave a model's lane waiting on a failed test's gate
        DownloadWorker.downloaderFactory = { Downloader(it) }
        DownloadWorker.catalog = Catalog.all
        server.close()
        graph.restore()
        modelsDir.deleteRecursively()
    }

    private fun work(state: WorkInfo.State, bytes: Long? = null, total: Long? = null, reason: String? = null,
                     offline: Boolean = false, network: NetworkType = NetworkType.UNMETERED) = WorkInfo(
        UUID.randomUUID(), state, emptySet(),
        outputData = if (reason != null) workDataOf(DownloadWorker.KEY_REASON to reason, DownloadWorker.KEY_OFFLINE to offline) else Data.EMPTY,
        progress = if (bytes != null) workDataOf(DownloadWorker.KEY_BYTES to bytes, DownloadWorker.KEY_TOTAL to total) else Data.EMPTY,
        constraints = Constraints.Builder().setRequiredNetworkType(network).build(),
    )

    private fun state(vararg infos: WorkInfo, refused: Boolean = false, deletePending: Boolean = false,
                      status: ModelStatus = ModelStatus.MISSING) =
        downloadState(model, infos.toList(), refused, deletePending) { status }

    private suspend fun awaitState(until: (DownloadState) -> Boolean): DownloadState =
        withTimeout(5_000) { ModelDownloads.state(context, model).first(until) }

    /** While shut, a start's space check waits and holds its model's lane: what a slow check looks like. */
    private val gateOpen = AtomicBoolean(true)
    private val spaceChecks = AtomicInteger()
    private val gatedDownloader: (File) -> Downloader = { dir ->
        Downloader(dir, usableSpace = {
            while (!gateOpen.get()) Thread.sleep(5)
            spaceChecks.incrementAndGet()
            Long.MAX_VALUE
        })
    }

    private fun tombstone(model: ModelFile) = File(modelsDir, "${model.fileName}.tombstone")
    private fun part(model: ModelFile) = File(modelsDir, "${model.fileName}.part")
    private fun works(model: ModelFile) =
        WorkManager.getInstance(context).getWorkInfosForUniqueWork(DownloadWorker.workName(model)).get()

    private fun waitUntil(condition: () -> Boolean) {
        val end = System.currentTimeMillis() + 5_000
        while (!condition()) {
            check(System.currentTimeMillis() < end) { "timed out" }
            Thread.sleep(10)
        }
    }

    @Test
    fun unfinishedWorkIsQueuedDownloadingOrVerifying() {
        assertThat(state(work(ENQUEUED))).isEqualTo(DownloadState.Queued(wifiOnly = true))
        assertThat(state(work(ENQUEUED, network = NetworkType.CONNECTED))).isEqualTo(DownloadState.Queued(wifiOnly = false))
        assertThat(state(work(RUNNING))).isEqualTo(DownloadState.Downloading(0, model.sizeBytes)) // before its first report
        assertThat(state(work(RUNNING, 500, 1_000))).isEqualTo(DownloadState.Downloading(500, 1_000))
        assertThat(state(work(RUNNING, 1_000, 1_000))).isEqualTo(DownloadState.Verifying)
        assertThat(state(work(RUNNING, 1_200, 1_000))).isEqualTo(DownloadState.Verifying)
        assertThat(state(work(RUNNING, 0, 0))).isEqualTo(DownloadState.Downloading(0, 0)) // no total, no check yet
    }

    // Every reason the downloader gives becomes one the screens can say in plain words.
    @Test
    fun failuresCarryAReasonToShow() {
        fun failed(reason: String, offline: Boolean = false) = state(work(FAILED, reason = reason, offline = offline))

        assertThat(failed("NO_SPACE")).isEqualTo(DownloadState.Failed(FailReason.NOT_ENOUGH_SPACE))
        assertThat(failed("HASH_MISMATCH")).isEqualTo(DownloadState.Failed(FailReason.FILE_CHECK_FAILED))
        assertThat(failed("SIZE_MISMATCH")).isEqualTo(DownloadState.Failed(FailReason.FILE_CHECK_FAILED))
        // A network failure is "no internet" only when the worker found no validated network.
        for (reason in listOf("NETWORK", "STALLED", "HTTP")) {
            assertThat(failed(reason)).isEqualTo(DownloadState.Failed(FailReason.INTERRUPTED))
            assertThat(failed(reason, offline = true)).isEqualTo(DownloadState.Failed(FailReason.NO_INTERNET))
        }
        assertThat(failed("UNKNOWN_MODEL")).isEqualTo(DownloadState.Failed(FailReason.INTERRUPTED))
        assertThat(failed("FOREGROUND_REFUSED")).isEqualTo(DownloadState.Failed(FailReason.INTERRUPTED))
        assertThat(state(refused = true)).isEqualTo(DownloadState.Failed(FailReason.NOT_ENOUGH_SPACE))
        assertThat(state(status = ModelStatus.CORRUPT)).isEqualTo(DownloadState.Failed(FailReason.FILE_CHECK_FAILED))
        assertThat(state(status = ModelStatus.WRONG_SIZE)).isEqualTo(DownloadState.Failed(FailReason.FILE_CHECK_FAILED))
    }

    @Test
    fun theFileDecidesOnceNoWorkIsLeft() {
        assertThat(state()).isEqualTo(DownloadState.NotDownloaded)
        assertThat(state(work(CANCELLED))).isEqualTo(DownloadState.NotDownloaded)
        assertThat(state(work(SUCCEEDED), status = ModelStatus.VERIFIED)).isEqualTo(DownloadState.Ready)
        assertThat(state(work(SUCCEEDED))).isEqualTo(DownloadState.NotDownloaded) // deleted since
        assertThat(state(work(FAILED, reason = "NETWORK"), status = ModelStatus.VERIFIED)).isEqualTo(DownloadState.Ready)
        assertThat(state(work(FAILED, reason = "CANCELLED"))).isEqualTo(DownloadState.NotDownloaded) // a stop, not a failure
        // A delete still pending on disk reads as gone, whatever the file and the work say.
        assertThat(state(work(RUNNING, 500, 1_000), deletePending = true, status = ModelStatus.VERIFIED)).isEqualTo(DownloadState.NotDownloaded)
        assertThat(downloadState(model, emptyList(), false, false) { error("unreadable") })
            .isEqualTo(DownloadState.Failed(FailReason.FILE_CHECK_FAILED))
    }

    // The flow the screens collect. The test WorkManager keeps constraints unmet, so a start stays queued; Cancel ends
    // it and removes the partial file.
    @Test
    fun startQueuesAndCancelRemovesThePartialFile() = runBlocking {
        modelsDir.mkdirs()
        val part = File(modelsDir, "${model.fileName}.part").apply { writeText("half") }
        val tag = File(modelsDir, "${model.fileName}.part.etag").apply { writeText("\"v1\"") }

        ModelDownloads.start(context, model, wifiOnly = true)
        assertThat(awaitState { it is DownloadState.Queued }).isEqualTo(DownloadState.Queued(wifiOnly = true))

        ModelDownloads.cancel(context, model)
        assertThat(awaitState { it !is DownloadState.Queued }).isEqualTo(DownloadState.NotDownloaded)
        waitUntil { !part.exists() && !tag.exists() }
    }

    // Not enough space is said before anything is queued, instead of after a wait for Wi-Fi.
    @Test
    fun startWithoutSpaceQueuesNothingAndSaysWhy() = runBlocking {
        DownloadWorker.downloaderFactory = { Downloader(it, usableSpace = { 0 }) }

        ModelDownloads.start(context, model, wifiOnly = true)

        assertThat(awaitState { it is DownloadState.Failed }).isEqualTo(DownloadState.Failed(FailReason.NOT_ENOUGH_SPACE))
        assertThat(WorkManager.getInstance(context).getWorkInfosForUniqueWork(DownloadWorker.workName(model)).get()).isEmpty()

        ModelDownloads.cancel(context, model) // forgets the refusal, for the tests after this one
        waitUntil { !tombstone(model).exists() }
    }

    // Start to Ready through the real worker and the real downloader (served real bytes): the test driver meets the
    // constraints, and the worker runs at once.
    @Test
    fun aFinishedDownloadIsReady() = runBlocking {
        DownloadWorker.catalog = Catalog.all + served
        DownloadWorker.downloaderFactory = { dir ->
            object : Downloader(dir, usableSpace = { Long.MAX_VALUE }) {
                override fun download(model: ModelFile, url: String, isCancelled: () -> Boolean, onProgress: (DownloadProgress) -> Unit) =
                    super.download(model, server.url(), isCancelled, onProgress)
            }
        }
        ModelDownloads.start(context, served, wifiOnly = false)
        withTimeout(5_000) { ModelDownloads.state(context, served).first { it is DownloadState.Queued } }
        val id = WorkManager.getInstance(context).getWorkInfosForUniqueWork(DownloadWorker.workName(served)).get().single().id

        WorkManagerTestInitHelper.getTestDriver(context)!!.setAllConstraintsMet(id)

        assertThat(withTimeout(5_000) { ModelDownloads.state(context, served).first { it == DownloadState.Ready } })
            .isEqualTo(DownloadState.Ready)
    }

    // The model a running take holds is not deleted. Otherwise the file, its verdict and any partial download all go,
    // and the state follows.
    @Test
    fun deleteRefusesAModelInUseAndOtherwiseRemovesEverything() = runBlocking {
        modelsDir.mkdirs()
        RandomAccessFile(File(modelsDir, model.fileName), "rw").use { it.setLength(model.sizeBytes) }
        AppGraph.modelStore = ModelStore(modelsDir) { model.sha256 } // a stand-in hash: the sparse file checks out
        File(modelsDir, "${model.fileName}.part").writeText("left over")
        assertThat(awaitState { true }).isEqualTo(DownloadState.Ready)

        val take = AppGraph.leases.hold(model)
        assertThat(ModelDownloads.delete(context, model)).isFalse()
        assertThat(File(modelsDir, model.fileName).exists()).isTrue()

        AppGraph.leases.release(model, take)
        assertThat(ModelDownloads.delete(context, model)).isTrue()
        assertThat(awaitState { it != DownloadState.Ready }).isEqualTo(DownloadState.NotDownloaded) // as soon as it's recorded
        waitUntil { modelsDir.list().isNullOrEmpty() }
    }

    // From the moment a delete is accepted until its files are gone, a take that starts gets no lease, so it
    // cannot hold the model the delete is removing (it finds the file gone and fails cleanly). Here the delete waits for a
    // stalled download of the same model to let go of its partial file.
    @Test
    fun aTakeStartingDuringADeleteGetsNoLease() {
        DownloadWorker.catalog = Catalog.all + served
        modelsDir.mkdirs()
        File(modelsDir, served.fileName).writeBytes(BODY)
        val stop = AtomicBoolean(false)
        val stalled = stalledDownload(stop)

        assertThat(ModelDownloads.delete(context, served)).isTrue()
        assertThat(AppGraph.leases.hold(served)).isEqualTo(0L)

        stop.set(true)
        stalled.join()
        waitUntil { !File(modelsDir, served.fileName).exists() }
        waitUntil { AppGraph.leases.hold(served).also { if (it != 0L) AppGraph.leases.release(served, it) } != 0L }
    }

    // A cancel called after a start wins, even while that start is still checking space: nothing is queued.
    @Test
    fun startThenCancelQueuesNothing() = runBlocking {
        DownloadWorker.downloaderFactory = gatedDownloader
        gateOpen.set(false)

        ModelDownloads.start(context, model, wifiOnly = true)
        ModelDownloads.cancel(context, model)
        gateOpen.set(true)

        waitUntil { spaceChecks.get() == 1 && !tombstone(model).exists() } // the start's check, then the cancel's cleanup
        Thread.sleep(100)
        assertThat(works(model).filterNot { it.state.isFinished }).isEmpty()
        assertThat(awaitState { true }).isEqualTo(DownloadState.NotDownloaded)
    }

    // The same for a delete, which also takes the partial file.
    @Test
    fun startThenDeleteQueuesNothing() = runBlocking {
        modelsDir.mkdirs()
        part(model).writeText("half")
        DownloadWorker.downloaderFactory = gatedDownloader
        gateOpen.set(false)

        ModelDownloads.start(context, model, wifiOnly = true)
        assertThat(ModelDownloads.delete(context, model)).isTrue()
        gateOpen.set(true)

        waitUntil { spaceChecks.get() == 1 && !tombstone(model).exists() }
        Thread.sleep(100)
        assertThat(works(model).filterNot { it.state.isFinished }).isEmpty()
        assertThat(modelsDir.list()).isEmpty()
    }

    // A start that comes before the cancel's cleanup has begun wins. That cleanup, which would wait for the
    // stopped run to let go of the partial file and then delete it from under the new start, does not run: the new start
    // resumes the file. A slow start holds the lane, so both calls come before any cleanup.
    @Test
    fun cancelThenStartBeforeItsCleanupKeepsThePartial() {
        DownloadWorker.catalog = Catalog.all + served
        DownloadWorker.downloaderFactory = gatedDownloader
        val stop = AtomicBoolean(false)
        val stalled = stalledDownload(stop)
        gateOpen.set(false)
        ModelDownloads.start(context, served, wifiOnly = true)

        ModelDownloads.cancel(context, served)
        ModelDownloads.start(context, served, wifiOnly = true)
        gateOpen.set(true)
        stop.set(true)
        stalled.join()

        waitUntil { works(served).any { !it.state.isFinished } }
        Thread.sleep(300) // time for a late cleanup to strike, as it used to
        assertThat(part(served).length()).isAtLeast(300_000L)
        assertThat(tombstone(served).exists()).isFalse()
    }

    // Once a cancel's cleanup has begun (it waits for the stopped run to let go of the partial file), a start
    // waits for it, so the new download starts after the file is gone and never has it deleted from under it.
    @Test
    fun aStartAfterTheCleanupBeganWaitsForIt() {
        DownloadWorker.catalog = Catalog.all + served
        val stop = AtomicBoolean(false)
        val stalled = stalledDownload(stop)

        ModelDownloads.cancel(context, served)
        Thread.sleep(300) // the idle lane runs the cleanup at once, which then waits for the stalled run
        ModelDownloads.start(context, served, wifiOnly = true)
        stop.set(true)
        stalled.join()

        waitUntil { works(served).any { !it.state.isFinished } }
        assertThat(part(served).exists()).isFalse() // already gone when the new download was queued
    }

    /** A real download of [served] stalled mid-body, holding the downloader's lock on it until [stop]. */
    private fun stalledDownload(stop: AtomicBoolean): Thread {
        server.sticky = CannedResponse(body = BODY, stallAfterBytes = 300_000, stallForMs = 1_000)
        val run = thread { Downloader(modelsDir, usableSpace = { Long.MAX_VALUE }).download(served, server.url(), { stop.get() }) }
        waitUntil { part(served).length() >= 300_000 }
        return run
    }

    // A download that finishes after a delete was asked for does not publish its file, even before the delete's
    // own cleanup has run (here that cleanup waits behind a slow start on the model's lane).
    @Test
    fun staleCompletionAfterADeleteIsNotPublished() = runBlocking {
        DownloadWorker.catalog = Catalog.all + served
        val finish = CountDownLatch(1)
        DownloadWorker.downloaderFactory = { dir ->
            object : Downloader(dir, usableSpace = { while (!gateOpen.get()) Thread.sleep(5); Long.MAX_VALUE }) {
                override fun download(model: ModelFile, url: String, isCancelled: () -> Boolean, onProgress: (DownloadProgress) -> Unit): DownloadResult {
                    finish.await()
                    dir.mkdirs()
                    val file = File(dir, model.fileName).apply { writeBytes(BODY) }
                    return DownloadResult.Done(file, FileCheck(file.length(), file.lastModified(), sha256Hex(file)))
                }
            }
        }
        ModelDownloads.start(context, served, wifiOnly = false)
        waitUntil { works(served).any { it.state == ENQUEUED } }
        WorkManagerTestInitHelper.getTestDriver(context)!!.setAllConstraintsMet(works(served).single().id)
        waitUntil { works(served).single().state == RUNNING }
        gateOpen.set(false)
        ModelDownloads.start(context, served, wifiOnly = false) // holds the lane in its space check
        assertThat(ModelDownloads.delete(context, served)).isTrue() // so this delete's cleanup waits

        finish.countDown()
        waitUntil { works(served).single().state.isFinished }
        assertThat(File(modelsDir, "${served.fileName}.verified").exists()).isFalse()

        gateOpen.set(true)
        waitUntil { !tombstone(served).exists() }
        assertThat(works(served).filterNot { it.state.isFinished }).isEmpty()
        assertThat(modelsDir.list()).isEmpty()
    }

    // Cancel and delete leave their durable record before they return (a delete still pending stays one), so a
    // cleanup that a crash interrupts is finished at the next start.
    @Test
    fun cancelAndDeleteRecordThemselvesBeforeTheyReturn() {
        DownloadWorker.downloaderFactory = gatedDownloader
        gateOpen.set(false)
        ModelDownloads.start(context, model, wifiOnly = true) // holds the lane, so no cleanup can run yet

        ModelDownloads.cancel(context, model)
        assertThat(tombstone(model).readText()).startsWith("cancel ")
        assertThat(ModelDownloads.delete(context, model)).isTrue()
        val delete = tombstone(model).readText()
        assertThat(delete).startsWith("delete ")
        ModelDownloads.cancel(context, model)
        assertThat(tombstone(model).readText()).isEqualTo(delete) // a pending delete is never turned into a cancel

        gateOpen.set(true)
        waitUntil { !tombstone(model).exists() }
    }

    // What a process that died before its cleanup leaves behind, its files and its tombstone, goes at the next
    // start. A delete takes the model and its check too; a cancel only the partial download.
    @Test
    fun reconcileFinishesWhatADeadProcessLeft() {
        val parakeet = Catalog.PARAKEET_UNIFIED_Q8
        modelsDir.mkdirs()
        for (name in listOf("", ".verified", ".part", ".part.etag")) File(modelsDir, model.fileName + name).writeText("x")
        tombstone(model).writeText("delete")
        for (name in listOf("", ".part", ".part.etag")) File(modelsDir, parakeet.fileName + name).writeText("x")
        tombstone(parakeet).writeText("cancel")

        ModelDownloads.reconcile(context)

        waitUntil { !tombstone(model).exists() && !tombstone(parakeet).exists() }
        assertThat(modelsDir.list()).asList().containsExactly(parakeet.fileName)
    }

    // A file that will not go is reported, and its tombstone stays, so the next start tries again.
    @Test
    fun aCleanupThatCannotDeleteKeepsItsTombstone() {
        modelsDir.mkdirs()
        part(model).apply { mkdirs() }.resolve("x").writeText("x") // a folder with a file in it will not delete
        tombstone(model).writeText("cancel")
        ShadowLog.clear()

        ModelDownloads.reconcile(context)

        waitUntil { ShadowLog.getLogsForTag("ThumbFree").any { "download_cleanup_failed" in it.msg } }
        assertThat(tombstone(model).exists()).isTrue()
    }

    /** Two starts for [model] queued behind a slow one, [first] then [second]; returns the one unfinished request left. */
    private fun twoStarts(first: Boolean, second: Boolean, expected: NetworkType): WorkInfo {
        DownloadWorker.downloaderFactory = gatedDownloader
        gateOpen.set(false)
        ModelDownloads.start(context, model, wifiOnly = true) // holds the lane in its space check
        ModelDownloads.start(context, model, wifiOnly = first)
        ModelDownloads.start(context, model, wifiOnly = second)
        gateOpen.set(true)
        waitUntil { works(model).any { !it.state.isFinished && it.constraints.requiredNetworkType == expected } }
        Thread.sleep(100)
        return works(model).filterNot { it.state.isFinished }.single()
    }

    // Of two starts, the newer wins with its own network rule, whichever order the rules come in.
    @Test
    fun aNewerMobileStartWinsOverWifiOnly() {
        assertThat(twoStarts(first = true, second = false, NetworkType.CONNECTED).constraints.requiredNetworkType)
            .isEqualTo(NetworkType.CONNECTED)
    }

    @Test
    fun aNewerWifiOnlyStartWinsOverMobile() {
        assertThat(twoStarts(first = false, second = true, NetworkType.UNMETERED).constraints.requiredNetworkType)
            .isEqualTo(NetworkType.UNMETERED)
    }

    // An older cancel's cleanup never removes the record of a newer delete. Here the cancel's cleanup
    // waits for a stalled download while the delete comes, and the delete's own cleanup then fails (its model is a folder
    // with a file in it), so only the record keeps the delete for the next app start.
    @Test
    fun anOlderCancelKeepsANewerDeleteRecord() {
        DownloadWorker.catalog = Catalog.all + served
        modelsDir.mkdirs()
        File(modelsDir, served.fileName).apply { mkdirs() }.resolve("x").writeText("x")
        val stop = AtomicBoolean(false)
        val stalled = stalledDownload(stop)
        ShadowLog.clear()

        ModelDownloads.cancel(context, served)
        Thread.sleep(300) // the idle lane runs the cancel's cleanup at once, which then waits for the stalled run
        assertThat(ModelDownloads.delete(context, served)).isTrue()
        stop.set(true)
        stalled.join()

        waitUntil { ShadowLog.getLogsForTag("ThumbFree").any { "download_cleanup_failed model=${served.fileName}" in it.msg && "left=m.gguf" in it.msg } }
        assertThat(tombstone(served).readText()).startsWith("delete ")
    }

    // A record that can't be read as a cancel counts as a delete.
    @Test
    fun anUnreadableRecordReconcilesAsADelete() {
        modelsDir.mkdirs()
        for (name in listOf("", ".verified", ".part")) File(modelsDir, model.fileName + name).writeText("x")
        tombstone(model).writeText("canc")

        ModelDownloads.reconcile(context)

        waitUntil { !tombstone(model).exists() }
        assertThat(modelsDir.list()).isEmpty()
    }

    // At app start, a pending delete waits while a take holds the model (the take started first), and
    // the next start finishes it.
    @Test
    fun reconcileWaitsForATakeHoldingTheModel() {
        modelsDir.mkdirs()
        File(modelsDir, model.fileName).writeText("x")
        tombstone(model).writeText("delete 1")
        val take = AppGraph.leases.hold(model)
        ShadowLog.clear()

        ModelDownloads.reconcile(context)
        waitUntil { ShadowLog.getLogsForTag("ThumbFree").any { "download_reconcile_busy model=${model.fileName}" in it.msg } }
        assertThat(File(modelsDir, model.fileName).exists()).isTrue()

        AppGraph.leases.release(model, take)
        ModelDownloads.reconcile(context)
        waitUntil { !tombstone(model).exists() }
        assertThat(modelsDir.list()).isEmpty()
    }

    // A screen that is already watching sees each cleanup's result, here a delete a dead process left.
    @Test
    fun aCleanupResultRefreshesAWatchingScreen() = runBlocking {
        modelsDir.mkdirs()
        RandomAccessFile(File(modelsDir, model.fileName), "rw").use { it.setLength(model.sizeBytes) }
        AppGraph.modelStore = ModelStore(modelsDir) { model.sha256 } // a stand-in hash: the sparse file checks out
        val seen = CopyOnWriteArrayList<DownloadState>()
        val watching = launch(Dispatchers.IO) { ModelDownloads.state(context, model).collect { seen += it } }
        waitUntil { DownloadState.Ready in seen }

        tombstone(model).writeText("delete 1")
        ModelDownloads.reconcile(context)

        waitUntil { seen.last() == DownloadState.NotDownloaded }
        watching.cancel()
        assertThat(modelsDir.list()).isEmpty()
    }
}
