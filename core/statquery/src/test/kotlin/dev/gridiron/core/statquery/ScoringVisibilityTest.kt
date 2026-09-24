package dev.gridiron.core.statquery

import dev.gridiron.core.model.ScoringRule
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ScoringVisibilityTest {
    @Test
    fun `RULE_INPUTS is public and covers every ScoringRule`() {
        assertEquals(ScoringRule.entries.toSet(), RULE_INPUTS.keys)
    }

    @Test
    fun `RULE_INPUTS reception rule reads the RECEPTIONS component`() {
        val inputs = RULE_INPUTS.getValue(ScoringRule.RECEPTION)
        assertTrue(inputs.actual.any { it.component == Components.RECEPTIONS })
    }
}
