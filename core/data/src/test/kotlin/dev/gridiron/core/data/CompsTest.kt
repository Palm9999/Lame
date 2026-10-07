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
            assertTrue(comps.all { it.games >= 4 && it.perGame.size == COMP_METRICS.getValue("WR").size })
        }
    }
}
