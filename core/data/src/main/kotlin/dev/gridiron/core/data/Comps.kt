package dev.gridiron.core.data

import dev.gridiron.core.database.QueryExecutor
import dev.gridiron.core.statquery.Bind
import dev.gridiron.core.statquery.SqlQuery
import kotlin.math.sqrt

/** One player-season with per-game rates of its position's [COMP_METRICS], in that order. */
public data class CompSeason(val playerId: String, val name: String, val season: Int, val team: String?, val games: Int, val perGame: List<Double>)

/** What each position's comps are measured on: counting stats per game (shares are rates, never averaged). */
public val COMP_METRICS: Map<String, List<String>> = mapOf(
    "QB" to listOf("attempts", "passing_yards", "passing_tds", "interceptions", "carries", "rushing_yards"),
    "RB" to listOf("carries", "rushing_yards", "rushing_tds", "targets", "receptions", "receiving_yards"),
    "WR" to listOf("targets", "receptions", "receiving_yards", "air_yards", "receiving_tds", "carries"),
    "TE" to listOf("targets", "receptions", "receiving_yards", "air_yards", "receiving_tds", "carries"),
)

/**
 * The [k] seasons in [pool] closest to [target], other players only: each metric standardized over the pool, then
 * plain distance, nearest first.
 */
public fun nearestSeasons(target: CompSeason, pool: List<CompSeason>, k: Int = 5): List<CompSeason> {
    if (pool.isEmpty()) return emptyList()
    val n = target.perGame.size
    val mean = DoubleArray(n) { i -> pool.sumOf { it.perGame[i] } / pool.size }
    val sd = DoubleArray(n) { i -> sqrt(pool.sumOf { (it.perGame[i] - mean[i]).let { d -> d * d } } / pool.size).takeIf { it > 0.0 } ?: 1.0 }
    fun distance(s: CompSeason) = sqrt((0 until n).sumOf { i -> ((s.perGame[i] - target.perGame[i]) / sd[i]).let { it * it } })
    return pool.filter { it.playerId != target.playerId }.sortedBy(::distance).take(k)
}

/** Player comps for the Player page: [season]'s line against every season the database holds at his position. */
public class CompsRepository(private val executor: QueryExecutor) {
    public suspend fun comps(playerId: String, season: Int, position: String?): List<CompSeason> {
        val metrics = COMP_METRICS[position] ?: return emptyList()
        val pool = seasons(position!!, metrics)
        val target = pool.firstOrNull { it.playerId == playerId && it.season == season } ?: return emptyList()
        return nearestSeasons(target, pool)
    }

    private suspend fun seasons(position: String, metrics: List<String>): List<CompSeason> {
        val sums = metrics.joinToString(", ") { "SUM(CASE WHEN s.metric_id = ? THEN s.value ELSE 0 END)" }
        return executor.query(
            SqlQuery(
                """
                SELECT p.player_id, p.full_name, s.season, MAX(p.team), SUM(CASE WHEN s.metric_id = 'g' THEN s.value ELSE 0 END) AS games, $sums
                FROM player_week_stat s JOIN player p ON p.player_id = s.player_id
                WHERE p.position = ? AND s.metric_id IN ('g', ${metrics.joinToString(", ") { "?" }})
                GROUP BY p.player_id, s.season
                HAVING games >= ?
                """.trimIndent(),
                metrics.map(Bind::Text) + Bind.Text(position) + metrics.map(Bind::Text) + Bind.Integer(MIN_GAMES),
            ),
        ) { row ->
            val games = row.double(4)
            CompSeason(row.text(0), row.text(1), row.long(2).toInt(), if (row.isNull(3)) null else row.text(3), games.toInt(), metrics.indices.map { row.double(5 + it) / games })
        }
    }

    private companion object {
        const val MIN_GAMES = 4L
    }
}
