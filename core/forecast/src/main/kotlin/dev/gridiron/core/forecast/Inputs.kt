package dev.gridiron.core.forecast

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement

internal val POSITIONS: List<String> = listOf("QB", "RB", "WR", "TE")

/** Kickers and team defenses: projected by their own models, from their own stats, never counted in team volume. */
internal val UNIT_POSITIONS: List<String> = listOf("K", "DST")

/** Sorts weeks chronologically across seasons: 2025 week 18 comes before 2026 week 1. */
internal fun order(season: Int, week: Int): Int = season * 100 + week

/** One week a player played: every stat the model reads. Scoring inputs are stored sparse, so absent means zero. */
internal class PlayerGame(
    val playerId: String,
    val season: Int,
    val week: Int,
    val team: String,
    private val stats: Map<String, Double>,
) {
    val order: Int get() = order(season, week)

    operator fun get(metric: String): Double = stats[metric] ?: 0.0
}

internal data class PlayerInfo(val playerId: String, val name: String, val position: String, val team: String?)

/** A scheduled game. Lines, QBs and coaches are pre-game information, so any week may read any game. */
internal data class Game(
    val season: Int,
    val week: Int,
    val regular: Boolean,
    val home: String,
    val away: String,
    val played: Boolean,
    /** Points the home team is favored by (nflverse's spread_line); null until posted. */
    val spread: Double?,
    val total: Double?,
    val homeQb: String?,
    val awayQb: String?,
    val homeCoach: String?,
    val awayCoach: String?,
) {
    fun involves(team: String): Boolean = team == home || team == away

    fun opponentOf(team: String): String = if (team == home) away else home

    fun isHome(team: String): Boolean = team == home

    fun qbOf(team: String): String? = if (team == home) homeQb else awayQb

    fun coachOf(team: String): String? = if (team == home) homeCoach else awayCoach

    fun favoredBy(team: String): Double? = spread?.let { if (team == home) it else -it }

    fun impliedPoints(team: String): Double? {
        val favored = favoredBy(team) ?: return null
        val t = total ?: return null
        return t / 2 + favored / 2
    }
}

/** A team's week, summed from its QBs, RBs, WRs and TEs: team volume and the matchup ratings' rows. */
internal class TeamGame(val team: String, val season: Int, val week: Int) {
    var passAttempts = 0.0
    var targets = 0.0
    var carries = 0.0
    var passYards = 0.0
    var rushYards = 0.0
    var passTds = 0.0
    var rushTds = 0.0

    val order: Int get() = order(season, week)
}

internal class ForecastInputs(
    val players: Map<String, PlayerInfo>,
    /** Per player id, oldest first. */
    val history: Map<String, List<PlayerGame>>,
    /** Every scheduled game of the built seasons, in (season, week) order. */
    val games: List<Game>,
    val teamGames: Map<Triple<String, Int, Int>, TeamGame>,
    /** Last week with expected-points data, per season; a missing season has none. */
    val expectedThrough: Map<Int, Int>,
    /** Kickers and D/STs, by player id. */
    val units: Map<String, PlayerInfo> = emptyMap(),
    /** Their weeks, per player id, oldest first. */
    val unitHistory: Map<String, List<PlayerGame>> = emptyMap(),
    /** (player id, season, week) for every QB, RB, WR or TE nflverse listed Out or Doubtful: none of them has ever played that week. */
    val absent: Set<Triple<String, Int, Int>> = emptySet(),
    /** Each [absent] listing's status and body part ("Out|Knee"): its own return curve when it has enough past cases. */
    val listing: Map<Triple<String, Int, Int>, String> = emptyMap(),
    /** Friday practice level per (player id, season, week) for every QB, RB, WR or TE nflverse listed Questionable. */
    val questionable: Map<Triple<String, Int, Int>, Practice> = emptyMap(),
    /** ESPN's projection per (player id, season, week), in our metric ids; empty when the build has none. */
    val espn: Map<Triple<String, Int, Int>, Map<String, Double>> = emptyMap(),
)

private val READ_METRICS = listOf(
    "g", "targets", "receptions", "receiving_yards", "receiving_tds", "carries", "rushing_yards", "rushing_tds",
    "attempts", "completions", "passing_yards", "passing_tds", "interceptions", "sacks_taken",
    "passing_first_downs", "rushing_first_downs", "receiving_first_downs",
    "passing_2pt", "rushing_2pt", "receiving_2pt", "fumbles_lost",
    "passing_tds_40", "passing_tds_50", "rushing_tds_40", "rushing_tds_50", "receiving_tds_40", "receiving_tds_50",
    "x_passing_tds", "x_rushing_tds", "x_receiving_tds",
    "x_receptions", "x_receiving_yards", "x_rushing_yards",
    "offense_snaps", "team_offense_snaps",
)

