package dev.gridiron.core.testing

import dev.gridiron.core.database.QueryExecutor
import dev.gridiron.core.database.ResultRow
import dev.gridiron.core.statquery.Bind
import dev.gridiron.core.statquery.SqlQuery
import java.io.File
import java.sql.Connection
import java.sql.ResultSet
import java.util.Properties

/**
 * A [QueryExecutor] over JDBC, for tests. Runs inline on the caller's
 * coroutine, so a test dispatcher fully controls when queries happen.
 */
public class JdbcQueryExecutor(path: String) : QueryExecutor, AutoCloseable {
    // The driver is constructed directly rather than found through DriverManager.
    // DriverManager hides drivers registered by another classloader, and
    // Robolectric tests load classes through their own sandbox loader, so
    // lookup would fail depending on which test class happened to run first.
    private val connection: Connection =
        checkNotNull(org.sqlite.JDBC().connect("jdbc:sqlite:file:$path?mode=ro", Properties())) {
            "couldn't open $path"
        }

    override suspend fun <T> query(query: SqlQuery, map: (ResultRow) -> T): List<T> =
        connection.prepareStatement(query.sql).use { ps ->
            query.binds.forEachIndexed { i, bind ->
                when (bind) {
                    is Bind.Text -> ps.setString(i + 1, bind.value)
                    is Bind.Integer -> ps.setLong(i + 1, bind.value)
                    is Bind.Real -> ps.setDouble(i + 1, bind.value)
                }
            }
            ps.executeQuery().use { rs ->
                val row = JdbcRow(rs)
                buildList { while (rs.next()) add(map(row)) }
            }
        }

    override fun close() {
        connection.close()
    }

    private class JdbcRow(private val rs: ResultSet) : ResultRow {
        // JDBC columns are one-based; ResultRow is zero-based.
        override fun isNull(index: Int): Boolean = rs.getObject(index + 1) == null
        override fun text(index: Int): String = rs.getString(index + 1)
        override fun long(index: Int): Long = rs.getLong(index + 1)
        override fun double(index: Int): Double = rs.getDouble(index + 1)
    }
}

/** The real ETL-built database named by GRIDIRON_STATS_DB, or null to skip. */
public object StatsDb {
    public val path: String? = System.getenv("GRIDIRON_STATS_DB")?.takeIf { File(it).isFile }
}
