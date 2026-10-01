package io.github.kabrapratik28.thumbfree.build

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test

class EngineIsolationTest {
    // Only :engine may load the native engine, so a native abort or a low-memory kill never takes the main process.
    @Test
    fun nativeEngineOnlyReferencedByEngineService() {
        // Unit tests run with the module directory (app/) as the working directory.
        val root = File("src/main/kotlin/io/github/kabrapratik28/thumbfree")
        val files = File("src/main/kotlin").walk().filter { it.extension == "kt" }.toList()
        val word = Regex("""\bNativeEngine\b""")

        val referencing = files.filter { word.containsMatchIn(it.readText()) }
            .map { it.relativeTo(root).invariantSeparatorsPath }

        assertThat(files.size).isAtLeast(10)
        assertThat(referencing).containsExactly("engine/NativeEngine.kt", "engine/EngineService.kt")
    }
}
