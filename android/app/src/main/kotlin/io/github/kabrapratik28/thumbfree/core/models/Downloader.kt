package io.github.kabrapratik28.thumbfree.core.models

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.concurrent.ConcurrentHashMap

/** The Hugging Face repo id: everything before the file name in [ModelFile.id]. */
val ModelFile.repo: String get() = id.substringBeforeLast('/')

data class DownloadProgress(val bytes: Long, val total: Long)

/**
 * What the downloader's locked finalization saw of the file it renamed into place: the SHA-256 it computed of those bytes
 * on disk, and the file's size and time right after the rename. ModelStore.markVerified takes only this, and only this
 * module can make one: the finalizer, and the tests.
 */
@ConsistentCopyVisibility
data class FileCheck internal constructor(val size: Long, val lastModified: Long, val sha256: String)

sealed interface DownloadResult {
    @ConsistentCopyVisibility
    data class Done internal constructor(val file: File, val check: FileCheck) : DownloadResult
    data class Failed(val reason: Reason, val detail: String) : DownloadResult
    enum class Reason { NO_SPACE, SIZE_MISMATCH, HASH_MISMATCH, HTTP, NETWORK, STALLED, CANCELLED }
}

/**
 * Downloads a model file over HTTPS: resumable via Range into a "<fileName>.part" sidecar,
 * verified by re-hashing the file on disk (not a streaming digest) right before an atomic rename
 * once size and hash both check out. Network errors, stalls and HTTP 408, 429 and 5xx get one try
 * plus three retries (2s, 4s, 8s backoff, or the server's longer Retry-After, up to a minute);
 * anything else that's wrong about the response fails at once and drops the partial file. One
 * download of a file runs at a time, and [discardPartial] waits for it.
 */
