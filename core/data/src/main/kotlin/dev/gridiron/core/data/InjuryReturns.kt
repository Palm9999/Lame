package dev.gridiron.core.data

import dev.gridiron.core.database.QueryExecutor
import dev.gridiron.core.database.textOrNull
import dev.gridiron.core.statquery.Bind
import dev.gridiron.core.statquery.SqlQuery
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One nflverse injury-report line: [status] "Out" or "Doubtful", [injury] as nflverse words it ("Hamstring"). */
public data class InjuryListing(val playerId: String, val season: Int, val week: Int, val team: String, val status: String, val injury: String?)

/**
 * One absence: a player listed [status] for a game after playing his team's game before it, with [injury] (see
 * [InjuryReturns.normalize]). He then missed [missed] of his team's games in a row (0: he played anyway). [censored]:
 * still out when the season or the data ended, so he missed at least [missed].
 */
public data class Absence(val season: Int, val status: String, val injury: String?, val missed: Int, val censored: Boolean)

/**
 * How soon a player out now is likely back. [chances] are the chances he has played by each of his team's next games,
 * in NFL [weeks] (byes skipped). From [cases] past absences still going after [missedSoFar] games (or the [minimum] IR
 * stint, when longer), with the same [injury] when there were enough of them, else every injury ([injury] null), in
 * seasons [firstSeason] to [lastSeason].
 */
public data class ReturnOutlook(
    val status: String,
    val injury: String?,
    val cases: Int,
    val missedSoFar: Int,
    val minimum: Int,
    val weeks: List<Int>,
    val chances: List<Double>,
    val firstSeason: Int,
    val lastSeason: Int,
)

public object InjuryReturns {
    /** Absences a body part needs before it gets its own numbers; fewer read as every injury together. */
    public const val MIN_CASES: Int = 30

    /** Fewer past absences than this and there are no numbers at all. */
    public const val MIN_POOLED: Int = 10

    /** A player on IR misses at least four games. */
    public const val IR_MIN_GAMES: Int = 4

    /** How many of his team's next games the outlook covers. */
    public const val HORIZON: Int = 4

    /** The positions counted: a fantasy lineup's, a D/ST aside. */
    public val POSITIONS: List<String> = listOf("QB", "RB", "WR", "TE", "K")

    private val SAME = mapOf(
        "ribs" to "rib", "quad" to "quadricep", "quadriceps" to "quadricep", "hamstrings" to "hamstring",
        "achilles tendon" to "achilles", "heel" to "foot",
    )

    /**
     * [injury] as one lower-case body part: the first one named ("Knee, Ankle" is a knee), plurals and short forms
     * merged; illness, rest and personal matters read as "non-injury". Null when nflverse names none.
     */
    public fun normalize(injury: String?): String? {
        val first = injury?.lowercase()?.split(',', '/')?.first()?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if ("not injury" in first || "personal" in first || "illness" in first || first == "rest") return "non-injury"
        return SAME[first] ?: first
    }

    /**
     * Every absence in [listings]: a listing for a game his team played ([played] weeks per season and team) after a
     * game he played ([appeared]: player, season, week), or for his team's first game. The games he then missed are
     * counted until he plays again; past the last game played the absence is censored.
     */
    public fun absences(
        listings: List<InjuryListing>,
        appeared: Set<Triple<String, Int, Int>>,
        played: Map<Pair<Int, String>, List<Int>>,
    ): List<Absence> = listings.mapNotNull { l ->
        val games = played[l.season to l.team] ?: return@mapNotNull null
        val i = games.indexOf(l.week)
        if (i < 0) return@mapNotNull null
        if (i > 0 && Triple(l.playerId, l.season, games[i - 1]) !in appeared) return@mapNotNull null
        val back = (i until games.size).firstOrNull { Triple(l.playerId, l.season, games[it]) in appeared }
        Absence(l.season, l.status, normalize(l.injury), (back ?: games.size) - i, censored = back == null)
    }

