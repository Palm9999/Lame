package dev.gridiron.core.data

import dev.gridiron.core.statquery.SqlQuery
import dev.gridiron.core.projections.DST_POINTS_YARDS_CORRELATION
import dev.gridiron.core.testing.JdbcQueryExecutor
import dev.gridiron.core.testing.StatsDb
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import kotlin.math.sqrt

class DstCorrelationTest {
    @Test
    fun `the simulation's correlation is the one the real games show, within 0-05`() = runTest {
        val path = StatsDb.path
        assumeTrue(path != null)
        JdbcQueryExecutor(path!!).use { executor ->
            val pairs = executor.query(
                SqlQuery("SELECT points_allowed, yards_allowed FROM team_week_defense WHERE season IN (2024, 2025)", emptyList()),
            ) { it.double(0) to it.double(1) }
            assertTrue(pairs.size > 500, "${pairs.size} team-games")
            val mx = pairs.map { it.first }.average()
            val my = pairs.map { it.second }.average()
            val cov = pairs.sumOf { (it.first - mx) * (it.second - my) }
            val corr = cov / (sqrt(pairs.sumOf { (it.first - mx) * (it.first - mx) }) * sqrt(pairs.sumOf { (it.second - my) * (it.second - my) }))
            assertEquals(DST_POINTS_YARDS_CORRELATION, corr, 0.05)
        }
    }
}
