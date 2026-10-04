package dev.gridiron.core.data

import dev.gridiron.core.statquery.Bind
import dev.gridiron.core.statquery.SqlQuery
import dev.gridiron.core.testing.JdbcQueryExecutor
import dev.gridiron.core.testing.StatsDb
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.io.File
import java.util.Locale

/**
 * The Rising roles backtest: among players entering weeks 6-14, the top 15% by score should keep a bigger role more
 * often than the rest. "Bigger" is next-four-game usage (RB: carries + targets; WR, TE: targets) at least 25% above the
 * eight games before his last four, the same baseline the score reads; so the figure is a check that the trend persists,
 * not a claim about points or beating a projection (neither held up). Runs only when GRIDIRON_ACCURACY_GATE names the
 * season, on a GRIDIRON_STATS_DB built with it and the season before; the parity job sets both. The table goes to
 * build/reports/breakout-backtest.txt.
 */
@EnabledIfEnvironmentVariable(named = "GRIDIRON_ACCURACY_GATE", matches = "\\d{4}")
class BreakoutBacktestTest {
    private data class Case(val position: String, val score: Double, val kept: Boolean)

    @Test
    fun `a high score means the role keeps growing more often than chance`() = runTest {
        val season = System.getenv("GRIDIRON_ACCURACY_GATE").toInt()
        val path = checkNotNull(StatsDb.path) { "GRIDIRON_STATS_DB must name a database built with ${season - 1} and $season" }
        JdbcQueryExecutor(path).use { executor ->
            val usage = HashMap<Pair<String, Int>, Double>()
            executor.query(
                SqlQuery(
                    "SELECT player_id, week, SUM(value) FROM player_week_stat WHERE season = ? AND metric_id IN ('carries', 'targets') GROUP BY player_id, week",
                    listOf(Bind.Integer(season.toLong())),
                ),
            ) { usage[it.text(0) to it.long(1).toInt()] = it.double(2) }
            // A WR's usage is his targets alone: carries are rare enough that adding them changes no ranking, and the score ignores them.
            val rows = executor.query(
                SqlQuery(
                    """SELECT s.player_id, s.week, p.position, s.score, s.usage_base FROM player_week_signal s
                       JOIN player p ON p.player_id = s.player_id
                       WHERE s.season = ? AND s.week BETWEEN 6 AND 14""",
                    listOf(Bind.Integer(season.toLong())),
                ),
            ) { listOf(it.text(0), it.long(1).toString(), it.text(2), it.double(3).toString(), it.double(4).toString()) }
            val cases = rows.mapNotNull { (id, week, position, score, base) ->
                val w = week.toInt()
                val next = (w..w + 3).mapNotNull { usage[id to it] }
                if (next.size < 2) null else Case(position, score.toDouble(), next.average() >= 1.25 * base.toDouble())
            }

            val lines = StringBuilder("Rising roles $season, weeks 6-14: top 15% by score vs everyone\n")
            val lifts = HashMap<String, Double>()
            for (position in listOf("RB", "WR", "TE")) {
                val of = cases.filter { it.position == position }.sortedByDescending { it.score }
                if (of.isEmpty()) continue
                val top = of.take(maxOf(1, of.size * 15 / 100))
                val base = of.count { it.kept }.toDouble() / of.size
                val hit = top.count { it.kept }.toDouble() / top.size
                lifts[position] = if (base > 0) hit / base else 0.0
                lines.append(String.format(Locale.US, "%-3s n=%4d  base %.2f  top %.2f  lift %.2f%n", position, of.size, base, hit, lifts.getValue(position)))
            }
            File("build/reports").mkdirs()
            File("build/reports/breakout-backtest.txt").writeText(lines.toString())

            for (position in listOf("RB", "WR", "TE")) {
                val lift = lifts[position]
                assertTrue(lift != null && lift >= MIN_LIFT, "$position lift $lift is under $MIN_LIFT\n$lines")
            }
        }
    }

    private companion object {
        /** 2025's lifts were 2.5, 2.7 and 2.2; this leaves room for a different season, not for a signal that stopped working. */
        const val MIN_LIFT = 1.5
    }
}
