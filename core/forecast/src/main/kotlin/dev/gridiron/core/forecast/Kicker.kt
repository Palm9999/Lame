package dev.gridiron.core.forecast

/** Field goal distance buckets, shortest first, as the kicking metrics name them. */
internal val FG_BUCKETS: List<String> = listOf("0_39", "40_49", "50")

/** One team-game's kicks, and its implied points when a line was posted: a row of the tries fit. */
internal class TeamKicks(val implied: Double?, val fieldGoals: Double, val extraPoints: Double)

/**
 * Field goal and extra point tries as a line in implied team points (spec
 * §6's linear model), fit walk-forward on the games before the week.
 */
internal class AttemptFit(val fgIntercept: Double, val fgSlope: Double, val xpIntercept: Double, val xpSlope: Double) {
    fun fieldGoals(implied: Double): Double = (fgIntercept + fgSlope * implied).coerceAtLeast(0.0)

    fun extraPoints(implied: Double): Double = (xpIntercept + xpSlope * implied).coerceAtLeast(0.0)

    companion object {
        /**
         * Least squares on the rows with a line. With fewer than
         * [K.KICK_MIN_FIT_ROWS] of them (or no spread in implied points), a
         * flat average over every row. Null with no rows at all.
         */
        fun fit(rows: List<TeamKicks>): AttemptFit? {
            if (rows.isEmpty()) return null
            val lined = rows.filter { it.implied != null }
            val sloped = lined.size >= K.KICK_MIN_FIT_ROWS
            val (fgA, fgB) = if (sloped) line(lined) { it.fieldGoals } else rows.map { it.fieldGoals }.average() to 0.0
            val (xpA, xpB) = if (sloped) line(lined) { it.extraPoints } else rows.map { it.extraPoints }.average() to 0.0
            return AttemptFit(fgA, fgB, xpA, xpB)
        }

        /** (intercept, slope) of [y] on implied points; flat when implied points don't vary. */
        private fun line(rows: List<TeamKicks>, y: (TeamKicks) -> Double): Pair<Double, Double> {
            val mx = rows.map { it.implied!! }.average()
            val my = rows.map(y).average()
            val sxx = rows.sumOf { (it.implied!! - mx) * (it.implied - mx) }
            if (sxx <= 1e-9) return my to 0.0
            val slope = rows.sumOf { (it.implied!! - mx) * (y(it) - my) } / sxx
            return (my - slope * mx) to slope
        }
    }
}

/** League kicking before a week: each bucket's share of field goal tries and make rate, and the extra point make rate. */
internal class KickLeague(val mix: List<Double>, val make: List<Double>, val xpMake: Double)

/** A kicker's own mix and accuracy, each shrunk toward the league's. The mix still sums to one. */
internal class KickerRates(val mix: List<Double>, val make: List<Double>, val xpMake: Double)

/** Pools every kick in [games]; null until there's at least one field goal and one extra point try. */
internal fun kickLeague(games: List<PlayerGame>): KickLeague? {
    val tries = FG_BUCKETS.map { b -> games.sumOf { it["fg_att_$b"] } }
    val made = FG_BUCKETS.map { b -> games.sumOf { it["fg_made_$b"] } }
    val total = tries.sum()
    val xpTries = games.sumOf { it["xp_att"] }
    if (total <= 0.0 || xpTries <= 0.0) return null
    return KickLeague(
        mix = tries.map { it / total },
        make = tries.indices.map { if (tries[it] > 0.0) made[it] / tries[it] else 0.0 },
        xpMake = games.sumOf { it["xp_made"] } / xpTries,
    )
}

/** A kicker's rates from his [games]: the mix shrunk by his field goal tries, each make rate by its bucket's. */
internal fun kickerRates(games: List<PlayerGame>, league: KickLeague): KickerRates {
    val tries = FG_BUCKETS.map { b -> games.sumOf { it["fg_att_$b"] } }
    val made = FG_BUCKETS.map { b -> games.sumOf { it["fg_made_$b"] } }
    val total = tries.sum()
    val xpTries = games.sumOf { it["xp_att"] }
    return KickerRates(
        mix = FG_BUCKETS.indices.map { shrink(if (total > 0.0) tries[it] / total else null, total, league.mix[it], K.KICK_MIX_K) },
        make = FG_BUCKETS.indices.map { shrink(if (tries[it] > 0.0) made[it] / tries[it] else null, tries[it], league.make[it], K.KICK_MAKE_K) },
        xpMake = shrink(if (xpTries > 0.0) games.sumOf { it["xp_made"] } / xpTries else null, xpTries, league.xpMake, K.XP_MAKE_K),
    )
}

/** A kicker's projected scoring stats in a game where his team tries [fieldGoals] field goals and [extraPoints] extra points. */
internal fun kickStats(rates: KickerRates, fieldGoals: Double, extraPoints: Double): Map<String, Double> = buildMap {
    var missed = 0.0
    FG_BUCKETS.forEachIndexed { i, bucket ->
        val tries = fieldGoals * rates.mix[i]
        put("fg_made_$bucket", tries * rates.make[i])
        missed += tries * (1 - rates.make[i])
    }
    put("fg_missed", missed)
    put("xp_made", extraPoints * rates.xpMake)
    put("xp_missed", extraPoints * (1 - rates.xpMake))
}

/** A team's expected points without a line: its [recent] scoring (oldest first), recency-weighted and shrunk toward [leagueAverage]. */
internal fun teamPoints(recent: List<Double>, leagueAverage: Double): Double =
    shrink(ewma(recent, K.TEAM_HALF_LIFE), recent.size.toDouble(), leagueAverage, K.TEAM_POINTS_K_GAMES)
