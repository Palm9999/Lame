package dev.gridiron.core.forecast

import kotlin.math.sqrt

/** The D/ST stats projected as rates. Points allowed are projected beside them, as a mean and a spread. */
internal val DST_STATS: List<String> = listOf("dst_sacks", "dst_interceptions", "dst_fumble_recoveries", "dst_tds", "dst_safeties")

internal const val POINTS_ALLOWED: String = "points_allowed"

/** How far real team scores land from their implied points ([misses]): the spread of points allowed. */
internal fun paSpread(misses: List<Double>): Double =
    if (misses.size < K.PA_SD_MIN_GAMES) K.PA_SD_DEFAULT else sqrt(misses.sumOf { it * it } / misses.size)

/** League per-game averages of every D/ST stat and of points allowed, before a week. */
internal class DefenseLeague(val perGame: Map<String, Double>, val pointsAllowed: Double)

internal fun defenseLeague(games: List<PlayerGame>): DefenseLeague? {
    if (games.isEmpty()) return null
    return DefenseLeague(
        DST_STATS.associateWith { stat -> games.sumOf { it[stat] } / games.size },
        games.sumOf { it["points_allowed"] } / games.size,
    )
}

/** A per-game rate from [values] (oldest first): recency-weighted, and shrunk toward [league] by games. */
internal fun unitRate(values: List<Double>, league: Double, k: Double): Double =
    shrink(ewma(values, K.DST_HALF_LIFE), values.size.toDouble(), league, k)

/**
 * How much of a stat an offense gives up against the league's per-game
 * [league], from what defenses got against it ([allowed], oldest first). It
 * multiplies the defense's own rate, within 1 ± [K.DST_CAP].
 */
internal fun opponentFactor(allowed: List<Double>, league: Double): Double =
    if (league <= 0.0) 1.0 else (unitRate(allowed, league, K.DST_OPP_K) / league).capAround(K.DST_CAP)

/** A D/ST's projection, stage by stage: its own rates and points allowed, then the matchup, then the line. */
internal class DefenseStages(val baseline: Map<String, Double>, val afterMatchup: Map<String, Double>, val final: Map<String, Double>)

/**
 * [own] per-game rates and [ownAllowed] points allowed are the baseline. The
 * matchup multiplies each stat by its opponent [factors] and scales points
 * allowed by how the opponent scores ([opponentScores]) against the league's
 * average. Game script replaces points allowed with the opponent's [implied]
 * points when a line is posted (spec §6). Points allowed are a mean; the
 * profile's tiers score them on the phone, in expectation.
 */
internal fun defenseStages(
    own: Map<String, Double>,
    ownAllowed: Double,
    factors: Map<String, Double>,
    opponentScores: Double,
    league: DefenseLeague,
    implied: Double?,
): DefenseStages {
    val matchupPoints = if (league.pointsAllowed > 0.0) ownAllowed * opponentScores / league.pointsAllowed else ownAllowed
    val matched = own.mapValues { (stat, rate) -> rate * (factors[stat] ?: 1.0) }
    return DefenseStages(
        baseline = own + (POINTS_ALLOWED to ownAllowed),
        afterMatchup = matched + (POINTS_ALLOWED to matchupPoints),
        final = matched + (POINTS_ALLOWED to (implied ?: matchupPoints)),
    )
}
