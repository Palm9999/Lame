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
}
