package dev.gridiron.core.ingest

import java.io.File

/** Serves canned bytes by URL; unknown URLs are 404s. Records every call. */
internal class FakeFetcher : Fetcher {
    private val files = mutableMapOf<String, Pair<ByteArray, Validators>>()
    val calls = mutableListOf<Pair<String, Validators?>>()
    var onFetch: suspend (url: String) -> Unit = {}

    fun serve(url: String, bytes: ByteArray, version: String) {
        files[url] = bytes to Validators("\"$version\"", null)
    }

    fun remove(url: String) {
        files.remove(url)
    }

    override suspend fun fetch(
        url: String,
        dest: File,
        previous: Validators?,
        onBytes: (read: Long, total: Long) -> Unit,
    ): FetchResult {
        calls += url to previous
        onFetch(url)
        val (bytes, validators) = files[url] ?: return FetchResult.NotPublished
        if (previous == validators) return FetchResult.NotModified
        dest.parentFile?.mkdirs()
        dest.writeBytes(bytes)
        onBytes(bytes.size.toLong(), bytes.size.toLong())
        return FetchResult.Downloaded(dest, validators, bytes.size.toLong())
    }
}
