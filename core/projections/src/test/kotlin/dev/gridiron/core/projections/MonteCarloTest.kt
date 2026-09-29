package dev.gridiron.core.projections

import dev.gridiron.core.model.PointsAllowedTier
import dev.gridiron.core.model.Position
import dev.gridiron.core.model.ScoringPresets
import dev.gridiron.core.model.ScoringTier
import dev.gridiron.core.statquery.Components
import java.util.SplittableRandom
import kotlin.math.abs
import org.junit.jupiter.api.Assertions.assertEquals
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

    private val twoTiers = ScoringPresets.PPR.copy(
        id = "u1", name = "Two tiers",
        pointsAllowedTiers = listOf(PointsAllowedTier(0, 10.0), PointsAllowedTier(21, -4.0)),
    )

    @Test
    fun `each simulated game's points allowed land in one tier`() {
        val game = listOf(
            DistributionSpec(Components.POINTS_ALLOWED, DistributionFamily.NORMAL, mean = 20.0, variance = 100.0),
            DistributionSpec(Components.GAMES, DistributionFamily.GAMMA, mean = 1.0, variance = 0.0),
        )
        val result = simulate(game, twoTiers, Position.DST, draws = 2_000)
        // About half the games allow 20 or fewer (+10), the rest 21 or more (-4): nothing in between.
        assertEquals(-4.0, result.p10, 1e-9)
        assertEquals(10.0, result.p90, 1e-9)
        assertTrue(result.p50 == -4.0 || result.p50 == 10.0, "p50 was ${result.p50}")
    }

    @Test
    fun `rest of season simulates each of its games`() {
        val twoGames = listOf(
            DistributionSpec(Components.POINTS_ALLOWED, DistributionFamily.NORMAL, mean = 40.0, variance = 200.0),
            DistributionSpec(Components.GAMES, DistributionFamily.GAMMA, mean = 2.0, variance = 0.0),
        )
        val result = simulate(twoGames, twoTiers, Position.DST, draws = 2_000)
        // Two games of 20 ± 10: 20, 6 or -8 in all. The tier of their sum, 40, would always be -4.
        assertEquals(-8.0, result.p10, 1e-9)
        assertEquals(20.0, result.p90, 1e-9)
    }

    private val yardTiers = ScoringPresets.PPR.copy(
        id = "u2", name = "Yards only",
        pointsAllowedTiers = listOf(ScoringTier(0, 10.0), ScoringTier(21, -4.0)),
        yardsAllowedTiers = listOf(ScoringTier(0, 3.0), ScoringTier(330, -3.0)),
    )

    private fun defenseGame(games: Double = 1.0) = listOf(
        DistributionSpec(Components.POINTS_ALLOWED, DistributionFamily.NORMAL, mean = 20.0 * games, variance = 100.0 * games),
        DistributionSpec(Components.YARDS_ALLOWED, DistributionFamily.NORMAL, mean = 330.0 * games, variance = 80.0 * 80.0 * games),
        DistributionSpec(Components.GAMES, DistributionFamily.GAMMA, mean = games, variance = 0.0),
    )

    @Test
    fun `a game's points and yards allowed are drawn together, each scored once`() {
        val result = simulate(defenseGame(), yardTiers, Position.DST, draws = 20_000)
        // Points +10 / -4, yards +3 / -3: 13, 7, -1 or -7, and nothing in between.
        for (p in listOf(result.p10, result.p25, result.p50, result.p90)) assertTrue(p in setOf(-7.0, -1.0, 7.0, 13.0), "$p")
    }

    @Test
    fun `rest of season draws each game's points and yards`() {
        val result = simulate(defenseGame(2.0), yardTiers, Position.DST, draws = 20_000)
        // Two games of +13, +7, -1 or -7 each, so only sums of two of those. The tier of the summed 660 yards and 40 points
        // would be one -3 and one -4: -7, which no pair of games adds up to.
        val each = listOf(13.0, 7.0, -1.0, -7.0)
        val possible = each.flatMap { a -> each.map { b -> a + b } }.toSet()
        for (p in listOf(result.p10, result.p25, result.p50, result.p90)) assertTrue(p in possible, "$p")
        assertTrue(result.p10 < result.p90, "$result")
    }

    @Test
    fun `without yards the draws are exactly what they were`() {
        val pointsOnly = listOf(
            DistributionSpec(Components.POINTS_ALLOWED, DistributionFamily.NORMAL, mean = 20.0, variance = 100.0),
            DistributionSpec(Components.GAMES, DistributionFamily.GAMMA, mean = 1.0, variance = 0.0),
        )
        assertEquals(simulate(pointsOnly, twoTiers, Position.DST, draws = 2_000), simulate(pointsOnly, twoTiers, Position.DST, draws = 2_000))
        assertEquals(-4.0, simulate(pointsOnly, twoTiers, Position.DST, draws = 2_000).p10, 1e-9)
    }

    @Test
    fun `the joint draw reproduces the correlation`() {
        val rng = java.util.SplittableRandom(11L)
        val n = 40_000
        val xs = DoubleArray(n)
        val ys = DoubleArray(n)
        for (i in 0 until n) {
            val (a, b) = drawJointAllowed(20.0, 10.0, 330.0, 80.0, DST_POINTS_YARDS_CORRELATION, rng)
            xs[i] = a
            ys[i] = b
        }
        val mx = xs.average()
        val my = ys.average()
        val cov = xs.indices.sumOf { (xs[it] - mx) * (ys[it] - my) } / n
        val corr = cov / (Math.sqrt(xs.sumOf { (it - mx) * (it - mx) } / n) * Math.sqrt(ys.sumOf { (it - my) * (it - my) } / n))
        assertEquals(DST_POINTS_YARDS_CORRELATION, corr, 0.03)
    }
}
