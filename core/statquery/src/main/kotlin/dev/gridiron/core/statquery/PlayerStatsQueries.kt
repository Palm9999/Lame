package dev.gridiron.core.statquery

/** The two small reads behind the Player page's game log and season chips. Every value is a bound `?`. */
public object PlayerStatsQueries {
    /** The seasons [playerId] has games in, oldest first: season. Reads the games component through the metric index. */
    public fun seasons(playerId: String): SqlQuery = SqlQuery(
        "SELECT DISTINCT season FROM player_week_stat WHERE metric_id = ? AND player_id = ? ORDER BY season",
        listOf(Bind.Text(Components.GAMES.id), Bind.Text(playerId)),
    )

    /** The weeks [playerId] played in [season] and his team each week: week, team. */
    public fun weekTeams(playerId: String, season: Int): SqlQuery = SqlQuery(
        "SELECT week, team FROM player_week_stat WHERE metric_id = ? AND player_id = ? AND season = ? ORDER BY week",
        listOf(Bind.Text(Components.GAMES.id), Bind.Text(playerId), Bind.Integer(season.toLong())),
    )

    /** [season]'s regular-season games: week, home_team, away_team, home_score, away_score. */
    public fun games(season: Int): SqlQuery = SqlQuery(
        "SELECT week, home_team, away_team, home_score, away_score FROM game WHERE season = ? AND game_type = 'REG' ORDER BY week",
        listOf(Bind.Integer(season.toLong())),
    )
}
