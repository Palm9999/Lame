package dev.gridiron.core.data.live

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.util.zip.GZIPInputStream

/** Fetches a URL's body as text; tests substitute canned responses. */
public fun interface HttpGet {
    public suspend fun get(url: String): String
}

/**
 * [HttpGet] over the JDK's connection. Asks for gzip (ESPN's injuries feed is
 * about 350 KB compressed, several MB plain) and throws [IOException] on any
 * status but 200.
 */
public class UrlConnectionHttpGet(
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 30_000,
) : HttpGet {
    override suspend fun get(url: String): String = withContext(Dispatchers.IO) {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = connectTimeoutMs
            connection.readTimeout = readTimeoutMs
            connection.setRequestProperty("Accept-Encoding", "gzip")
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) throw IOException("HTTP $code from ${connection.url.host}")
            val body = connection.inputStream
            val input = if ("gzip".equals(connection.contentEncoding, ignoreCase = true)) GZIPInputStream(body) else body
            input.use { it.readBytes().decodeToString() }
        } finally {
            connection.disconnect()
        }
    }
}
