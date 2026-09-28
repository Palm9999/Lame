package dev.gridiron.core.model

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * A D/ST points-allowed scoring tier: [points] for a game in which the
 * opponent scored at least [min], up to the next tier's [min]. A profile's
 * tiers start at 0, so every game lands in exactly one.
 */
public data class PointsAllowedTier(val min: Int, val points: Double) {
    init {
        require(min >= 0) { "a tier can't start below 0 points, was $min" }
        require(points.isFinite()) { "tier points must be finite, was $points" }
    }
}

/** ESPN's default tiers: 0, 1-6, 7-13, 14-17, 18-21, 22-27, 28-34, 35-45 and 46+ points allowed. */
public val ESPN_POINTS_ALLOWED: List<PointsAllowedTier> = listOf(
    PointsAllowedTier(0, 5.0), PointsAllowedTier(1, 4.0), PointsAllowedTier(7, 3.0),
    PointsAllowedTier(14, 1.0), PointsAllowedTier(18, 0.0), PointsAllowedTier(22, -1.0),
    PointsAllowedTier(28, -4.0), PointsAllowedTier(35, -5.0), PointsAllowedTier(46, -5.0),
)

/** The standard normal CDF, through Abramowitz and Stegun's 7.1.26 erf (error below 1.5e-7). */
public fun normalCdf(z: Double): Double {
    val x = abs(z) / sqrt(2.0)
    val t = 1.0 / (1.0 + 0.3275911 * x)
    val poly = t * (0.254829592 + t * (-0.284496736 + t * (1.421413741 + t * (-1.453152027 + t * 1.061405429))))
    val erf = 1.0 - poly * exp(-x * x)
    return if (z >= 0) 0.5 * (1.0 + erf) else 0.5 * (1.0 - erf)
}
