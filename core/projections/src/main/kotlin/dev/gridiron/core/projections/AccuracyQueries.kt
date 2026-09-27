package dev.gridiron.core.projections

import dev.gridiron.core.statquery.Bind
import dev.gridiron.core.statquery.Components
import dev.gridiron.core.statquery.SqlQuery

/** The backtest's reads. Every value is bound, as in [ProjectionQueries]. */
public object AccuracyQueries {
    private fun placeholders(n: Int): String = List(n) { "?" }.joinToString(",")

    /** Each season's first week with a final projection. */
    public fun firstProjectedWeeks(): SqlQuery = SqlQuery(
        "SELECT season, MIN(week) FROM player_week_projection WHERE stage = 'final' GROUP BY season ORDER BY season",
        emptyList(),
    )

    /** [season]'s final projections for weeks before [beforeWeek], at the positions the backtest measures. */
    public fun projected(season: Int, beforeWeek: Int): SqlQuery = SqlQuery(
        """
        SELECT p.player_id, pl.position, p.week, p.metric_id, p.mean, p.variance, m.dist_family
        FROM player_week_projection p
        JOIN player pl ON pl.player_id = p.player_id
        LEFT JOIN metric m ON m.id = p.metric_id
        WHERE p.season = ? AND p.week < ? AND p.stage = 'final'
          AND pl.position IN (${placeholders(ACCURACY_POSITIONS.size)})
        """.trimIndent(),
        listOf(Bind.Integer(season.toLong()), Bind.Integer(beforeWeek.toLong())) + ACCURACY_POSITIONS.map { Bind.Text(it) },
    )

    /** Every week with a recorded play ([Components.GAMES]) and the scoring stats, [fromSeason] through [toSeason]. */
    public fun played(fromSeason: Int, toSeason: Int): SqlQuery {
        val metrics = listOf(Components.GAMES) + ACTUAL_SCORING_COMPONENTS
        return SqlQuery(
            """
            SELECT player_id, season, week, metric_id, value
            FROM player_week_stat
            WHERE season BETWEEN ? AND ? AND metric_id IN (${placeholders(metrics.size)})
            """.trimIndent(),
            listOf(Bind.Integer(fromSeason.toLong()), Bind.Integer(toSeason.toLong())) + metrics.map { Bind.Text(it.id) },
        )
    }
}