private val UNIT_METRICS = listOf(
    "g", "fg_att_0_39", "fg_att_40_49", "fg_att_50", "fg_made_0_39", "fg_made_40_49", "fg_made_50",
    "fg_missed", "xp_att", "xp_made", "xp_missed",
    "dst_sacks", "dst_interceptions", "dst_fumble_recoveries", "dst_tds", "dst_safeties", "dst_blocked_kicks", "points_allowed", "yards_allowed",
)

internal fun loadInputs(conn: SQLiteConnection): ForecastInputs {
    val history = readHistory(conn, POSITIONS, READ_METRICS)
    val teamGames = HashMap<Triple<String, Int, Int>, TeamGame>()
    for (g in history.values.flatten()) {
        val team = teamGames.getOrPut(Triple(g.team, g.season, g.week)) { TeamGame(g.team, g.season, g.week) }
        team.passAttempts += g["attempts"]
        team.targets += g["targets"]
        team.carries += g["carries"]
        team.passYards += g["passing_yards"]
        team.rushYards += g["rushing_yards"]
        team.passTds += g["passing_tds"]
        team.rushTds += g["rushing_tds"]
    }
    return ForecastInputs(
        readPlayers(conn, POSITIONS), history, readGames(conn), teamGames, readExpectedThrough(conn),
        units = readPlayers(conn, UNIT_POSITIONS),
        unitHistory = readHistory(conn, UNIT_POSITIONS, UNIT_METRICS),
        absent = readAbsent(conn),
        listing = readListings(conn),
        questionable = readQuestionable(conn),
        espn = readEspn(conn),
    )
}

private fun readEspn(conn: SQLiteConnection): Map<Triple<String, Int, Int>, Map<String, Double>> {
    val exists = conn.prepare("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'espn_projection'").use { it.step() }
    if (!exists) return emptyMap()
    val out = HashMap<Triple<String, Int, Int>, MutableMap<String, Double>>()
    conn.prepare("SELECT player_id, season, week, metric_id, value FROM espn_projection").use { st ->
        while (st.step()) {
            out.getOrPut(Triple(st.getText(0), st.getLong(1).toInt(), st.getLong(2).toInt())) { HashMap() }[st.getText(3)] = st.getDouble(4)
        }
    }
    return out
}

private fun readListings(conn: SQLiteConnection): Map<Triple<String, Int, Int>, String> = conn.prepare(
    "SELECT player_id, season, week, status, injury FROM injury_report WHERE status IN ('Out', 'Doubtful')",
).use { st ->
    buildMap {
        while (st.step()) {
            val injury = if (st.isNull(4)) "" else st.getText(4)
            put(Triple(st.getText(0), st.getLong(1).toInt(), st.getLong(2).toInt()), "${st.getText(3)}|${bodyPart(injury)}")
        }
    }
}

/** nflverse's first named injury, title-cased: "knee, ankle" and "Knee" both read "Knee". */
internal fun bodyPart(injury: String): String =
    injury.substringBefore(',').trim().split(' ').joinToString(" ") { w -> w.lowercase().replaceFirstChar { it.titlecase() } }

private fun readAbsent(conn: SQLiteConnection): Set<Triple<String, Int, Int>> = conn.prepare(
    """SELECT i.player_id, i.season, i.week FROM injury_report i
       JOIN player p ON p.player_id = i.player_id
       WHERE i.status IN ('Out', 'Doubtful') AND p.position IN (${POSITIONS.joinToString(",") { "?" }})""",
).use { st ->
    POSITIONS.forEachIndexed { i, p -> st.bindText(i + 1, p) }
    buildSet {
        while (st.step()) add(Triple(st.getText(0), st.getLong(1).toInt(), st.getLong(2).toInt()))
    }
}

/** The last practice of an injury-report week, as nflverse words it. */
internal enum class Practice(val label: String) {
    FULL("full practice"),
    LIMITED("limited practice"),
    NONE("no practice"),
    ;

    internal companion object {
        /** "Did Not Participate In Practice", "Limited Participation…", "Full Participation…"; anything else is limited. */
        fun of(text: String?): Practice = when {
            text == null -> LIMITED
            text.contains("did not", ignoreCase = true) -> NONE
            text.contains("full", ignoreCase = true) -> FULL
            else -> LIMITED
        }
    }
}

