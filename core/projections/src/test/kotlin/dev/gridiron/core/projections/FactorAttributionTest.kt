package dev.gridiron.core.projections

import dev.gridiron.core.model.Position
import dev.gridiron.core.model.ScoringPresets
import dev.gridiron.core.statquery.Components
import kotlin.math.abs
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FactorAttributionTest {
    @Test
    fun `attributed factor points sum exactly to the real FP delta`() {
        val baseline = mapOf(Components.RECEPTIONS to 5.0, Components.RECEIVING_YARDS to 50.0)
        val final = mapOf(Components.RECEPTIONS to 6.0, Components.RECEIVING_YARDS to 65.0)
        val factors = listOf(
            ProjectionFactor("matchup", logMultiplier = 0.10, note = null),
            ProjectionFactor("game_script", logMultiplier = 0.05, note = null),
        )
        val attributed = attributeFactors(baseline, final, factors, ScoringPresets.PPR, Position.WR)

        val fpBaseline = score(baseline, ScoringPresets.PPR, Position.WR)
        val fpFinal = score(final, ScoringPresets.PPR, Position.WR)
        val expectedDelta = fpFinal - fpBaseline

        assertTrue(abs(attributed.sumOf { it.points } - expectedDelta) < 1e-6)
    }

    @Test
    fun `near-zero total log-multiplier does not divide by zero or produce NaN`() {
        val baseline = mapOf(Components.RECEPTIONS to 5.0)
        val final = mapOf(Components.RECEPTIONS to 5.0) // no real change
        val factors = listOf(
            ProjectionFactor("matchup", logMultiplier = 0.001, note = null),
            ProjectionFactor("game_script", logMultiplier = -0.001, note = null),
        )
        val attributed = attributeFactors(baseline, final, factors, ScoringPresets.PPR, Position.WR)
        attributed.forEach {
            assertTrue(it.points.isFinite(), "factor ${it.factor} produced a non-finite value: ${it.points}")
        }
    }
}
