package dev.gridiron.core.data

import dev.gridiron.core.database.QueryExecutor
import dev.gridiron.core.statquery.Bind
import dev.gridiron.core.statquery.SqlQuery
import kotlin.math.sqrt

/**
 * One player-season: per-game rates of its position's [COMP_METRICS], in that order, then [rates] (each of
 * [COMP_RATES] recomputed over the season, never averaged), and his [age] on September 1 of the season.
 */
public data class CompSeason(
    val playerId: String,
    val name: String,
    val season: Int,
    val team: String?,
    val games: Int,
    val perGame: List<Double>,
    val rates: List<Double> = emptyList(),
    val age: Double? = null,
)

/** What each position's comps are measured on: counting stats per game (shares are rates, never averaged). */
public val COMP_METRICS: Map<String, List<String>> = mapOf(
    "QB" to listOf("attempts", "passing_yards", "passing_tds", "interceptions", "carries", "rushing_yards"),
    "RB" to listOf("carries", "rushing_yards", "rushing_tds", "targets", "receptions", "receiving_yards"),
    "WR" to listOf("targets", "receptions", "receiving_yards", "air_yards", "receiving_tds", "carries"),
    "TE" to listOf("targets", "receptions", "receiving_yards", "air_yards", "receiving_tds", "carries"),
    "K" to listOf("fg_att", "fg_made", "fg_made_50", "xp_made"),
    "DST" to listOf("points_allowed", "yards_allowed", "dst_sacks", "dst_interceptions", "dst_fumble_recoveries", "dst_tds"),
)

/** Efficiency and share, as season sums over season sums: numerator to denominator. */
public val COMP_RATES: Map<String, List<Pair<String, String>>> = mapOf(
    "QB" to listOf("passing_yards" to "attempts", "passing_tds" to "attempts"),
    "RB" to listOf("rushing_yards" to "carries", "carries" to "team_carries", "targets" to "team_targets"),
    "WR" to listOf("receiving_yards" to "targets", "targets" to "team_targets", "air_yards" to "team_air_yards"),
    "TE" to listOf("receiving_yards" to "targets", "targets" to "team_targets", "air_yards" to "team_air_yards"),
    "K" to listOf("fg_made" to "fg_att"),
)

/**
 * The [k] seasons in [pool] closest to [target], other players only: each per-game stat, rate and age standardized over
 * the pool, then plain distance, nearest first. A missing age counts as the pool's average (no pull either way).
 */
public fun nearestSeasons(target: CompSeason, pool: List<CompSeason>, k: Int = 5): List<CompSeason> {
    if (pool.isEmpty()) return emptyList()
    val ages = pool.mapNotNull { it.age }
    val meanAge = if (ages.isEmpty()) 0.0 else ages.average()
    fun features(s: CompSeason) = s.perGame + s.rates + (s.age ?: meanAge)
    val all = pool.map(::features)
    val mine = features(target)
    val n = mine.size
    val mean = DoubleArray(n) { i -> all.sumOf { it[i] } / all.size }
    val sd = DoubleArray(n) { i -> sqrt(all.sumOf { (it[i] - mean[i]).let { d -> d * d } } / all.size).takeIf { it > 0.0 } ?: 1.0 }
    fun distance(f: List<Double>) = sqrt((0 until n).sumOf { i -> ((f[i] - mine[i]) / sd[i]).let { it * it } })
    return pool.zip(all).filter { it.first.playerId != target.playerId }.sortedBy { distance(it.second) }.take(k).map { it.first }
}

/** Player comps for the Player page: [season]'s line against every season the database holds at his position. */
public class CompsRepository(private val executor: QueryExecutor) {
    public suspend fun comps(playerId: String, season: Int, position: String?): List<CompSeason> {
        val metrics = COMP_METRICS[position] ?: return emptyList()
        val pool = seasons(position!!, metrics, COMP_RATES[position].orEmpty())
        val target = pool.firstOrNull { it.playerId == playerId && it.season == season } ?: return emptyList()
        return nearestSeasons(target, pool)
    }

    private suspend fun seasons(position: String, metrics: List<String>, rates: List<Pair<String, String>>): List<CompSeason> {
        val all = (metrics + rates.flatMap { listOf(it.first, it.second) }).distinct()
        val sums = all.joinToString(", ") { "SUM(CASE WHEN s.metric_id = ? THEN s.value ELSE 0 END)" }
        return executor.query(
            SqlQuery(
                """
                SELECT p.player_id, p.full_name, s.season, MAX(p.team), SUM(CASE WHEN s.metric_id = 'g' THEN s.value ELSE 0 END) AS games,
                       MAX(p.birth_date), $sums
                FROM player_week_stat s JOIN player p ON p.player_id = s.player_id
                WHERE p.position = ? AND s.metric_id IN ('g', ${all.joinToString(", ") { "?" }})
                GROUP BY p.player_id, s.season
                HAVING games >= ?
                """.trimIndent(),
                all.map(Bind::Text) + Bind.Text(position) + all.map(Bind::Text) + Bind.Integer(MIN_GAMES),
            ),
        ) { row ->
            val games = row.double(4)
            val season = row.long(2).toInt()
            val sum = all.indices.associate { all[it] to row.double(6 + it) }
            CompSeason(
                row.text(0), row.text(1), season, if (row.isNull(3)) null else row.text(3), games.toInt(),
                metrics.map { sum.getValue(it) / games },
                rates.map { (num, den) -> sum.getValue(den).let { d -> if (d > 0.0) sum.getValue(num) / d else 0.0 } },
                if (row.isNull(5)) null else ageOn(row.text(5), season),
            )
        }
    }

    private companion object {
        const val MIN_GAMES = 4L
    }
}

/** Age in years on September 1 of [season] from a "1997-05-14" birth date; null when it doesn't parse. */
internal fun ageOn(birthDate: String, season: Int): Double? = try {
    val born = java.time.LocalDate.parse(birthDate.take(10))
    java.time.temporal.ChronoUnit.DAYS.between(born, java.time.LocalDate.of(season, 9, 1)) / 365.25
} catch (_: java.time.format.DateTimeParseException) {
    null
}