private fun readQuestionable(conn: SQLiteConnection): Map<Triple<String, Int, Int>, Practice> = conn.prepare(
    """SELECT i.player_id, i.season, i.week, i.practice FROM injury_report i
       JOIN player p ON p.player_id = i.player_id
       WHERE i.status = 'Questionable' AND p.position IN (${POSITIONS.joinToString(",") { "?" }})""",
).use { st ->
    POSITIONS.forEachIndexed { i, p -> st.bindText(i + 1, p) }
    buildMap {
        while (st.step()) put(Triple(st.getText(0), st.getLong(1).toInt(), st.getLong(2).toInt()), Practice.of(if (st.isNull(3)) null else st.getText(3)))
    }
}

private fun readPlayers(conn: SQLiteConnection, positions: List<String>): Map<String, PlayerInfo> =
    conn.prepare(
        "SELECT player_id, full_name, position, team FROM player WHERE position IN (${positions.joinToString(",") { "?" }})",
    ).use { st ->
        positions.forEachIndexed { i, p -> st.bindText(i + 1, p) }
        buildMap {
            while (st.step()) put(st.getText(0), PlayerInfo(st.getText(0), st.getText(1), st.getText(2), st.textOrNull(3)))
        }
    }

private fun readHistory(conn: SQLiteConnection, positions: List<String>, metrics: List<String>): Map<String, List<PlayerGame>> {
    val history = HashMap<String, MutableList<PlayerGame>>()
    conn.prepare(
        """SELECT s.player_id, s.season, s.week, s.team, s.metric_id, s.value FROM player_week_stat s
           JOIN player p ON p.player_id = s.player_id
           WHERE p.position IN (${positions.joinToString(",") { "?" }}) AND s.team IS NOT NULL
             AND s.metric_id IN (${metrics.joinToString(",") { "?" }})
           ORDER BY s.player_id, s.season, s.week""",
    ).use { st ->
        (positions + metrics).forEachIndexed { i, v -> st.bindText(i + 1, v) }
        var key: Triple<String, Int, Int>? = null
        var team = ""
        var stats = HashMap<String, Double>()
        fun flush() {
            val k = key ?: return
            // A week counts only if the player recorded a play (g = 1).
            if ((stats["g"] ?: 0.0) > 0.0) history.getOrPut(k.first) { ArrayList() }.add(PlayerGame(k.first, k.second, k.third, team, stats))
        }
        while (st.step()) {
            val k = Triple(st.getText(0), st.getLong(1).toInt(), st.getLong(2).toInt())
            if (k != key) {
                flush()
                key = k
                team = st.getText(3)
                stats = HashMap()
            }
            stats[st.getText(4)] = st.getDouble(5)
        }
        flush()
    }
    return history
}

private fun readGames(conn: SQLiteConnection): List<Game> = conn.prepare(
    """SELECT season, week, game_type, home_team, away_team, home_score, away_score, spread_line, total_line,
       home_qb_id, away_qb_id, home_coach, away_coach FROM game ORDER BY season, week, game_id""",
).use { st ->
    buildList {
        while (st.step()) {
            add(
                Game(
                    season = st.getLong(0).toInt(),
                    week = st.getLong(1).toInt(),
                    regular = st.getText(2) == "REG",
                    home = st.getText(3),
                    away = st.getText(4),
                    played = !st.isNull(5) && !st.isNull(6),
                    spread = st.doubleOrNull(7),
                    total = st.doubleOrNull(8),
                    homeQb = st.textOrNull(9),
                    awayQb = st.textOrNull(10),
                    homeCoach = st.textOrNull(11),
                    awayCoach = st.textOrNull(12),
                ),
            )
        }
    }
}

private fun readExpectedThrough(conn: SQLiteConnection): Map<Int, Int> =
    conn.prepare("SELECT key, value FROM schema_meta WHERE key LIKE 'expected_through_week:%'").use { st ->
        buildMap {
            while (st.step()) {
                val season = st.getText(0).substringAfter(':').toIntOrNull()
                val week = st.getText(1).toIntOrNull()
                if (season != null && week != null) put(season, week)
            }
        }
    }

private fun SQLiteStatement.textOrNull(index: Int): String? = if (isNull(index)) null else getText(index)

private fun SQLiteStatement.doubleOrNull(index: Int): Double? = if (isNull(index)) null else getDouble(index)
