package io.github.kabrapratik28.thumbfree.core.models

import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.concurrent.ConcurrentLinkedQueue

/** The file every test downloads: 1 MiB of deterministic, non-repeating bytes. */
val BODY: ByteArray = ByteArray(1_048_576) { (it * 31 % 251).toByte() }

private const val ETAG = "\"v1\""

/**
 * One canned response a test can queue in place of the server's default Range-aware behavior.
 * [closeAfterBytes] sends only that many body bytes then drops the connection.
 * [stallAfterBytes] sends that many body bytes, sleeps [stallForMs], then sends the rest.
 * [chunkBytes] sends the body in that many bytes at a time, flushing (and briefly pacing)
 * between pieces, so a client's reads land in small pieces like Android/OkHttp's do.
 */
data class CannedResponse(
    val status: Int = 200,
    val headers: Map<String, String> = mapOf("ETag" to ETAG),
    val body: ByteArray = BODY,
    val closeAfterBytes: Int? = null,
    val stallAfterBytes: Int? = null,
    val stallForMs: Long = 0,
    val chunkBytes: Int? = null,
)

/**
 * A minimal HTTP/1.1 server on a raw ServerSocket: the JDK's com.sun.net.httpserver isn't on the
 * Android unit-test compile classpath. Accepts on a background thread and hands each connection
 * to its own daemon thread, so one stalled or slow response can't block the next connection from
 * being accepted. Default behavior serves [BODY] with ETag "v1", honors `Range`, and answers a
 * stale `If-Range` with a full 200. A test overrides upcoming responses with [enqueue] (one-shot,
 * FIFO) or by setting [sticky] (repeats for every request until cleared).
 */
class TestHttpServer : AutoCloseable {
    private val serverSocket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    val port: Int get() = serverSocket.localPort

    private val queue = ConcurrentLinkedQueue<CannedResponse>()
    @Volatile var sticky: CannedResponse? = null

    /** Each request's headers, names lowercased, in arrival order. */
    val requests: MutableList<Map<String, String>> = Collections.synchronizedList(mutableListOf())

    private val thread = Thread {
        while (!serverSocket.isClosed) {
            val client = try {
                serverSocket.accept()
            } catch (e: IOException) {
                break // socket closed under us: shutting down
            }
            // A per-connection thread: a stalled/slow response must not block accept() from
            // picking up the next connection, e.g. a client that gives up and retries while
            // this one is still being handled.
            Thread {
                client.use {
                    try {
                        handle(it)
                    } catch (e: IOException) {
                        // client hung up early (e.g. it gave up mid-response); nothing to do
                    }
                }
            }.apply { isDaemon = true; start() }
        }
    }.apply { isDaemon = true; start() }

    fun enqueue(response: CannedResponse) = queue.add(response)

    fun url(path: String = "/m.gguf") = "http://127.0.0.1:$port$path"

    override fun close() {
        serverSocket.close()
        thread.join(2_000)
    }

    private fun handle(client: Socket) {
        val reader = BufferedReader(InputStreamReader(client.getInputStream(), StandardCharsets.ISO_8859_1))
        reader.readLine() ?: return // request line; path is irrelevant, there's only ever one file

        val headers = mutableMapOf<String, String>()
        while (true) {
            val line = reader.readLine()
            if (line.isNullOrEmpty()) break
            val colon = line.indexOf(':')
            if (colon < 0) continue
            headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
        }
        requests.add(headers)

        val canned = queue.poll() ?: sticky
        respond(client.getOutputStream(), canned ?: defaultResponse(headers))
    }

    private fun defaultResponse(headers: Map<String, String>): CannedResponse {
        val range = headers["range"]
        val ifRange = headers["if-range"]
        if (range == null || (ifRange != null && ifRange != ETAG)) {
            return CannedResponse(status = 200, body = BODY)
        }
        val start = range.removePrefix("bytes=").substringBefore('-').trim().toLongOrNull()
        if (start == null || start < 0 || start >= BODY.size) {
            return CannedResponse(status = 416, headers = emptyMap(), body = ByteArray(0))
        }
        return CannedResponse(
            status = 206,
            headers = mapOf("ETag" to ETAG, "Content-Range" to "bytes $start-${BODY.size - 1}/${BODY.size}"),
            body = BODY.copyOfRange(start.toInt(), BODY.size),
        )
    }

    private fun respond(output: OutputStream, canned: CannedResponse) {
        try {
            writeHead(output, canned)
            when {
                canned.closeAfterBytes != null -> {
                    output.write(canned.body, 0, canned.closeAfterBytes)
                    output.flush()
                    // abrupt: deliberately no more bytes; the socket closes when handle() returns
                }
                canned.stallAfterBytes != null -> {
                    output.write(canned.body, 0, canned.stallAfterBytes)
                    output.flush()
                    Thread.sleep(canned.stallForMs)
                    output.write(canned.body, canned.stallAfterBytes, canned.body.size - canned.stallAfterBytes)
                    output.flush()
                }
                canned.chunkBytes != null -> {
                    var offset = 0
                    while (offset < canned.body.size) {
                        val len = minOf(canned.chunkBytes, canned.body.size - offset)
                        output.write(canned.body, offset, len)
                        output.flush()
                        offset += len
                        Thread.sleep(1) // paces sends so the client's reads don't coalesce into one
                    }
                }
                else -> {
                    output.write(canned.body)
                    output.flush()
                }
            }
        } catch (e: IOException) {
            // client already gave up reading; nothing more to do
        }
    }

    private fun writeHead(output: OutputStream, canned: CannedResponse) {
        val text = STATUS_TEXT[canned.status] ?: "Unknown"
        val head = StringBuilder()
            .append("HTTP/1.1 ${canned.status} $text\r\n")
            .append("Connection: close\r\n")
            .append("Content-Length: ${canned.body.size}\r\n")
        for ((name, value) in canned.headers) head.append("$name: $value\r\n")
        head.append("\r\n")
        output.write(head.toString().toByteArray(StandardCharsets.ISO_8859_1))
    }

    private companion object {
        val STATUS_TEXT = mapOf(
            200 to "OK",
            206 to "Partial Content",
            302 to "Found",
            416 to "Range Not Satisfiable",
            500 to "Internal Server Error",
        )
    }
}
