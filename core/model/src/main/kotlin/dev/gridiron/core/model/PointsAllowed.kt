package dev.gridiron.core.model

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * A D/ST scoring tier: [points] for a game in which the opponent scored or
 * gained at least [min] (points, or yards), up to the next tier's [min]. A
 * profile's tiers start at 0, so every game lands in exactly one.
 */
public data class ScoringTier(val min: Int, val points: Double) {
    init {
        require(min >= 0) { "a tier can't start below 0, was $min" }
        require(points.isFinite()) { "tier points must be finite, was $points" }
    }
}

/** The points-allowed tiers' original name. */
public typealias PointsAllowedTier = ScoringTier

/** ESPN's default tiers: 0, 1-6, 7-13, 14-17, 18-21, 22-27, 28-34, 35-45 and 46+ points allowed. */
public val ESPN_POINTS_ALLOWED: List<ScoringTier> = listOf(
    ScoringTier(0, 5.0), ScoringTier(1, 4.0), ScoringTier(7, 3.0),
    ScoringTier(14, 1.0), ScoringTier(18, 0.0), ScoringTier(22, -1.0),
    ScoringTier(28, -4.0), ScoringTier(35, -5.0), ScoringTier(46, -5.0),
)

/** ESPN's default yards-allowed tiers (net yards): 0-99, 100-199, 200-299, 300-349, 350-399, 400-449, 450-499, 500-549 and 550+. */
public val ESPN_YARDS_ALLOWED: List<ScoringTier> = listOf(
    ScoringTier(0, 5.0), ScoringTier(100, 3.0), ScoringTier(200, 2.0),
    ScoringTier(300, 0.0), ScoringTier(350, -1.0), ScoringTier(400, -3.0),
    ScoringTier(450, -5.0), ScoringTier(500, -6.0), ScoringTier(550, -7.0),
)

/** A tier list is empty, or starts at 0 and rises. */
internal fun tiersAreValid(tiers: List<ScoringTier>): Boolean =
    (tiers.isEmpty() || tiers.first().min == 0) && tiers.zipWithNext().all { (a, b) -> a.min < b.min }

/** The points of the highest tier starting at or below [x]; no tiers score nothing. */
internal fun tierPoints(tiers: List<ScoringTier>, x: Double): Double = tiers.lastOrNull { x >= it.min }?.points ?: 0.0

/**
 * The expected [tierPoints] for a game whose value is about Normal([mean], [sd])
 * and lands on whole units: a tier starting at 7 takes everything from 6.5 up,
 * and anything below the second tier's start is the first tier. A zero [sd] is
 * the tier of [mean] rounded.
 */
internal fun expectedTierPoints(tiers: List<ScoringTier>, mean: Double, sd: Double): Double {
    if (sd <= 0.0) return tierPoints(tiers, Math.round(mean).toDouble())
    return tiers.indices.sumOf { i ->
        val from = if (i == 0) 0.0 else normalCdf((tiers[i].min - 0.5 - mean) / sd)
        val to = tiers.getOrNull(i + 1)?.let { normalCdf((it.min - 0.5 - mean) / sd) } ?: 1.0
        tiers[i].points * (to - from)
    }
}

/** The standard normal CDF, through Abramowitz and Stegun's 7.1.26 erf (error below 1.5e-7). */
public fun normalCdf(z: Double): Double {
    val x = abs(z) / sqrt(2.0)
    val t = 1.0 / (1.0 + 0.3275911 * x)
    val poly = t * (0.254829592 + t * (-0.284496736 + t * (1.421413741 + t * (-1.453152027 + t * 1.061405429))))
    val erf = 1.0 - poly * exp(-x * x)
    return if (z >= 0) 0.5 * (1.0 + erf) else 0.5 * (1.0 - erf)
}
