package io.github.kabrapratik28.thumbfree.build

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test

/**
 * INTERNET is for model downloads only: audio and text never leave the phone. A build-time regression guard, not a
 * sandbox: Android grants INTERNET to the whole app, so nothing enforces this at run time. It catches a second way out
 * added to our own sources or to the engine's build; ManifestContractTest pins the merged permissions.
 */
class NetworkRegressionGuardTest {
    // Unit tests run with the module directory (android/app/) as the working directory.
    private val main = File("src/main")

    // A socket, an HTTP client or a WebView anywhere but the downloader would be a second way out.
    @Test
    fun guardOnlyTheDownloaderNamesANetworkApi() {
        val network = Regex("""\b(java\.net|javax\.net|okhttp3|android\.net\.http|android\.webkit)\.|\b(Socket|DatagramSocket|DatagramChannel|SocketChannel)\b""")
        val sources = main.walk().filter { it.extension in setOf("kt", "java", "aidl") }.toList()

        val using = sources.filter { network.containsMatchIn(it.readText()) }.map { it.relativeTo(main).invariantSeparatorsPath }

        assertThat(sources.size).isAtLeast(10)
        assertThat(using).containsExactly("kotlin/io/github/kabrapratik28/thumbfree/core/models/Downloader.kt")
    }

    // Our native sources (the JNI bridge and the VAD) include no socket or resolver header and no HTTP library.
    @Test
    fun guardNativeSourcesOpenNoConnections() {
        val network = Regex("""#\s*include\s*<(sys/socket|netdb|netinet/in|arpa/inet)\.h>|\bcurl\b""")
        val sources = File(main, "cpp").walk().filter { it.extension in setOf("c", "cc", "cpp", "h", "hpp") }.toList()

        assertThat(sources).isNotEmpty()
        assertThat(sources.filter { network.containsMatchIn(it.readText()) }).isEmpty()
    }

    // ggml's RPC backend is the one socket client in the engine's tree: forced off, not left to its default.
    @Test
    fun guardGgmlRpcIsForcedOff() {
        assertThat(File(main, "cpp/CMakeLists.txt").readText()).containsMatch("""set\(GGML_RPC OFF CACHE BOOL "" FORCE\)""")
    }
}
