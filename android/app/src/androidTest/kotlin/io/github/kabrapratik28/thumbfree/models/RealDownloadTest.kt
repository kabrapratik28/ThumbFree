package io.github.kabrapratik28.thumbfree.models

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import io.github.kabrapratik28.thumbfree.app.AppGraph
import io.github.kabrapratik28.thumbfree.core.models.Catalog
import io.github.kabrapratik28.thumbfree.core.models.sha256Hex
import io.github.kabrapratik28.thumbfree.engine.RemoteEngine
import io.github.kabrapratik28.thumbfree.testing.JFK_TEXT
import io.github.kabrapratik28.thumbfree.testing.normalizeTranscript
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * One real download of Canary (218 MB) from Hugging Face at the catalog's pinned revision, through ModelDownloads and
 * the real worker; then an independent SHA-256 of the file against the catalog, and a load and a JFK transcription of
 * that very file in :engine. It needs internet, so it runs only with `-e real_download 1`. Canary files already on the
 * device are set aside first, so nothing can be reused, and put back at the end.
 */
@RunWith(AndroidJUnit4::class)
class RealDownloadTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val model = Catalog.CANARY_180M_FLASH_Q8

    @Before
    fun realDownloadOnly() =
        assumeTrue("pass -e real_download 1", InstrumentationRegistry.getArguments().getString("real_download") == "1")

    @Test
    fun canaryDownloadsVerifiesAndLoads() = runBlocking {
        val dir = File(context.filesDir, "models").apply { mkdirs() }
        val files = listOf("", ".verified", ".part", ".part.etag", ".tombstone").map { File(dir, model.fileName + it) }
        val aside = File(context.filesDir, "real-download-aside").apply { deleteRecursively(); mkdirs() }
        for (file in files.filter { it.exists() }) check(file.renameTo(File(aside, file.name))) { "cannot set ${file.name} aside" }
        AppGraph.modelStore.delete(model) // and its verdict in memory
        try {
            assertThat(files.filter { it.exists() }).isEmpty() // nothing left that the worker could reuse

            var started = false
            var transferred = false
            ModelDownloads.start(context, model, wifiOnly = false)
            val end = withTimeout(20 * 60_000L) {
                ModelDownloads.state(context, model).first {
                    Log.i(TAG, "real_download state=$it")
                    if (it is DownloadState.Queued || it is DownloadState.Downloading) started = true // this start's work
                    if (it is DownloadState.Downloading && it.bytes in 1 until it.total) transferred = true
                    started && (it == DownloadState.Ready || it is DownloadState.Failed)
                }
            }

            assertThat(end).isEqualTo(DownloadState.Ready)
            assertThat(transferred).isTrue()
            val file = files[0]
            assertThat(sha256Hex(file)).isEqualTo(model.sha256)
            val engine = RemoteEngine(context)
            try {
                assertThat(engine.load(file.path, 4)).isEqualTo(0)
                val result = engine.transcribe(jfk(), 0, JFK_SAMPLES, "en")
                Log.i(TAG, "real_download transcribe status=${result.status} encodeMs=${result.encodeMs}")
                assertThat(result.status).isEqualTo(0)
                assertThat(normalizeTranscript(result.text)).isEqualTo(JFK_TEXT)
            } finally {
                engine.unload()
            }
        } finally {
            files.forEach { it.delete() }
            AppGraph.modelStore.delete(model)
            aside.listFiles()?.forEach { it.renameTo(File(dir, it.name)) }
            aside.delete()
        }
    }

    private fun jfk(): String {
        val file = File(context.cacheDir, "jfk.wav")
        instrumentation.context.assets.open("audio/jfk.wav").use { input -> file.outputStream().use { input.copyTo(it) } }
        return file.path
    }

    private companion object {
        const val TAG = "ThumbFree"
        const val JFK_SAMPLES = 176_000L // 11.0 s at 16 kHz
    }
}
