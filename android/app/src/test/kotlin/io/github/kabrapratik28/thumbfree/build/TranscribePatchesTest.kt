package io.github.kabrapratik28.thumbfree.build

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import java.nio.file.Files
import java.util.Properties
import org.junit.Test

class TranscribePatchesTest {
    private val submodule = File("../../third_party/transcribe.cpp").canonicalFile
    private val patches = File("../../third_party/patches").canonicalFile
    private val series = patches.listFiles { f -> f.name.endsWith(".patch") }!!.sorted()

    // The commit this repository pins: the submodule's gitlink, not whatever the submodule has checked out.
    private val pinned =
        run(File("../.."), "git", "ls-files", "--stage", "--", "third_party/transcribe.cpp").split(" ")[1]

    // The native build includes transcribe-patches.cmake to patch the pinned submodule (docs/decisions/native-build.md).
    // On a clone of the pinned commit it must apply the whole series, then change nothing, and finish a tree that
    // has only the first patch.
    @Test
    fun appliesTheWholeSeriesOnceFromAnyPrefix() = withClone { tree ->
        assertThat(patch(tree).first).isEqualTo(0)
        val applied = run(tree, "git", "diff")
        assertThat(applied).isNotEmpty()
        run(tree, "sh", "-c", "cat ${series.joinToString(" ")} | git apply --reverse --check")
        assertThat(patch(tree).first).isEqualTo(0)
        assertThat(run(tree, "git", "diff")).isEqualTo(applied)

        run(tree, "git", "checkout", "--quiet", "--", ".")
        run(tree, "git", "apply", series.first().path)
        assertThat(patch(tree).first).isEqualTo(0)
        assertThat(run(tree, "git", "diff")).isEqualTo(applied)
    }

    // A build must never go on with a different engine than the series: not after a patch left the series while it
    // was still applied, and not when the patch directory is empty or wrong.
    @Test
    fun stopsWhenTheTreeIsNotThePinnedCommitPlusTheSeries() = withClone { tree ->
        assertThat(patch(tree).first).isEqualTo(0)
        val shorter = Files.createTempDirectory("patches").toFile()
        try {
            series.dropLast(1).forEach { it.copyTo(File(shorter, it.name)) }
            val (status, output) = patch(tree, shorter)
            assertThat(status).isNotEqualTo(0)
            // The fix is a line of its own with the checkout's absolute path, so it pastes into a shell in any folder
            // (Gradle runs in android/).
            assertThat(output.lines().map { it.trim() }).contains("git -C ${tree.canonicalPath} checkout -- .")

            shorter.listFiles()!!.forEach { it.delete() }
            val (emptyStatus, emptyOutput) = patch(tree, shorter)
            assertThat(emptyStatus).isNotEqualTo(0)
            assertThat(emptyOutput).contains("No patches")
        } finally {
            shorter.deleteRecursively()
        }

        // An uninitialized submodule: a plain directory inside some other checkout.
        val (status, output) = patch(tree, checkout = File(tree, "src"))
        assertThat(status).isNotEqualTo(0)
        assertThat(output.lines().map { it.trim() }).contains("git submodule update --init")
    }

    // Nor with another engine commit, even one the series applies to, whose files then match it plus the series.
    @Test
    fun stopsOnAnEngineCommitOtherThanThePinnedOne() = withClone { tree ->
        File(tree, "README.md").appendText("\nnot the pinned engine\n")
        run(tree, "git", "-c", "user.name=test", "-c", "user.email=test@localhost", "commit", "--quiet", "-am", "other")
        run(tree, "sh", "-c", "cat ${series.joinToString(" ")} | git apply --check")

        val (status, output) = patch(tree)
        assertThat(status).isNotEqualTo(0)
        assertThat(output).contains("not the pinned commit $pinned")
        assertThat(output.lines().map { it.trim() })
            .contains("git -C ${tree.parentFile.canonicalPath} submodule update -- transcribe.cpp")
        assertThat(run(tree, "git", "diff")).isEmpty()
    }

    // A clone of the pinned commit inside a scratch superproject whose gitlink pins it, as this repository does.
    private fun withClone(test: (File) -> Unit) {
        val root = Files.createTempDirectory("transcribe").toFile()
        try {
            run(root, "git", "init", "--quiet")
            val tree = File(root, "transcribe.cpp").apply { mkdir() }
            run(tree, "git", "clone", "--quiet", "--shared", "--no-checkout", submodule.path, ".")
            run(tree, "git", "checkout", "--quiet", pinned)
            run(root, "git", "update-index", "--add", "--cacheinfo", "160000,$pinned,transcribe.cpp")
            test(tree)
        } finally {
            root.deleteRecursively()
        }
    }

    /** Runs transcribe-patches.cmake from [tree] on [checkout] with the patches in [dir]; returns its status and output. */
    private fun patch(tree: File, dir: File = patches, checkout: File = tree): Pair<Int, String> {
        val sdk = Properties().apply { File("../local.properties").reader().use { load(it) } }.getProperty("sdk.dir")
        val cmake = File(sdk, "cmake").listFiles()!!.maxBy { it.name }.resolve("bin/cmake")
        return exec(tree, cmake.path, "-DTRANSCRIBE_DIR=${checkout.path}", "-DTRANSCRIBE_PATCH_DIR=${dir.path}", "-P",
            File(patches, "transcribe-patches.cmake").path)
    }

    private fun run(dir: File, vararg command: String): String {
        val (status, output) = exec(dir, *command)
        assertWithMessage("%s", "${command.joinToString(" ")}\n$output").that(status).isEqualTo(0)
        return output
    }

    private fun exec(dir: File, vararg command: String): Pair<Int, String> {
        val process = ProcessBuilder(*command).directory(dir).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        return process.waitFor() to output
    }
}
