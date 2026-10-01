package dev.gridiron.core.data

import dev.gridiron.core.database.QueryExecutor
import dev.gridiron.core.database.doubleOrNull
import dev.gridiron.core.data.live.EspnGame
import dev.gridiron.core.data.live.EspnParser
import dev.gridiron.core.data.live.HttpGet
import dev.gridiron.core.data.live.LiveFormatException
import dev.gridiron.core.model.ScoringProfile
import dev.gridiron.core.statquery.Bind
import dev.gridiron.core.statquery.Components
import dev.gridiron.core.statquery.SqlQuery
import kotlinx.coroutines.CancellationException
import java.io.IOException
import java.time.Instant
import kotlin.math.abs

public enum class GameState { SCHEDULED, LIVE, FINAL }

/**
 * One NFL game of a week. [homeScore] and [awayScore] are null before kickoff. [spread] is nflverse's line from the
 * home side (positive: the home team is favored).
 */
public data class ScoreGame(
    val home: String,
    val away: String,
    val homeScore: Int?,
    val awayScore: Int?,
    val state: GameState,
    val kickoff: Instant?,
    /** ESPN's status text when it is live or just final: "4:14 - 3rd", "Final/OT". */
    val detail: String?,
    val spread: Double?,
    val total: Double?,
) {
    /** "KC -2.5": the favorite and its line, or "Pick'em"; null with no line. */
    public val spreadText: String?
        get() = spread?.let { s ->
            if (s == 0.0) "Pick'em" else "${if (s > 0) home else away} -${formatLine(abs(s))}"
        }

    /** The winner's code once final, null for a tie or an unfinished game. */
    public val winner: String?
        get() = if (state != GameState.FINAL || homeScore == null || awayScore == null || homeScore == awayScore) {
            null
        } else if (homeScore > awayScore) {
            home
        } else {
            away
        }
}

private fun formatLine(x: Double): String = if (x % 1.0 == 0.0) x.toInt().toString() else x.toString()

/** [week]'s games, the teams with no game, and why ESPN's live layer is missing, if it is. */
public data class ScoresWeek(
    val season: Int,
    val week: Int,
    val games: List<ScoreGame>,
    val byes: List<String>,
    val liveError: String?,
)

/** One player's week in a game: his fantasy points under the scoring profile. */
public data class GamePlayer(val playerId: String, val name: String, val position: String?, val points: Double?)

/** Both teams' players in a game, best fantasy week first. */
public data class GameDetail(val home: List<GamePlayer>, val away: List<GamePlayer>)

/**
 * The week-by-week NFL scores. `stats.db`'s `game` table holds every game of the season (the schedule, final scores
 * and the spread), so the screen works offline; ESPN's scoreboard adds kickoff times, live scores and the clock for a
 * week that isn't over, and a failure there leaves the table's view with a note.
 */
