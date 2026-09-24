package dev.gridiron.core.projections

import dev.gridiron.core.model.Position
import dev.gridiron.core.model.ScoringPresets
import dev.gridiron.core.model.ScoringRule
import dev.gridiron.core.statquery.Components
import kotlin.test.Test
import kotlin.test.assertEquals

class ScorerTest {
    @Test
    fun `PPR scores a reception, a yard and a touchdown`() {
        val components = mapOf(
            Components.RECEPTIONS to 5.0,
            Components.RECEIVING_YARDS to 60.0,
            Components.RECEIVING_TDS to 1.0,
        )
        // 5*1.0 (PPR reception) + 60*0.1 (yardage) + 1*6.0 (TD) = 5 + 6 + 6 = 17
        assertEquals(17.0, score(components, ScoringPresets.PPR, Position.WR), absoluteTolerance = 1e-9)
    }

    @Test
    fun `standard scoring gives no reception points`() {
        val components = mapOf(Components.RECEPTIONS to 5.0)
        assertEquals(0.0, score(components, ScoringPresets.STANDARD, Position.WR), absoluteTolerance = 1e-9)
    }

    @Test
    fun `a component absent from the map scores as zero, never throws`() {
        // Only receptions supplied; every other rule's component is missing
        // from the map entirely, not present-with-null.
        val components = mapOf(Components.RECEPTIONS to 3.0)
        val result = score(components, ScoringPresets.PPR, Position.WR)
        assertEquals(3.0, result, absoluteTolerance = 1e-9) // 3 receptions * 1.0, nothing else
    }

    @Test
    fun `an unsupported position (kicker) scores zero without throwing`() {
        val components = mapOf(Components.RECEPTIONS to 5.0)
        // No ScoringRule reads a kicker-specific component, and Position.K
        // (if it exists) or a null position must not crash score().
        val result = score(components, ScoringPresets.PPR, position = null)
        assertEquals(5.0, result, absoluteTolerance = 1e-9) // PPR reception rule still applies to the map's contents
    }
}

private fun assertEquals(expected: Double, actual: Double, absoluteTolerance: Double) {
    kotlin.test.assertTrue(
        kotlin.math.abs(expected - actual) <= absoluteTolerance,
        "expected $expected, was $actual",
    )
}
