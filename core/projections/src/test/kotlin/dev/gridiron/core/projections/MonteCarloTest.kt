package dev.gridiron.core.projections

import dev.gridiron.core.model.Position
import dev.gridiron.core.model.ScoringPresets
import dev.gridiron.core.statquery.Components
import java.util.SplittableRandom
import kotlin.math.abs
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MonteCarloTest {
    @Test
    fun `simulated median converges to the analytic mean within tolerance for a Gamma component`() {
        val distributions = listOf(
            DistributionSpec(Components.RECEIVING_YARDS, DistributionFamily.GAMMA, mean = 60.0, variance = 400.0),
        )
        val result = simulate(distributions, ScoringPresets.STANDARD, Position.WR, draws = 20_000)
        // Standard scoring: 0.1 pt/yard, so FP mean should track 6.0 (=60*0.1).
        assertTrue(abs(result.p50 - 6.0) < 0.5, "p50 was ${result.p50}, expected close to 6.0")
    }

    @Test
    fun `shape less than one gamma converges to the requested mean and variance`() {
        // mean=2, variance=50 => shape = mean^2 / variance = 0.08, a
        // realistic low-volume/high-variance component (e.g. a boom/bust
        // low-target receiver's weekly yards). The clamp-to-shape-1 bug
        // produced sample mean~25, variance~627 here (no defined relationship
        // to the requested distribution) — the boost trick must instead land
        // close to the requested mean=2.0 / variance=50.0.
        //
        // simulate() only exposes percentiles, which for a heavily
        // right-skewed shape<1 Gamma diverge sharply from the mean, so this
        // calls the internal drawGamma sampler directly to check the raw
        // sample mean/variance instead of going through score()/percentiles.
        val mean = 2.0
        val variance = 50.0
        val n = 50_000
        val rng = SplittableRandom(123L)
        var sum = 0.0
        var sumSq = 0.0
        repeat(n) {
            val x = drawGamma(mean, variance, rng)
            assertTrue(x >= 0.0 && x.isFinite(), "gamma draw was not a finite non-negative value: $x")
            sum += x
            sumSq += x * x
        }
        val sampleMean = sum / n
        val sampleVariance = sumSq / n - sampleMean * sampleMean
        assertTrue(
            abs(sampleMean - mean) / mean < 0.15,
            "sample mean was $sampleMean, expected close to $mean (shape<1 boost trick)",
        )
        assertTrue(
            abs(sampleVariance - variance) / variance < 0.30,
            "sample variance was $sampleVariance, expected close to $variance (shape<1 boost trick)",
        )
    }

    @Test
    fun `zero variance produces a point mass at the mean, not NaN`() {
        val distributions = listOf(
            DistributionSpec(Components.RECEIVING_YARDS, DistributionFamily.GAMMA, mean = 60.0, variance = 0.0),
        )
        val result = simulate(distributions, ScoringPresets.STANDARD, Position.WR, draws = 1_000)
        assertTrue(result.p10.isFinite() && result.p90.isFinite())
        assertTrue(abs(result.p10 - result.p90) < 1e-6) // no spread when variance is zero
    }

    @Test
    fun `zero mean with positive variance produces a finite point mass, not Infinity`() {
        // scale = variance / mean divides by zero when mean = 0 but
        // variance > 0 — a realistic case for a rookie/inactive player with
        // a shrinkage-derived nonzero variance but a true zero mean for some
        // component. This must degrade to a point mass at 0, not Infinity.
        val distributions = listOf(
            DistributionSpec(Components.RECEIVING_YARDS, DistributionFamily.GAMMA, mean = 0.0, variance = 25.0),
        )
        val result = simulate(distributions, ScoringPresets.STANDARD, Position.WR, draws = 1_000)
        assertTrue(
            result.p10.isFinite() && result.p25.isFinite() && result.p50.isFinite() && result.p90.isFinite(),
            "expected all percentiles finite, got $result",
        )
        assertTrue(result.p10 == 0.0 && result.p90 == 0.0, "expected an exact point mass at 0.0, got $result")
    }

    @Test
    fun `same seed gives the same result twice`() {
        val distributions = listOf(
            DistributionSpec(Components.RECEIVING_YARDS, DistributionFamily.GAMMA, mean = 60.0, variance = 400.0),
        )
        val a = simulate(distributions, ScoringPresets.PPR, Position.WR, draws = 5_000, seed = 7L)
        val b = simulate(distributions, ScoringPresets.PPR, Position.WR, draws = 5_000, seed = 7L)
        assertTrue(a == b, "same seed must reproduce identical percentiles: $a vs $b")
    }
}
