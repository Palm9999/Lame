package dev.gridiron.core.projections

import dev.gridiron.core.model.Position
import dev.gridiron.core.model.ScoringProfile
import dev.gridiron.core.statquery.Component

public data class ProjectedPoints(val points: Double, val floor: Double, val ceiling: Double)

/** The metric registry's `dist_family` as the simulation's family; unknown or missing is gamma. */
public fun familyOf(name: String?): DistributionFamily = when (name) {
    "negbinom" -> DistributionFamily.NEGBINOM
    "binomial" -> DistributionFamily.BINOMIAL
    "poisson" -> DistributionFamily.POISSON
    else -> DistributionFamily.GAMMA
}

/**
 * A projection's points under [profile]: the projected means scored, and the
 * floor and ceiling (10th and 90th percentiles) from simulating each stat
 * from its own distribution family. Fewer [draws] for long lists.
 */
public fun projectPoints(
    components: List<ProjectionComponent>,
    profile: ScoringProfile,
    position: Position?,
    draws: Int = 10_000,
): ProjectedPoints {
    val means = components.associate { Component(it.metricId) to it.mean }
    val specs = components.map { DistributionSpec(Component(it.metricId), familyOf(it.family), it.mean, it.variance) }
    val simulated = simulate(specs, profile, position, draws)
    return ProjectedPoints(score(means, profile, position), simulated.p10, simulated.p90)
}
