package dev.gridiron.core.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class PointsAllowedTest {
    private val espn = ScoringPresets.PPR

    @Test
    fun `the normal CDF matches known values`() {
        assertEquals(0.5, normalCdf(0.0), 1e-7)
        assertEquals(0.975, normalCdf(1.959964), 1e-6)
        assertEquals(0.025, normalCdf(-1.959964), 1e-6)
        assertEquals(0.8413447, normalCdf(1.0), 1e-6)
    }

    @ParameterizedTest
    @CsvSource(
        "0,5", "1,4", "6,4", "7,3", "13,3", "14,1", "17,1", "18,0", "21,0",
        "22,-1", "27,-1", "28,-4", "34,-4", "35,-5", "45,-5", "46,-5", "70,-5",
    )
    fun `a game lands in exactly one of ESPN's tiers, edges included`(allowed: Double, points: Double) {
        assertEquals(points, espn.pointsAllowedPoints(allowed), 0.0)
    }

    @Test
    fun `a profile with no tiers scores points allowed as nothing`() {
        assertEquals(0.0, espn.copy(id = "u1", name = "Mine", pointsAllowedTiers = emptyList()).pointsAllowedPoints(0.0), 0.0)
    }

    @Test
    fun `expected tier points weigh each tier by its chance, on whole points`() {
        val two = espn.copy(id = "u1", name = "Mine", pointsAllowedTiers = listOf(PointsAllowedTier(0, 10.0), PointsAllowedTier(21, -4.0)))
        // Up to 20 points is everything below 20.5.
        val below = normalCdf((20.5 - 22.0) / 10.0)
        assertEquals(10.0 * below - 4.0 * (1 - below), two.expectedPointsAllowedPoints(22.0, 10.0), 1e-12)
        // A vanishing spread is the game's own tier; a zero one rounds to whole points.
        assertEquals(espn.pointsAllowedPoints(17.0), espn.expectedPointsAllowedPoints(17.0, 1e-6), 1e-9)
        assertEquals(0.0, espn.expectedPointsAllowedPoints(17.6, 0.0), 0.0) // 18: the 18-21 tier
    }

    @Test
    fun `more points expected means fewer tier points`() {
        assertEquals(true, espn.expectedPointsAllowedPoints(14.0, 10.0) > espn.expectedPointsAllowedPoints(30.0, 10.0))
    }

    @Test
    fun `tiers start at 0 and rise`() {
        val base = espn.copy(id = "u1", name = "Mine")
        assertThrows<IllegalArgumentException> { base.copy(pointsAllowedTiers = listOf(PointsAllowedTier(1, 4.0))) }
        assertThrows<IllegalArgumentException> { base.copy(pointsAllowedTiers = listOf(PointsAllowedTier(0, 5.0), PointsAllowedTier(0, 4.0))) }
        assertThrows<IllegalArgumentException> { base.copy(pointsAllowedTiers = listOf(PointsAllowedTier(0, 5.0), PointsAllowedTier(14, 1.0), PointsAllowedTier(7, 3.0))) }
        assertThrows<IllegalArgumentException> { PointsAllowedTier(-1, 0.0) }
        assertThrows<IllegalArgumentException> { PointsAllowedTier(0, Double.NaN) }
    }
}
