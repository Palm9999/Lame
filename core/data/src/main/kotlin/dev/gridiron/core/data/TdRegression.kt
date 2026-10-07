package dev.gridiron.core.data

import dev.gridiron.core.database.QueryExecutor
import dev.gridiron.core.statquery.Bind
import dev.gridiron.core.statquery.SqlQuery

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
public data class TdRegressionBoard(val throughWeek: Int, val rows: List<TdRegressionRow>)

/**
 * The TD regression board: every QB, RB, WR and TE's passing, rushing and receiving touchdowns against expected
 * touchdowns over the weeks ffopportunity has processed (its rows lag a week or so, and absent means zero, so later
 * weeks would count real touchdowns against no expectation).
 */
public class TdRegressionRepository(private val executor: QueryExecutor) {
    public suspend fun board(season: Int): TdRegressionBoard {
        val through = executor.query(
            SqlQuery("SELECT value FROM schema_meta WHERE key = ?", listOf(Bind.Text("expected_through_week:$season"))),
        ) { it.text(0).toIntOrNull() }.firstOrNull() ?: 0
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

    private companion object {
        /** Below two touchdowns either way, the gap is mostly noise. */
        const val MIN_TDS = 2.0
    }
}
