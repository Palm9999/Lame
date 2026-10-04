package dev.gridiron.core.projections

/** 6*lambda_TD / FP — the single most explanatory number for weekly
 * volatility . Always a share from 0 to 1: a total at or
 * below zero (a D/ST whose points-allowed tiers cost more than it earns)
 * means zero dependence, not a divide-by-zero or a negative share. */
public fun tdDependence(tdComponentPoints: Double, totalPoints: Double): Double =
    if (totalPoints <= 0.0) 0.0 else (tdComponentPoints / totalPoints).coerceIn(0.0, 1.0)
