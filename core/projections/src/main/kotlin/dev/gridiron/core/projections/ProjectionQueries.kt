package dev.gridiron.core.projections

import dev.gridiron.core.statquery.Bind
import dev.gridiron.core.statquery.SqlQuery

/**
 * Raw SQL for the projection tables, following [dev.gridiron.core.statquery.CatalogQueries]'s
 * convention: every value is a `?` bound through [Bind], never string-interpolated
 * (`SqlQuery`'s own `init` block enforces placeholder count == bind count, so a
 * mismatched query fails to construct rather than reaching the database). An
 * empty [playerIds] set produces a `WHERE 0 = 1` clause rather than an empty
 * `IN ()`, which SQLite rejects outright.
 */
public object ProjectionQueries {
    private fun placeholders(n: Int): String = List(n) { "?" }.joinToString(",")

    public fun weekly(playerIds: Set<String>, season: Int, week: Int): SqlQuery {
        if (playerIds.isEmpty()) {
            return SqlQuery(
                "SELECT player_id, metric_id, stage, mean, variance " +
                    "FROM player_week_projection WHERE 0 = 1",
                emptyList(),
            )
        }
        val ids = playerIds.toList()
        return SqlQuery(
            """
            SELECT player_id, metric_id, stage, mean, variance
            FROM player_week_projection
            WHERE player_id IN (${placeholders(ids.size)}) AND season = ? AND week = ?
            """.trimIndent(),
            ids.map { Bind.Text(it) } + listOf(Bind.Integer(season.toLong()), Bind.Integer(week.toLong())),
        )
    }

    public fun factors(playerIds: Set<String>, season: Int, week: Int): SqlQuery {
        if (playerIds.isEmpty()) {
            return SqlQuery(
                "SELECT player_id, factor, log_multiplier, note " +
                    "FROM player_week_projection_factor WHERE 0 = 1",
                emptyList(),
            )
        }
        val ids = playerIds.toList()
        return SqlQuery(
            """
            SELECT player_id, factor, log_multiplier, note
            FROM player_week_projection_factor
            WHERE player_id IN (${placeholders(ids.size)}) AND season = ? AND week = ?
            """.trimIndent(),
            ids.map { Bind.Text(it) } + listOf(Bind.Integer(season.toLong()), Bind.Integer(week.toLong())),
        )
    }

    public fun ros(playerIds: Set<String>, season: Int): SqlQuery {
        if (playerIds.isEmpty()) {
            return SqlQuery(
                "SELECT player_id, metric_id, mean, variance FROM player_ros_projection WHERE 0 = 1",
                emptyList(),
            )
        }
        val ids = playerIds.toList()
        return SqlQuery(
            """
            SELECT player_id, metric_id, mean, variance
            FROM player_ros_projection
            WHERE player_id IN (${placeholders(ids.size)}) AND season = ?
              AND as_of_week = (SELECT MAX(as_of_week) FROM player_ros_projection WHERE season = ?)
            """.trimIndent(),
            ids.map { Bind.Text(it) } + listOf(Bind.Integer(season.toLong()), Bind.Integer(season.toLong())),
        )
    }
}
