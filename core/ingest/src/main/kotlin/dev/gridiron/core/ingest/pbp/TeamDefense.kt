package dev.gridiron.core.ingest.pbp

internal data class TeamDefenseRow(
    val team: String,
    val season: Int,
    val week: Int,
    val pointsAllowed: Double,
    val yardsAllowed: Double,
    val sacks: Double,
    val interceptions: Double,
    val fumblesRecovered: Double,
    val defensiveTds: Double,
)

/** One row per (team, season, week): what the defense allowed and took away. Ports `teams.team_defense_from`. */
internal class TeamDefenseAggregator {
    private class Game(val season: Int, val week: Int) {
        var home: String? = null
        var away: String? = null
        var homeScore = 0.0
        var awayScore = 0.0
    }

    private class Allowed {
        var yards = 0.0
        var sacks = 0.0
        var interceptions = 0.0
        var fumbles = 0.0
        var tds = 0.0
    }

    private val games = LinkedHashMap<String, Game>()
    private val allowed = HashMap<Triple<String, Int, Int>, Allowed>()

    fun add(p: Play) {
        if (p.seasonType !in SEASON_TYPES) return
        p.gameId?.let { id ->
            val g = games.getOrPut(id) { Game(p.season, p.week) }
            if (g.home == null) g.home = p.homeTeam
            if (g.away == null) g.away = p.awayTeam
            g.homeScore = maxOf(g.homeScore, p.totalHomeScore ?: 0.0)
            g.awayScore = maxOf(g.awayScore, p.totalAwayScore ?: 0.0)
        }
        val defense = p.defteam ?: return
        val a = allowed.getOrPut(Triple(defense, p.season, p.week)) { Allowed() }
        if (p.playType == "pass" || p.playType == "run") a.yards += p.yardsGained ?: 0.0
        a.sacks += p.sack ?: 0.0
        a.interceptions += p.interception ?: 0.0
        a.fumbles += p.fumbleLost ?: 0.0
        if (p.tdTeam != null && p.tdTeam == defense) a.tds += p.touchdown ?: 0.0
    }

    fun rows(): List<TeamDefenseRow> = games.values
        .flatMap { g ->
            listOfNotNull(
                g.home?.let { row(it, g, g.awayScore) },
                g.away?.let { row(it, g, g.homeScore) },
            )
        }
        .sortedWith(compareBy({ it.season }, { it.week }, { it.team }))

    private fun row(team: String, g: Game, pointsAllowed: Double): TeamDefenseRow {
        val a = allowed[Triple(team, g.season, g.week)]
        return TeamDefenseRow(
            team, g.season, g.week, pointsAllowed,
            a?.yards ?: 0.0, a?.sacks ?: 0.0, a?.interceptions ?: 0.0, a?.fumbles ?: 0.0, a?.tds ?: 0.0,
        )
    }
}
