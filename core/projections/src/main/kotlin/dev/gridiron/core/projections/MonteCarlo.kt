package dev.gridiron.core.projections

import dev.gridiron.core.model.Position
import dev.gridiron.core.model.ScoringProfile
import dev.gridiron.core.statquery.Component
import java.util.SplittableRandom
import kotlin.math.ln
import kotlin.math.max

public enum class DistributionFamily { NEGBINOM, BINOMIAL, GAMMA, POISSON }

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
 * millisecond even on a mid-range device.
 */
public fun simulate(
    distributions: List<DistributionSpec>,
    profile: ScoringProfile,
    position: Position?,
    draws: Int = 10_000,
    seed: Long = 42L,
): SimulationResult {
    val rng = SplittableRandom(seed)
    val samples = DoubleArray(draws)
    val componentMap = HashMap<Component, Double>(distributions.size)

    for (i in 0 until draws) {
        for (spec in distributions) {
            componentMap[spec.component] = drawOne(spec, rng)
        }
        samples[i] = score(componentMap, profile, position)
    }
    samples.sort()

    fun percentile(p: Double): Double {
        val idx = (p * (samples.size - 1)).toInt().coerceIn(0, samples.size - 1)
        return samples[idx]
    }
    return SimulationResult(percentile(0.10), percentile(0.25), percentile(0.50), percentile(0.90))
}

private fun drawOne(spec: DistributionSpec, rng: SplittableRandom): Double {
    // variance = 0 is a point mass at the mean for every family — a bye-week
    // placeholder or a data gap must never divide by zero or produce NaN.
    if (spec.variance <= 0.0) return spec.mean
    return when (spec.family) {
        DistributionFamily.GAMMA -> drawGamma(spec.mean, spec.variance, rng)
        DistributionFamily.POISSON -> drawPoisson(spec.mean, rng)
        DistributionFamily.NEGBINOM -> drawGamma(spec.mean, spec.variance, rng) // shape-compatible fallback; a
        // dedicated NegBinom sampler is a follow-up once real dist_family
        // data from the ETL side is available to validate against.
        DistributionFamily.BINOMIAL -> drawGamma(spec.mean, spec.variance, rng)
    }
}

private fun drawGamma(mean: Double, variance: Double, rng: SplittableRandom): Double {
    val shape = mean * mean / variance
    val scale = variance / mean
    // Marsaglia-Tsang method, shape >= 1 (clamp — shrinkage-heavy small-n
    // components can produce shape < 1 from a tiny mean/large variance; a
    // proper shape<1 boost-trick sampler is a follow-up, this clamp keeps
    // the draw defined and non-crashing in the meantime).
    val d = max(shape, 1.0) - 1.0 / 3.0
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
