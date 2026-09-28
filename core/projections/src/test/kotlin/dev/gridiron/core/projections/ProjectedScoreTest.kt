package dev.gridiron.core.projections

import dev.gridiron.core.model.Position
import dev.gridiron.core.model.ScoringPresets
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

class ProjectedScoreTest {
    private val ppr = ScoringPresets.PPR

    @Test
    fun `a D-ST's tiers are scored in expectation, never as the tier of the mean`() {
        val week = listOf(
            ProjectionComponent("dst_sacks", 2.0, 2.0, "negbinom"),
            ProjectionComponent("points_allowed", 17.6, 100.0, "normal"),
            ProjectionComponent("g", 1.0, 0.0),
        )
        val expected = 2.0 + ppr.expectedPointsAllowedPoints(17.6, 10.0)
        assertEquals(expected, projectedScore(week, ppr, Position.DST), 1e-9)
        assertNotEquals(2.0 + ppr.pointsAllowedPoints(17.6), projectedScore(week, ppr, Position.DST), 1e-3)
    }

    @Test
    fun `rest of season scores each of its games' tiers`() {
        // Three games: 60 points allowed in all, a variance of 300 in all: 20 and 10 a game.
        val ros = listOf(ProjectionComponent("points_allowed", 60.0, 300.0, "normal"), ProjectionComponent("g", 3.0, 0.0))
        assertEquals(3 * ppr.expectedPointsAllowedPoints(20.0, 10.0), projectedScore(ros, ppr, Position.DST), 1e-9)
    }

    @Test
    fun `without points allowed it's the means scored`() {
        val kicker = listOf(ProjectionComponent("fg_made_0_39", 1.5, 1.5, "poisson"), ProjectionComponent("xp_made", 2.0, 2.0, "poisson"))
        assertEquals(6.5, projectedScore(kicker, ppr, Position.K), 1e-9)
    }
}