open class Downloader(
    private val modelsDir: File,
    private val usableSpace: () -> Long = { modelsDir.usableSpace },
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
    private val stallMs: Int = 60_000,
) {
    fun url(model: ModelFile): String =
        "https://huggingface.co/${model.repo}/resolve/${model.revision}/${model.fileName}"

    /**
     * Blocking; call from a worker thread. Open so DownloadWorkerTest can fake a result without a real transfer.
     * Waits while another download of the same file still runs, even a stopped one that has not yet seen its stop: a
     * Cancel then Download again, or a "Download now" REPLACE, then never has two runs writing one part.
     */
    open fun download(
        model: ModelFile,
        url: String = url(model),
        isCancelled: () -> Boolean = { false },
        onProgress: (DownloadProgress) -> Unit = {},
    ): DownloadResult = synchronized(lockFor(model)) { transfer(model, url, isCancelled, onProgress) }

    /** Deletes [model]'s partial download, once no download of it runs: a stopped run lets go at its next read. */
    fun discardPartial(model: ModelFile) = synchronized(lockFor(model)) {
        File(modelsDir, "${model.fileName}.part").delete()
        File(modelsDir, "${model.fileName}.part.etag").delete()
    }

    /** Whether [model] fits: what is left to fetch, counting a partial file already here, plus the 1 GiB margin. */
    fun hasSpaceFor(model: ModelFile): Boolean {
        modelsDir.mkdirs() // a folder that does not exist has no usable space
        val part = File(modelsDir, "${model.fileName}.part").length().takeIf { it <= model.sizeBytes } ?: 0L
        return usableSpace() >= spaceNeeded(model, part)
    }

    private fun spaceNeeded(model: ModelFile, resumeLength: Long) = model.sizeBytes - resumeLength + EXTRA_FREE_BYTES

    // ponytail: in-process locks only, enough while WorkManager runs every download in the main process.
    private fun lockFor(model: ModelFile): Any = LOCKS.computeIfAbsent(File(modelsDir, model.fileName).absolutePath) { Any() }

    private fun transfer(
        model: ModelFile,
        url: String,
        isCancelled: () -> Boolean,
        onProgress: (DownloadProgress) -> Unit,
    ): DownloadResult {
        modelsDir.mkdirs()
        val target = File(modelsDir, model.fileName)
        val part = File(modelsDir, "${model.fileName}.part")
        val etagFile = File(modelsDir, "${model.fileName}.part.etag")

        var retries = 0
        while (true) {
            if (isCancelled()) return DownloadResult.Failed(DownloadResult.Reason.CANCELLED, "cancelled")

            if (part.length() > model.sizeBytes) {
                // Longer than the catalog says it should ever be: not resumable, and if left in
                // place it would skew the free-space check below against a bogus resume offset.
                part.delete()
                etagFile.delete()
            } else if (part.exists() && part.length() == model.sizeBytes) {
                // Cancelled on the last block, or killed after the last byte but before the
                // rename: the file may already be whole on disk. Verify it locally instead of
                // re-downloading from scratch. A read failure here (e.g. a concurrent delete
                // racing this exact check) isn't proof the bytes are bad, just unreadable right
                // now: retried like any other NETWORK-class hiccup instead of discarding a
                // possibly-fine file or crashing download() with an uncaught IOException.
                // Every byte is here: the caller shows the hash that follows as checking the file.
                onProgress(DownloadProgress(model.sizeBytes, model.sizeBytes))
                var digest = ""
                val hashMatches = try {
                    digest = sha256Hex(part)
                    if (digest != model.sha256) {
                        false
                    } else {
                        // The bytes are correct, but the file may have gotten here from a crash
                        // or kill that never reached streamBody's own fsync (the only other
                        // place this file is written): sync it before trusting it into a rename.
                        // No CREATE option: if part vanished between the hash above and this open
                        // (a concurrent delete), this must fail into the retry path below instead
                        // of silently fabricating an empty file that would then get renamed.
                        FileChannel.open(part.toPath(), StandardOpenOption.WRITE).use { it.force(true) }
                        true
                    }
                } catch (e: IOException) {
                    if (retries >= RETRY_DELAYS_MS.size) {
                        // A cancel that arrived during this last attempt must win over whatever
                        // reason that attempt itself failed for, same as the Step.Retry case below.
                        return if (isCancelled()) {
                            DownloadResult.Failed(DownloadResult.Reason.CANCELLED, "cancelled")
                        } else {
                            DownloadResult.Failed(DownloadResult.Reason.NETWORK, e.message ?: "read failed")
                        }
                    }
                    sleep(RETRY_DELAYS_MS[retries])
                    retries++
                    continue
                }
                if (hashMatches) {
                    // A cancel arriving after this on-disk verify already succeeded must still
                    // not rename: the caller stopped caring about this result.
                    if (isCancelled()) return DownloadResult.Failed(DownloadResult.Reason.CANCELLED, "cancelled")
                    return finishRename(part, target, etagFile, digest)
                }
                // Same reasoning as the Step.Done mismatch deletes below: a stopped run must not delete a
                // part that might already belong to whatever superseded it.
                if (isCancelled()) return DownloadResult.Failed(DownloadResult.Reason.CANCELLED, "cancelled")
                part.delete()
                etagFile.delete()
                return DownloadResult.Failed(DownloadResult.Reason.HASH_MISMATCH, "hash mismatch")
            }

            // A part is only trustworthy paired with the etag it was resumed against; an orphan
            // of either (e.g. a leftover .part from a killed process, or a tag corrupted by a
            // partial write) is discarded up front so it can never wedge a resume into a
            // permanent loop or crash setRequestProperty with an illegal header value. A failed
            // read of an existing tag (e.g. a concurrent delete racing the exists() check) is the
            // same kind of untrustworthy and is treated the same way, instead of crashing here.
            val hasValidPart = part.exists() && etagFile.exists() &&
                runCatching { isValidHeaderValue(etagFile.readText()) }.getOrDefault(false)
            if (!hasValidPart) {
                part.delete()
                etagFile.delete()
            }
            val resumeLength = if (hasValidPart) part.length() else 0L

            val needed = spaceNeeded(model, resumeLength)
            if (usableSpace() < needed) {
                return DownloadResult.Failed(DownloadResult.Reason.NO_SPACE, "need $needed bytes free")
            }

            val step = try {
                runRequest(model, url, part, etagFile, resumeLength, isCancelled, onProgress)
            } catch (e: SocketTimeoutException) {
                Step.Retry(DownloadResult.Reason.STALLED, e.message ?: "stalled")
            } catch (e: IOException) {
                Step.Retry(DownloadResult.Reason.NETWORK, e.message ?: "network error")
            }

            when (step) {
                is Step.Done -> {
                    // Belt-and-suspenders against a stale, superseded run (WorkManager KEEP
                    // policy) still touching this same part: our in-memory total only ever
                    // covers the bytes *we* streamed, and a stale run's late-arriving 200
                    // can truncate the file out from under us without changing its final length
                    // (the isCancelled() check right after responseCode above closes the common
                    // case, but can't help once a stale run already got past it). So length
                    // alone can't prove the bytes are ours; re-hash the file itself, on disk,
                    // right before the rename.
                    // ponytail: no file lock guarding writers against each other, so a stale
                    // writer still wastes a full retry cycle instead of failing fast; add
                    // FileChannel.tryLock() around streamBody's write if that cost matters.
                    val onDiskLength = part.length()
                    if (onDiskLength != model.sizeBytes) {
                        // A stopped run must not delete what might by now be a newer run's part:
                        // Cancel then Download again, or a "Download now" REPLACE, can both
                        // leave one in place while this run is still unwinding.
                        if (isCancelled()) return DownloadResult.Failed(DownloadResult.Reason.CANCELLED, "cancelled")
                        part.delete()
                        etagFile.delete()
                        return DownloadResult.Failed(
                            DownloadResult.Reason.SIZE_MISMATCH,
                            "part is $onDiskLength bytes at rename, expected ${model.sizeBytes}",
                        )
                    }
                    // Same read-failure concern as the exact-size fast path above: a concurrent delete
                    // racing this on-disk re-hash would otherwise throw straight out of
                    // download() instead of retrying or failing cleanly.
                    var digest = ""
                    val hashMatches = try {
                        digest = sha256Hex(part)
                        digest == model.sha256
                    } catch (e: IOException) {
                        if (retries >= RETRY_DELAYS_MS.size) {
                            // Same reasoning as the exact-size fast path's own read above: a cancel that
                            // arrived during this last attempt must win over the attempt's own reason.
                            return if (isCancelled()) {
                                DownloadResult.Failed(DownloadResult.Reason.CANCELLED, "cancelled")
                            } else {
                                DownloadResult.Failed(DownloadResult.Reason.NETWORK, e.message ?: "read failed")
                            }
                        }
                        sleep(RETRY_DELAYS_MS[retries])
                        retries++
                        continue
                    }
                    if (!hashMatches) {
                        // Same reasoning as the size check above: don't delete a part that
                        // might already belong to a run that superseded this one.
                        if (isCancelled()) return DownloadResult.Failed(DownloadResult.Reason.CANCELLED, "cancelled")
                        part.delete()
                        etagFile.delete()
                        return DownloadResult.Failed(DownloadResult.Reason.HASH_MISMATCH, "hash mismatch")
                    }
                    // A cancel arriving after the network transfer and this on-disk re-verify
                    // both succeeded must still not rename: the caller stopped caring about
                    // this result, and a stale run's own late finishRename could otherwise
                    // still land as Done.
                    if (isCancelled()) return DownloadResult.Failed(DownloadResult.Reason.CANCELLED, "cancelled")
                    return finishRename(part, target, etagFile, digest)
                }
                is Step.Fatal -> {
                    // Same reasoning as the Step.Done mismatch deletes above: a stopped run
                    // must not delete a part that might already belong to whatever superseded it.
                    if (isCancelled()) return DownloadResult.Failed(DownloadResult.Reason.CANCELLED, "cancelled")
                    part.delete()
                    etagFile.delete()
                    return DownloadResult.Failed(step.reason, step.detail)
                }
                is Step.Cancelled -> return DownloadResult.Failed(DownloadResult.Reason.CANCELLED, "cancelled")
                is Step.RestartFresh -> {
                    part.delete()
                    etagFile.delete()
                }
                is Step.Retry -> {
                    if (retries >= RETRY_DELAYS_MS.size) {
                        // A cancel that arrived during this last attempt must win over
                        // whatever reason that attempt itself failed for.
                        return if (isCancelled()) {
                            DownloadResult.Failed(DownloadResult.Reason.CANCELLED, "cancelled")
                        } else {
                            DownloadResult.Failed(step.reason, step.detail)
                        }
                    }
                    // The server's Retry-After when it asks for longer than the backoff step, at most a minute a wait.
                    sleep(minOf(maxOf(RETRY_DELAYS_MS[retries], step.waitMs), MAX_RETRY_AFTER_MS))
                    retries++
                }
            }
        }
    }

    /** One HTTP request. May decide mid-flight to discard the part and loop again in the same try. */
    private fun runRequest(
        model: ModelFile,
        url: String,
        part: File,
        etagFile: File,
        resumeLength: Long,
        isCancelled: () -> Boolean,
        onProgress: (DownloadProgress) -> Unit,
    ): Step {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = stallMs
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", "ThumbFree")
        // Without this, a transparently gzipped response would decouple Content-Length and our
        // Range offsets from the raw file bytes this class hashes and size-checks.
        conn.setRequestProperty("Accept-Encoding", "identity")
        if (resumeLength > 0) {
            conn.setRequestProperty("Range", "bytes=$resumeLength-")
            conn.setRequestProperty("If-Range", etagFile.readText())
        }

        val code = conn.responseCode
        if (isCancelled()) {
            // A stopped run (superseded by a newer one targeting the same part, e.g. Cancel then
            // Download while this request was in flight, or a WorkManager restart that outlived
            // its stop signal) must not touch the file or tag from here: writeEtag, a Fatal path's
            // delete, or streamBody's FileOutputStream(part, false) open would all step on the
            // live run's part out from under it.
            conn.disconnect()
            return Step.Cancelled
        }
        if (!isTrustedResponseUrl(conn.url)) {
            // instanceFollowRedirects doesn't itself enforce scheme: an https request can be
            // redirected to a plain-http host. conn.url is the final URL after any redirects,
            // so this catches the downgrade regardless of which hop introduced it.
            conn.disconnect()
            return Step.Fatal(DownloadResult.Reason.HTTP, "insecure response from ${conn.url}")
        }
        return when (code) {
            HttpURLConnection.HTTP_OK -> {
                // The server ignored our Range, or there was none: this is the whole file from
                // byte zero, so any stale part is superseded.
                val declared = conn.contentLengthLong
                if (declared >= 0 && declared != model.sizeBytes) {
                    // A complete response with the wrong length (proxy block page, wrong file)
                    // is a permanent mismatch, not a dropped connection: fail now, one request,
                    // before streaming a single byte.
                    conn.disconnect()
                    Step.Fatal(DownloadResult.Reason.SIZE_MISMATCH, "declared $declared bytes, expected ${model.sizeBytes}")
                } else {
                    streamBody(conn, part, etagFile, append = false, startOffset = 0L, model = model, isCancelled, onProgress)
                }
            }
            HttpURLConnection.HTTP_PARTIAL -> {
                val contentRange = conn.getHeaderField("Content-Range")
                val total = contentRangeTotal(contentRange)
                if (contentRangeStart(contentRange) != resumeLength) {
                    conn.disconnect()
                    // We were already asking for the whole file (resumeLength 0): a 206 that
                    // doesn't start at 0 can't be fixed by "restarting fresh", that's what this
                    // request already was. Restarting again would just repeat forever.
                    if (resumeLength == 0L) Step.Fatal(DownloadResult.Reason.HTTP, "HTTP $code with unexpected Content-Range")
                    else Step.RestartFresh
                } else if (total != null && total != model.sizeBytes) {
                    conn.disconnect()
                    Step.Fatal(DownloadResult.Reason.SIZE_MISMATCH, "declared total $total bytes, expected ${model.sizeBytes}")
                } else {
                    streamBody(conn, part, etagFile, append = true, startOffset = resumeLength, model = model, isCancelled, onProgress)
                }
            }
            HTTP_RANGE_NOT_SATISFIABLE -> {
                conn.disconnect()
                // Same reasoning as above: a 416 to a no-Range request isn't a resume problem.
                if (resumeLength == 0L) Step.Fatal(DownloadResult.Reason.HTTP, "HTTP $code")
                else Step.RestartFresh
            }
            HttpURLConnection.HTTP_CLIENT_TIMEOUT, HTTP_TOO_MANY_REQUESTS, in 500..599 -> {
                val waitMs = retryAfterMs(conn)
                conn.disconnect()
                Step.Retry(DownloadResult.Reason.HTTP, "HTTP $code", waitMs)
            }
            else -> {
                conn.disconnect()
                Step.Fatal(DownloadResult.Reason.HTTP, "HTTP $code")
            }
        }
    }

    /**
     * Streams the response body onto [part] (appending when resuming, else truncating) and records its
     * ETag, both only once the body's first bytes are here. Stops the instant the byte count passes
     * the catalog size. The caller re-hashes the file on disk before renaming it, so this only has to
     * prove the size is right before it fsyncs.
     */
    private fun streamBody(
        conn: HttpURLConnection,
        part: File,
        etagFile: File,
        append: Boolean,
        startOffset: Long,
        model: ModelFile,
        isCancelled: () -> Boolean,
        onProgress: (DownloadProgress) -> Unit,
    ): Step {
        var total = startOffset
        var sinceReport = 0L
        if (startOffset > 0) onProgress(DownloadProgress(startOffset, model.sizeBytes)) // a resume starts there, not at 0
        // HttpURLConnection doesn't throw when a peer closes mid-body despite a larger Content-Length:
        // the stream just ends. A short read is a dropped connection, not a corrupt file (the loop
        // below catches "too long"), so it's retryable rather than fatal.
        fun short() = Step.Retry(DownloadResult.Reason.NETWORK, "connection closed at $total of ${model.sizeBytes} bytes")
        conn.inputStream.use { input ->
            // Android/OkHttp's read() can return as little as one 8 KiB segment at a time,
            // unlike the JDK's own HttpURLConnection on localhost; batching by bytes-since-
            // last-report (not by read count) keeps progress writes rare on real devices too.
            val buffer = ByteArray(BLOCK_BYTES)
            var read = input.read(buffer)
            if (read < 0) return short()
            if (isCancelled()) return Step.Cancelled
            // Nothing on disk changes before the body's first bytes are in hand. A 200
            // replaces the part from byte 0, and a body that fails before its first byte must leave
            // a resumable part and its tag as they were.
            writeEtag(conn, etagFile)
            FileOutputStream(part, append).use { out ->
                while (true) {
                    out.write(buffer, 0, read)
                    total += read
                    sinceReport += read
                    if (total > model.sizeBytes) {
                        return Step.Fatal(DownloadResult.Reason.SIZE_MISMATCH, "body exceeds ${model.sizeBytes} bytes")
                    }
                    if (sinceReport >= BLOCK_BYTES || total == model.sizeBytes) {
                        onProgress(DownloadProgress(total, model.sizeBytes))
                        sinceReport = 0
                    }
                    if (isCancelled()) return Step.Cancelled
                    read = input.read(buffer)
                    if (read < 0) break
                    if (isCancelled()) return Step.Cancelled
                }
                if (total < model.sizeBytes) return short()
                out.fd.sync()
            }
        }
        return Step.Done
    }

    /** Retry-After in delay-seconds or as an HTTP date, in ms from now and at most the cap; 0 when absent or unreadable. */
    private fun retryAfterMs(conn: HttpURLConnection): Long {
        val value = conn.getHeaderField("Retry-After")?.trim() ?: return 0
        // Seconds are capped before they become milliseconds, so no value can overflow.
        if (value.isNotEmpty() && value.all(Char::isDigit)) {
            return minOf(value.toLongOrNull() ?: Long.MAX_VALUE, MAX_RETRY_AFTER_MS / 1_000) * 1_000
        }
        val at = conn.getHeaderFieldDate("Retry-After", 0)
        return if (at > 0) (at - System.currentTimeMillis()).coerceIn(0, MAX_RETRY_AFTER_MS) else 0
    }

    private fun writeEtag(conn: HttpURLConnection, etagFile: File) {
        val etag = conn.getHeaderField("ETag") ?: return
        etagFile.writeText(etag)
    }

    /**
     * Atomically renames a fully-verified [part] to [target] and drops the etag. A rename can
     * still fail (disk error, cross-device move): that's a local IOException, not a network one,
     * but there's no dedicated Reason for it and the effect is the same as any other dropped
     * write, so it's reported as NETWORK. The part is left in place rather than deleted, so a
     * retry lands back on the exact-size fast path above instead of re-downloading.
     */
    private fun finishRename(part: File, target: File, etagFile: File, digest: String): DownloadResult = try {
        Files.move(part.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        etagFile.delete()
        // Still under this run's lock: the size and time of exactly the bytes [digest] was computed from.
        DownloadResult.Done(target, FileCheck(target.length(), target.lastModified(), digest))
    } catch (e: IOException) {
        DownloadResult.Failed(DownloadResult.Reason.NETWORK, e.message ?: "rename failed")
    }

    private companion object {
        const val HTTP_RANGE_NOT_SATISFIABLE = 416
        const val HTTP_TOO_MANY_REQUESTS = 429
        const val EXTRA_FREE_BYTES = 1_073_741_824L // 1 GiB
        const val BLOCK_BYTES = 256 * 1024
        const val MAX_RETRY_AFTER_MS = 60_000L
        val RETRY_DELAYS_MS = longArrayOf(2_000, 4_000, 8_000)
        val LOCKS = ConcurrentHashMap<String, Any>() // by target path
    }
}

/** Parses "bytes <start>-<end>/<total>", returning null if it isn't that shape. */
private fun contentRangeStart(header: String?): Long? =
    header?.removePrefix("bytes ")?.substringBefore('-')?.trim()?.toLongOrNull()

/** Parses the "<total>" out of "bytes <start>-<end>/<total>", null if absent or "*" (unknown). */
private fun contentRangeTotal(header: String?): Long? =
    header?.substringAfter('/', "")?.trim()?.toLongOrNull()

/** setRequestProperty throws for a header value outside printable ASCII (control chars, or
 * anything above 0x7E); a stored etag that bad can only be a corrupted sidecar. */
private fun isValidHeaderValue(text: String): Boolean = text.all { it.code in 0x20..0x7E }

/** True for real HTTPS or our own loopback test server; false for anything else, e.g. an https
 * request redirected to a plain-http host. `internal` (not `private`) purely so the test can
 * exercise both branches directly instead of needing a reachable second host. */
internal fun isTrustedResponseUrl(url: URL): Boolean =
    url.protocol == "https" || isLoopbackHost(url.host)

private fun isLoopbackHost(host: String): Boolean = host == "127.0.0.1" || host == "localhost"

private sealed interface Step {
    data object Done : Step
    data class Fatal(val reason: DownloadResult.Reason, val detail: String) : Step
    data object Cancelled : Step
    data object RestartFresh : Step
    data class Retry(val reason: DownloadResult.Reason, val detail: String, val waitMs: Long = 0) : Step
}
