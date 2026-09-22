package dev.gridiron.core.database

import dev.gridiron.core.statquery.SqlQuery
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Opens the real executor on first use. On first launch the app must copy the
 * bundled database out of its APK before it can be opened, which should
 * neither block startup nor race with the first query.
 */
public class DeferredQueryExecutor(private val open: suspend () -> QueryExecutor) : QueryExecutor {
    private val mutex = Mutex()
    private var delegate: QueryExecutor? = null

    private suspend fun delegate(): QueryExecutor =
        delegate ?: mutex.withLock { delegate ?: open().also { delegate = it } }

    override suspend fun <T> query(query: SqlQuery, map: (ResultRow) -> T): List<T> =
        delegate().query(query, map)
}
