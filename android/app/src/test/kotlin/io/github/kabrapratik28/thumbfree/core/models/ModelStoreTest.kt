package io.github.kabrapratik28.thumbfree.core.models

import com.google.common.truth.Truth.assertThat
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import org.junit.Assume.assumeFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ModelStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    // sha256("abc")
    private val model = ModelFile(
        id = "t",
        fileName = "m.gguf",
        sizeBytes = 3,
        sha256 = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
        languages = listOf("en"),
    )

    @Test
    fun missing() {
        val store = ModelStore(tmp.root)

        assertThat(store.status(model)).isEqualTo(ModelStatus.MISSING)
        assertThat(store.verifiedPath(model)).isNull()
    }

    @Test
    fun wrongSize() {
        tmp.newFile("m.gguf").writeText("abcd")
        val store = ModelStore(tmp.root)

        assertThat(store.status(model)).isEqualTo(ModelStatus.WRONG_SIZE)
    }

    @Test
    fun corrupt() {
        tmp.newFile("m.gguf").writeText("abd")
        val store = ModelStore(tmp.root)

        assertThat(store.status(model)).isEqualTo(ModelStatus.CORRUPT)
    }

    @Test
    fun unreadableFileIsCorrupt() {
        val file = tmp.newFile("m.gguf").apply { writeText("abc"); setReadable(false) } // right bytes, unreadable
        assumeFalse(file.canRead()) // root reads it anyway
        val store = ModelStore(tmp.root)

        assertThat(store.status(model)).isEqualTo(ModelStatus.CORRUPT)
    }

    // The home screen's refresh reads the sidecar, where an exception would end the app. An unreadable sidecar is a
    // stale one.
    @Test
    fun unreadableSidecarIsHashedAgain() {
        val file = tmp.newFile("m.gguf").apply { writeText("abc") }
        val sidecar = tmp.newFile("m.gguf.verified").apply { writeText("${file.length()}:${file.lastModified()}") }
        sidecar.setReadable(false)
        assumeFalse(sidecar.canRead()) // root reads it anyway
        var calls = 0
        val store = ModelStore(tmp.root) { f -> calls++; sha256Hex(f) }

        assertThat(store.status(model)).isEqualTo(ModelStatus.VERIFIED)
        assertThat(store.status(model)).isEqualTo(ModelStatus.VERIFIED) // from memory, not a second hash
        assertThat(calls).isEqualTo(1)
    }

    // Storage full or an I/O error after a good hash: the sidecar is best effort. The good hash is kept in memory, so
    // the queue (once per take) and the home screen do not hash all 731 MB again on every check.
    @Test
    fun unwritableSidecarStillVerifies() {
        tmp.newFile("m.gguf").writeText("abc")
        tmp.root.setWritable(false)
        try {
            assumeFalse(runCatching { File(tmp.root, "probe").createNewFile() }.getOrDefault(false)) // root writes anyway
            var calls = 0
            val store = ModelStore(tmp.root) { f -> calls++; sha256Hex(f) }

            assertThat(store.status(model)).isEqualTo(ModelStatus.VERIFIED)
            assertThat(store.status(model)).isEqualTo(ModelStatus.VERIFIED)
            assertThat(calls).isEqualTo(1)
            assertThat(File(tmp.root, "m.gguf.verified").exists()).isFalse()
        } finally {
            tmp.root.setWritable(true) // so the rule can delete it
        }
    }

    @Test
    fun verified() {
        tmp.newFile("m.gguf").writeText("abc")
        val store = ModelStore(tmp.root)

        assertThat(store.status(model)).isEqualTo(ModelStatus.VERIFIED)
        assertThat(File(tmp.root, "m.gguf.verified").exists()).isTrue()
    }

    @Test
    fun hashComputedOnce() {
        tmp.newFile("m.gguf").writeText("abc")
        var calls = 0
        val store = ModelStore(tmp.root) { file -> calls++; sha256Hex(file) }

        store.status(model)
        store.status(model)

        assertThat(calls).isEqualTo(1)
    }

    @Test
    fun concurrentCallsHashOnce() {
        tmp.newFile("m.gguf").writeText("abc")
        val calls = AtomicInteger()
        val hashing = CountDownLatch(1)
        val release = CountDownLatch(1)
        val store = ModelStore(tmp.root) { file ->
            calls.incrementAndGet()
            hashing.countDown()
            release.await(5, TimeUnit.SECONDS)
            sha256Hex(file)
        }
        val first = thread { store.status(model) }
        hashing.await(5, TimeUnit.SECONDS)

        // Home asks again while the first hash runs: the second caller waits for that hash instead of starting its own.
        var second: ModelStatus? = null
        val waiter = thread { second = store.status(model) }
        val deadline = System.currentTimeMillis() + 5_000
        while (waiter.state != Thread.State.BLOCKED && calls.get() < 2 && System.currentTimeMillis() < deadline) Thread.sleep(1)
        release.countDown()
        first.join()
        waiter.join()

        assertThat(calls.get()).isEqualTo(1)
        assertThat(second).isEqualTo(ModelStatus.VERIFIED)
    }

    @Test
    fun markVerifiedSkipsHash() {
        val file = tmp.newFile("m.gguf").apply { writeText("abc") }
        var calls = 0
        val store = ModelStore(tmp.root) { f -> calls++; sha256Hex(f) }

        assertThat(store.markVerified(model, FileCheck(file.length(), file.lastModified(), model.sha256))).isTrue()

        assertThat(store.status(model)).isEqualTo(ModelStatus.VERIFIED)
        assertThat(calls).isEqualTo(0)
    }

    // Only the file the downloader hashed gets marked. A check with another digest (same size, other bytes), for
    // another size or time (the file changed since), or for a file that is not there records nothing.
    @Test
    fun markVerifiedRefusesAFileItsCheckDoesNotDescribe() {
        val file = tmp.newFile("m.gguf").apply { writeText("abd") }
        var calls = 0
        val store = ModelStore(tmp.root) { f -> calls++; sha256Hex(f) }

        assertThat(store.markVerified(model, FileCheck(file.length(), file.lastModified(), sha256Hex(file)))).isFalse()
        assertThat(store.markVerified(model, FileCheck(4, file.lastModified(), model.sha256))).isFalse()
        assertThat(store.markVerified(model, FileCheck(file.length(), file.lastModified() - 10_000, model.sha256))).isFalse()
        assertThat(ModelStore(tmp.newFolder("empty")).markVerified(model, FileCheck(3, 0, model.sha256))).isFalse()

        assertThat(store.status(model)).isEqualTo(ModelStatus.CORRUPT)
        assertThat(calls).isEqualTo(1) // status() had to hash: nothing was recorded
    }

    @Test
    fun deleteRemovesFileAndSidecar() {
        tmp.newFile("m.gguf").writeText("abc")
        val store = ModelStore(tmp.root)
        store.status(model) // writes the sidecar
        assertThat(File(tmp.root, "m.gguf.verified").exists()).isTrue()

        store.delete(model)

        assertThat(store.file(model).exists()).isFalse()
        assertThat(File(tmp.root, "m.gguf.verified").exists()).isFalse()
    }

    // With the in-memory verdict: a deleted file replaced by another of the same size and time must not inherit the old
    // file's verdict.
    @Test
    fun deleteForgetsTheVerdict() {
        val file = tmp.newFile("m.gguf").apply { writeText("abc") }
        val store = ModelStore(tmp.root)
        assertThat(store.status(model)).isEqualTo(ModelStatus.VERIFIED)
        val stamp = file.lastModified()

        store.delete(model)
        File(tmp.root, "m.gguf").apply { writeText("abd") }.setLastModified(stamp)

        assertThat(store.status(model)).isEqualTo(ModelStatus.CORRUPT)
    }

    // Storage full right after a download: markVerified is best effort like status(), so the finished work does not
    // fail, and the verdict holds in memory instead of costing a full hash at the next check.
    @Test
    fun markVerifiedWithoutASidecarStillSkipsTheHash() {
        tmp.newFile("m.gguf").writeText("abc")
        tmp.root.setWritable(false)
        try {
            assumeFalse(runCatching { File(tmp.root, "probe").createNewFile() }.getOrDefault(false)) // root writes anyway
            var calls = 0
            val store = ModelStore(tmp.root) { f -> calls++; sha256Hex(f) }

            assertThat(store.markVerified(model, FileCheck(3, File(tmp.root, "m.gguf").lastModified(), model.sha256))).isTrue()

            assertThat(store.status(model)).isEqualTo(ModelStatus.VERIFIED)
            assertThat(calls).isEqualTo(0)
        } finally {
            tmp.root.setWritable(true) // so the rule can delete it
        }
    }

    @Test
    fun touchedFileRehashes() {
        val file = tmp.newFile("m.gguf").apply { writeText("abc") }
        var calls = 0
        val store = ModelStore(tmp.root) { f -> calls++; sha256Hex(f) }

        store.status(model)
        file.setLastModified(file.lastModified() + 10_000)
        store.status(model)

        assertThat(calls).isEqualTo(2)
    }
}
