package dev.gridiron.core.projections

import dev.gridiron.core.model.Position
import dev.gridiron.core.model.ScoringPresets
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ProjectedPointsTest {
    @Test
    fun `family names map to the simulation's families, and unknown ones to gamma`() {
        assertEquals(DistributionFamily.NEGBINOM, familyOf("negbinom"))
        assertEquals(DistributionFamily.BINOMIAL, familyOf("binomial"))
        assertEquals(DistributionFamily.POISSON, familyOf("poisson"))
        assertEquals(DistributionFamily.GAMMA, familyOf("gamma"))
        assertEquals(DistributionFamily.GAMMA, familyOf(null))
    }

    @Test
    fun `points are the scored means, with the floor below and the ceiling above`() {
        val components = listOf(
            ProjectionComponent("receptions", 5.0, 6.0, "binomial"),
            ProjectionComponent("receiving_yards", 60.0, 900.0, "gamma"),
            ProjectionComponent("receiving_tds", 0.4, 0.4, "poisson"),
        )

        val points = projectPoints(components, ScoringPresets.PPR, Position.WR)

        assertEquals(5 + 6 + 2.4, points.points, 1e-9)
        assertTrue(points.floor < points.points && points.points < points.ceiling, "$points")
    }

    @Test
    fun `each position's range is widened around the points by its calibration factor`() {
        assertEquals(4.92 to 16.35, calibratedRange(10.0, 6.0, 15.0, Position.QB).let { round(it.first) to round(it.second) })
        assertEquals(10.0 - 1.38 * 4 to 10.0 + 1.38 * 5, calibratedRange(10.0, 6.0, 15.0, Position.WR))
        // No calibration data for other positions: the simulation's own range.
        assertEquals(6.0 to 15.0, calibratedRange(10.0, 6.0, 15.0, null))
        assertEquals(6.0 to 15.0, calibratedRange(10.0, 6.0, 15.0, Position.K))
    }

    @Test
    fun `a widened floor stops at zero, or at the simulation's own floor when that is below zero`() {
        assertEquals(0.0, calibratedRange(5.0, 1.0, 12.0, Position.RB).first)
        assertEquals(-1.0, calibratedRange(5.0, -1.0, 12.0, Position.RB).first)
    }

    @Test
    fun `projected points carry the calibrated range`() {
        val components = listOf(
            ProjectionComponent("receptions", 5.0, 6.0, "binomial"),
            ProjectionComponent("receiving_yards", 60.0, 900.0, "gamma"),
            ProjectionComponent("receiving_tds", 0.4, 0.4, "poisson"),
        )
        val raw = simulate(
            components.map { DistributionSpec(dev.gridiron.core.statquery.Component(it.metricId), familyOf(it.family), it.mean, it.variance) },
            ScoringPresets.PPR,
            Position.WR,
        )

        val points = projectPoints(components, ScoringPresets.PPR, Position.WR)

        assertEquals(calibratedRange(points.points, raw.p10, raw.p90, Position.WR), points.floor to points.ceiling)
    }

    private fun round(x: Double) = Math.round(x * 1e9) / 1e9

    @Test
    fun `points allowed simulate as normal`() {
        assertEquals(DistributionFamily.NORMAL, familyOf("normal"))
    }

    @Test
    fun `a caller can try other widening factors`() {
        assertEquals(2.0 to 20.0, calibratedRange(10.0, 6.0, 15.0, Position.K, widening = mapOf(Position.K to 2.0)))
    }

    @Test
    fun `a D-ST's projected points are its tiers in expectation`() {
        val week = listOf(ProjectionComponent("points_allowed", 17.6, 100.0, "normal"), ProjectionComponent("g", 1.0, 0.0))
        assertEquals(ScoringPresets.PPR.expectedPointsAllowedPoints(17.6, 10.0), projectPoints(week, ScoringPresets.PPR, Position.DST).points, 1e-9)
    }
}
