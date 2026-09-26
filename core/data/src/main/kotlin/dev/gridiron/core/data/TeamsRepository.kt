package dev.gridiron.core.data

import dev.gridiron.core.database.QueryExecutor
import dev.gridiron.core.database.textOrNull
import dev.gridiron.core.statquery.Bind
import dev.gridiron.core.statquery.SqlQuery

public data class InjuryRow(
    val playerId: String,
    val name: String,
    val team: String,
    val position: String,
    val week: Int,
    val status: String?,
    val injury: String?,
    val practice: String?,
)

/** Season totals for one defense; per-game averages are derived. */
public data class DefenseRow(
    val team: String,
    val games: Int,
    val pointsAllowed: Double,
    val yardsAllowed: Double,
    val sacks: Double,
    val interceptions: Double,
    val fumblesRecovered: Double,
    val defensiveTds: Double,
)

/** The injury report and team defense tables. */
public class TeamsRepository(private val executor: QueryExecutor) {

    /** Each player's latest report in [season], most recent week first. */
    public suspend fun injuries(season: Int): List<InjuryRow> = executor.query(
        SqlQuery(
            """
            SELECT i.player_id, i.name, i.team, i.position, i.week, i.status, i.injury, i.practice
            FROM injury_report i
            JOIN (SELECT player_id, MAX(week) AS week FROM injury_report WHERE season = ? GROUP BY player_id) latest
              ON latest.player_id = i.player_id AND latest.week = i.week
            WHERE i.season = ? AND (i.status IS NOT NULL OR i.injury IS NOT NULL)
            ORDER BY i.week DESC, i.team, i.name
            """.trimIndent(),
            listOf(Bind.Integer(season.toLong()), Bind.Integer(season.toLong())),
        ),
    ) {
        InjuryRow(
            playerId = it.text(0),
            name = it.textOrNull(1) ?: "?",
            team = it.textOrNull(2) ?: "",
            position = it.textOrNull(3) ?: "",
            week = it.long(4).toInt(),
            status = it.textOrNull(5),
            injury = it.textOrNull(6),
            practice = it.textOrNull(7),
        )
    }

    /** Every defense's [season] totals, fewest points allowed per game first. */
    public suspend fun defense(season: Int): List<DefenseRow> = executor.query(
        SqlQuery(
            """
            SELECT team, COUNT(*), SUM(points_allowed), SUM(yards_allowed), SUM(sacks),
                   SUM(interceptions), SUM(fumbles_recovered), SUM(defensive_tds)
            FROM team_week_defense
            WHERE season = ?
            GROUP BY team
            ORDER BY SUM(points_allowed) * 1.0 / COUNT(*), team
            """.trimIndent(),
            listOf(Bind.Integer(season.toLong())),
        ),
    ) {
        DefenseRow(
            team = it.text(0),
            games = it.long(1).toInt(),
            pointsAllowed = it.double(2),
            yardsAllowed = it.double(3),
            sacks = it.double(4),
            interceptions = it.double(5),
            fumblesRecovered = it.double(6),
            defensiveTds = it.double(7),
        )
    }
}
