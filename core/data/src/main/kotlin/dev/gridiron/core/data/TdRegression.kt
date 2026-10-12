package dev.gridiron.core.data

import dev.gridiron.core.database.QueryExecutor
import dev.gridiron.core.model.Position
import dev.gridiron.core.model.ScoringProfile
import dev.gridiron.core.projections.score
import dev.gridiron.core.statquery.Bind
import dev.gridiron.core.statquery.Component
import dev.gridiron.core.statquery.SqlQuery
import java.util.Locale

/** One player's touchdowns this season against ffopportunity's expected touchdowns, through [throughWeek]. */
public data class TdRegressionRow(
    val playerId: String,
    val name: String,
    val position: String,
    val team: String?,
    val tds: Double,
    val expected: Double,
) {
    /** Touchdowns above expected: positive runs hot (likely to fall back), negative runs cold. */
    val gap: Double get() = tds - expected
}

/** [rows] best gap first; [throughWeek] is the last week ffopportunity has processed (0 when none). */
public data class TdRegressionBoard(val throughWeek: Int, val rows: List<TdRegressionRow>) {
    /** Touchdowns above expected by player id, for Trade's rows. */
    val gaps: Map<String, Double> get() = rows.associate { it.playerId to it.gap }
}

/** One player's fantasy points against those expected from his usage, over [games] games. */
public data class PointsGapRow(
    val playerId: String,
    val name: String,
    val position: String,
    val team: String?,
    val games: Int,
    val points: Double,
    val expected: Double,
) {
    /** Points above expected a game: positive is a sell-high (likely to fall back), negative a buy-low. */
    val gapPerGame: Double get() = (points - expected) / games
}

/** [rows] best gap a game first; [throughWeek] as in [TdRegressionBoard]. */
public data class PointsGapBoard(val throughWeek: Int, val rows: List<PointsGapRow>)

/** "14.2 pts a game on 11.0 expected (+3.2)". */
public fun pointsGapLine(row: PointsGapRow): String =
    String.format(Locale.US, "%.1f pts a game on %.1f expected (%+.1f)", row.points / row.games, row.expected / row.games, row.gapPerGame)

/** The Player page's line: "7 TDs against 4.3 expected through week 5: running hot (+2.7)". */
public fun tdRegressionLine(row: TdRegressionRow, throughWeek: Int): String {
    val side = if (row.gap >= 0.0) "running hot" else "running cold"
    return String.format(Locale.US, "%.0f TDs against %.1f expected through week %d: %s (%+.1f)", row.tds, row.expected, throughWeek, side, row.gap)
}

/** Trade's tag: "TDs +2.7 vs exp". */
public fun tdGapTag(gap: Double): String = String.format(Locale.US, "TDs %+.1f vs exp", gap)

/**
 * The TD regression board: every QB, RB, WR and TE's passing, rushing and receiving touchdowns against expected
 * touchdowns over the weeks ffopportunity has processed (its rows lag a week or so, and absent means zero, so later
 * weeks would count real touchdowns against no expectation).
 */
public class TdRegressionRepository(private val executor: QueryExecutor) {
    public suspend fun board(season: Int): TdRegressionBoard {
        val through = throughWeek(season)
        if (through < 1) return TdRegressionBoard(0, emptyList())
        val rows = executor.query(
            SqlQuery(
                """
                SELECT p.player_id, p.full_name, p.position, p.team,
                       SUM(CASE WHEN s.metric_id IN ('passing_tds', 'rushing_tds', 'receiving_tds') THEN s.value ELSE 0 END) AS tds,
                       SUM(CASE WHEN s.metric_id IN ('x_passing_tds', 'x_rushing_tds', 'x_receiving_tds') THEN s.value ELSE 0 END) AS xtds
                FROM player_week_stat s JOIN player p ON p.player_id = s.player_id
                WHERE s.season = ? AND s.week <= ? AND p.position IN ('QB', 'RB', 'WR', 'TE')
                  AND s.metric_id IN ('passing_tds', 'rushing_tds', 'receiving_tds', 'x_passing_tds', 'x_rushing_tds', 'x_receiving_tds')
                GROUP BY p.player_id
                HAVING xtds >= ? OR tds >= ?
                """.trimIndent(),
                listOf(Bind.Integer(season.toLong()), Bind.Integer(through.toLong()), Bind.Real(MIN_TDS), Bind.Real(MIN_TDS)),
            ),
        ) { TdRegressionRow(it.text(0), it.text(1), it.text(2), if (it.isNull(3)) null else it.text(3), it.double(4), it.double(5)) }
        return TdRegressionBoard(through, rows.sortedByDescending { it.gap })
    }