    /**
     * The chance an absence lasts at least t games, for t = 0..[upTo] (Kaplan-Meier: a censored absence counts as
     * still out only for the games it was seen to miss), and how many absences were still going at each t.
     */
    public fun lasting(absences: List<Absence>, upTo: Int): Pair<DoubleArray, IntArray> {
        val g = DoubleArray(upTo + 1)
        val atRisk = IntArray(upTo + 1)
        g[0] = 1.0
        for (t in 0..upTo) {
            atRisk[t] = absences.count { it.missed > t || (it.missed == t && !it.censored) }
            if (t == upTo) break
            val back = absences.count { it.missed == t && !it.censored }
            g[t + 1] = if (atRisk[t] == 0) g[t] else g[t] * (1.0 - back.toDouble() / atRisk[t])
        }
        return g to atRisk
    }

    /**
     * The outlook for a player listed [status] with [injury] who has missed [missedSoFar] games, whose team plays next
     * in [weeks] ([HORIZON] of them used). [minimum]: games he must miss in all (an IR stint). A player who already
     * missed games is compared with every absence that lasted as long, whatever it was first listed as. Null when
     * there is no next game or too few absences went that long.
     */
    public fun outlook(
        absences: List<Absence>,
        status: String,
        injury: String?,
        missedSoFar: Int,
        weeks: List<Int>,
        minimum: Int = 0,
    ): ReturnOutlook? {
        val next = weeks.take(HORIZON)
        if (next.isEmpty()) return null
        val from = maxOf(missedSoFar, minimum)
        val upTo = missedSoFar + next.size
        val asListed = if (missedSoFar == 0) absences.filter { it.status == status } else absences
        val part = normalize(injury)
        val choices = listOfNotNull(
            part?.let { p -> Triple(asListed.filter { it.injury == p }, p, MIN_CASES) },
            Triple(asListed, null, MIN_POOLED),
        )
        for ((pool, label, needed) in choices) {
            val (g, atRisk) = lasting(pool, maxOf(upTo, from))
            if (atRisk[from] < needed || g[from] <= 0.0) continue
            val chances = next.indices.map { j ->
                val t = missedSoFar + j + 1
                if (t <= from) 0.0 else 1.0 - g[t] / g[from]
            }
            return ReturnOutlook(
                status, label, atRisk[from], missedSoFar, minimum, next, chances,
                pool.minOf { it.season }, pool.maxOf { it.season },
            )
        }
        return null
    }

    /** ESPN's designation as the outlook's status: Out (IR too) or Doubtful; null for anything else. */
    public fun statusOf(abbr: String?): String? = when (abbr) {
        "O", "IR" -> "Out"
        "D" -> "Doubtful"
        else -> null
    }
}

/**
 * Reads past absences out of `stats.db` (every season it holds) and gives an injured player's [ReturnOutlook]. The
 * absences are read once per [version] (bump it when a refresh swaps the database).
 */
public class InjuryReturnRepository(private val executor: QueryExecutor) {
    private val lock = Mutex()
    private var cached: Pair<Long, List<Absence>>? = null

    internal suspend fun absences(version: Long): List<Absence> = lock.withLock {
        cached?.takeIf { it.first == version }?.let { return it.second }
        val listings = executor.query(
            SqlQuery(
                """
                SELECT i.player_id, i.season, i.week, i.team, i.status, i.injury
                FROM injury_report i JOIN player p ON p.player_id = i.player_id
                WHERE i.status IN ('Out', 'Doubtful') AND i.team IS NOT NULL AND p.position IN (${InjuryReturns.POSITIONS.joinToString { "?" }})
                """.trimIndent(),
                InjuryReturns.POSITIONS.map(Bind::Text),
            ),
        ) { InjuryListing(it.text(0), it.long(1).toInt(), it.long(2).toInt(), it.text(3), it.text(4), it.textOrNull(5)) }
        val appeared = executor.query(
            SqlQuery(
                """
                SELECT player_id, season, week FROM player_week_stat
                WHERE metric_id = 'g' AND value > 0
                  AND player_id IN (SELECT player_id FROM injury_report WHERE status IN ('Out', 'Doubtful'))
                """.trimIndent(),
                emptyList(),
            ),
        ) { Triple(it.text(0), it.long(1).toInt(), it.long(2).toInt()) }.toHashSet()
        val result = InjuryReturns.absences(listings, appeared, playedGames())
        cached = version to result
        result
    }

