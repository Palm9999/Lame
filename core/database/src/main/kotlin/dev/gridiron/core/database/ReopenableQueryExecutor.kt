package dev.gridiron.core.database

import dev.gridiron.core.statquery.SqlQuery
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The stats database's executor. It opens on first use, and it can be closed
 * and reopened around a file swap: the phone builds a new `stats.db` beside
 * the live one and moves it into place while the app runs.
 *
 * One lock covers every query and the swap, so a swap waits for the query in
 * flight and no query ever meets a closed connection. Queries were already
 * serial (one connection, one thread), so the lock costs nothing.
 */
public class ReopenableQueryExecutor(private val open: suspend () -> QueryExecutor) : QueryExecutor {
    private val mutex = Mutex()
    private var delegate: QueryExecutor? = null
    private val _version = MutableStateFlow(0L)

    /** Bumped after every successful [swap]; screens reload when it changes. */
    public val version: StateFlow<Long> = _version.asStateFlow()

    override suspend fun <T> query(query: SqlQuery, map: (ResultRow) -> T): List<T> =
        mutex.withLock {
            val db = delegate ?: open().also { delegate = it }
            db.query(query, map)
        }

    /**
     * Closes the open database, runs [replace] (which moves the new file into
     * place) and bumps [version]. The next query opens whatever file is then in
     * place. If [replace] throws, [version] stays put and the next query
     * reopens the old file.
     */
    public suspend fun swap(replace: suspend () -> Unit) {
        mutex.withLock {
            (delegate as? AutoCloseable)?.close()
            delegate = null
            replace()
            _version.value += 1
        }
    }
}
