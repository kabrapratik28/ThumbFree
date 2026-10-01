package io.github.kabrapratik28.thumbfree.ui

import android.Manifest
import android.provider.Settings
import com.google.common.truth.Truth.assertThat
import io.github.kabrapratik28.thumbfree.core.models.Catalog
import io.github.kabrapratik28.thumbfree.core.models.ModelFile
import io.github.kabrapratik28.thumbfree.core.models.ModelStatus
import io.github.kabrapratik28.thumbfree.core.models.ModelStore
import java.io.File
import java.io.RandomAccessFile
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class SetupStateTest {
    private val context = RuntimeEnvironment.getApplication()

    @Test
    fun readsGrants() {
        shadowOf(context).denyPermissions(Manifest.permission.RECORD_AUDIO)
        putServices("com.example/.Other")

        val missing = SetupState.read(context, ModelStatus.VERIFIED)
        assertThat(missing.micGranted).isFalse()
        assertThat(missing.serviceEnabled).isFalse()
        assertThat(missing.model).isEqualTo(ModelStatus.VERIFIED)

        shadowOf(context).grantPermissions(Manifest.permission.RECORD_AUDIO)
        putServices("com.example/.Other:io.github.kabrapratik28.thumbfree/io.github.kabrapratik28.thumbfree.a11y.DictationAccessibilityService")

        val granted = SetupState.read(context, ModelStatus.VERIFIED)
        assertThat(granted.micGranted).isTrue()
        assertThat(granted.serviceEnabled).isTrue()
    }

    // The refresh reads the model status in a coroutine nothing guards, where an exception would end the app. A check
    // that throws shows on the model line instead.
    @Test
    fun failedModelCheckIsShownNotThrown() {
        val dir = File(context.filesDir, "models-test").apply { mkdirs() }
        File(dir, "m.gguf").writeText("abc")
        val model = ModelFile("t", "m.gguf", 3, "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", listOf("en"))
        val store = ModelStore(dir) { throw IllegalStateException("the hash broke") }

        assertThat(SetupState.modelStatus(store, model)).isEqualTo(ModelStatus.CHECK_FAILED)
    }

    // The owner can switch only to a model whose file ModelStore verified.
    @Test
    fun onlyVerifiedModelsAreOffered() {
        val setup = SetupState(true, true, null)

        assertThat(setup.withModels(store(verified = listOf(parakeet))).offered).containsExactly(parakeet) // Canary absent
        assertThat(setup.withModels(store(verified = listOf(parakeet), corrupt = listOf(canary))).offered)
            .containsExactly(parakeet)
        assertThat(setup.withModels(store(verified = listOf(canary), corrupt = listOf(parakeet))).offered)
            .containsExactly(canary)
        assertThat(setup.withModels(store(verified = listOf(parakeet, canary))).offered)
            .containsExactly(parakeet, canary).inOrder()
    }

    // A file of the wrong size, or a check that throws, is not offered either.
    @Test
    fun wrongSizeAndFailedChecksAreNotOffered() {
        val setup = SetupState(true, true, null)

        val wrongSize = setup.withModels(store(verified = emptyList(), short = listOf(parakeet, canary)))
        assertThat(wrongSize.offered).isEmpty()
        assertThat(wrongSize.model).isEqualTo(ModelStatus.WRONG_SIZE)

        val failed = setup.withModels(ModelStore(dir(listOf(parakeet, canary))) { throw IllegalStateException("the hash broke") })
        assertThat(failed.offered).isEmpty()
        assertThat(failed.model).isEqualTo(ModelStatus.CHECK_FAILED)
    }

    @Test
    fun modelLineShowsTheChosenModel() {
        val store = store(verified = listOf(canary)) // Parakeet absent

        assertThat(SetupState(true, true, null).withModels(store).model).isEqualTo(ModelStatus.MISSING)
        assertThat(SetupState(true, true, null, chosen = canary).withModels(store).model)
            .isEqualTo(ModelStatus.VERIFIED)
    }

    // A take needs the microphone, the service and a verified model, and nothing else.
    @Test
    fun readyNeedsMicServiceAndModel() {
        val ready = SetupState(micGranted = true, serviceEnabled = true, model = ModelStatus.VERIFIED)
        assertThat(ready.ready).isTrue()
        assertThat(ready.copy(micGranted = false).ready).isFalse()
        assertThat(ready.copy(serviceEnabled = false).ready).isFalse()
        for (status in ModelStatus.entries - ModelStatus.VERIFIED) assertThat(ready.copy(model = status).ready).isFalse()
        assertThat(ready.copy(model = null).ready).isFalse() // still checking
    }

    private val parakeet = Catalog.PARAKEET_UNIFIED_Q8
    private val canary = Catalog.CANARY_180M_FLASH_Q8

    /** The hash is the pinned one for [verified] and wrong for [corrupt]; [short] files are one byte short. */
    private fun store(verified: List<ModelFile>, corrupt: List<ModelFile> = emptyList(), short: List<ModelFile> = emptyList()) =
        ModelStore(dir(verified + corrupt, short)) { file -> verified.firstOrNull { it.fileName == file.name }?.sha256 ?: "0".repeat(64) }

    /** Sparse files, so no real space: the pinned size for [right], one byte less for [short]. */
    private fun dir(right: List<ModelFile>, short: List<ModelFile> = emptyList()): File {
        val dir = File(context.cacheDir, "models-${System.nanoTime()}").apply { mkdirs() }
        for (model in right) RandomAccessFile(File(dir, model.fileName), "rw").use { it.setLength(model.sizeBytes) }
        for (model in short) RandomAccessFile(File(dir, model.fileName), "rw").use { it.setLength(model.sizeBytes - 1) }
        return dir
    }

    private fun putServices(value: String) {
        Settings.Secure.putString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, value)
    }
}
