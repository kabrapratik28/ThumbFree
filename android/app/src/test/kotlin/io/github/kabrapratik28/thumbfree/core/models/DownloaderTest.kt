package io.github.kabrapratik28.thumbfree.core.models

import com.google.common.collect.Range
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.io.RandomAccessFile
import java.net.ServerSocket
import java.net.URL
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DownloaderTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var server: TestHttpServer
    private val sleeps = mutableListOf<Long>()
    private val fakeSleep: (Long) -> Unit = { sleeps.add(it) }

    private val bodyHash = MessageDigest.getInstance("SHA-256").digest(BODY).joinToString("") { "%02x".format(it) }
    private val model = ModelFile(
        id = "t/r/m.gguf",
        fileName = "m.gguf",
        sizeBytes = BODY.size.toLong(),
        sha256 = bodyHash,
        languages = listOf("en"),
        revision = "abc",
    )

    @Before
    fun setUp() {
        server = TestHttpServer()
    }

    @After
    fun tearDown() = server.close()

    private fun downloader(usableSpace: () -> Long = { Long.MAX_VALUE }, stallMs: Int = 60_000) =
        Downloader(tmp.root, usableSpace = usableSpace, sleep = fakeSleep, stallMs = stallMs)

    private fun part() = File(tmp.root, "m.gguf.part")
    private fun etag() = File(tmp.root, "m.gguf.part.etag")
    private fun target() = File(tmp.root, "m.gguf")

    private fun seedPart(bytes: ByteArray, etagValue: String = "\"v1\"") {
        part().writeBytes(bytes)
        etag().writeText(etagValue)
    }

    @Test
    fun urlUsesPinnedRevision() {
        val downloader = Downloader(tmp.root)

        assertThat(downloader.url(Catalog.PARAKEET_UNIFIED_Q8)).isEqualTo(
            "https://huggingface.co/handy-computer/parakeet-unified-en-0.6b-gguf/resolve/" +
                "7e948f21b7bdbac698d3318db9d350f1096f3b6c/parakeet-unified-en-0.6b-Q8_0.gguf",
        )
    }

    @Test
    fun freshDownloadVerifiesAndRenames() {
        val result = downloader().download(model, url = server.url())

        assertThat(result).isInstanceOf(DownloadResult.Done::class.java)
        assertThat(target().length()).isEqualTo(BODY.size.toLong())
        assertThat(sha256Hex(target())).isEqualTo(model.sha256)
        assertThat(part().exists()).isFalse()
        assertThat(etag().exists()).isFalse()
    }

    // Done carries the check of exactly the file it renamed into place, taken under this run's lock: the digest of
    // those bytes on disk, and the target's size and time right after the rename.
    @Test
    fun doneCarriesTheCheckOfTheRenamedFile() {
        val result = downloader().download(model, url = server.url()) as DownloadResult.Done

        assertThat(result.check).isEqualTo(FileCheck(target().length(), target().lastModified(), model.sha256))
    }

    @Test
    fun resumeSendsRangeAndIfRange() {
        seedPart(BODY.copyOfRange(0, 300_000))

        val result = downloader().download(model, url = server.url())

        assertThat(server.requests).hasSize(1)
        assertThat(server.requests[0]["range"]).isEqualTo("bytes=300000-")
        assertThat(server.requests[0]["if-range"]).isEqualTo("\"v1\"")
        assertThat(result).isInstanceOf(DownloadResult.Done::class.java)
        assertThat(sha256Hex(target())).isEqualTo(model.sha256)
    }

    @Test
    fun wrongOffset206Restarts() {
        seedPart(BODY.copyOfRange(0, 300_000))
        server.enqueue(
            CannedResponse(
                status = 206,
                headers = mapOf("ETag" to "\"v1\"", "Content-Range" to "bytes 0-1048575/1048576"),
                body = ByteArray(0),
            ),
        )

        val result = downloader().download(model, url = server.url())

        assertThat(server.requests).hasSize(2)
        assertThat(server.requests[1]["range"]).isNull()
        assertThat(result).isInstanceOf(DownloadResult.Done::class.java)
    }

    @Test
    fun freshDownload416IsFatalNotInfiniteRestart() {
        // A no-Range request answered with 416 makes no sense; if it looped forever on
        // RestartFresh (the bug), it would burn through these and fall to the default 200.
        repeat(3) { server.enqueue(CannedResponse(status = 416, headers = emptyMap(), body = ByteArray(0))) }

        val result = downloader().download(model, url = server.url())

        assertThat(server.requests).hasSize(1)
        assertThat(result).isInstanceOf(DownloadResult.Failed::class.java)
        assertThat((result as DownloadResult.Failed).reason).isEqualTo(DownloadResult.Reason.HTTP)
    }

    @Test
    fun freshDownloadWrongOffset206IsFatalNotInfiniteRestart() {
        repeat(3) {
            server.enqueue(
                CannedResponse(
                    status = 206,
                    headers = mapOf("ETag" to "\"v1\"", "Content-Range" to "bytes 500000-1048575/1048576"),
                    body = ByteArray(0),
                ),
            )
        }

        val result = downloader().download(model, url = server.url())

        assertThat(server.requests).hasSize(1)
        assertThat(result).isInstanceOf(DownloadResult.Failed::class.java)
        assertThat((result as DownloadResult.Failed).reason).isEqualTo(DownloadResult.Reason.HTTP)
    }

    @Test
    fun mismatchedContentRangeTotalIsFatal() {
        // Offset matches resumeLength (unlike wrongOffset206Restarts/
        // freshDownloadWrongOffset206IsFatalNotInfiniteRestart, which cover the offset
        // check), so this is the only thing that can trip: the declared total itself.
        seedPart(BODY.copyOfRange(0, 300_000))
        server.enqueue(
            CannedResponse(
                status = 206,
                headers = mapOf("ETag" to "\"v1\"", "Content-Range" to "bytes 300000-1048575/2000000"),
                body = ByteArray(0),
            ),
        )

        val result = downloader().download(model, url = server.url())

        assertThat(server.requests).hasSize(1)
        assertThat(result).isInstanceOf(DownloadResult.Failed::class.java)
        assertThat((result as DownloadResult.Failed).reason).isEqualTo(DownloadResult.Reason.SIZE_MISMATCH)
    }

    @Test
    fun status200RestartsFromZero() {
        seedPart(BODY.copyOfRange(0, 300_000))
        server.enqueue(CannedResponse(status = 200, headers = mapOf("ETag" to "\"v1\""), body = BODY))

        val result = downloader().download(model, url = server.url())

        assertThat(result).isInstanceOf(DownloadResult.Done::class.java)
        assertThat(sha256Hex(target())).isEqualTo(model.sha256)
    }

    @Test
    fun status416DeletesPart() {
        // Smaller than model.sizeBytes: a stale/wrong resume point, not the oversized-leftover
        // case (that one's covered by oversizedLeftoverDoesNotBypassSpaceCheck).
        seedPart(ByteArray(500_000))
        server.enqueue(CannedResponse(status = 416, headers = emptyMap(), body = ByteArray(0)))

        val result = downloader().download(model, url = server.url())

        assertThat(server.requests).hasSize(2)
        assertThat(server.requests[1]["range"]).isNull()
        assertThat(result).isInstanceOf(DownloadResult.Done::class.java)
    }

    // A 200 to a ranged request replaces the part from byte 0, but only once its first bytes are here. A body that
    // fails before them leaves the part and its tag as they were, so the retry resumes instead of starting over.
    @Test
    fun fullResponseKeepsThePartUntilItsFirstBytes() {
        seedPart(BODY.copyOfRange(0, 300_000))
        server.enqueue(CannedResponse(status = 200, headers = mapOf("ETag" to "\"v2\""), body = BODY, closeAfterBytes = 0))

        val result = downloader().download(model, url = server.url())

        assertThat(server.requests).hasSize(2)
        assertThat(server.requests[1]["range"]).isEqualTo("bytes=300000-")
        assertThat(server.requests[1]["if-range"]).isEqualTo("\"v1\"")
        assertThat(result).isInstanceOf(DownloadResult.Done::class.java)
    }

    @Test
    fun changedEtagRestarts() {
        seedPart(BODY.copyOfRange(0, 300_000), etagValue = "\"v0\"")
        server.enqueue(CannedResponse(status = 200, headers = mapOf("ETag" to "\"v1\""), body = BODY))

        val result = downloader().download(model, url = server.url())

        assertThat(server.requests[0]["if-range"]).isEqualTo("\"v0\"")
        assertThat(result).isInstanceOf(DownloadResult.Done::class.java)
    }

    @Test
    fun oversizedBodyFails() {
        server.enqueue(
            CannedResponse(status = 200, headers = mapOf("ETag" to "\"v1\""), body = BODY + byteArrayOf(0)),
        )

        val result = downloader().download(model, url = server.url())

        assertThat(server.requests).hasSize(1)
        assertThat(result).isInstanceOf(DownloadResult.Failed::class.java)
        assertThat((result as DownloadResult.Failed).reason).isEqualTo(DownloadResult.Reason.SIZE_MISMATCH)
        assertThat(target().exists()).isFalse()
        assertThat(part().exists()).isFalse()
    }

    @Test
    fun oversizedPartialBodyFailsMidStreamNotAtDeclaredLength() {
        // A 206 has no Content-Length pre-check the way a 200 does (that's what
        // oversizedBodyFails exercises): the declared Content-Range total matches, so this
        // can only be caught by streamBody's own running total as the extra byte streams in.
        seedPart(BODY.copyOfRange(0, 500_000))
        server.enqueue(
            CannedResponse(
                status = 206,
                headers = mapOf("ETag" to "\"v1\"", "Content-Range" to "bytes 500000-1048575/1048576"),
                body = BODY.copyOfRange(500_000, BODY.size) + byteArrayOf(0),
            ),
        )

        val result = downloader().download(model, url = server.url())

        assertThat(server.requests).hasSize(1)
        assertThat(result).isInstanceOf(DownloadResult.Failed::class.java)
        assertThat((result as DownloadResult.Failed).reason).isEqualTo(DownloadResult.Reason.SIZE_MISMATCH)
        assertThat(target().exists()).isFalse()
        assertThat(part().exists()).isFalse()
    }

    @Test
    fun shortCompleteBodyFails() {
        server.enqueue(CannedResponse(status = 200, headers = mapOf("ETag" to "\"v1\""), body = ByteArray(500_000)))

        val result = downloader().download(model, url = server.url())

        assertThat(server.requests).hasSize(1)
        assertThat(result).isInstanceOf(DownloadResult.Failed::class.java)
        assertThat((result as DownloadResult.Failed).reason).isEqualTo(DownloadResult.Reason.SIZE_MISMATCH)
        assertThat(part().exists()).isFalse()
    }

    @Test
    fun hashMismatchFails() {
        val corrupted = BODY.copyOf().also { it[500_000] = (it[500_000] + 1).toByte() }
        server.enqueue(CannedResponse(status = 200, headers = mapOf("ETag" to "\"v1\""), body = corrupted))

        val result = downloader().download(model, url = server.url())

        assertThat(server.requests).hasSize(1)
        assertThat(result).isInstanceOf(DownloadResult.Failed::class.java)
        assertThat((result as DownloadResult.Failed).reason).isEqualTo(DownloadResult.Reason.HASH_MISMATCH)
        assertThat(target().exists()).isFalse()
        assertThat(part().exists()).isFalse()
    }

    @Test
    fun disconnectRetriesAndResumes() {
        server.enqueue(
            CannedResponse(status = 200, headers = mapOf("ETag" to "\"v1\""), body = BODY, closeAfterBytes = 400_000),
        )

        val result = downloader().download(model, url = server.url())

        assertThat(server.requests).hasSize(2)
        assertThat(server.requests[1]["range"]).isEqualTo("bytes=400000-")
        assertThat(result).isInstanceOf(DownloadResult.Done::class.java)
        assertThat(sha256Hex(target())).isEqualTo(model.sha256)
        assertThat(sleeps).isEqualTo(listOf(2_000L))
    }

    @Test
    fun retriesThreeTimesThenFails() {
        server.sticky = CannedResponse(status = 500, headers = emptyMap(), body = ByteArray(0))

        val result = downloader().download(model, url = server.url())

        assertThat(server.requests).hasSize(4)
        assertThat(result).isInstanceOf(DownloadResult.Failed::class.java)
        assertThat((result as DownloadResult.Failed).reason).isEqualTo(DownloadResult.Reason.HTTP)
        assertThat(sleeps).isEqualTo(listOf(2_000L, 4_000L, 8_000L))
    }

    @Test
    fun noSpaceRefusesBeforeRequest() {
        val result = downloader(usableSpace = { 1_048_576L + 1_073_741_823L }).download(model, url = server.url())

        assertThat(result).isInstanceOf(DownloadResult.Failed::class.java)
        assertThat((result as DownloadResult.Failed).reason).isEqualTo(DownloadResult.Reason.NO_SPACE)
        assertThat(server.requests).isEmpty()
    }

    // The screens check the space before they queue a download, with the same 1 GiB margin, counting a part that is
    // already on disk.
    @Test
    fun hasSpaceForCountsThePartAndTheMargin() {
        seedPart(BODY.copyOfRange(0, 300_000))
        val needed = BODY.size - 300_000L + 1_073_741_824L

        assertThat(downloader(usableSpace = { needed }).hasSpaceFor(model)).isTrue()
        assertThat(downloader(usableSpace = { needed - 1 }).hasSpaceFor(model)).isFalse()
    }

    // The usable space of a folder that does not exist yet reads 0, so a fresh install would be refused before its
    // first download.
    @Test
    fun hasSpaceForOnAFreshInstall() {
        assertThat(Downloader(File(tmp.root, "models")).hasSpaceFor(model)).isTrue()
    }

    @Test
    fun completePartVerifiesLocallyWithoutNetwork() {
        // Cancelled on the last block, or killed after the last byte but before the rename:
        // the file is already whole on disk, no need to re-download it.
        part().writeBytes(BODY)

        val result = downloader().download(model, url = server.url())

        assertThat(server.requests).isEmpty()
        assertThat(result).isInstanceOf(DownloadResult.Done::class.java)
        assertThat(sha256Hex(target())).isEqualTo(model.sha256)
        assertThat(part().exists()).isFalse()
        assertThat((result as DownloadResult.Done).check).isEqualTo(FileCheck(target().length(), target().lastModified(), model.sha256))
    }

    @Test
    fun completePartWithBadHashFailsWithoutNetwork() {
        val corrupted = BODY.copyOf().also { it[0] = (it[0] + 1).toByte() }
        part().writeBytes(corrupted)

        val result = downloader().download(model, url = server.url())

        assertThat(server.requests).isEmpty()
        assertThat(result).isInstanceOf(DownloadResult.Failed::class.java)
        assertThat((result as DownloadResult.Failed).reason).isEqualTo(DownloadResult.Reason.HASH_MISMATCH)
        assertThat(part().exists()).isFalse()
    }

    @Test
    fun unreadablePartRetriesAsNetworkInsteadOfCrashing() {
        // A concurrent delete racing the exists()/length() check would throw the same
        // FileNotFoundException sha256Hex does here; unreadable is a portable stand-in.
        part().writeBytes(BODY)
        part().setReadable(false)

        val result = downloader().download(model, url = server.url())

        assertThat(result).isInstanceOf(DownloadResult.Failed::class.java)
        assertThat((result as DownloadResult.Failed).reason).isEqualTo(DownloadResult.Reason.NETWORK)
        assertThat(sleeps).isEqualTo(listOf(2_000L, 4_000L, 8_000L))
        // Never touches the network: purely a local read retried on its own backoff schedule.
        assertThat(server.requests).isEmpty()
        // Not deleted: a read failure isn't proof the bytes are bad, unlike a real hash mismatch.
        assertThat(part().exists()).isTrue()
    }

    @Test
    fun cancelDuringExhaustedM4ReadRetryReturnsCancelledNotNetwork() {
        part().writeBytes(BODY)
        part().setReadable(false)
        // The exact-size fast path calls isCancelled() only once per attempt (loop top, no
        // network request involved): four attempts is 4 calls, and the 5th is the new check
        // at the exhausted-retries return this covers.
        var calls = 0
        val result = downloader().download(model, url = server.url(), isCancelled = { ++calls > 4 })

        assertThat(result).isInstanceOf(DownloadResult.Failed::class.java)
        assertThat((result as DownloadResult.Failed).reason).isEqualTo(DownloadResult.Reason.CANCELLED)
        assertThat(sleeps).isEqualTo(listOf(2_000L, 4_000L, 8_000L))
    }

    @Test
    fun cancelJustBeforeM4RenameEndsAsCancelledNotDone() {
        part().writeBytes(BODY)
        // First call is the loop-top check (must stay false to reach the fast path); the
        // second is the new check right before finishRename, once the on-disk hash has
        // already verified clean.
        var calls = 0
        val result = downloader().download(model, url = server.url(), isCancelled = { ++calls > 1 })

        assertThat(result).isInstanceOf(DownloadResult.Failed::class.java)
        assertThat((result as DownloadResult.Failed).reason).isEqualTo(DownloadResult.Reason.CANCELLED)
        // Preserved, same as every other cancel path: a later resume can reuse the verified bytes.
        assertThat(part().exists()).isTrue()
    }

    @Test
    fun oversizedLeftoverDoesNotBypassSpaceCheck() {
        // A stale part longer than the catalog size must not skew the free-space arithmetic
        // once it's discarded and a fresh full download is about to start from zero.
        java.io.RandomAccessFile(part(), "rw").use { it.setLength(model.sizeBytes + 1_073_741_824L) }
        etag().writeText("\"v1\"")

        val result = downloader(usableSpace = { 500_000_000L }).download(model, url = server.url())

        assertThat(server.requests).isEmpty()
        assertThat(result).isInstanceOf(DownloadResult.Failed::class.java)
        assertThat((result as DownloadResult.Failed).reason).isEqualTo(DownloadResult.Reason.NO_SPACE)
    }

    @Test
    fun stallTimesOut() {
        server.sticky = CannedResponse(
            status = 200,
            headers = mapOf("ETag" to "\"v1\""),
            body = BODY,
            stallAfterBytes = 10,
            stallForMs = 2_000,
        )

        val start = System.currentTimeMillis()
        val result = downloader(stallMs = 300).download(model, url = server.url())
        val elapsed = System.currentTimeMillis() - start

        assertThat(server.requests).hasSize(4)
        assertThat(result).isInstanceOf(DownloadResult.Failed::class.java)
        assertThat((result as DownloadResult.Failed).reason).isEqualTo(DownloadResult.Reason.STALLED)
        assertThat(elapsed).isLessThan(5_000L)
    }

    @Test
    fun progressNeverExceedsTotal() {
        val reports = mutableListOf<DownloadProgress>()

        val result = downloader().download(model, url = server.url(), onProgress = { reports.add(it) })

        assertThat(result).isInstanceOf(DownloadResult.Done::class.java)
        assertThat(reports).isNotEmpty()
        var last = 0L
        for (report in reports) {
            assertThat(report.bytes).isAtLeast(last)
            assertThat(report.bytes).isAtMost(BODY.size.toLong())
            last = report.bytes
        }
        assertThat(reports.last().bytes).isEqualTo(BODY.size.toLong())
    }

    @Test
    fun progressBatchesToAtLeast256KiBPerReport() {
        server.enqueue(CannedResponse(status = 200, headers = mapOf("ETag" to "\"v1\""), body = BODY, chunkBytes = 8_192))
        val reports = mutableListOf<DownloadProgress>()

        val result = downloader().download(model, url = server.url(), onProgress = { reports.add(it) })

        assertThat(result).isInstanceOf(DownloadResult.Done::class.java)
        // BODY is exactly 4 * 256 KiB: at most one report per block, plus one final report.
        assertThat(reports.size).isAtMost(5)
        assertThat(reports.last().bytes).isEqualTo(BODY.size.toLong())
    }

    @Test
    fun cancelKeepsPart() {
        val result = downloader().download(model, url = server.url(), isCancelled = { part().length() >= 200_000 })

        assertThat(server.requests).hasSize(1)
        assertThat(result).isInstanceOf(DownloadResult.Failed::class.java)
        assertThat((result as DownloadResult.Failed).reason).isEqualTo(DownloadResult.Reason.CANCELLED)
        assertThat(part().length()).isAtLeast(200_000L)
        assertThat(etag().readText()).isEqualTo("\"v1\"")
        // Not just long enough: genuinely the start of the real file, byte for byte.
        assertThat(part().readBytes()).isEqualTo(BODY.copyOfRange(0, part().length().toInt()))

        val resumed = downloader().download(model, url = server.url())

        assertThat(resumed).isInstanceOf(DownloadResult.Done::class.java)
        assertThat(sha256Hex(target())).isEqualTo(model.sha256)
    }

    @Test
    fun cancelBeforeFirstRequestNeverConnects() {
        val result = downloader().download(model, url = server.url(), isCancelled = { true })

        assertThat(result).isInstanceOf(DownloadResult.Failed::class.java)
        assertThat((result as DownloadResult.Failed).reason).isEqualTo(DownloadResult.Reason.CANCELLED)
        assertThat(server.requests).isEmpty()
    }

    @Test
    fun cancelDuringBackoffReturnsCancelledNotHttp() {
        server.sticky = CannedResponse(status = 500, headers = emptyMap(), body = ByteArray(0))

        val result = downloader().download(model, url = server.url(), isCancelled = { sleeps.isNotEmpty() })

        assertThat(result).isInstanceOf(DownloadResult.Failed::class.java)
        assertThat((result as DownloadResult.Failed).reason).isEqualTo(DownloadResult.Reason.CANCELLED)
        assertThat(server.requests).hasSize(1)
        assertThat(sleeps).isEqualTo(listOf(2_000L))
    }

    @Test
    fun cancelDuringFinalExhaustedRetryReturnsCancelledNotThatAttemptsReason() {
        server.sticky = CannedResponse(status = 500, headers = emptyMap(), body = ByteArray(0))
        // Every attempt calls isCancelled() twice (loop top, then again right after
        // responseCode in runRequest) with nothing observable in between the two to key a
        // condition off of. Four attempts is 8 calls; the 9th is the one right after the
        // 4th attempt's own Step.Retry(HTTP), at the exhausted-retries check this covers.
        var calls = 0
        val result = downloader().download(model, url = server.url(), isCancelled = { ++calls > 8 })

        assertThat(result).isInstanceOf(DownloadResult.Failed::class.java)
        assertThat((result as DownloadResult.Failed).reason).isEqualTo(DownloadResult.Reason.CANCELLED)
        assertThat(sleeps).isEqualTo(listOf(2_000L, 4_000L, 8_000L))
    }

    @Test
    fun staleWriteAfterDigestFailsInsteadOfRenamingCorruptFile() {
        var tampered = false
        val result = downloader().download(model, url = server.url(), onProgress = {
            if (!tampered && it.bytes == it.total) {
                tampered = true
                part().appendBytes(byteArrayOf(0)) // a stale, superseded run's late write landing after ours
            }
        })

        assertThat(result).isInstanceOf(DownloadResult.Failed::class.java)
        assertThat((result as DownloadResult.Failed).reason).isEqualTo(DownloadResult.Reason.SIZE_MISMATCH)
        // The detail must read the on-disk length before deleting the file, not after: deleted
        // first would always report 0 regardless of what the size actually was at rename time.
        assertThat(result.detail).contains("${BODY.size + 1} bytes")
        assertThat(part().exists()).isFalse()
        assertThat(target().exists()).isFalse()
    }

    @Test
    fun truncationDuringStreamFailsInsteadOfRenamingZeroedFile() {
        var tampered = false
        val result = downloader().download(model, url = server.url(), onProgress = {
            if (!tampered) {
                tampered = true
                // A stale, superseded run's late 200 opens FileOutputStream(part, false) and
                // truncates this file out from under the live run, which keeps writing at its
                // own fd position: the file ends up the right length with a zero-filled hole
                // where the truncated bytes used to be, so a length-only check can't catch it.
                RandomAccessFile(part(), "rw").use { it.setLength(0) }
            }
        })

        assertThat(result).isInstanceOf(DownloadResult.Failed::class.java)
        assertThat((result as DownloadResult.Failed).reason).isEqualTo(DownloadResult.Reason.HASH_MISMATCH)
        assertThat(part().exists()).isFalse()
        assertThat(target().exists()).isFalse()
    }

    @Test
    fun cancelAfterStreamingCompletesEndsAsCancelledNotDone() {
        server.enqueue(CannedResponse(status = 200, headers = mapOf("ETag" to "\"v1\""), body = BODY))
        // Arms on the last progress report (fires exactly once, when the byte count reaches
        // the total); the very next isCancelled() call is streamBody's own last internal
        // check, which must still see false so streaming finishes as Done, not Cancelled.
        // Only the call after that, the new check ahead of finishRename, should see true.
        var pending = 0
        val result = downloader().download(
            model,
            url = server.url(),
            onProgress = { if (it.bytes == it.total) pending = 1 },
            isCancelled = {
                if (pending == 1) {
                    pending = 2
                    false
                } else {
                    pending == 2
                }
            },
        )

        assertThat(result).isInstanceOf(DownloadResult.Failed::class.java)
        assertThat((result as DownloadResult.Failed).reason).isEqualTo(DownloadResult.Reason.CANCELLED)
        // Preserved, same as every other cancel path: a later resume can reuse the verified bytes.
        assertThat(part().exists()).isTrue()
        assertThat(target().exists()).isFalse()
    }

    @Test
    fun unreadablePartAfterStreamingRetriesAsNetworkInsteadOfCrashing() {
        // Same concern as the exact-size fast path's own read (sha256Hex outside a try/catch):
        // the streamed path's on-disk re-hash is its sibling, and a concurrent delete racing
        // it would crash the same way if left unwrapped here too.
        server.enqueue(CannedResponse(status = 200, headers = mapOf("ETag" to "\"v1\""), body = BODY))

        val result = downloader().download(model, url = server.url(), onProgress = {
            if (it.bytes == it.total) part().setReadable(false)
        })

        assertThat(result).isInstanceOf(DownloadResult.Failed::class.java)
        assertThat((result as DownloadResult.Failed).reason).isEqualTo(DownloadResult.Reason.NETWORK)
        assertThat(sleeps).isEqualTo(listOf(2_000L, 4_000L, 8_000L))
        // Only the one real request: once the part is already the right length, the retries
        // re-verify it locally on the fast path rather than re-fetching from the network.
        assertThat(server.requests).hasSize(1)
        assertThat(part().exists()).isTrue()
    }

    @Test
    fun cancelDuringExhaustedStepDoneReadRetryReturnsCancelledNotNetwork() {
        repeat(3) { server.enqueue(CannedResponse(status = 500, headers = emptyMap(), body = ByteArray(0))) }
        // Three HTTP failures exhaust all three retries; the fourth request (server's default,
        // once the queue drains) streams the full body successfully, so retries is already at
        // its limit by the time the on-disk re-hash below fails, and this is the one and only
        // pass through the Step.Done catch block. onProgress fires exactly once at the final
        // byte; the isCancelled call right after it is streamBody's own last internal check,
        // which must still see false so streaming finishes as Done. Only the call after that,
        // the new check this covers, should see true.
        var pending = 0
        val result = downloader().download(
            model,
            url = server.url(),
            onProgress = {
                if (it.bytes == it.total) {
                    part().setReadable(false)
                    pending = 1
                }
            },
            isCancelled = {
                if (pending == 1) {
                    pending = 2
                    false
                } else {
                    pending == 2
                }
            },
        )

        assertThat(result).isInstanceOf(DownloadResult.Failed::class.java)
        assertThat((result as DownloadResult.Failed).reason).isEqualTo(DownloadResult.Reason.CANCELLED)
        assertThat(server.requests).hasSize(4)
    }

    @Test
    fun createsModelsDirOnFirstDownload() {
        val modelsDir = File(tmp.root, "models")
        val downloader = Downloader(modelsDir, sleep = fakeSleep)

        val result = downloader.download(model, url = server.url())

        assertThat(result).isInstanceOf(DownloadResult.Done::class.java)
        assertThat(File(modelsDir, "m.gguf").length()).isEqualTo(BODY.size.toLong())
    }

    @Test
    fun renameFailureReturnsNetworkFailureAndKeepsPart() {
        // Files.move(ATOMIC_MOVE) of a regular file onto an existing directory fails (EISDIR):
        // a stand-in for any local I/O error at the last step, e.g. a failed rename.
        target().mkdirs()

        val result = downloader().download(model, url = server.url())

        assertThat(result).isInstanceOf(DownloadResult.Failed::class.java)
        assertThat((result as DownloadResult.Failed).reason).isEqualTo(DownloadResult.Reason.NETWORK)
        // Not deleted: the bytes are already verified, so a retry can resume the rename via the
        // exact-size fast path instead of re-downloading everything.
        assertThat(part().length()).isEqualTo(BODY.size.toLong())
    }

    @Test
    fun corruptEtagWithControlCharacterIsTreatedAsOrphan() {
        // setRequestProperty rejects header values with raw control characters; a stored tag
        // that bad is corrupt (e.g. a partial write), so it's discarded like a missing pair
        // instead of throwing out of runRequest.
        seedPart(BODY.copyOfRange(0, 300_000), etagValue = "\"v1\"\n\u0000garbage")

        val result = downloader().download(model, url = server.url())

        assertThat(server.requests).hasSize(1)
        assertThat(server.requests[0]["range"]).isNull()
        assertThat(result).isInstanceOf(DownloadResult.Done::class.java)
    }

    @Test
    fun unreadableEtagIsTreatedAsOrphanInsteadOfCrashing() {
        // A concurrent delete racing the exists() check would throw the same
        // FileNotFoundException readText() does here; unreadable is a portable stand-in.
        seedPart(BODY.copyOfRange(0, 300_000))
        etag().setReadable(false)

        val result = downloader().download(model, url = server.url())

        assertThat(server.requests).hasSize(1)
        assertThat(server.requests[0]["range"]).isNull()
        assertThat(result).isInstanceOf(DownloadResult.Done::class.java)
    }

    @Test
    fun trustedResponseUrlRequiresHttpsUnlessLoopback() {
        // A pure predicate, tested directly rather than through download(): reproducing the
        // "https redirected to a real non-loopback http host" case end-to-end would need a
        // reachable second host, which isn't available offline. Constructing a URL never
        // connects anywhere, so this needs no network at all.
        assertThat(isTrustedResponseUrl(URL("https://huggingface.co/x"))).isTrue()
        assertThat(isTrustedResponseUrl(URL("http://127.0.0.1:1234/x"))).isTrue()
        assertThat(isTrustedResponseUrl(URL("http://localhost:1234/x"))).isTrue()
        assertThat(isTrustedResponseUrl(URL("http://example.com/x"))).isFalse()
        // startsWith("127.") used to accept any hostname with that prefix, attacker-controlled
        // domains included; only the exact loopback address counts now.
        assertThat(isTrustedResponseUrl(URL("http://127.attacker.example/x"))).isFalse()
        // IPv6 loopback dropped along with the prefix match: nothing in this codebase resolves
        // or tests against ::1 (TestHttpServer binds 127.0.0.1 only), and the narrower two-case
        // allowlist is the one actually asked for.
        assertThat(isTrustedResponseUrl(URL("http://[::1]:1234/x"))).isFalse()
    }

    @Test
    fun sendsIdentityAcceptEncoding() {
        // A transparent gzip re-encoding would decouple Content-Length/Content-Range and the
        // Range offsets we send from the raw file bytes we hash and size-check.
        downloader().download(model, url = server.url())

        assertThat(server.requests[0]["accept-encoding"]).isEqualTo("identity")
    }

    // A stopped run must not delete a part that might already belong to whatever superseded
    // it (Cancel then Download again, or the REPLACE on "Download now"). Same tamper as
    // staleWriteAfterDigestFailsInsteadOfRenamingCorruptFile, but this run is itself the one
    // that gets stopped, right where that test's mismatch would otherwise delete the file.
    @Test
    fun cancelledStopsSizeMismatchDeleteInStepDone() {
        var pending = 0
        val result = downloader().download(
            model,
            url = server.url(),
            onProgress = {
                if (pending == 0 && it.bytes == it.total) {
                    pending = 1
                    part().appendBytes(byteArrayOf(0)) // a stale, superseded run's late write landing after ours
                }
            },
            // The call right after the tamper is streamBody's own last internal check, which must
            // still see false so streaming finishes as Done. Only the next one, the new guard
            // ahead of the size-mismatch delete this covers, should see true.
            isCancelled = {
                if (pending == 1) {
                    pending = 2
                    false
                } else {
                    pending == 2
                }
            },
        )

        assertThat(result).isInstanceOf(DownloadResult.Failed::class.java)
        assertThat((result as DownloadResult.Failed).reason).isEqualTo(DownloadResult.Reason.CANCELLED)
        assertThat(part().exists()).isTrue()
        assertThat(target().exists()).isFalse()
    }

    // The HASH_MISMATCH sibling of the test above. Same truncation tamper as
    // truncationDuringStreamFailsInsteadOfRenamingZeroedFile (fires at the first progress event,
    // not the last, since that's what leaves the length right but the bytes wrong), staggered so
    // the stop is only honored once streamBody itself has already finished.
    @Test
    fun cancelledStopsHashMismatchDeleteInStepDone() {
        var truncated = false
        var pending = 0
        val result = downloader().download(
            model,
            url = server.url(),
            onProgress = {
                if (!truncated) {
                    truncated = true
                    RandomAccessFile(part(), "rw").use { it.setLength(0) }
                }
                if (pending == 0 && it.bytes == it.total) pending = 1
            },
            isCancelled = {
                if (pending == 1) {
                    pending = 2
                    false
                } else {
                    pending == 2
                }
            },
        )

        assertThat(result).isInstanceOf(DownloadResult.Failed::class.java)
        assertThat((result as DownloadResult.Failed).reason).isEqualTo(DownloadResult.Reason.CANCELLED)
        assertThat(part().exists()).isTrue()
        assertThat(target().exists()).isFalse()
    }

    // The Step.Fatal sibling: same mismatched-total setup as mismatchedContentRangeTotalIsFatal,
    // a path with no onProgress hook at all, so the stagger is a plain call count instead: the
    // loop-top check and runRequest's own check right after responseCode must both stay false for
    // the mismatch to actually be reached, and only the new guard ahead of this branch's delete
    // should see true.
    @Test
    fun cancelledStopsFatalDelete() {
        seedPart(BODY.copyOfRange(0, 300_000))
        server.enqueue(
            CannedResponse(
                status = 206,
                headers = mapOf("ETag" to "\"v1\"", "Content-Range" to "bytes 300000-1048575/2000000"),
                body = ByteArray(0),
            ),
        )
        var calls = 0
        val result = downloader().download(model, url = server.url(), isCancelled = { ++calls > 2 })

        assertThat(result).isInstanceOf(DownloadResult.Failed::class.java)
        assertThat((result as DownloadResult.Failed).reason).isEqualTo(DownloadResult.Reason.CANCELLED)
        assertThat(part().exists()).isTrue()
    }

    @Test
    fun rangeAndIfRangeSurviveA302Redirect() {
        // Hugging Face answers `resolve` URLs with a redirect to its CDN (see url());
        // instanceFollowRedirects must carry our Range/If-Range onto the followed
        // request, or a resume would silently restart from zero on every real download.
        seedPart(BODY.copyOfRange(0, 300_000))
        server.enqueue(
            CannedResponse(status = 302, headers = mapOf("Location" to server.url("/redirected")), body = ByteArray(0)),
        )

        val result = downloader().download(model, url = server.url())

        assertThat(server.requests).hasSize(2)
        assertThat(server.requests[1]["range"]).isEqualTo("bytes=300000-")
        assertThat(server.requests[1]["if-range"]).isEqualTo("\"v1\"")
        assertThat(result).isInstanceOf(DownloadResult.Done::class.java)
        assertThat(sha256Hex(target())).isEqualTo(model.sha256)
    }

    // The fast path's own mismatch delete gets the same guard as the Step.Done ones: a stopped run must not delete a
    // part that might already belong to whatever superseded it.
    @Test
    fun cancelledStopsFastPathHashMismatchDelete() {
        part().writeBytes(BODY.copyOf().also { it[0] = (it[0] + 1).toByte() })
        // The loop-top check must see false to reach the fast path; the next call is the guard this covers.
        var calls = 0
        val result = downloader().download(model, url = server.url(), isCancelled = { ++calls > 1 })

        assertThat((result as DownloadResult.Failed).reason).isEqualTo(DownloadResult.Reason.CANCELLED)
        assertThat(part().exists()).isTrue()
    }

    // 408 and 429 are retried like 5xx. Each wait is the backoff step or the server's Retry-After, whichever is longer.
    @Test
    fun status408And429RetryLike5xxHonoringRetryAfter() {
        server.enqueue(CannedResponse(status = 429, headers = mapOf("Retry-After" to "5"), body = ByteArray(0)))
        server.enqueue(CannedResponse(status = 408, headers = emptyMap(), body = ByteArray(0)))
        server.enqueue(CannedResponse(status = 503, headers = mapOf("Retry-After" to "1"), body = ByteArray(0)))

        val result = downloader().download(model, url = server.url())

        assertThat(result).isInstanceOf(DownloadResult.Done::class.java)
        assertThat(server.requests).hasSize(4)
        assertThat(sleeps).isEqualTo(listOf(5_000L, 4_000L, 8_000L))
    }

    @Test
    fun retryAfterAsAnHttpDateIsHonored() {
        val format = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US).apply { timeZone = TimeZone.getTimeZone("GMT") }
        val at = format.format(Date(System.currentTimeMillis() + 30_000))
        server.enqueue(CannedResponse(status = 429, headers = mapOf("Retry-After" to at), body = ByteArray(0)))

        val result = downloader().download(model, url = server.url())

        assertThat(result).isInstanceOf(DownloadResult.Done::class.java)
        assertThat(sleeps.single()).isIn(Range.closed(25_000L, 30_000L)) // the date has whole seconds
    }

    // A Retry-After over a minute is capped at a minute per wait, and the retries go on.
    @Test
    fun retryAfterOverAMinuteWaitsTheCapAndRetries() {
        server.enqueue(CannedResponse(status = 429, headers = mapOf("Retry-After" to "61"), body = ByteArray(0)))

        val result = downloader().download(model, url = server.url())

        assertThat(result).isInstanceOf(DownloadResult.Done::class.java)
        assertThat(server.requests).hasSize(2)
        assertThat(sleeps).isEqualTo(listOf(60_000L))
    }

    // Seconds are capped before they become milliseconds, so no value overflows into a short wait.
    @Test
    fun hugeRetryAfterIsCappedWithoutOverflow() {
        server.enqueue(CannedResponse(status = 503, headers = mapOf("Retry-After" to "9223372036854775807"), body = ByteArray(0)))
        server.enqueue(CannedResponse(status = 429, headers = mapOf("Retry-After" to "99999999999999999999"), body = ByteArray(0)))

        val result = downloader().download(model, url = server.url())

        assertThat(result).isInstanceOf(DownloadResult.Done::class.java)
        assertThat(sleeps).isEqualTo(listOf(60_000L, 60_000L))
    }

    @Test
    fun farFutureRetryAfterDateIsCapped() {
        val format = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US).apply { timeZone = TimeZone.getTimeZone("GMT") }
        val at = format.format(Date(System.currentTimeMillis() + 86_400_000L))
        server.enqueue(CannedResponse(status = 429, headers = mapOf("Retry-After" to at), body = ByteArray(0)))

        val result = downloader().download(model, url = server.url())

        assertThat(result).isInstanceOf(DownloadResult.Done::class.java)
        assertThat(sleeps).isEqualTo(listOf(60_000L))
    }

    // A server that can't be reached is a NETWORK failure after the usual retries. Whether that means "no internet" is
    // the worker's call, from the phone's validated connectivity, not from the kind of exception.
    @Test
    fun unreachableServerRetriesAsNetwork() {
        val closedPort = ServerSocket(0).use { it.localPort }

        val result = downloader().download(model, url = "http://127.0.0.1:$closedPort/m.gguf")

        assertThat((result as DownloadResult.Failed).reason).isEqualTo(DownloadResult.Reason.NETWORK)
        assertThat(sleeps).isEqualTo(listOf(2_000L, 4_000L, 8_000L))
    }

    // The worker reads bytes == total as "checking the file": a part that is already whole says so before its hash.
    @Test
    fun completePartReportsFullSizeBeforeItsHash() {
        part().writeBytes(BODY)
        val reports = mutableListOf<DownloadProgress>()

        val result = downloader().download(model, url = server.url(), onProgress = { reports += it })

        assertThat(result).isInstanceOf(DownloadResult.Done::class.java)
        assertThat(reports).containsExactly(DownloadProgress(BODY.size.toLong(), BODY.size.toLong()))
    }

    @Test
    fun resumeReportsWhereItStartsAtOnce() {
        seedPart(BODY.copyOfRange(0, 300_000))
        val reports = mutableListOf<DownloadProgress>()

        downloader().download(model, url = server.url(), onProgress = { reports += it })

        assertThat(reports.first()).isEqualTo(DownloadProgress(300_000, BODY.size.toLong()))
    }

    // Cancel removes the partial file, but only once the stopped run has let go of it; deleted earlier, a run that has
    // not yet seen the stop could write it back.
    @Test
    fun discardPartialWaitsForTheRunningDownload() {
        server.sticky = CannedResponse(body = BODY, stallAfterBytes = 300_000, stallForMs = 1_000)
        val stop = AtomicBoolean(false)
        val events = CopyOnWriteArrayList<String>()
        val run = thread {
            downloader().download(model, url = server.url(), isCancelled = { stop.get() })
            events += "download returned"
        }
        val deadline = System.currentTimeMillis() + 5_000
        while (part().length() < 300_000) {
            check(System.currentTimeMillis() < deadline) { "no bytes" }
            Thread.sleep(10)
        }

        stop.set(true) // the run is inside the server's stall now, and sees the stop at its next read
        downloader().discardPartial(model)
        events += "discard returned"
        run.join()

        assertThat(events).containsExactly("download returned", "discard returned").inOrder()
        assertThat(part().exists()).isFalse()
        assertThat(etag().exists()).isFalse()
    }
}
