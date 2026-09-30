package dev.gridiron.core.statquery

/**
 * Fixed queries describing what the database holds. Kept beside the Grid
 * builder so every piece of SQL the app issues lives in this module and is
 * exercised by its contract tests.
 */
public object CatalogQueries {
    /** Visible metrics: id, name, abbr, definition, formula, predicts, stability. */
    public val metrics: SqlQuery = SqlQuery(
        "SELECT id, name, abbr, definition, formula, predicts, stability FROM metric WHERE internal = 0",
        emptyList(),
    )

    /**
     * Each season with the last week that has any data: season, last_week.
     * Reads only the games component, through the metric index.
     */
    public val seasons: SqlQuery = SqlQuery(
        "SELECT season, MAX(week) FROM player_week_stat WHERE metric_id = ? GROUP BY season ORDER BY season",
        listOf(Bind.Text(Components.GAMES.id)),
    )

    /**
     * The pre-aggregated windows stored for one season: window, first_week, last_week. The table is missing from a
     * database built before schema 9.
     */
    public fun windows(season: Int): SqlQuery = SqlQuery(
        "SELECT window, first_week, last_week FROM window_def WHERE season = ? ORDER BY window",
        listOf(Bind.Integer(season.toLong())),
    )

    /** Every team a player is currently on, alphabetically: team. */
    public val teams: SqlQuery = SqlQuery(
        "SELECT DISTINCT team FROM player WHERE team IS NOT NULL ORDER BY team",
        emptyList(),
    )
}
