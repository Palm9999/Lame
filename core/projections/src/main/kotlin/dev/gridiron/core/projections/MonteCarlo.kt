package dev.gridiron.core.projections

import dev.gridiron.core.model.Position
import dev.gridiron.core.model.ScoringProfile
import dev.gridiron.core.statquery.Component
import dev.gridiron.core.statquery.Components
import java.util.SplittableRandom
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sqrt

public enum class DistributionFamily {
    NEGBINOM,
    BINOMIAL,
    GAMMA,
    POISSON,

    /** About normal, drawn on whole points and never below 0: a D/ST's points allowed in one game. */
    NORMAL,
}

public data class DistributionSpec(
    val component: Component,
    val family: DistributionFamily,
    val mean: Double,
    val variance: Double,
)

public data class SimulationResult(val p10: Double, val p25: Double, val p50: Double, val p90: Double)

/**
 * Single-player Monte Carlo: no cross-player correlation (that needs the
 * Gaussian-copula machinery, out of scope until Phase 6's decision tools —
 * see design spec §3). Plain [DoubleArray] and [SplittableRandom], matching
 * the research doc's implementation notes; 10k draws is comfortably under a
 * millisecond even on a mid-range device. A D/ST's points allowed are drawn
 * per game (`g` of them) and scored through the profile's tiers.
 */
public fun simulate(
    distributions: List<DistributionSpec>,
    profile: ScoringProfile,
    position: Position?,
    draws: Int = 10_000,
    seed: Long = 42L,
): SimulationResult {
    val samples = drawPoints(distributions, profile, position, draws, seed)

    fun percentile(p: Double): Double {
        val idx = (p * (samples.size - 1)).toInt().coerceIn(0, samples.size - 1)
        return samples[idx]
    }
    return SimulationResult(percentile(0.10), percentile(0.25), percentile(0.50), percentile(0.90))
}

/** Every draw's points from [simulate]'s simulation, lowest first. */
public fun drawPoints(
    distributions: List<DistributionSpec>,
    profile: ScoringProfile,
    position: Position?,
    draws: Int = 10_000,
    seed: Long = 42L,
): DoubleArray {
    val rng = SplittableRandom(seed)
    val samples = DoubleArray(draws)
    val componentMap = HashMap<Component, Double>(distributions.size)

    // A D/ST's points and yards allowed score a tier per game, so each of its `g` games is drawn on its own,
    // the two together, and scored through the profile's tiers; everything else is drawn once and scored by score().
    val allowed = distributions.firstOrNull { it.component == Components.POINTS_ALLOWED }
    val yards = distributions.firstOrNull { it.component == Components.YARDS_ALLOWED }
    val independent = distributions.filter { it.component != Components.POINTS_ALLOWED && it.component != Components.YARDS_ALLOWED }
    val games = distributions.firstOrNull { it.component == Components.GAMES }?.mean?.roundToInt()?.coerceAtLeast(1) ?: 1
    fun perGameMean(d: DistributionSpec?) = (d?.mean ?: 0.0) / games
    fun perGameSd(d: DistributionSpec?) = sqrt((d?.variance ?: 0.0).coerceAtLeast(0.0) / games)

    for (i in 0 until draws) {
        for (spec in independent) {
            componentMap[spec.component] = drawOne(spec, rng)
        }
        var points = score(componentMap, profile, position)
        if (allowed != null || yards != null) {
            repeat(games) {
                val (pa, ya) = drawJointAllowed(
                    perGameMean(allowed), perGameSd(allowed), perGameMean(yards), perGameSd(yards), DST_POINTS_YARDS_CORRELATION, rng,
                )
                if (allowed != null) points += profile.pointsAllowedPoints(pa)
                if (yards != null) points += profile.yardsAllowedPoints(ya)
            }
        }
        samples[i] = points
    }
    samples.sort()
    return samples
}

private fun drawOne(spec: DistributionSpec, rng: SplittableRandom): Double {
    // variance = 0, or a non-positive mean, is a point mass at the mean for
    // every family — a bye-week placeholder, an inactive/rookie player with
    // a true zero mean, or a data gap must never divide by zero or produce
    // NaN/Infinity.
    if (spec.mean <= 0.0 || spec.variance <= 0.0) return spec.mean
    return when (spec.family) {
        DistributionFamily.GAMMA -> drawGamma(spec.mean, spec.variance, rng)
        DistributionFamily.POISSON -> drawPoisson(spec.mean, rng)
        DistributionFamily.NEGBINOM -> drawGamma(spec.mean, spec.variance, rng) // shape-compatible fallback; a
        // dedicated NegBinom sampler is a follow-up once real dist_family
        // data from the ETL side is available to validate against.
        DistributionFamily.BINOMIAL -> drawGamma(spec.mean, spec.variance, rng)
        DistributionFamily.NORMAL -> drawAllowed(spec.mean, sqrt(spec.variance), rng)
    }
}

