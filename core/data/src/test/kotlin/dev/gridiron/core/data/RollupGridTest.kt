package dev.gridiron.core.data

import dev.gridiron.core.database.QueryExecutor
import dev.gridiron.core.database.ResultRow
import dev.gridiron.core.model.WeekRange
import dev.gridiron.core.statquery.SqlQuery
import dev.gridiron.core.testing.JdbcQueryExecutor
import dev.gridiron.core.testing.StatsDb
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.sql.DriverManager
import java.util.Locale

/** Records the SQL it runs, to see which table a grid read. */
private class Recording(private val inner: QueryExecutor) : QueryExecutor {
    val sql = mutableListOf<String>()

    override suspend fun <T> query(query: SqlQuery, map: (ResultRow) -> T): List<T> {
        sql += query.sql
        return inner.query(query, map)
    }
}

/** The pre-aggregated windows against the real database: same grid as the weekly facts, or the weekly path. */
class RollupGridTest {
    @TempDir
    lateinit var dir: File

    private lateinit var real: JdbcQueryExecutor
    private lateinit var stripped: JdbcQueryExecutor
    private lateinit var fast: Recording
    private lateinit var fastRepo: StatsRepository
    private lateinit var slowRepo: StatsRepository
    private lateinit var catalog: Catalog
    private lateinit var slowCatalog: Catalog

    @BeforeEach
    fun setUp() = runTest {
        assumeTrue(StatsDb.path != null, "GRIDIRON_STATS_DB not set")
        // The same database without the rollup tables: what the phone holds before its first refresh.
        val copy = File(dir, "old.db")
        File(StatsDb.path!!).copyTo(copy)
        DriverManager.getConnection("jdbc:sqlite:${copy.path}").use { c ->
            c.createStatement().use {
                it.executeUpdate("DROP TABLE player_window_stat")
                it.executeUpdate("DROP TABLE window_def")
            }
        }
        real = JdbcQueryExecutor(StatsDb.path!!)
        stripped = JdbcQueryExecutor(copy.path)
        fast = Recording(real)
        fastRepo = StatsRepository(fast, Locale.US)
        slowRepo = StatsRepository(stripped, Locale.US)
        catalog = fastRepo.catalog()
        slowCatalog = slowRepo.catalog()
    }

    @AfterEach
    fun tearDown() {
        if (::real.isInitialized) real.close()
        if (::stripped.isInitialized) stripped.close()
    }

    private fun request(of: Catalog, weeks: WeekRange, pack: StatPack, perGame: Boolean = false) =
        GridRequest(of.season(2025), weeks, pack, PositionFilter.ALL, perGame = perGame)

    private fun GridPage.snapshot() = rows.map { r -> Triple(r.playerId, r.detail, r.cells.map { it.text to it.heat }) }

    @Test
    fun `every window gives the weekly path's grid for each non-fantasy pack`() = runTest {
        val windows = catalog.season(2025).rollups
        assertTrue(windows.map { it.window }.containsAll(listOf("S", "L3", "L4", "L5", "L8")), "windows: $windows")
        assertTrue(slowCatalog.season(2025).rollups.isEmpty())
        for ((window, weeks) in windows) {
            for (pack in StatPack.entries.filter { p -> p.columns.none { it.isFantasy } }) {
                for (perGame in listOf(false, true)) {
                    fast.sql.clear()
                    val a = fastRepo.grid(request(catalog, weeks, pack, perGame), catalog)
                    assertTrue(fast.sql.any { "player_window_stat" in it }, "$window $pack did not read the rollup")
                    val b = slowRepo.grid(request(slowCatalog, weeks, pack, perGame), slowCatalog)
                    assertEquals(b.snapshot(), a.snapshot(), "$window $pack perGame=$perGame")
                }
            }
        }
    }

    @Test
    fun `a database without the window tables still serves the grid from weekly facts`() = runTest {
        val weeks = catalog.season(2025).defaultWeeks
        assertTrue(slowRepo.grid(request(slowCatalog, weeks, StatPack.RECEIVING), slowCatalog).rows.isNotEmpty())
        assertEquals(slowRepo.count(request(slowCatalog, weeks, StatPack.RECEIVING)), fastRepo.count(request(catalog, weeks, StatPack.RECEIVING)))
    }

    @Test
    fun `the fantasy pack reads the rollup and gives the weekly path's grid`() = runTest {
        for ((window, weeks) in catalog.season(2025).rollups) {
            fast.sql.clear()
            val a = fastRepo.grid(request(catalog, weeks, StatPack.FANTASY), catalog)
            assertTrue(fast.sql.any { "player_window_stat" in it }, "$window did not read the rollup")
            val b = slowRepo.grid(request(slowCatalog, weeks, StatPack.FANTASY), slowCatalog)
            // Summing weeks in another order moves the last float bit, so tied players can swap percentile ranks: text within one displayed step, heat within a tie cluster (exact percentiles are checked on fixtures).
            val byPlayer = a.rows.associateBy { it.playerId }
            assertEquals(b.rows.map { it.playerId }.toSet(), byPlayer.keys, window)
            for (y in b.rows) {
                val x = byPlayer.getValue(y.playerId)
                assertEquals(y.detail, x.detail, window)
                for ((yc, xc) in y.cells.zip(x.cells)) {
                    // A sum in another order can land a hair across a rounding edge: one displayed step, never more (raw values are compared in the contract test).
                    val (yn, xn) = yc.text.trimEnd('%').toDoubleOrNull() to xc.text.trimEnd('%').toDoubleOrNull()
                    if (yn == null || xn == null) assertEquals(yc.text, xc.text) else assertEquals(yn, xn, 0.1001, "$window ${y.playerId} col ${y.cells.indexOf(yc)} ${y.detail}")
                    assertEquals(yc.heat?.toDouble() ?: 0.0, xc.heat?.toDouble() ?: 0.0, 0.15, "$window ${y.playerId} ${yc.text}")
                }
            }
        }
    }
}