public class ScoresRepository(
    private val executor: QueryExecutor,
    private val http: HttpGet,
) {
    /** The season's weeks with games, in order. */
    public suspend fun weeks(season: Int): List<Int> =
        executor.query(
            SqlQuery("SELECT DISTINCT week FROM game WHERE season = ? ORDER BY week", listOf(Bind.Integer(season.toLong()))),
        ) { it.long(0).toInt() }

    /** The first week with a game still to play, else the season's last week. */
    public suspend fun currentWeek(season: Int): Int? {
        val weeks = weeks(season)
        if (weeks.isEmpty()) return null
        val open = executor.query(
            SqlQuery(
                "SELECT MIN(week) FROM game WHERE season = ? AND home_score IS NULL",
                listOf(Bind.Integer(season.toLong())),
            ),
        ) { if (it.isNull(0)) null else it.long(0).toInt() }.singleOrNull()
        return open ?: weeks.last()
    }

    public suspend fun week(season: Int, week: Int): ScoresWeek {
        val table = executor.query(
            SqlQuery(
                "SELECT home_team, away_team, home_score, away_score, spread_line, total_line FROM game " +
                    "WHERE season = ? AND week = ? ORDER BY game_id",
                listOf(Bind.Integer(season.toLong()), Bind.Integer(week.toLong())),
            ),
        ) {
            ScoreGame(
                home = it.text(0),
                away = it.text(1),
                homeScore = if (it.isNull(2)) null else it.long(2).toInt(),
                awayScore = if (it.isNull(3)) null else it.long(3).toInt(),
                state = if (it.isNull(2)) GameState.SCHEDULED else GameState.FINAL,
                kickoff = null,
                detail = null,
                spread = it.doubleOrNull(4),
                total = it.doubleOrNull(5),
            )
        }
        // A week that is over in the table needs nothing from ESPN.
        val needsLive = table.isEmpty() || table.any { it.state != GameState.FINAL }
        var error: String? = null
        val live = if (needsLive) {
            try {
                EspnParser.scoreboard(http.get(EspnParser.scoreboardUrl(season, week)))
            } catch (e: CancellationException) {
                throw e
            } catch (e: LiveFormatException) {
                error = e.message
                emptyList()
            } catch (e: IOException) {
                error = "Couldn't reach ESPN: ${e.message ?: "network error"}"
                emptyList()
            }
        } else {
            emptyList()
        }
        val games = merge(table, live)
        val playing = games.flatMap { listOf(it.home, it.away) }.toSet()
        val teams = executor.query(
            SqlQuery(
                "SELECT home_team FROM game WHERE season = ? UNION SELECT away_team FROM game WHERE season = ?",
                listOf(Bind.Integer(season.toLong()), Bind.Integer(season.toLong())),
            ),
        ) { it.text(0) }
        return ScoresWeek(season, week, games, teams.filter { it !in playing }.sorted(), error)
    }

    /** Both teams' players that week, scored with [scoring]. */
    public suspend fun detail(season: Int, week: Int, home: String, away: String, scoring: ScoringProfile): GameDetail {
        val teamOf = executor.query(
            SqlQuery(
                "SELECT DISTINCT player_id, team FROM player_week_stat WHERE metric_id = ? AND season = ? AND week = ? AND team IN (?, ?)",
                listOf(Bind.Text(Components.GAMES.id), Bind.Integer(season.toLong()), Bind.Integer(week.toLong()), Bind.Text(home), Bind.Text(away)),
            ),
        ) { it.text(0) to it.text(1) }.toMap()
        if (teamOf.isEmpty()) return GameDetail(emptyList(), emptyList())
        val players = executor.weekPoints(season, week, teamOf.keys, scoring)
        fun side(team: String) = players.filter { teamOf[it.playerId] == team }
        return GameDetail(side(home), side(away))
    }

    internal companion object {
        /**
         * The table's games with ESPN's overlay: a game ESPN has started or finished takes its scores, state and clock,
         * and a game ESPN has scheduled takes its kickoff. A game only ESPN lists is added.
         */
        fun merge(table: List<ScoreGame>, live: List<EspnGame>): List<ScoreGame> {
            val byTeams = live.associateBy { it.home to it.away }
            val merged = table.map { g ->
                val e = byTeams[g.home to g.away] ?: return@map g
                if (e.state == EspnGame.State.SCHEDULED) {
                    g.copy(kickoff = e.kickoff, detail = e.detail)
                } else {
                    g.copy(
                        homeScore = e.homeScore ?: g.homeScore,
                        awayScore = e.awayScore ?: g.awayScore,
                        state = if (e.state == EspnGame.State.LIVE) GameState.LIVE else GameState.FINAL,
                        kickoff = e.kickoff,
                        detail = e.detail,
                    )
                }
            }
            val known = table.map { it.home to it.away }.toSet()
            val extra = live.filter { (it.home to it.away) !in known }.map { e ->
                ScoreGame(
                    e.home, e.away, e.homeScore, e.awayScore,
                    when (e.state) {
                        EspnGame.State.SCHEDULED -> GameState.SCHEDULED
                        EspnGame.State.LIVE -> GameState.LIVE
                        EspnGame.State.FINAL -> GameState.FINAL
                    },
                    e.kickoff, e.detail, null, null,
                )
            }
            return (merged + extra).sortedWith(compareBy(nullsLast<Instant>(), ScoreGame::kickoff))
        }
    }
}
