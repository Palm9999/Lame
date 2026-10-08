package dev.gridiron.core.data

import dev.gridiron.core.testing.JdbcQueryExecutor
import dev.gridiron.core.testing.StatsDb
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CompsTest {
    private fun s(id: String, season: Int, vararg perGame: Double) = CompSeason(id, id, season, null, 10, perGame.toList())

    @Test
    fun `the nearest seasons are other players', standardized metric by metric`() {
        val target = s("me", 2025, 8.0, 100.0)
        // "near" is close on both; "far" matches yards exactly but is far on the small-scale metric.
        val pool = listOf(target, s("me", 2024, 8.0, 100.0), s("near", 2025, 7.5, 95.0), s("far", 2024, 2.0, 100.0), s("mid", 2025, 6.5, 90.0))
        assertEquals(listOf("near", "mid", "far"), nearestSeasons(target, pool, k = 3).map { it.playerId })
    }

    @Test
    fun `age and rates count, and a season without an age sits at the pool's average`() {
        fun c(id: String, age: Double?, vararg rates: Double) = CompSeason(id, id, 2025, null, 10, listOf(5.0), rates.toList(), age)
        val target = c("me", 24.0, 0.25)
        val pool = listOf(target, c("young", 23.0, 0.25), c("old", 33.0, 0.25), c("share", 24.0, 0.10), c("unknown", null, 0.25))
        assertEquals("young", nearestSeasons(target, pool, k = 1).single().playerId)
        assertEquals(26.0, ageOn("2000-09-01", 2026)!!, 0.01)
        assertEquals(null, ageOn("unknown", 2026))
    }

    @Test
    fun `a real receiver's comps are five other wide receivers' seasons`() = runTest {
        val path = StatsDb.path ?: return@runTest
        JdbcQueryExecutor(path).use { executor ->
            val top = executor.query(
                dev.gridiron.core.statquery.SqlQuery(
                    "SELECT s.player_id FROM player_week_stat s JOIN player p ON p.player_id = s.player_id WHERE s.season = 2025 AND s.metric_id = 'targets' AND p.position = 'WR' GROUP BY s.player_id ORDER BY SUM(s.value) DESC LIMIT 1",
                    emptyList(),
                ),
            ) { it.text(0) }.single()
            val comps = CompsRepository(executor).comps(top, 2025, "WR")
            assertEquals(5, comps.size)
            assertTrue(comps.none { it.playerId == top })
            assertTrue(comps.all { it.games >= 4 && it.perGame.size == COMP_METRICS.getValue("WR").size && it.rates.size == 3 })
            assertTrue(comps.count { it.age != null } >= 4, comps.toString())
        }
    }
}
