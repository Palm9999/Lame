package dev.gridiron.core.projections

import dev.gridiron.core.model.Position
import dev.gridiron.core.model.ScoringPresets
import dev.gridiron.core.statquery.Components
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
    fun `zero variance produces a point mass at the mean, not NaN`() {
        val distributions = listOf(
            DistributionSpec(Components.RECEIVING_YARDS, DistributionFamily.GAMMA, mean = 60.0, variance = 0.0),
        )
        val result = simulate(distributions, ScoringPresets.STANDARD, Position.WR, draws = 1_000)
        assertTrue(result.p10.isFinite() && result.p90.isFinite())
        assertTrue(abs(result.p10 - result.p90) < 1e-6) // no spread when variance is zero
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
