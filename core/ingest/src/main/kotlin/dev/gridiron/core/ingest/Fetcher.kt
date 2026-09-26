package dev.gridiron.core.ingest

import java.io.File

/** An HTTP response's version tags, sent back later to ask "changed since?". */
public data class Validators(public val etag: String?, public val lastModified: String?) {
    public fun encode(): String = "${etag.orEmpty()}\n${lastModified.orEmpty()}"

    public companion object {
        public fun decode(text: String): Validators? {
            val parts = text.split('\n', limit = 2)
            val etag = parts[0].ifEmpty { null }
            val lastModified = parts.getOrElse(1) { "" }.ifEmpty { null }
            return if (etag == null && lastModified == null) null else Validators(etag, lastModified)
        }
    }
}

public sealed interface FetchResult {
    public data class Downloaded(public val file: File, public val validators: Validators, public val bytes: Long) : FetchResult
    public data object NotModified : FetchResult

    /** 404: the file doesn't exist yet, e.g. a season before kickoff. */
    public data object NotPublished : FetchResult
}

public fun interface Fetcher {
    /**
     * Downloads [url] to [dest] unless [previous] still describes the server's
     * copy. [dest] only ever appears complete: an interrupted download leaves
     * nothing behind and throws.
     */
    public suspend fun fetch(
        url: String,
        dest: File,
        previous: Validators?,
        onBytes: (read: Long, total: Long) -> Unit,
    ): FetchResult
}
