package dev.gridiron.core.data

import dev.gridiron.core.model.ScoringPresets
import dev.gridiron.core.testing.JdbcQueryExecutor
import dev.gridiron.core.testing.StatsDb
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable

@EnabledIfEnvironmentVariable(named = "GRIDIRON_STATS_DB", matches = ".+")
class TdRegressionRepositoryTest {
    @Test
    fun `a finished season lists players hot and cold against expected touchdowns, hottest first`() = runTest {
        JdbcQueryExecutor(checkNotNull(StatsDb.path)).use { executor ->
            val board = TdRegressionRepository(executor).board(2025)
            assertTrue(board.throughWeek >= 17, "through ${board.throughWeek}")
            assertTrue(board.rows.size > 100)
            assertEquals(board.rows.sortedByDescending { it.gap }, board.rows)
            assertTrue(board.rows.first().gap > 2 && board.rows.last().gap < -2)
            assertTrue(board.rows.all { it.position in setOf("QB", "RB", "WR", "TE") && (it.tds >= 2 || it.expected >= 2) })
        }
    }

    @Test
    fun `a season ffopportunity hasn't reached has an empty board`() = runTest {
        JdbcQueryExecutor(checkNotNull(StatsDb.path)).use { executor ->
            assertEquals(TdRegressionBoard(0, emptyList()), TdRegressionRepository(executor).board(1990))
        }
    }

    @Test
    fun `the points board ranks per-game gaps under the profile, and points and expected agree league-wide`() = runTest {
        JdbcQueryExecutor(checkNotNull(StatsDb.path)).use { executor ->
            val board = TdRegressionRepository(executor).pointsBoard(2025, ScoringPresets.PPR)
            assertTrue(board.throughWeek >= 17)
            assertTrue(board.rows.size > 200, "${board.rows.size}")
            assertEquals(board.rows.sortedByDescending { it.gapPerGame }, board.rows)
            assertTrue(board.rows.all { it.games >= 3 && it.position in setOf("QB", "RB", "WR", "TE") })
            val points = board.rows.sumOf { it.points }
            val expected = board.rows.sumOf { it.expected }
            assertTrue(kotlin.math.abs(points - expected) / expected < 0.1, "points $points, expected $expected")
            assertEquals(PointsGapBoard(0, emptyList()), TdRegressionRepository(executor).pointsBoard(1990, ScoringPresets.PPR))
        }
    }
}
