package dev.gridiron.core.projections

import dev.gridiron.core.model.Position
import dev.gridiron.core.model.ScoringProfile
import dev.gridiron.core.statquery.Component

public data class ProjectedPoints(val points: Double, val floor: Double, val ceiling: Double)

/**
 * How much farther than the simulation's 10th and 90th percentiles each
 * position's floor and ceiling sit from the projection. The simulation draws
 * every stat independently, and TD counts ignore the projected variance, so
 * its range alone held only 53-65% of real games. These factors were fitted
 * on the 2024 and 2025 backtests (pooled) so the range holds about 80% at
 * each position (spec section 4's calibration target). K and D/ST were
 * fitted the same way when they arrived (sub-project 4): a kicker's
 * simulation alone already holds 87%, so K has no factor.
 */
public val RANGE_WIDENING: Map<Position, Double> = mapOf(
    Position.QB to 1.40, Position.RB to 1.59, Position.WR to 1.52, Position.TE to 1.40,
    Position.DST to 1.09,
)

/**
 * The floor and ceiling shown for a projection of [points] whose simulation
 * gave [p10] and [p90]: each moved away from [points] by [position]'s
 * [RANGE_WIDENING] factor, never narrower than the simulation's. The floor
 * stops at zero, or at [p10] when the simulation itself went below zero.
 * [widening] is for fitting the factors; everyone else uses [RANGE_WIDENING].
 */
public fun calibratedRange(
    points: Double,
    p10: Double,
    p90: Double,
    position: Position?,
    widening: Map<Position, Double> = RANGE_WIDENING,
): Pair<Double, Double> {
    val k = position?.let { widening[it] } ?: 1.0
    val floor = minOf(p10, maxOf(points - k * (points - p10), minOf(p10, 0.0)))
    val ceiling = maxOf(p90, points + k * (p90 - points))
    return floor to ceiling
}

/** The metric registry's `dist_family` as the simulation's family; unknown or missing is gamma. */
public fun familyOf(name: String?): DistributionFamily = when (name) {
    "negbinom" -> DistributionFamily.NEGBINOM
    "binomial" -> DistributionFamily.BINOMIAL
    "poisson" -> DistributionFamily.POISSON
    "normal" -> DistributionFamily.NORMAL
    else -> DistributionFamily.GAMMA
}

/**
 * A projection's points under [profile] ([projectedScore]), and the floor
 * and ceiling from simulating each stat from its own distribution family
 * (10th and 90th percentiles), widened by [calibratedRange]. Fewer [draws]
 * for long lists; [widening] is for fitting the factors.
 */
public fun projectPoints(
    components: List<ProjectionComponent>,
    profile: ScoringProfile,
    position: Position?,
    draws: Int = 10_000,
    widening: Map<Position, Double> = RANGE_WIDENING,
): ProjectedPoints {
    val specs = components.map { DistributionSpec(Component(it.metricId), familyOf(it.family), it.mean, it.variance) }
    val simulated = simulate(specs, profile, position, draws)
    val points = projectedScore(components, profile, position)
    val (floor, ceiling) = calibratedRange(points, simulated.p10, simulated.p90, position, widening)
    return ProjectedPoints(points, floor, ceiling)
}
