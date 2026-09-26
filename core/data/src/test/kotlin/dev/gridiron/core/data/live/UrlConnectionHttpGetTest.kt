package dev.gridiron.core.data.live

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.util.zip.GZIPOutputStream

class UrlConnectionHttpGetTest {
    private lateinit var server: HttpServer

    @BeforeEach
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.start()
    }

    @AfterEach
    fun stop() {
        server.stop(0)
    }

    private fun url(path: String) = "http://127.0.0.1:${server.address.port}$path"

    private fun respond(path: String, status: Int, body: ByteArray, gzip: Boolean = false) {
        server.createContext(path) { ex ->
            if (gzip) ex.responseHeaders.add("Content-Encoding", "gzip")
            ex.sendResponseHeaders(status, if (body.isEmpty()) -1 else body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
    }

    @Test
    fun `reads a plain body`() = runTest {
        respond("/plain", 200, """{"ok":true}""".toByteArray())
        assertEquals("""{"ok":true}""", UrlConnectionHttpGet().get(url("/plain")))
    }

    @Test
    fun `reads a gzipped body`() = runTest {
        val bytes = ByteArrayOutputStream()
        GZIPOutputStream(bytes).use { it.write("""{"zipped":true}""".toByteArray()) }
        respond("/gz", 200, bytes.toByteArray(), gzip = true)
        assertEquals("""{"zipped":true}""", UrlConnectionHttpGet().get(url("/gz")))
    }

    @Test
    fun `an error status is an IOException naming the code`() = runTest {
        respond("/down", 503, ByteArray(0))
        val e = runCatching { UrlConnectionHttpGet().get(url("/down")) }.exceptionOrNull()
        assertTrue(e is IOException && e.message.orEmpty().contains("503"), "got $e")
    }
}
