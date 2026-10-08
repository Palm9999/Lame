package dev.gridiron.core.projections

import dev.gridiron.core.model.Position
import dev.gridiron.core.model.ScoringProfile
import dev.gridiron.core.statquery.Component

public data class ProjectedPoints(val points: Double, val floor: Double, val ceiling: Double)

/**
 * Correlation between one game's points allowed and yards allowed, measured
 * on every team-game of 2024-2025 (0.666, 1,140 games) and pinned by
 * `DstCorrelationTest` within 0.05. The Monte Carlo draws the two jointly.
 */
public const val DST_POINTS_YARDS_CORRELATION: Double = 0.67

/**
 * How much farther than the simulation's 10th and 90th percentiles each
 * position's floor and ceiling sit from the projection. The simulation draws
 * every stat independently, and TD counts ignore the projected variance, so
 * its range alone held only 58-76% of real games (a kicker's 86%). These
 * factors make it hold about 80% at each position (spec section 4's
 * calibration target): refitted 2026-10-04 on top of the ESPN blend, under
 * PPR, on a 2021-2025 build's 2022-2025 backtests pooled (2021 is a cold
 * start), each the smallest factor holding 80%; fitted season by season they
 * stay within about 0.1 of these (D/ST 0.2). K has no factor. Refit whenever
 * a forecast layer changes the error's spread: dump each counted
 * player-week's points, unwidened p10 and p90, and actual score, then
 * bisect each position's factor on the same rule as [calibratedRange].
 */
public val RANGE_WIDENING: Map<Position, Double> = mapOf(
    Position.QB to 1.27, Position.RB to 1.46, Position.WR to 1.38, Position.TE to 1.24,
    Position.DST to 1.22,
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

/**
 * The chance of at least one rushing or receiving TD in one game, treating the projected count as Poisson. On 2022-2025
 * (every projected QB, RB, WR and TE week, a missed game counting as none) it held within a few points at every level:
 * 24% scored 24% of the time, 34% 35%, 45% 44%, 54% 53%; the lowest bins ran a little high (15% scored 12%).
 */
public fun anytimeTd(components: List<ProjectionComponent>): Double {
    val expected = components.filter { it.metricId == "rushing_tds" || it.metricId == "receiving_tds" }.sumOf { it.mean }
    return 1.0 - kotlin.math.exp(-expected.coerceAtLeast(0.0))
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

/**
 * Where a projection's outcomes fall, for a chart: the share of [draws] simulated outcomes in each of [bins] equal bins
 * from the lowest to the 99th percentile, each outcome widened as [calibratedRange] widens the floor and ceiling (so the
 * chart and the range agree), and that span's ends.
 */
public fun projectedSpread(
    components: List<ProjectionComponent>,
    profile: ScoringProfile,
    position: Position?,
    bins: Int = 24,
    draws: Int = 4_000,
): ProjectedSpread {
    val specs = components.map { DistributionSpec(Component(it.metricId), familyOf(it.family), it.mean, it.variance) }
    val raw = drawPoints(specs, profile, position, draws)
    val points = projectedScore(components, profile, position)
    val k = position?.let { RANGE_WIDENING[it] } ?: 1.0
    // The same rule as the floor and ceiling: away from the projection by k, a low outcome stopping at zero.
    val widened = raw.map { s -> if (s < points) maxOf(points - k * (points - s), minOf(s, 0.0)) else points + k * (s - points) }
    val low = widened.first()
    val high = widened[(0.99 * (widened.size - 1)).toInt()]
    if (high <= low) return ProjectedSpread(List(bins) { if (it == 0) 1.0 else 0.0 }, low, low + 1.0)
    val counts = IntArray(bins)
    for (v in widened) if (v <= high) counts[((v - low) / (high - low) * bins).toInt().coerceIn(0, bins - 1)]++
    return ProjectedSpread(counts.map { it.toDouble() / widened.size }, low, high)
}

/** [shares] of outcomes per bin over [low]..[high] ([projectedSpread]). */
public data class ProjectedSpread(val shares: List<Double>, val low: Double, val high: Double)