    /**
     * Buy low / sell high: every QB, RB, WR and TE with [MIN_GAMES] or more games, his fantasy points under [profile]
     * against those ffopportunity expects from his usage, over the same weeks as [board]. Both sides count only the
     * stats ffopportunity models ([USAGE_STATS]: yards, touchdowns, receptions, completions, interceptions, first
     * downs), so fumbles, two-point plays and per-game yardage bonuses are left out of each.
     */
    public suspend fun pointsBoard(season: Int, profile: ScoringProfile): PointsGapBoard {
        val through = throughWeek(season)
        if (through < 1) return PointsGapBoard(0, emptyList())
        val metrics = listOf("g") + USAGE_STATS + USAGE_STATS.map { "x_$it" }
        data class Sum(val id: String, val name: String, val position: String, val team: String?, val metric: String, val value: Double)
        val sums = executor.query(
            SqlQuery(
                """
                SELECT p.player_id, p.full_name, p.position, p.team, s.metric_id, SUM(s.value)
                FROM player_week_stat s JOIN player p ON p.player_id = s.player_id
                WHERE s.season = ? AND s.week <= ? AND p.position IN ('QB', 'RB', 'WR', 'TE')
                  AND s.metric_id IN (${metrics.joinToString { "?" }})
                GROUP BY p.player_id, s.metric_id
                """.trimIndent(),
                listOf(Bind.Integer(season.toLong()), Bind.Integer(through.toLong())) + metrics.map { Bind.Text(it) },
            ),
        ) { Sum(it.text(0), it.text(1), it.text(2), if (it.isNull(3)) null else it.text(3), it.text(4), it.double(5)) }
        // Season totals: a per-game yardage bonus would read as earned every week.
        val flat = profile.copy(yardageBonuses = emptyList())
        val rows = sums.groupBy { it.id }.mapNotNull { (_, player) ->
            val first = player.first()
            val by = player.associate { it.metric to it.value }
            val games = (by["g"] ?: 0.0).toInt()
            if (games < MIN_GAMES) return@mapNotNull null
            val position = Position.fromCode(first.position)
            fun points(prefix: String) = score(USAGE_STATS.associate { Component(it) to (by[prefix + it] ?: 0.0) }, flat, position)
            PointsGapRow(first.id, first.name, first.position, first.team, games, points(""), points("x_"))
        }
        return PointsGapBoard(through, rows.sortedByDescending { it.gapPerGame })
    }

    /** The last week ffopportunity has processed for [season]; 0 when none. */
    private suspend fun throughWeek(season: Int): Int = executor.query(
        SqlQuery("SELECT value FROM schema_meta WHERE key = ?", listOf(Bind.Text("expected_through_week:$season"))),
    ) { it.text(0).toIntOrNull() }.firstOrNull() ?: 0

    private companion object {
        /** Below two touchdowns either way, the gap is mostly noise. */
        const val MIN_TDS = 2.0

        /** Fewer games and one long touchdown is the whole gap. */
        const val MIN_GAMES = 3

        /** The stats ffopportunity gives an expected value for (its `x_` twin). */
        val USAGE_STATS = listOf(
            "completions", "interceptions", "passing_yards", "passing_tds", "passing_first_downs",
            "rushing_yards", "rushing_tds", "rushing_first_downs",
            "receptions", "receiving_yards", "receiving_tds", "receiving_first_downs",
        )
    }
}