/** One game's points allowed: Normal([mean], [sd]) on whole points, never below 0. */
private fun drawAllowed(mean: Double, sd: Double, rng: SplittableRandom): Double =
    Math.round(mean + sd * gaussian(rng)).toDouble().coerceAtLeast(0.0)

/**
 * One game's points and yards allowed, Normal on whole units, never below 0,
 * correlated at [correlation]. One gaussian draw decides the points, and the
 * yards take [correlation] of it plus an independent draw for the rest. With
 * no yards (a zero spread and mean) the draw is the same as [drawAllowed]'s.
 */
internal fun drawJointAllowed(
    pointsMean: Double,
    pointsSd: Double,
    yardsMean: Double,
    yardsSd: Double,
    correlation: Double,
    rng: SplittableRandom,
): Pair<Double, Double> {
    val z1 = gaussian(rng)
    val points = Math.round(pointsMean + pointsSd * z1).toDouble().coerceAtLeast(0.0)
    if (yardsSd <= 0.0 && yardsMean <= 0.0) return points to 0.0
    val z2 = correlation * z1 + sqrt(1.0 - correlation * correlation) * gaussian(rng)
    return points to Math.round(yardsMean + yardsSd * z2).toDouble().coerceAtLeast(0.0)
}

// internal (not private) so MonteCarloTest can call it directly to verify the
// shape<1 boost trick's raw mean/variance, matching this module's existing
// pattern of `internal` test seams (see e.g. core/statquery's
// SCORING_COMPONENTS) rather than only checking through simulate()'s
// percentile-only, skew-obscured surface.
internal fun drawGamma(mean: Double, variance: Double, rng: SplittableRandom): Double {
    val shape = mean * mean / variance
    val scale = variance / mean
    // Shrinkage-heavy small-n components (low volume, high variance) can
    // produce shape < 1 — a realistic case (e.g. a boom/bust low-target
    // receiver), not a degenerate one, so it must be sampled correctly
    // rather than clamped up to shape 1.
    return if (shape < 1.0) {
        // Boost trick: draw Gamma(shape+1, scale) via the shape>=1 method
        // below, then correct down to Gamma(shape, scale) by multiplying by
        // U^(1/shape) (Ahrens-Dieter / standard boost-trick construction).
        val boosted = drawGammaShapeAtLeastOne(shape + 1.0, scale, rng)
        val u = rng.nextDouble().coerceAtLeast(1e-12)
        boosted * Math.pow(u, 1.0 / shape)
    } else {
        drawGammaShapeAtLeastOne(shape, scale, rng)
    }
}

private fun drawGammaShapeAtLeastOne(shape: Double, scale: Double, rng: SplittableRandom): Double {
    // Marsaglia-Tsang method. Callers must guarantee shape >= 1 — no
    // clamping here, since a clamp would silently sample the wrong
    // distribution (see drawGamma's boost trick for shape < 1).
    val d = shape - 1.0 / 3.0
    val c = 1.0 / kotlin.math.sqrt(9.0 * d)
    while (true) {
        var x: Double
        var v: Double
        do {
            x = gaussian(rng)
            v = 1.0 + c * x
        } while (v <= 0.0)
        v *= v * v
        val u = rng.nextDouble()
        if (u < 1.0 - 0.0331 * x * x * x * x) return d * v * scale
        if (ln(u) < 0.5 * x * x + d * (1.0 - v + ln(v))) return d * v * scale
    }
}

private fun drawPoisson(lambda: Double, rng: SplittableRandom): Double {
    // Knuth's method — fine for the small lambdas (TD counts) this is used for.
    val l = kotlin.math.exp(-lambda)
    var k = 0
    var p = 1.0
    do {
        k += 1
        p *= rng.nextDouble()
    } while (p > l)
    return (k - 1).toDouble()
}

private fun gaussian(rng: SplittableRandom): Double {
    // Box-Muller, one value per call (the cached-pair optimization is a
    // follow-up if profiling ever shows this as a hot path).
    val u1 = rng.nextDouble().coerceAtLeast(1e-12)
    val u2 = rng.nextDouble()
    return kotlin.math.sqrt(-2.0 * ln(u1)) * kotlin.math.cos(2.0 * Math.PI * u2)
}
