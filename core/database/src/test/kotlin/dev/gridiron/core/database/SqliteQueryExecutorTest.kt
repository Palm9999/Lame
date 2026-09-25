package dev.gridiron.core.database

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import dev.gridiron.core.model.Position
import dev.gridiron.core.model.ScoringPresets
import dev.gridiron.core.model.WeekRange
import dev.gridiron.core.statquery.Bind
import dev.gridiron.core.statquery.CatalogQueries
import dev.gridiron.core.statquery.SqlQuery
import dev.gridiron.core.statquery.StatColumn
import dev.gridiron.core.statquery.StatQueryBuilder
import dev.gridiron.core.statquery.StatQuerySpec
import dev.gridiron.core.testing.JdbcQueryExecutor
import dev.gridiron.core.testing.StatsDb
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/** The executor the phone uses, running here on the bundled driver's desktop build. */
class SqliteQueryExecutorTest {

    @TempDir
    lateinit var dir: Path

    private fun fixture(): String {
        val path = dir.resolve("fixture.db").toString()
        BundledSQLiteDriver().open(path).use { c ->
            c.execSQL("CREATE TABLE t (name TEXT, n INTEGER, x REAL)")
            c.execSQL("INSERT INTO t VALUES ('a', 1, 1.5), ('b', 2, NULL), ('c', 3, -2.25)")
        }
        return path
    }

    @Test
    fun `binds every value type and reads every column type`() = runTest {
        SqliteQueryExecutor.openReadOnly(fixture()).use { db ->
            val rows = db.query(
                SqlQuery(
                    "SELECT name, n, x FROM t WHERE name <> ? AND n >= ? AND (x IS NULL OR x < ?) ORDER BY n",
                    listOf(Bind.Text("c"), Bind.Integer(1), Bind.Real(100.0)),
                ),
            ) { Triple(it.text(0), it.long(1), it.doubleOrNull(2)) }

            assertEquals(listOf(Triple("a", 1L, 1.5), Triple("b", 2L, null)), rows)
        }
    }

    @Test
    fun `the connection is read-only`() = runTest {
        SqliteQueryExecutor.openReadOnly(fixture()).use { db ->
            assertThrows<Exception> {
                db.query(SqlQuery("DELETE FROM t", emptyList())) { }
            }
        }
    }

    @Test
    fun `the deferred executor opens once, on first use`() = runTest {
        var opens = 0
        val path = fixture()
        val deferred = DeferredQueryExecutor {
            opens++
            SqliteQueryExecutor.openReadOnly(path)
        }
        assertEquals(0, opens)
        repeat(3) { deferred.query(SqlQuery("SELECT COUNT(*) FROM t", emptyList())) { it.long(0) } }
        assertEquals(1, opens)
    }

    /**
     * Screenshot and ViewModel tests read the real database through JDBC. This
     * checks that stand-in against the device driver: same queries, same cells.
     */
    @Test
    fun `the device driver and the JDBC test executor return identical results`() = runTest {
        val path = StatsDb.path
        assumeTrue(path != null, "GRIDIRON_STATS_DB not set")

        val season = JdbcQueryExecutor(path!!).use { it.query(CatalogQueries.seasons) { r -> r.long(0) }.last() }.toInt()
        val queries = listOf(
            CatalogQueries.metrics,
            CatalogQueries.seasons,
            StatQueryBuilder.grid(
                StatQuerySpec(
                    season, WeekRange.regularSeason(season), StatColumn.entries,
                    positions = Position.FLEX, percentiles = true, limit = StatQuerySpec.MAX_LIMIT,
                    scoring = ScoringPresets.PPR,
                ),
            ).query,
            StatQueryBuilder.search("ja")!!,
        )

        SqliteQueryExecutor.openReadOnly(path).use { device ->
            JdbcQueryExecutor(path).use { jdbc ->
                for (q in queries) {
                    val width = q.sql.substringBefore("FROM").count { it == ',' } + 1
                    // Numbers compare as the stored doubles, bit for bit. Their
                    // text forms legitimately differ: SQLite renders 15
                    // significant digits, JDBC 17. The app only reads doubles.
                    fun read(r: ResultRow): List<Any?> = (0 until width).map { i ->
                        when {
                            r.isNull(i) -> null
                            r.text(i).toDoubleOrNull() != null -> r.double(i)
                            else -> r.text(i)
                        }
                    }
                    val a = device.query(q, ::read)
                    val b = jdbc.query(q, ::read)
                    assertTrue(a.isNotEmpty(), "no rows for ${q.sql.take(60)}")
                    assertEquals(b.size, a.size, "row count differs for ${q.sql.take(60)}")
                    b.zip(a).forEachIndexed { row, (expected, actual) ->
                        assertEquals(expected, actual, "row $row differs for ${q.sql.take(60)}")
                    }
                }
            }
        }
    }
}
