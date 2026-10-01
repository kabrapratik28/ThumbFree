package io.github.kabrapratik28.thumbfree.engine

import android.content.Context
import io.github.kabrapratik28.thumbfree.core.models.sha256Hex
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Silero's ggml model for the speech check, shipped as an APK asset: ggml-silero-v6.2.0.bin from
 * huggingface.co/ggml-org/whisper-vad at revision 9ffd54a1e1ee413ddf265af9913beaf518d1639b. The native loader reads a
 * file, so it is copied to app-private storage on first use.
 */
object VadModel {
    const val ASSET = "vad/ggml-silero-v6.2.0.bin"
    const val SIZE = 885_098L
    const val SHA256 = "2aa269b785eeb53a82983a20501ddf7c1d9c48e33ab63a41391ac6c9f7fb6987"

    /**
     * Silero for :engine: [file], then [load], the native loader, on it. Returns the native handle, or 0 when either
     * step fails or throws, and then logs only a reason code: the speech check fails open and the model load goes on.
     * Throws nothing for an ordinary failure.
     */
    fun open(
        context: Context,
        load: (String) -> Long,
        dir: File = context.noBackupFilesDir,
        log: (String) -> Unit,
    ): Long {
        val model = try {
            file(context, dir)
        } catch (e: Exception) {
            null
        } ?: return 0L.also { log("speech_check_unavailable asset") }
        val handle = try {
            load(model.path)
        } catch (e: Exception) {
            0L
        }
        if (handle <= 0) log("speech_check_unavailable load")
        return handle.coerceAtLeast(0L)
    }

    /**
     * The model in [dir], checked against [SHA256] on every call. When it is missing or not the pinned file, the asset
     * is copied to a temporary file, synced, checked and renamed over it, so a crash never leaves a partial model
     * behind. Null when the copy fails; reading an existing file can throw, which [open] turns into no VAD.
     */
    fun file(context: Context, dir: File = context.noBackupFilesDir): File? {
        val file = File(dir, ASSET.substringAfter('/'))
        if (file.length() == SIZE && sha256Hex(file) == SHA256) return file
        val tmp = runCatching { File.createTempFile("silero", ".tmp", dir) }.getOrNull() ?: return null
        try {
            context.assets.open(ASSET).use { input ->
                FileOutputStream(tmp).use { out ->
                    input.copyTo(out)
                    out.fd.sync()
                }
            }
            return file.takeIf { sha256Hex(tmp) == SHA256 && tmp.renameTo(it) }
        } catch (e: IOException) {
            return null
        } finally {
            tmp.delete() // a no-op after the rename
        }
    }
}
