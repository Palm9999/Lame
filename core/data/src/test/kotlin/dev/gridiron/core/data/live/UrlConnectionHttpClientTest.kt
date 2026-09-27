package dev.gridiron.core.data.live

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.IOException

class UrlConnectionHttpClientTest {
    private lateinit var server: HttpServer

    @BeforeEach
    fun start() {
        server = HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        server.start()
    }

    @AfterEach
    fun stop() {
        server.stop(0)
    }

    private fun url(path: String) = "http://127.0.0.1:${server.address.port}$path"

    private fun respond(path: String, status: Int, body: String, headers: Map<String, String> = emptyMap()) {
        server.createContext(path) { ex ->
            headers.forEach { (k, v) -> ex.responseHeaders.add(k, v) }
            val bytes = body.toByteArray()
            ex.sendResponseHeaders(status, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
    }

    @Test
    fun `a response keeps its status, body and headers`() = runTest {
        respond("/ok", 200, "[]", mapOf("X-Requests-Remaining" to "480"))

        val response = UrlConnectionHttpClient().get(url("/ok"))

        assertEquals(200, response.code)
        assertEquals("[]", response.body)
        assertEquals("480", response.header("x-requests-remaining"))
        assertEquals("480", response.header("X-Requests-Remaining"))
    }

    @Test
    fun `an error status is returned with its body, not thrown`() = runTest {
        respond("/denied", 401, """{"message": "API key is not valid"}""")

        val response = UrlConnectionHttpClient().get(url("/denied"))

        assertEquals(401, response.code)
        assertEquals("""{"message": "API key is not valid"}""", response.body)
    }

    @Test
    fun `a redirect is returned, not followed, so the key never reaches another host`() = runTest {
        var followed = false
        server.createContext("/elsewhere") { ex ->
            followed = true
            ex.sendResponseHeaders(200, -1)
            ex.close()
        }
        respond("/moved", 302, "", mapOf("Location" to url("/elsewhere")))

        val response = UrlConnectionHttpClient().get(url("/moved?apiKey=SECRET123"))

        assertEquals(302, response.code)
        assertFalse(followed)
    }

    @Test
    fun `no response at all is an IOException that doesn't repeat the URL`() = runTest {
        val dead = url("/events?apiKey=SECRET123")
        server.stop(0)

        val e = runCatching { UrlConnectionHttpClient(connectTimeoutMs = 2_000).get(dead) }.exceptionOrNull()

        assertEquals(IOException::class, e!!::class)
        assertEquals("couldn't reach 127.0.0.1", e.message)
        assertFalse("SECRET123" in e.toString())
    }
}
