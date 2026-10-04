package dev.gridiron.core.data

import dev.gridiron.core.database.QueryExecutor
import dev.gridiron.core.database.ResultRow
import dev.gridiron.core.statquery.SqlQuery
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BreakoutRepositoryTest {
    private fun row(
        position: String = "WR",
        recent: Double = 8.0,
        base: Double = 5.0,
        xr: Double? = null,
        xb: Double? = null,
        note: String? = null,
    ) = BreakoutRow("p1", "A Player", position, "KC", 60.0, recent, base, xr, xb, 0.0, note)

    @Test
    fun `the reason says what is moving, in the position's own words`() {
        assertEquals("targets 5.0 → 8.0 a game", row().reason)
        assertEquals("touches 12.0 → 18.0 a game · expected pts 6.1 → 8.0 · Smith, Jones out", row("RB", 18.0, 12.0, 8.0, 6.1, "Smith, Jones").reason)
        // Expected points that fell, or usage that did, aren't reasons to be listed.
        assertEquals("role holding steady", row(recent = 4.0, xr = 3.0, xb = 4.0).reason)
        assertEquals("expected pts 4.0 → 5.0", row(recent = 5.0, xr = 5.0, xb = 4.0).reason)
    }

    private class Failing : QueryExecutor {
        override suspend fun <T> query(query: SqlQuery, map: (ResultRow) -> T): List<T> = error("no such table: player_week_signal")
    }

    private class Empty : QueryExecutor {
        override suspend fun <T> query(query: SqlQuery, map: (ResultRow) -> T): List<T> = emptyList()
    }

    @Test
    fun `one player's signal is read for the season's newest week, and any failure reads as none`() = runTest {
        val executor = Recording(mapOf(2025 to 18, 2026 to 5))
        // The fake returns no rows for the player query, which reads as no signal rather than an error.
        assertEquals(null, BreakoutRepository(executor).forPlayer("p1", 2025))
        assertEquals(listOf<Long>(2025, 18), executor.seen.last().take(2).map { (it as dev.gridiron.core.statquery.Bind.Integer).value })
        assertEquals(dev.gridiron.core.statquery.Bind.Text("p1"), executor.seen.last().last())
        assertEquals(null, BreakoutRepository(Failing()).forPlayer("p1", 2025))
        assertEquals(null, BreakoutRepository(Empty()).forPlayer("p1", 2025))
    }

    @Test
    fun `a database without the table, or with no rows, says so instead of failing`() = runTest {
        val old = BreakoutRepository(Failing()).find(2025)
        assertTrue(old.rows.isEmpty())
        assertEquals("Refresh stats to build Rising roles.", old.message)
        assertEquals("Rising roles aren't built for 2025 yet.", BreakoutRepository(Empty()).find(2025).message)
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named = "GRIDIRON_ACCURACY_GATE", matches = "\\d{4}")
    fun `a real build lists the best rising roles first, each with a reason`() = runTest {
        val season = System.getenv("GRIDIRON_ACCURACY_GATE").toInt()
        dev.gridiron.core.testing.JdbcQueryExecutor(checkNotNull(dev.gridiron.core.testing.StatsDb.path)).use { executor ->
            val result = BreakoutRepository(executor).find(season)
            assertTrue(result.rows.size > 50, "${result.rows.size} rows, ${result.message}")
            assertEquals(result.rows.sortedByDescending { it.score }.map { it.playerId }, result.rows.map { it.playerId })
            assertTrue(result.rows.all { it.position in setOf("RB", "WR", "TE") && it.score > 0.0 && it.reason.isNotBlank() })
            assertTrue(result.week in 2..18, "week ${result.week}")
        }
    }

    private class Recording(private val weeks: Map<Int, Int>) : QueryExecutor {
        val seen = mutableListOf<List<dev.gridiron.core.statquery.Bind>>()

        override suspend fun <T> query(query: SqlQuery, map: (ResultRow) -> T): List<T> {
            seen += query.binds
            if (!query.sql.contains("GROUP BY week")) return emptyList()
            val season = (query.binds.single() as dev.gridiron.core.statquery.Bind.Integer).value.toInt()
            val week = weeks[season] ?: return emptyList()
            return listOf(
                map(
                    object : ResultRow {
                        override fun isNull(index: Int) = false
                        override fun text(index: Int) = ""
                        override fun long(index: Int) = week.toLong()
                        override fun double(index: Int) = 0.0
                    },
                ),
            )
        }
    }

    @Test
    fun `the newest week is the requested season's own, not the table's newest season's`() = runTest {
        val executor = Recording(mapOf(2025 to 18, 2026 to 5))
        val old = BreakoutRepository(executor).find(2025)
        assertEquals(18, old.week)
        // The rows query for 2025 binds 2025 and its own week 18, never 2026's week 5.
        assertEquals(listOf<Long>(2025, 18), executor.seen.last().map { (it as dev.gridiron.core.statquery.Bind.Integer).value })
        assertEquals("Rising roles aren't built for 2024 yet.", BreakoutRepository(executor).find(2024).message)
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named = "GRIDIRON_ACCURACY_GATE", matches = "\\d{4}")
    fun `a real build gives a listed player his own signal and a quarterback none`() = runTest {
        val season = System.getenv("GRIDIRON_ACCURACY_GATE").toInt()
        dev.gridiron.core.testing.JdbcQueryExecutor(checkNotNull(dev.gridiron.core.testing.StatsDb.path)).use { executor ->
            val repo = BreakoutRepository(executor)
            val top = repo.find(season).rows.first()
            val own = repo.forPlayer(top.playerId, season)
            assertEquals(top, own)
            val qb = executor.query(
                SqlQuery("SELECT player_id FROM player WHERE position = 'QB' LIMIT 1", emptyList()),
            ) { it.text(0) }.single()
            assertEquals(null, repo.forPlayer(qb, season))
        }
    }
}
