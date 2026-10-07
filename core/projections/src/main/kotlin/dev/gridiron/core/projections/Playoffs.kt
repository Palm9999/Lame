package dev.gridiron.core.projections

import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.random.Random

/** A league team's record so far: [wins] counts a tie as half. */
public data class SimTeam(val id: Int, val name: String, val wins: Double, val losses: Double, val pointsFor: Double)

/** A head-to-head still to play, in NFL [week]. */
public data class SimGame(val week: Int, val home: Int, val away: Int)

/** A team's score in one week as a normal: its best lineup's projection and spread. */
public data class TeamWeek(val mean: Double, val sd: Double)

/** One team's outlook over [Playoffs.SIMS] seasons: its chance to make the playoffs and to be the top seed. */
public data class PlayoffOdds(
    val teamId: Int,
    val name: String,
    val playoffChance: Double,
    val topSeedChance: Double,
    val expectedWins: Double,
    val expectedLosses: Double,
)

public object Playoffs {
    /**
     * Each player's weekly spread as a share of his projection, from 2022-2025's counted player-weeks (the residual's
     * spread over the mean projection; K and D/ST from the forecast's own CVs).
     */
    public val WEEKLY_CV: Map<String, Double> = mapOf("QB" to 0.47, "RB" to 0.63, "WR" to 0.70, "TE" to 0.77, "K" to 0.52, "DST" to 0.85)

    public const val SIMS: Int = 10_000

    /**
     * A roster's score in each of [weeks]: the best lineup on [slots] from each player's points that week (none on a
     * bye), and its spread, the starters' spreads combined as independent.
     */
    public fun teamWeeks(slots: Map<String, Int>, roster: List<LineupCandidate>, weeks: Collection<Int>): Map<Int, TeamWeek> =
        weeks.associateWith { w ->
            val best = Lineups.best(slots, roster.map { it.copy(points = it.weekly[w] ?: 0.0) })
            val starters = best.spots.mapNotNull { it.player }
            TeamWeek(
                mean = best.total,
                sd = sqrt(starters.sumOf { s -> ((WEEKLY_CV[s.position] ?: 0.6) * s.points).let { it * it } }),
            )
        }

    /**
     * Plays [games] [sims] times from [teams]' records: each side scores its [weekly] normal (zero when unknown, never
     * below zero), the higher wins (a dead heat is a tie), and the top [playoffTeams] by wins, then points for, make the
     * playoffs. Fixed [seed], so the numbers don't flicker. Best chance first.
     */
    public fun simulate(
        teams: List<SimTeam>,
        games: List<SimGame>,
        weekly: Map<Int, Map<Int, TeamWeek>>,
        playoffTeams: Int,
        sims: Int = SIMS,
        seed: Int = 7,
    ): List<PlayoffOdds> {
        if (teams.isEmpty()) return emptyList()
        val index = teams.withIndex().associate { (i, t) -> t.id to i }
        val plays = games.mapNotNull { g ->
            val h = index[g.home] ?: return@mapNotNull null
            val a = index[g.away] ?: return@mapNotNull null
            Triple(h, a, weekly[g.home]?.get(g.week) to weekly[g.away]?.get(g.week))
        }
        val rng = Random(seed)
        val made = DoubleArray(teams.size)
        val top = DoubleArray(teams.size)
        val winsTotal = DoubleArray(teams.size)
        val lossesTotal = DoubleArray(teams.size)
        val wins = DoubleArray(teams.size)
        val losses = DoubleArray(teams.size)
        val points = DoubleArray(teams.size)
        val cut = playoffTeams.coerceIn(0, teams.size)
        repeat(sims) {
            for (i in teams.indices) {
                wins[i] = teams[i].wins
                losses[i] = teams[i].losses
                points[i] = teams[i].pointsFor
            }
            for ((h, a, both) in plays) {
                val hs = draw(both.first, rng)
                val aws = draw(both.second, rng)
                points[h] += hs
                points[a] += aws
                when {
                    hs > aws -> { wins[h] += 1.0; losses[a] += 1.0 }
                    aws > hs -> { wins[a] += 1.0; losses[h] += 1.0 }
                    else -> { wins[h] += 0.5; wins[a] += 0.5; losses[h] += 0.5; losses[a] += 0.5 }
                }
            }
            val order = teams.indices.sortedWith(compareByDescending<Int> { wins[it] }.thenByDescending { points[it] })
            for ((seedIndex, i) in order.withIndex()) {
                if (seedIndex < cut) made[i] += 1.0
                if (seedIndex == 0) top[i] += 1.0
                winsTotal[i] += wins[i]
                lossesTotal[i] += losses[i]
            }
        }
        return teams.indices.map { i ->
            PlayoffOdds(teams[i].id, teams[i].name, made[i] / sims, top[i] / sims, winsTotal[i] / sims, lossesTotal[i] / sims)
        }.sortedWith(compareByDescending<PlayoffOdds> { it.playoffChance }.thenByDescending { it.expectedWins }.thenBy { it.name })
    }

    private fun draw(week: TeamWeek?, rng: Random): Double {
        week ?: return 0.0
        if (week.sd <= 0.0) return week.mean
        val u = 1.0 - rng.nextDouble()
        val v = rng.nextDouble()
        return maxOf(0.0, week.mean + week.sd * sqrt(-2.0 * ln(u)) * cos(2 * Math.PI * v))
    }
}
