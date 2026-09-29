package dev.gridiron.core.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class YardsAllowedTest {
    private val ppr = ScoringPresets.PPR

    @Test
    fun `ESPN's yards tiers are on in every preset`() {
        assertEquals(
            listOf(0 to 5.0, 100 to 3.0, 200 to 2.0, 300 to 0.0, 350 to -1.0, 400 to -3.0, 450 to -5.0, 500 to -6.0, 550 to -7.0),
            ESPN_YARDS_ALLOWED.map { it.min to it.points },
        )
        for (preset in ScoringPresets.all) assertEquals(ESPN_YARDS_ALLOWED, preset.yardsAllowedTiers, preset.id)
    }

    @ParameterizedTest
    @CsvSource(
        "0,5", "99,5", "100,3", "199,3", "200,2", "299,2", "300,0", "349,0",
        "350,-1", "399,-1", "400,-3", "449,-3", "450,-5", "499,-5", "500,-6", "549,-6", "550,-7", "900,-7",
    )
    fun `a game lands in exactly one yards tier, edges included`(allowed: Double, points: Double) {
        assertEquals(points, ppr.yardsAllowedPoints(allowed), 0.0)
    }

    @Test
    fun `a profile with no yards tiers scores yards as nothing, and its points tiers still score`() {
        val noYards = ppr.copy(id = "u1", name = "Mine", yardsAllowedTiers = emptyList())
        assertEquals(0.0, noYards.yardsAllowedPoints(0.0), 0.0)
        assertEquals(5.0, noYards.pointsAllowedPoints(0.0), 0.0)
    }

    @Test
    fun `expected yards points weigh each tier by its chance, on whole yards`() {
        val two = ppr.copy(id = "u1", name = "Mine", yardsAllowedTiers = listOf(ScoringTier(0, 10.0), ScoringTier(300, -4.0)))
        val below = normalCdf((299.5 - 320.0) / 80.0)
        assertEquals(10.0 * below - 4.0 * (1 - below), two.expectedYardsAllowedPoints(320.0, 80.0), 1e-12)
        assertEquals(ppr.yardsAllowedPoints(310.0), ppr.expectedYardsAllowedPoints(310.0, 1e-6), 1e-9)
        assertEquals(0.0, ppr.expectedYardsAllowedPoints(299.6, 0.0), 0.0) // 300: the 300-349 tier
    }

    @Test
    fun `more yards expected means fewer tier points`() {
        assertEquals(true, ppr.expectedYardsAllowedPoints(250.0, 80.0) > ppr.expectedYardsAllowedPoints(420.0, 80.0))
    }

    @Test
    fun `yards tiers start at 0 and rise, like points tiers`() {
        val base = ppr.copy(id = "u1", name = "Mine")
        assertThrows<IllegalArgumentException> { base.copy(yardsAllowedTiers = listOf(ScoringTier(100, 3.0))) }
        assertThrows<IllegalArgumentException> { base.copy(yardsAllowedTiers = listOf(ScoringTier(0, 5.0), ScoringTier(0, 3.0))) }
        assertThrows<IllegalArgumentException> { base.copy(yardsAllowedTiers = listOf(ScoringTier(0, 5.0), ScoringTier(200, 2.0), ScoringTier(100, 3.0))) }
        assertThrows<IllegalArgumentException> { ScoringTier(-1, 0.0) }
    }

    @Test
    fun `PointsAllowedTier is ScoringTier under its old name`() {
        assertEquals(ScoringTier(7, 3.0), PointsAllowedTier(7, 3.0))
    }
}
