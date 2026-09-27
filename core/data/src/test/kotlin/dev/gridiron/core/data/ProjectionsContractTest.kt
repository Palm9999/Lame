package dev.gridiron.core.data

import dev.gridiron.core.database.QueryExecutor
import dev.gridiron.core.model.Position
import dev.gridiron.core.model.ScoringPresets
import dev.gridiron.core.projections.ListedProjection
import dev.gridiron.core.projections.projectPoints
import dev.gridiron.core.projections.score
import dev.gridiron.core.statquery.Bind
import dev.gridiron.core.statquery.Component
import dev.gridiron.core.statquery.SqlQuery
import dev.gridiron.core.testing.JdbcQueryExecutor
import dev.gridiron.core.testing.StatsDb
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import kotlin.math.sqrt

/**
 * The phone's projection reads, end to end, against the database CI builds
 * with the same Kotlin code the phone runs.
 */
@EnabledIfEnvironmentVariable(named = "GRIDIRON_STATS_DB", matches = ".+")
class ProjectionsContractTest {
    /** In season, the upcoming week; off-season, the last week projected. */
    private suspend fun projectedWeek(repo: ProjectionsRepository, executor: QueryExecutor): Pair<Int, Int> {
        val status = repo.status()
        assertEquals("ok", status.status)
        return status.upcoming.maxByOrNull { it.key }?.toPair()
            ?: executor.query(
                SqlQuery(
                    "SELECT season, MAX(week) FROM player_week_projection " +
                        "WHERE season = (SELECT MAX(season) FROM player_week_projection)",
                    emptyList(),
                ),
            ) { it.long(0).toInt() to it.long(1).toInt() }.single()
    }

    @Test
    fun `the refresh-built database projects a full week that scores sensibly`() = runTest {
        JdbcQueryExecutor(StatsDb.path!!).use { executor ->
            val repo = ProjectionsRepository(executor)
            val (season, week) = projectedWeek(repo, executor)

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

    @Test
    fun `every team's projected week adds up to one game, with one passer`() = runTest {
        JdbcQueryExecutor(StatsDb.path!!).use { executor ->
            val repo = ProjectionsRepository(executor)
            val (season, week) = projectedWeek(repo, executor)

            // Grouped by nflverse's current team: a traded player can land on his old team here, which the bounds allow for.
            for ((team, players) in repo.weekAll(season, week).groupBy { it.team }) {
                fun total(metric: String) = players.sumOf { p -> p.components.filter { it.metricId == metric }.sumOf { it.mean } }
                val passers = players.filter { p -> p.components.any { it.metricId == "attempts" && it.mean > 5.0 } }.map { it.name }
                assertTrue(passers.size <= 1, "$team has ${passers.size} passers: $passers")
                // Real teams average about 34 pass attempts, 30 targets and 27 carries; matchup and script move them ±20%.
                assertTrue(total("attempts") <= 50.0, "$team: ${total("attempts")} pass attempts")
                assertTrue(total("targets") <= 50.0, "$team: ${total("targets")} targets")
                assertTrue(total("carries") <= 45.0, "$team: ${total("carries")} carries")
            }
        }
    }

    @Test
    fun `every team playing has one D-ST and at most one kicker, scoring sensibly`() = runTest {
        JdbcQueryExecutor(StatsDb.path!!).use { executor ->
            val repo = ProjectionsRepository(executor)
            val (season, week) = projectedWeek(repo, executor)
            val listed = repo.weekAll(season, week)
            val playing = executor.query(
                SqlQuery(
                    "SELECT home_team, away_team FROM game WHERE season = ? AND week = ? AND game_type = 'REG'",
                    listOf(Bind.Integer(season.toLong()), Bind.Integer(week.toLong())),
                ),
            ) { listOf(it.text(0), it.text(1)) }.flatten().toSet()
            val defenses = listed.filter { it.position == "DST" }
            val kickers = listed.filter { it.position == "K" }

            assertEquals(playing, defenses.map { it.team }.toSet())
            assertEquals(playing.size, defenses.size)
            assertTrue(kickers.size in playing.size - 2..playing.size, "${kickers.size} kickers for ${playing.size} teams")
            for (d in defenses) {
                val allowed = d.components.single { it.metricId == "points_allowed" }
                assertTrue(allowed.mean in 10.0..40.0 && allowed.variance in 25.0..400.0, "${d.name}: $allowed")
                assertEquals(1.0, d.components.single { it.metricId == "g" }.mean, 0.0, d.name)
            }
            // Scored as the phone will (Task 10's projectedScore): tiers in expectation, never the tier of the mean.
            fun points(p: ListedProjection): Double {
                val means = p.components.filter { it.metricId != "points_allowed" }.associate { Component(it.metricId) to it.mean }
                val allowed = p.components.firstOrNull { it.metricId == "points_allowed" }
                return score(means, ScoringPresets.PPR, Position.fromCode(p.position!!)) +
                    (allowed?.let { ScoringPresets.PPR.expectedPointsAllowedPoints(it.mean, sqrt(it.variance)) } ?: 0.0)
            }
            fun top(position: String) = listed.filter { it.position == position }.map(::points).sortedDescending().take(12).average()
            // Loose bands under the default kicking and D/ST scoring: a broken model lands far outside them.
            assertTrue(top("K") in 6.0..12.0, "K1-12 average ${top("K")}")
            assertTrue(top("DST") in 4.0..14.0, "DST1-12 average ${top("DST")}")
        }
    }
}
