package io.github.kabrapratik28.thumbfree.core.models

import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** Streams [file] through SHA-256 in 1 MiB reads, so a 731 MB model never sits fully in memory. */
fun sha256Hex(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(1 shl 20)
    file.inputStream().use { input ->
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

/** [CHECK_FAILED]: the check itself threw. The home screen shows it; [ModelStore.status] never returns it. */
enum class ModelStatus { MISSING, WRONG_SIZE, CORRUPT, VERIFIED, CHECK_FAILED }

/** Verifies model files on disk, caching the SHA-256 result in memory and in a "<fileName>.verified" sidecar. */
class ModelStore(private val modelsDir: File, private val hash: (File) -> String = ::sha256Hex) {
    // File name to the stamp its last good hash in this process confirmed: it holds when the sidecar cannot be written
    // or read, so that is not a full hash on every check. Guarded by this.
    private val confirmed = HashMap<String, String>()

    fun file(model: ModelFile): File = File(modelsDir, model.fileName)

    /**
     * Size first (cheap), then SHA-256 once per (size, lastModified) unless this process or the sidecar already
     * confirmed it. A caller that comes during a hash waits for it and then finds its result: a cancelled coroutine
     * cannot stop a blocking hash, so without the lock a second one would start.
     */
    @Synchronized // ponytail: one lock for every model; per-model locks if two models ever verify at the same time
    fun status(model: ModelFile): ModelStatus {
        val target = file(model)
        if (!target.exists()) return ModelStatus.MISSING
        if (target.length() != model.sizeBytes) return ModelStatus.WRONG_SIZE

        val sidecar = verifiedSidecar(model)
        val stamp = "${target.length()}:${target.lastModified()}"
        if (confirmed[model.fileName] == stamp) return ModelStatus.VERIFIED
        // A missing or unreadable sidecar is a stale one: the file is hashed again.
        val saved = try { sidecar.readText() } catch (e: IOException) { null }
        if (saved == stamp) return ModelStatus.VERIFIED

        // A file that cannot be read is no more usable than a corrupt one.
        val actual = try { hash(target) } catch (e: IOException) { null }
        if (actual != model.sha256) return ModelStatus.CORRUPT
        confirmed[model.fileName] = stamp
        // Best effort: without the sidecar the next process hashes again.
        try { sidecar.writeText(stamp) } catch (e: IOException) { }
        return ModelStatus.VERIFIED
    }

    fun verifiedPath(model: ModelFile): File? = if (status(model) == ModelStatus.VERIFIED) file(model) else null

    /**
     * Records the verdict for the file the downloader's locked finalization hashed, so the next status() trusts it
     * instead of hashing the whole file again. False, recording nothing, unless [check] has the catalog's size and
     * SHA-256 and the file on disk is still the one it describes: same size, same time.
     */
    @Synchronized
    fun markVerified(model: ModelFile, check: FileCheck): Boolean {
        val target = file(model)
        if (check.sha256 != model.sha256 || check.size != model.sizeBytes) return false
        if (target.length() != check.size || target.lastModified() != check.lastModified) return false // 0 when missing
        val stamp = "${check.size}:${check.lastModified}"
        confirmed[model.fileName] = stamp
        try { verifiedSidecar(model).writeText(stamp) } catch (e: IOException) { } // best effort, as in status()
        return true
    }

    /** Removes the model file and its verdict together, so a caller never has to know the sidecar's name. */
    @Synchronized
    fun delete(model: ModelFile) {
        confirmed -= model.fileName
        file(model).delete()
        verifiedSidecar(model).delete()
    }

    private fun verifiedSidecar(model: ModelFile) = File(modelsDir, "${model.fileName}.verified")
}
