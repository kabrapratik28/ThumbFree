package io.github.kabrapratik28.thumbfree.build

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test

class CmakeFlagsTest {
    @Test
    fun noNativeFlags() {
        // Host-tuned builds crash on other CPUs. Unit tests run in android/app/.
        val cmake = File("src/main/cpp/CMakeLists.txt").readText()

        assertThat(cmake).containsMatch("""set\(GGML_NATIVE OFF\b""")
        assertThat(cmake).containsMatch("""set\(GGML_OPENMP OFF\b""")
        assertThat(cmake).doesNotContain("-march=native")
    }

    @Test
    fun releaseBuildsTheDebugEngine() {
        // RelWithDebInfo's default -O2 crashes NDK 30's clang on ggml's SVE2 and SME kernels, and release should ship
        // the engine the tests ran: the debug flags.
        val cmake = File("src/main/cpp/CMakeLists.txt").readText()

        for (lang in listOf("C", "CXX")) {
            assertThat(cmake).contains("""set(CMAKE_${lang}_FLAGS_DEBUG "-O3")""")
            assertThat(cmake).contains("""set(CMAKE_${lang}_FLAGS_RELWITHDEBINFO "-O3")""")
        }
    }
}