    /** Each season's and team's regular-season weeks with a final score, in order. */
    private suspend fun playedGames(): Map<Pair<Int, String>, List<Int>> {
        val games = executor.query(
            SqlQuery("SELECT season, week, home_team, away_team FROM game WHERE game_type = 'REG' AND home_score IS NOT NULL", emptyList()),
        ) { listOf(it.long(0).toInt() to it.text(2), it.long(0).toInt() to it.text(3)).map { k -> k to it.long(1).toInt() } }
        return games.flatten().groupBy({ it.first }, { it.second }).mapValues { (_, w) -> w.distinct().sorted() }
    }

    /**
     * [playerId]'s outlook in [season]: [abbr] is his ESPN designation (O, IR or D; anything else, or none, falls back
     * to nflverse's listing for his team's next game). His injury is nflverse's latest named one this season; the
     * games missed so far are his team's played games since his last one. Null when he isn't out or there is too
     * little history.
     */
    public suspend fun outlook(playerId: String, season: Int, abbr: String?, version: Long = 0L): ReturnOutlook? {
        val team = executor.query(
            SqlQuery("SELECT team FROM player WHERE player_id = ?", listOf(Bind.Text(playerId))),
        ) { it.textOrNull(0) }.firstOrNull() ?: return null
        val schedule = executor.query(
            SqlQuery(
                """
                SELECT week, home_score IS NOT NULL FROM game
                WHERE season = ? AND game_type = 'REG' AND (home_team = ? OR away_team = ?) ORDER BY week
                """.trimIndent(),
                listOf(Bind.Integer(season.toLong()), Bind.Text(team), Bind.Text(team)),
            ),
        ) { it.long(0).toInt() to (it.long(1) != 0L) }
        val upcoming = schedule.filterNot { it.second }.map { it.first }
        val listings = executor.query(
            SqlQuery(
                "SELECT week, status, injury FROM injury_report WHERE player_id = ? AND season = ? ORDER BY week DESC",
                listOf(Bind.Text(playerId), Bind.Integer(season.toLong())),
            ),
        ) { Triple(it.long(0).toInt(), it.textOrNull(1), it.textOrNull(2)) }
        val listed = listings.firstOrNull { it.first == upcoming.firstOrNull() }?.second?.takeIf { it == "Out" || it == "Doubtful" }
        val status = InjuryReturns.statusOf(abbr) ?: listed ?: return null
        val playedWeeks = executor.query(
            SqlQuery(
                "SELECT week FROM player_week_stat WHERE player_id = ? AND season = ? AND metric_id = 'g' AND value > 0",
                listOf(Bind.Text(playerId), Bind.Integer(season.toLong())),
            ),
        ) { it.long(0).toInt() }.toSet()
        val missed = schedule.filter { it.second }.map { it.first }.takeLastWhile { it !in playedWeeks }.size
        return InjuryReturns.outlook(
            absences(version), status, listings.firstNotNullOfOrNull { it.third }, missed, upcoming,
            minimum = if (abbr == "IR") InjuryReturns.IR_MIN_GAMES else 0,
        )
    }
}

/** "wk 7 28% · wk 8 53% · wk 9 70%": the chance he has played by each of his team's next games. */
public fun ReturnOutlook.chancesText(): String =
    weeks.zip(chances).joinToString(" · ") { (w, c) -> "wk $w ${returnPercent(c)}" }

/**
 * Where the numbers come from: "From 97 hamstring absences (players listed Out), 2024–2026", "From 120 absences that
 * had lasted 2 games, 2024–2026". IR says its minimum first.
 */
public fun ReturnOutlook.basisText(): String = buildString {
    if (minimum > missedSoFar) append("IR: at least $minimum games out. ")
    val what = injury?.let { "$it absences" } ?: "absences"
    append("From $cases past $what")
    val lasted = maxOf(missedSoFar, minimum)
    append(if (lasted > 0) " that had lasted $lasted game${if (lasted == 1) "" else "s"}" else " (players listed $status)")
    append(if (firstSeason == lastSeason) ", $firstSeason" else ", $firstSeason–$lastSeason")
    append(". QB, RB, WR, TE and K; byes skipped.")
}

/** A chance as "28%": whole percents, never a certainty unless it is one. */
private fun returnPercent(c: Double): String = when {
    c >= 1.0 -> "100%"
    c <= 0.0 -> "0%"
    else -> "${Math.round(c * 100).toInt().coerceIn(1, 99)}%"
}
