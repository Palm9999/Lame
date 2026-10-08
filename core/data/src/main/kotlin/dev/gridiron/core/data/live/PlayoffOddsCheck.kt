package dev.gridiron.core.data.live

import dev.gridiron.core.projections.Playoffs
import dev.gridiron.core.projections.SimGame
import dev.gridiron.core.projections.SimTeam
import dev.gridiron.core.projections.TeamWeek
import kotlin.math.sqrt

/** Playoff odds as of [week] against who made it, over [cases] team-seasons: [brier] against [chanceBrier], every team at the cut's share. */
public data class OddsCheck(val week: Int, val seasons: Int, val cases: Int, val brier: Double, val chanceBrier: Double)

/**
 * The playoff simulator checked on the league's finished seasons: as of each of [weeks], every team scores its season so
 * far (mean and spread of its scores, standing in for projections the app never stored) in the games left, and the odds
 * are scored against the seeds ESPN gave. A season is used only when every regular-season game was decided and ESPN
 * names its playoff teams and seeds.
 */
public fun playoffOddsCheck(seasons: List<HistorySeason>, weeks: List<Int> = listOf(6, 9, 12), sims: Int = 2_000): List<OddsCheck> =
    weeks.mapNotNull { asOf ->
        var cases = 0
        var used = 0
        var brier = 0.0
        var chance = 0.0
        for (s in seasons) {
            val cut = s.playoffTeams ?: continue
            val regular = s.games.filter { !it.playoff && it.awayId != null }
            if (regular.isEmpty() || regular.any { !it.decided } || s.teams.none { it.playoffSeed != null }) continue
            if (regular.maxOf { it.week } <= asOf) continue
            val played = regular.filter { it.week < asOf }
            val scores = HashMap<Int, MutableList<Double>>()
            val wins = HashMap<Int, Double>()
            val losses = HashMap<Int, Double>()
            for (g in played) {
                val away = g.awayId!!
                scores.getOrPut(g.homeId) { mutableListOf() } += g.homePoints
                scores.getOrPut(away) { mutableListOf() } += g.awayPoints
                val (h, a) = when {
                    g.homePoints > g.awayPoints -> 1.0 to 0.0
                    g.awayPoints > g.homePoints -> 0.0 to 1.0
                    else -> 0.5 to 0.5
                }
                wins.merge(g.homeId, h, Double::plus); losses.merge(g.homeId, 1 - h, Double::plus)
                wins.merge(away, a, Double::plus); losses.merge(away, 1 - a, Double::plus)
            }
            if (s.teams.any { (scores[it.id]?.size ?: 0) < 2 }) continue
            val rest = regular.filter { it.week >= asOf }
            val weekly = s.teams.associate { t ->
                val xs = scores.getValue(t.id)
                val mean = xs.average()
                val sd = sqrt(xs.sumOf { (it - mean) * (it - mean) } / (xs.size - 1))
                t.id to rest.map { it.week }.distinct().associateWith { TeamWeek(mean, sd) }
            }
            val odds = Playoffs.simulate(
                s.teams.map { SimTeam(it.id, it.name, wins[it.id] ?: 0.0, losses[it.id] ?: 0.0, scores.getValue(it.id).sum()) },
                rest.map { SimGame(it.week, it.homeId, it.awayId!!) },
                weekly, cut, sims,
            ).associateBy { it.teamId }
            val base = cut.toDouble() / s.teams.size
            for (t in s.teams) {
                val made = if ((t.playoffSeed ?: Int.MAX_VALUE) <= cut) 1.0 else 0.0
                val p = odds.getValue(t.id).playoffChance
                brier += (p - made) * (p - made)
                chance += (base - made) * (base - made)
                cases++
            }
            used++
        }
        if (cases == 0) null else OddsCheck(asOf, used, cases, brier / cases, chance / cases)
    }
