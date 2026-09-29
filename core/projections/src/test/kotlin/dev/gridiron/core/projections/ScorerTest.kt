package dev.gridiron.core.projections

import dev.gridiron.core.model.Position
import dev.gridiron.core.model.ScoringPresets
import dev.gridiron.core.statquery.Components
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ScorerTest {
    @Test
    fun `PPR scores a reception, a yard and a touchdown`() {
        val components = mapOf(
            Components.RECEPTIONS to 5.0,
            Components.RECEIVING_YARDS to 60.0,
            Components.RECEIVING_TDS to 1.0,
        )
        // 5*1.0 (PPR reception) + 60*0.1 (yardage) + 1*6.0 (TD) = 5 + 6 + 6 = 17
        assertEquals(17.0, score(components, ScoringPresets.PPR, Position.WR), 1e-9)
    }

    @Test
    fun `standard scoring gives no reception points`() {
        val components = mapOf(Components.RECEPTIONS to 5.0)
        assertEquals(0.0, score(components, ScoringPresets.STANDARD, Position.WR), 1e-9)
    }

    @Test
    fun `a component absent from the map scores as zero, never throws`() {
        // Only receptions supplied; every other rule's component is missing
        // from the map entirely, not present-with-null.
        val components = mapOf(Components.RECEPTIONS to 3.0)
        val result = score(components, ScoringPresets.PPR, Position.WR)
        assertEquals(3.0, result, 1e-9) // 3 receptions * 1.0, nothing else
    }

    @Test
    fun `a null position scores without throwing`() {
        val components = mapOf(Components.RECEPTIONS to 5.0)
        // A null position must not crash score(); the reception rule still reads the map.
        val result = score(components, ScoringPresets.PPR, position = null)
        assertEquals(5.0, result, 1e-9) // PPR reception rule still applies to the map's contents
    }

    @Test
    fun `kicking and team defense score with the presets' values`() {
        val kicker = mapOf(Components.FG_MADE_50 to 1.0, Components.FG_MISSED to 1.0, Components.XP_MADE to 2.0)
        assertEquals(6.0, score(kicker, ScoringPresets.PPR, Position.K), 1e-9)
        val defense = mapOf(Components.DST_SACKS to 2.0, Components.POINTS_ALLOWED to 0.0)
        // 2 sacks and a shutout (5).
        assertEquals(7.0, score(defense, ScoringPresets.STANDARD, Position.DST), 1e-9)
    }

    @Test
    fun `a week without points allowed scores no tier`() {
        assertEquals(3.0, score(mapOf(Components.FG_MADE_0_39 to 1.0), ScoringPresets.PPR, Position.K), 1e-9)
    }

    @Test
    fun `a D-ST week scores its yards tier, and a week without yards scores none`() {
        val defense = mapOf(Components.DST_SACKS to 2.0, Components.POINTS_ALLOWED to 0.0, Components.YARDS_ALLOWED to 250.0)
        // 2 sacks, 5 for a shutout, 2 for 200-299 yards.
        assertEquals(9.0, score(defense, ScoringPresets.STANDARD, Position.DST), 1e-9)
        assertEquals(7.0, score(defense - Components.YARDS_ALLOWED, ScoringPresets.STANDARD, Position.DST), 1e-9)
    }
}
