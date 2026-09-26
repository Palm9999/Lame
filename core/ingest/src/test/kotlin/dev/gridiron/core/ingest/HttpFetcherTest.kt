package dev.gridiron.core.ingest

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.util.Collections

class HttpFetcherTest {
    @TempDir
    lateinit var dir: File

    private lateinit var server: HttpServer
    private val body = "a,b\n1,2\n".repeat(20_000).toByteArray()
    private val seenOnFile: MutableList<String?> = Collections.synchronizedList(mutableListOf())
    private val fetcher = HttpFetcher()

    @BeforeEach
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/file") { ex ->
            val tag = ex.requestHeaders.getFirst("If-None-Match")
            seenOnFile += tag
            if (tag == "\"v1\"") {
                ex.sendResponseHeaders(304, -1)
            } else {
                ex.responseHeaders.add("ETag", "\"v1\"")
                ex.responseHeaders.add("Last-Modified", "Thu, 13 Aug 2026 12:26:09 GMT")
                ex.sendResponseHeaders(200, body.size.toLong())
                ex.responseBody.use { it.write(body) }
            }
            ex.close()
        }
        server.createContext("/redirect") { ex ->
            ex.responseHeaders.add("Location", "/file")
            ex.sendResponseHeaders(302, -1)
            ex.close()
        }
        server.createContext("/missing") { ex ->
            ex.sendResponseHeaders(404, -1)
            ex.close()
        }
        server.createContext("/broken") { ex ->
            ex.sendResponseHeaders(500, -1)
            ex.close()
        }
        server.createContext("/short") { ex ->
            // Promises 1000 bytes, sends 10, hangs up: a dropped connection.
            ex.sendResponseHeaders(200, 1000)
            runCatching {
                ex.responseBody.write(ByteArray(10))
                ex.responseBody.flush()
            }
            ex.close()
        }
        server.start()
    }

    @AfterEach
    fun stop() = server.stop(0)

    private fun url(path: String) = "http://127.0.0.1:${server.address.port}$path"

    @Test
    fun `downloads the file and returns its validators`() = runTest {
        val dest = File(dir, "x.csv")
        var last = 0L
        val result = fetcher.fetch(url("/file"), dest, previous = null) { read, _ -> last = read }
        result as FetchResult.Downloaded
        assertArrayEquals(body, dest.readBytes())
        assertEquals(Validators("\"v1\"", "Thu, 13 Aug 2026 12:26:09 GMT"), result.validators)
        assertEquals(body.size.toLong(), result.bytes)
        assertEquals(body.size.toLong(), last)
    }

    @Test
    fun `an unchanged file is not downloaded again`() = runTest {
        val dest = File(dir, "x.csv")
        val result = fetcher.fetch(url("/file"), dest, Validators("\"v1\"", null)) { _, _ -> }
        assertEquals(FetchResult.NotModified, result)
        assertFalse(dest.exists())
    }

    @Test
    fun `redirects carry the conditional headers to the final host`() = runTest {
        val result = fetcher.fetch(url("/redirect"), File(dir, "x.csv"), Validators("\"v1\"", null)) { _, _ -> }
        assertEquals(FetchResult.NotModified, result)
        assertEquals(listOf<String?>("\"v1\""), seenOnFile.toList())
    }

    @Test
    fun `a 404 means not published and leaves the destination alone`() = runTest {
        val dest = File(dir, "x.csv").apply { writeText("old") }
        assertEquals(FetchResult.NotPublished, fetcher.fetch(url("/missing"), dest, null) { _, _ -> })
        assertEquals("old", dest.readText())
    }

    @Test
    fun `a server error fails`() = runTest {
        val e = runCatching { fetcher.fetch(url("/broken"), File(dir, "x.csv"), null) { _, _ -> } }.exceptionOrNull()
        assertTrue(e is IOException, "got $e")
    }

    @Test
    fun `an interrupted download leaves nothing behind`() = runTest {
        val dest = File(dir, "x.csv")
        val e = runCatching { fetcher.fetch(url("/short"), dest, null) { _, _ -> } }.exceptionOrNull()
        assertTrue(e is IOException, "got $e")
        assertFalse(dest.exists())
        assertEquals(emptyList<String>(), dir.list()!!.toList())
    }
}
