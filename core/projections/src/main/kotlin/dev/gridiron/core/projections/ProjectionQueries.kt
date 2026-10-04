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
                "SELECT player_id, metric_id, stage, mean, variance, NULL AS dist_family " +
                    "FROM player_week_projection WHERE 0 = 1",
                emptyList(),
            )
        }
        val ids = playerIds.toList()
        return SqlQuery(
            """
            SELECT p.player_id, p.metric_id, p.stage, p.mean, p.variance, m.dist_family
            FROM player_week_projection p
            LEFT JOIN metric m ON m.id = p.metric_id
            WHERE p.player_id IN (${placeholders(ids.size)}) AND p.season = ? AND p.week = ?
            """.trimIndent(),
            ids.map { Bind.Text(it) } + listOf(Bind.Integer(season.toLong()), Bind.Integer(week.toLong())),
        )
    }

    /** Every player's Questionable discount that week: player id and its log multiplier. */
    public fun questionable(season: Int, week: Int): SqlQuery = SqlQuery(
        "SELECT player_id, log_multiplier FROM player_week_projection_factor WHERE season = ? AND week = ? AND factor = 'questionable'",
        listOf(Bind.Integer(season.toLong()), Bind.Integer(week.toLong())),
    )

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
                "SELECT player_id, metric_id, mean, variance, NULL AS dist_family FROM player_ros_projection WHERE 0 = 1",
                emptyList(),
            )
        }
        val ids = playerIds.toList()
        return SqlQuery(
            """
            SELECT r.player_id, r.metric_id, r.mean, r.variance, m.dist_family
            FROM player_ros_projection r
            LEFT JOIN metric m ON m.id = r.metric_id
            WHERE r.player_id IN (${placeholders(ids.size)}) AND r.season = ?
              AND r.as_of_week = (SELECT MAX(as_of_week) FROM player_ros_projection WHERE season = ?)
            """.trimIndent(),
            ids.map { Bind.Text(it) } + listOf(Bind.Integer(season.toLong()), Bind.Integer(season.toLong())),
        )
    }

    /** Every player's final projection for one week, with who he is. */
    public fun weekAll(season: Int, week: Int): SqlQuery = SqlQuery(
        """
        SELECT p.player_id, pl.full_name, pl.position, pl.team, p.metric_id, p.mean, p.variance, m.dist_family
        FROM player_week_projection p
        JOIN player pl ON pl.player_id = p.player_id
        LEFT JOIN metric m ON m.id = p.metric_id
        WHERE p.season = ? AND p.week = ? AND p.stage = 'final'
        """.trimIndent(),
        listOf(Bind.Integer(season.toLong()), Bind.Integer(week.toLong())),
    )

    /** Every player's rest of season as of the latest week it was built for. */
    public fun rosAll(season: Int): SqlQuery = SqlQuery(
        """
        SELECT r.player_id, pl.full_name, pl.position, pl.team, r.metric_id, r.mean, r.variance, m.dist_family
        FROM player_ros_projection r
        JOIN player pl ON pl.player_id = r.player_id
        LEFT JOIN metric m ON m.id = r.metric_id
        WHERE r.season = ? AND r.as_of_week = (SELECT MAX(as_of_week) FROM player_ros_projection WHERE season = ?)
        """.trimIndent(),
        listOf(Bind.Integer(season.toLong()), Bind.Integer(season.toLong())),
    )

    /**
     * Rest of season week by week, as of the latest week it was built for (`player_ros_week`): every player's, or only
     * [playerId]'s.
     */
    public fun rosWeeks(season: Int, playerId: String? = null): SqlQuery = SqlQuery(
        """
        SELECT r.player_id, pl.position, r.week, r.metric_id, r.mean, r.variance
        FROM player_ros_week r
        JOIN player pl ON pl.player_id = r.player_id
        WHERE r.season = ? AND r.as_of_week = (SELECT MAX(as_of_week) FROM player_ros_week WHERE season = ?)
        """.trimIndent() + if (playerId == null) "" else " AND r.player_id = ?",
        listOf(Bind.Integer(season.toLong()), Bind.Integer(season.toLong())) + listOfNotNull(playerId?.let { Bind.Text(it) }),
    )

    /** The forecast's `schema_meta` keys: status, build time, upcoming week per season. */
    public fun status(): SqlQuery = SqlQuery("SELECT key, value FROM schema_meta WHERE key LIKE 'forecast%'", emptyList())

    public fun game(season: Int, week: Int, team: String): SqlQuery = SqlQuery(
        """
        SELECT home_team, away_team, spread_line, total_line FROM game
        WHERE season = ? AND week = ? AND game_type = 'REG' AND (home_team = ? OR away_team = ?)
        """.trimIndent(),
        listOf(Bind.Integer(season.toLong()), Bind.Integer(week.toLong()), Bind.Text(team), Bind.Text(team)),
    )

    /** Every regular-season game of [season]: week and the two teams, for bye weeks. */
    public fun regularGames(season: Int): SqlQuery = SqlQuery(
        "SELECT week, home_team, away_team FROM game WHERE season = ? AND game_type = 'REG'",
        listOf(Bind.Integer(season.toLong())),
    )

    /** Regular-season games [team] plays from [fromWeek] on: rest of season's divisor for points per game. */
    public fun remainingGames(season: Int, fromWeek: Int, team: String): SqlQuery = SqlQuery(
        "SELECT COUNT(*) FROM game WHERE season = ? AND week >= ? AND game_type = 'REG' AND (home_team = ? OR away_team = ?)",
        listOf(Bind.Integer(season.toLong()), Bind.Integer(fromWeek.toLong()), Bind.Text(team), Bind.Text(team)),
    )
}
