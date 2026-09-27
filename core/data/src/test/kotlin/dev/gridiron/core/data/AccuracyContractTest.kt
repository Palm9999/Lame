package dev.gridiron.core.data

import dev.gridiron.core.model.ScoringPresets
import dev.gridiron.core.projections.ACCURACY_POSITIONS
import dev.gridiron.core.testing.JdbcQueryExecutor
import dev.gridiron.core.testing.StatsDb
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable

/** The accuracy page's backtest, end to end, on the database CI builds with the phone's code. */
@EnabledIfEnvironmentVariable(named = "GRIDIRON_STATS_DB", matches = ".+")
class AccuracyContractTest {
    @Test
    fun `a finished season backtests every position with sensible, finite numbers`() = runTest {
        JdbcQueryExecutor(StatsDb.path!!).use { executor ->
            val repo = AccuracyRepository(executor)
            val seasons = repo.seasons()
            // The newest season may be only a week or two old: measure the one before it.
            val season = seasons.dropLast(1).lastOrNull() ?: seasons.last()

            val started = System.nanoTime()
            val results = repo.backtest(season, ScoringPresets.PPR)
            val seconds = (System.nanoTime() - started) / 1e9

            assertEquals(ACCURACY_POSITIONS, results.map { it.position })
            for (r in results) {
                assertTrue(r.playerWeeks >= 100, "${r.position}: only ${r.playerWeeks} player-weeks in $season")
                for (stats in listOf(r.model, r.seasonAverage, r.lastFour)) {
                    // Half-point-per-reception misses land around 4 to 8 points; a scoring bug lands far outside.
                    assertTrue(stats.mae in 2.0..12.0, "${r.position}: MAE ${stats.mae}")
                    assertTrue(stats.bias.isFinite() && stats.r2?.isFinite() != false, "${r.position}: $stats")
                }
                assertTrue(r.calibration in 0.3..1.0, "${r.position}: floor to ceiling held ${r.calibration}")
            }
            // About 2 s on a CI JVM; the budget only catches a runaway.
            assertTrue(seconds < 15.0, "$season's backtest took $seconds s")
        }
    }
}
