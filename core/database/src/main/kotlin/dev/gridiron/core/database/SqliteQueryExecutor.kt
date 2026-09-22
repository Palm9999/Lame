package dev.gridiron.core.database

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.driver.bundled.SQLITE_OPEN_READONLY
import dev.gridiron.core.statquery.Bind
import dev.gridiron.core.statquery.SqlQuery
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * Executes queries on one read-only connection to `stats.db`.
 *
 * A connection must not be used by two threads at once, so every call runs on
 * a dispatcher with parallelism 1. Queries check for cancellation between rows,
 * so a superseded Grid query (the user tapped another sort) stops promptly.
 */
public class SqliteQueryExecutor private constructor(
    private val connection: SQLiteConnection,
    private val dispatcher: CoroutineDispatcher,
) : QueryExecutor, AutoCloseable {

    override suspend fun <T> query(query: SqlQuery, map: (ResultRow) -> T): List<T> =
        withContext(dispatcher) {
            connection.prepare(query.sql).use { statement ->
                query.binds.forEachIndexed { i, bind ->
                    when (bind) {
                        is Bind.Text -> statement.bindText(i + 1, bind.value)
                        is Bind.Integer -> statement.bindLong(i + 1, bind.value)
                        is Bind.Real -> statement.bindDouble(i + 1, bind.value)
                    }
                }
                val row = StatementRow(statement)
                buildList {
                    while (statement.step()) {
                        coroutineContext.ensureActive()
                        add(map(row))
                    }
                }
            }
        }

    override fun close() {
        connection.close()
    }

    private class StatementRow(private val statement: SQLiteStatement) : ResultRow {
        override fun isNull(index: Int): Boolean = statement.isNull(index)
        override fun text(index: Int): String = statement.getText(index)
        override fun long(index: Int): Long = statement.getLong(index)
        override fun double(index: Int): Double = statement.getDouble(index)
    }

    public companion object {
        /**
         * Opens [path] read-only with the bundled SQLite build: the same
         * engine version on every device, with the window functions the Grid's
         * percentiles need.
         */
        public fun openReadOnly(
            path: String,
            dispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1),
        ): SqliteQueryExecutor =
            SqliteQueryExecutor(BundledSQLiteDriver().open(path, SQLITE_OPEN_READONLY), dispatcher)
    }
}
