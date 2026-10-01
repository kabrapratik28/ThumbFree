package io.github.kabrapratik28.thumbfree.build

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test

class CorePurityTest {
    @Test
    fun noAndroidImportsInCore() {
        // core.* is plain Kotlin so all of it runs in host tests. Unit tests run in app/.
        val files = File("src/main/kotlin/io/github/kabrapratik28/thumbfree/core").walk().filter { it.extension == "kt" }.toList()
        assertThat(files.size).isAtLeast(8)

        val offenders = files.filter { file -> file.readLines().any { it.trimStart().startsWith("import android.") } }
        assertThat(offenders).isEmpty()
    }
}
