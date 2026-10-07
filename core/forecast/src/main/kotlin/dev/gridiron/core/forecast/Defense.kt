package dev.gridiron.core.forecast

import kotlin.math.pow
import kotlin.math.sqrt

/** The D/ST stats projected as rates. Points allowed are projected beside them, as a mean and a spread. */
internal val DST_STATS: List<String> = listOf("dst_sacks", "dst_interceptions", "dst_fumble_recoveries", "dst_tds", "dst_safeties", "dst_blocked_kicks")

internal const val POINTS_ALLOWED: String = "points_allowed"

internal const val YARDS_ALLOWED: String = "yards_allowed"

/** How far real team scores land from their implied points ([misses]): the spread of points allowed. */
internal fun paSpread(misses: List<Double>): Double =
    if (misses.size < K.PA_SD_MIN_GAMES) K.PA_SD_DEFAULT else sqrt(misses.sumOf { it * it } / misses.size)

/** League per-game averages of every D/ST stat, of points allowed and of yards allowed (0 when the database has none), before a week. */
internal class DefenseLeague(val perGame: Map<String, Double>, val pointsAllowed: Double, val yardsAllowed: Double = 0.0)

internal fun defenseLeague(games: List<PlayerGame>): DefenseLeague? {
    if (games.isEmpty()) return null
    return DefenseLeague(
        DST_STATS.associateWith { stat -> games.sumOf { it[stat] } / games.size },
        games.sumOf { it["points_allowed"] } / games.size,
        games.sumOf { it[YARDS_ALLOWED] } / games.size,
    )
}

/** How a line moves a defense's yards: the opponent's implied points over the matchup's expected points, capped, damped. */
internal fun yardsScript(implied: Double?, expectedPoints: Double): Double {
    if (implied == null || expectedPoints <= 0.0) return 1.0
    return (implied / expectedPoints).coerceIn(K.IMPLIED_RATIO_MIN, K.IMPLIED_RATIO_MAX).pow(K.DST_YA_SCRIPT_ELASTICITY)
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
 * profile's tiers score them on the phone, in expectation. Yards allowed follow
 * the same stages when there is a yards history ([ownYards] above 0): the rate,
 * the opponent's [yardsFactor], and a damped, capped game script ([yardsScript]).
 */
internal fun defenseStages(
    own: Map<String, Double>,
    ownAllowed: Double,
    factors: Map<String, Double>,
    opponentScores: Double,
    league: DefenseLeague,
    implied: Double?,
    ownYards: Double = 0.0,
    yardsFactor: Double = 1.0,
): DefenseStages {
    val matchupPoints = if (league.pointsAllowed > 0.0) ownAllowed * opponentScores / league.pointsAllowed else ownAllowed
    val matched = own.mapValues { (stat, rate) -> rate * (factors[stat] ?: 1.0) }
    val matchupYards = ownYards * yardsFactor
    val yards = { yardsAllowed: Double -> if (ownYards > 0.0) mapOf(YARDS_ALLOWED to yardsAllowed) else emptyMap() }
    return DefenseStages(
        baseline = own + (POINTS_ALLOWED to ownAllowed) + yards(ownYards),
        afterMatchup = matched + (POINTS_ALLOWED to matchupPoints) + yards(matchupYards),
        final = matched + (POINTS_ALLOWED to (implied ?: matchupPoints)) + yards(matchupYards * yardsScript(implied, matchupPoints)),
    )
}
