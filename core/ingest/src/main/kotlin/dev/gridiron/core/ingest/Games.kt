package dev.gridiron.core.ingest

import dev.gridiron.core.ingest.csv.readCsv
import java.io.InputStream

/** One game from nflverse's schedule: who played whom, the result once played, and the pre-game line. */
internal data class GameRow(
    val gameId: String,
    val season: Int,
    val week: Int,
    /** REG, WC, DIV, CON or SB. */
    val gameType: String,
    val homeTeam: String,
    val awayTeam: String,
    val homeScore: Int?,
    val awayScore: Int?,
    /** Points the home team is favored by; negative when the away team is. */
    val spreadLine: Double?,
    val totalLine: Double?,
    val roof: String?,
    val homeQbId: String?,
    val awayQbId: String?,
    val homeCoach: String?,
    val awayCoach: String?,
)

private val REQUIRED = listOf("game_id", "season", "game_type", "week", "home_team", "away_team")
private val OPTIONAL = listOf(
    "home_score", "away_score", "spread_line", "total_line", "roof", "home_qb_id", "away_qb_id", "home_coach", "away_coach",
)

/** nflverse's `games.csv`, which covers every season since 1999, cut down to [seasons]. */
internal fun readGames(input: InputStream, source: String, seasons: Set<Int>): List<GameRow> {
    val games = ArrayList<GameRow>()
    readCsv(input, source, REQUIRED + OPTIONAL, required = REQUIRED) { row ->
        // nflverse writes missing values as empty fields, but older rows of this file use NA.
        fun text(name: String): String? = row.text(name)?.takeUnless { it == "NA" }
        fun number(name: String): Double? = text(name)?.toDoubleOrNull()

        val season = number("season")?.toInt() ?: return@readCsv
        if (season !in seasons) return@readCsv
        games += GameRow(
            gameId = text("game_id") ?: return@readCsv,
            season = season,
            week = number("week")?.toInt() ?: return@readCsv,
            gameType = text("game_type") ?: return@readCsv,
            homeTeam = text("home_team") ?: return@readCsv,
            awayTeam = text("away_team") ?: return@readCsv,
            homeScore = number("home_score")?.toInt(),
            awayScore = number("away_score")?.toInt(),
            spreadLine = number("spread_line"),
            totalLine = number("total_line"),
            roof = text("roof"),
            homeQbId = text("home_qb_id"),
            awayQbId = text("away_qb_id"),
            homeCoach = text("home_coach"),
            awayCoach = text("away_coach"),
        )
    }
    return games
}
