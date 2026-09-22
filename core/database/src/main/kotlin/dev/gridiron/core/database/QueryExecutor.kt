package dev.gridiron.core.database

import dev.gridiron.core.statquery.SqlQuery

/**
 * Runs a [SqlQuery] and maps each result row. The one seam between the app and
 * SQLite: on the device it's [SqliteQueryExecutor]; in tests it can be JDBC
 * against the same database file.
 */
public interface QueryExecutor {
    public suspend fun <T> query(query: SqlQuery, map: (ResultRow) -> T): List<T>
}

/** Read access to the current row. Column indexes are zero-based. */
public interface ResultRow {
    public fun isNull(index: Int): Boolean
    public fun text(index: Int): String
    public fun long(index: Int): Long
    public fun double(index: Int): Double
}

public fun ResultRow.textOrNull(index: Int): String? = if (isNull(index)) null else text(index)

public fun ResultRow.doubleOrNull(index: Int): Double? = if (isNull(index)) null else double(index)
