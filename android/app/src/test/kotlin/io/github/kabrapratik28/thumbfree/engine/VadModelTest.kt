package io.github.kabrapratik28.thumbfree.engine

import com.google.common.truth.Truth.assertThat
import io.github.kabrapratik28.thumbfree.core.models.sha256Hex
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class VadModelTest {
    @get:Rule val tmp = TemporaryFolder()
    private val app = RuntimeEnvironment.getApplication()

    // The APK ships the pinned file: ggml-silero-v6.2.0.bin of huggingface.co/ggml-org/whisper-vad at 9ffd54a.
    @Test
    fun theAssetIsThePinnedModel() {
        // Unit tests run with the module directory (app/) as the working directory.
        val asset = File("src/main/assets/${VadModel.ASSET}")

        assertThat(asset.length()).isEqualTo(VadModel.SIZE)
        assertThat(sha256Hex(asset)).isEqualTo(VadModel.SHA256)
    }

    // Copied on first use and used as it is after; a file that is not the pinned model, same size or cut short, is
    // copied again; no temporary file stays behind.
    @Test
    fun copiesTheAssetAndReplacesABadCopy() {
        val file = VadModel.file(app, tmp.root)!!
        assertThat(sha256Hex(file)).isEqualTo(VadModel.SHA256)
        file.setLastModified(0)
        assertThat(VadModel.file(app, tmp.root)!!.lastModified()).isEqualTo(0) // not copied again

        for (bad in listOf(ByteArray(VadModel.SIZE.toInt()), ByteArray(10))) {
            file.writeBytes(bad)
            assertThat(sha256Hex(VadModel.file(app, tmp.root)!!)).isEqualTo(VadModel.SHA256)
        }
        assertThat(tmp.root.list()!!.toList()).containsExactly(file.name)
    }

    // Silero's setup fails open. However preparing or loading it fails or throws, open() returns 0 without throwing and
    // logs only a reason code. EngineService keeps that 0, its model load succeeds, and a speech check with no VAD
    // passes every transcribe through (SpeechCheckTest.failsOpen).
    @Test
    fun setupThatFailsOrThrowsLeavesNoVadAndThrowsNothing() {
        val logs = mutableListOf<String>()
        assertThat(VadModel.open(app, { 7L }, tmp.root, logs::add)).isEqualTo(7L) // copied, then loaded
        val copy = File(tmp.root, VadModel.ASSET.substringAfter('/'))

        copy.setReadable(false) // the hash read of the copy throws
        assertThat(VadModel.open(app, { 7L }, tmp.root, logs::add)).isEqualTo(0L)
        copy.setReadable(true)
        assertThat(VadModel.open(app, { throw IllegalStateException("native") }, tmp.root, logs::add)).isEqualTo(0L)
        assertThat(VadModel.open(app, { 0L }, tmp.root, logs::add)).isEqualTo(0L)

        assertThat(logs).containsExactly(
            "speech_check_unavailable asset", "speech_check_unavailable load", "speech_check_unavailable load",
        ).inOrder()
    }
}
