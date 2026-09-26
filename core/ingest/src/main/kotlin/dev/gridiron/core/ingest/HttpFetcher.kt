package dev.gridiron.core.ingest

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI

/**
 * [Fetcher] over HttpURLConnection. Follows redirects itself so the "changed
 * since?" headers reach every hop: GitHub release downloads redirect to a file
 * host, and that host is the one that answers 304.
 */
public class HttpFetcher(
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 60_000,
) : Fetcher {

    override suspend fun fetch(
        url: String,
        dest: File,
        previous: Validators?,
        onBytes: (read: Long, total: Long) -> Unit,
    ): FetchResult = withContext(Dispatchers.IO) {
        var location = URI(url)
        repeat(MAX_REDIRECTS + 1) {
            val conn = (location.toURL().openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                // Stored bytes with a true Content-Length; .gz assets stay compressed.
                setRequestProperty("Accept-Encoding", "identity")
                previous?.etag?.let { setRequestProperty("If-None-Match", it) }
                previous?.lastModified?.let { setRequestProperty("If-Modified-Since", it) }
            }
            try {
                val code = conn.responseCode
                when {
                    code == HttpURLConnection.HTTP_NOT_MODIFIED -> return@withContext FetchResult.NotModified
                    code in 300..399 -> {
                        val next = conn.getHeaderField("Location")
                            ?: throw IOException("HTTP $code without a Location from $location")
                        location = location.resolve(next)
                    }
                    code == HttpURLConnection.HTTP_NOT_FOUND -> return@withContext FetchResult.NotPublished
                    code == HttpURLConnection.HTTP_OK -> return@withContext save(conn, dest, onBytes)
                    else -> throw IOException("HTTP $code from $location")
                }
            } finally {
                conn.disconnect()
            }
        }
        throw IOException("too many redirects from $url")
    }

    private fun CoroutineScope.save(
        conn: HttpURLConnection,
        dest: File,
        onBytes: (read: Long, total: Long) -> Unit,
    ): FetchResult {
        val total = conn.contentLengthLong
        dest.parentFile?.mkdirs()
        val part = File(dest.path + ".part")
        var read = 0L
        try {
            conn.inputStream.use { input ->
                part.outputStream().use { out ->
                    val buf = ByteArray(1 shl 16)
                    while (true) {
                        ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        read += n
                        onBytes(read, total)
                    }
                }
            }
            if (total >= 0 && read != total) throw IOException("${dest.name} stopped at $read of $total bytes")
            dest.delete()
            if (!part.renameTo(dest)) throw IOException("couldn't move ${dest.name} into place")
        } catch (t: Throwable) {
            part.delete()
            throw t
        }
        val validators = Validators(conn.getHeaderField("ETag"), conn.getHeaderField("Last-Modified"))
        return FetchResult.Downloaded(dest, validators, read)
    }

    private companion object {
        const val MAX_REDIRECTS = 5
    }
}
