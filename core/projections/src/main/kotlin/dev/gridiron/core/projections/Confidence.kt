package dev.gridiron.core.projections

/** 6*lambda_TD / FP — the single most explanatory number for weekly
 * volatility (research doc §6.2). Always a share from 0 to 1: a total at or
 * below zero (a D/ST whose points-allowed tiers cost more than it earns)
 * means zero dependence, not a divide-by-zero or a negative share. */
public fun tdDependence(tdComponentPoints: Double, totalPoints: Double): Double =
    if (totalPoints <= 0.0) 0.0 else (tdComponentPoints / totalPoints).coerceIn(0.0, 1.0)

public enum class ConfidenceLevel { LOW, MEDIUM, HIGH }

/** Confidence badge driven by the James-Stein shrinkage weight (design spec §3),
 * not vibes: `w` close to 0 means the projection is mostly the positional
 * baseline (low confidence); `w` close to 1 means it's mostly the player's
 * own data (high confidence). */
public fun confidenceFrom(shrinkageWeight: Double): ConfidenceLevel = when {
    shrinkageWeight < 0.3 -> ConfidenceLevel.LOW
    shrinkageWeight < 0.7 -> ConfidenceLevel.MEDIUM
    else -> ConfidenceLevel.HIGH
}
