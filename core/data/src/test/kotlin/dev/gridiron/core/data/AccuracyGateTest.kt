package dev.gridiron.core.data

import dev.gridiron.core.model.ScoringPresets
import dev.gridiron.core.projections.ACCURACY_POSITIONS
import dev.gridiron.core.projections.ErrorStats
import dev.gridiron.core.projections.PositionAccuracy
import dev.gridiron.core.testing.JdbcQueryExecutor
import dev.gridiron.core.testing.StatsDb
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.io.File
import java.util.Locale

/**
 * CI's accuracy gate (spec §4): under PPR, the model's MAE must be below the
 * season-to-date average's at QB, RB, WR and TE. It runs only when
 * GRIDIRON_ACCURACY_GATE names the season, on a GRIDIRON_STATS_DB built with
 * that season and the one before it. The parity job sets both. The table
 * goes to build/reports/accuracy-gate.txt for the job log.
 */
@EnabledIfEnvironmentVariable(named = "GRIDIRON_ACCURACY_GATE", matches = "\\d{4}")
class AccuracyGateTest {
    @Test
    fun `the model beats the season-to-date average at every position`() = runTest {
        val season = System.getenv("GRIDIRON_ACCURACY_GATE").toInt()
        val path = checkNotNull(StatsDb.path) { "GRIDIRON_STATS_DB must name a database built with ${season - 1} and $season" }
        JdbcQueryExecutor(path).use { executor ->
            val results = AccuracyRepository(executor).backtest(season, ScoringPresets.PPR)
            val table = table(season, results)
            File("build/reports").mkdirs()
            File("build/reports/accuracy-gate.txt").writeText(table)

            assertEquals(ACCURACY_POSITIONS, results.map { it.position }, table)
            val losing = results.filter { it.model.mae >= it.seasonAverage.mae }.map { it.position }
            assertTrue(losing.isEmpty(), "the model doesn't beat the season-to-date average at $losing\n$table")
        }
    }

    private fun table(season: Int, results: List<PositionAccuracy>): String = buildString {
        fun cell(stats: ErrorStats) = String.format(Locale.US, "%.2f (%+.2f)", stats.mae, stats.bias)
        appendLine("$season, PPR: MAE (bias) by predictor")
        appendLine(String.format(Locale.US, "%-4s %6s %15s %15s %15s %6s", "pos", "n", "model", "season avg", "last 4", "held"))
        for (r in results) {
            appendLine(
                String.format(
                    Locale.US, "%-4s %6d %15s %15s %15s %5.0f%%",
                    r.position, r.playerWeeks, cell(r.model), cell(r.seasonAverage), cell(r.lastFour), r.calibration * 100,
                ),
            )
        }
    }
}
