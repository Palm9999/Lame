package dev.gridiron.core.data

import dev.gridiron.core.model.Position
import dev.gridiron.core.model.ScoringPresets
import dev.gridiron.core.projections.projectPoints
import dev.gridiron.core.statquery.SqlQuery
import dev.gridiron.core.testing.JdbcQueryExecutor
import dev.gridiron.core.testing.StatsDb
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable

/**
 * The phone's projection reads, end to end, against the database CI builds
 * with the same Kotlin code the phone runs: the gap the old fixture-only
 * projection tests left open.
 */
@EnabledIfEnvironmentVariable(named = "GRIDIRON_STATS_DB", matches = ".+")
class ProjectionsContractTest {
    @Test
    fun `the refresh-built database projects a full week that scores sensibly`() = runTest {
        JdbcQueryExecutor(StatsDb.path!!).use { executor ->
            val repo = ProjectionsRepository(executor)
            val status = repo.status()
            assertEquals("ok", status.status)

            // In season, the upcoming week; off-season, the last week projected.
            val (season, week) = status.upcoming.maxByOrNull { it.key }?.toPair()
                ?: executor.query(
                    SqlQuery(
                        "SELECT season, MAX(week) FROM player_week_projection " +
                            "WHERE season = (SELECT MAX(season) FROM player_week_projection)",
                        emptyList(),
                    ),
                ) { it.long(0).toInt() to it.long(1).toInt() }.single()

            val scored = repo.weekAll(season, week).mapNotNull { p ->
                val position = p.position ?: return@mapNotNull null
                position to projectPoints(p.components, ScoringPresets.PPR, Position.fromCode(position), draws = 500).points
            }
            assertTrue(scored.size >= 150, "only ${scored.size} players projected for $season week $week")
            assertTrue(scored.all { it.second.isFinite() })

            fun topAverage(position: String, n: Int) = scored.filter { it.first == position }.map { it.second }.sortedDescending().take(n).average()
            // Loose sanity bands for full PPR, 4-point passing TDs: a broken layer lands far outside them.
            assertTrue(topAverage("QB", 12) in 12.0..30.0, "QB1-12 average ${topAverage("QB", 12)}")
            assertTrue(topAverage("RB", 24) in 8.0..25.0, "RB1-24 average ${topAverage("RB", 24)}")
            assertTrue(topAverage("WR", 24) in 8.0..25.0, "WR1-24 average ${topAverage("WR", 24)}")
            assertTrue(topAverage("TE", 12) in 5.0..20.0, "TE1-12 average ${topAverage("TE", 12)}")
        }
    }
}
