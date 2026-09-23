package dev.gridiron.core.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ScoringProfileTest {

    @Test
    fun `presets use ESPN defaults and differ only in reception points`() {
        val ppr = ScoringPresets.PPR
        assertEquals(0.04, ppr.weight(ScoringRule.PASS_YARD))
        assertEquals(4.0, ppr.weight(ScoringRule.PASS_TD))
        assertEquals(-2.0, ppr.weight(ScoringRule.INTERCEPTION))
        assertEquals(0.1, ppr.weight(ScoringRule.RUSH_YARD))
        assertEquals(6.0, ppr.weight(ScoringRule.REC_TD))
        assertEquals(2.0, ppr.weight(ScoringRule.RUSH_2PT))
        assertEquals(-2.0, ppr.weight(ScoringRule.FUMBLE_LOST))
        assertEquals(0.0, ppr.weight(ScoringRule.PASS_FIRST_DOWN))
        assertEquals(1.0, ScoringPresets.PPR.weight(ScoringRule.RECEPTION))
        assertEquals(0.5, ScoringPresets.HALF_PPR.weight(ScoringRule.RECEPTION))
        assertEquals(0.0, ScoringPresets.STANDARD.weight(ScoringRule.RECEPTION))
        val others = ScoringRule.entries - ScoringRule.RECEPTION
        for (rule in others) {
            assertEquals(ppr.weight(rule), ScoringPresets.STANDARD.weight(rule), rule.name)
        }
        assertTrue(ScoringPresets.all.all { it.yardageBonuses.isEmpty() && it.isPreset })
        assertEquals(listOf("preset:ppr", "preset:half", "preset:standard"), ScoringPresets.all.map { it.id })
    }

    @Test
    fun `reception weight uses the position override, else the base rule`() {
        val tePremium = ScoringPresets.PPR.copy(id = "u1", name = "TE premium", receptionByPosition = mapOf(Position.TE to 1.5))
        assertEquals(1.5, tePremium.receptionWeight(Position.TE))
        assertEquals(1.0, tePremium.receptionWeight(Position.WR))
        assertEquals(1.0, tePremium.receptionWeight(null))
        assertFalse(tePremium.isPreset)
    }

    @Test
    fun `invalid profiles are rejected`() {
        val base = ScoringPresets.PPR.copy(id = "u1", name = "Mine")
        assertThrows<IllegalArgumentException> { base.copy(name = " ") }
        assertThrows<IllegalArgumentException> { base.copy(id = "") }
        assertThrows<IllegalArgumentException> { base.copy(weights = mapOf(ScoringRule.PASS_TD to Double.NaN)) }
        assertThrows<IllegalArgumentException> { base.copy(receptionByPosition = mapOf(Position.QB to 1.0)) }
        assertThrows<IllegalArgumentException> { base.copy(receptionByPosition = mapOf(Position.TE to Double.POSITIVE_INFINITY)) }
    }

    @Test
    fun `bonus ranges include the minimum and exclude the maximum`() {
        val tier = YardageBonus(BonusStat.RUSHING_YARDS, min = 100, maxExclusive = 200, points = 3.0)
        assertFalse(tier.applies(99.0))
        assertTrue(tier.applies(100.0))
        assertTrue(tier.applies(199.0))
        assertFalse(tier.applies(200.0))
        val open = YardageBonus(BonusStat.RUSHING_YARDS, min = 200, maxExclusive = null, points = 6.0)
        assertTrue(open.applies(200.0))
        assertTrue(open.applies(412.0))
    }

    @Test
    fun `invalid bonuses are rejected`() {
        assertThrows<IllegalArgumentException> { YardageBonus(BonusStat.PASSING_YARDS, -1, null, 1.0) }
        assertThrows<IllegalArgumentException> { YardageBonus(BonusStat.PASSING_YARDS, 300, 300, 1.0) }
        assertThrows<IllegalArgumentException> { YardageBonus(BonusStat.PASSING_YARDS, 300, 200, 1.0) }
        assertThrows<IllegalArgumentException> { YardageBonus(BonusStat.PASSING_YARDS, 300, null, Double.NaN) }
    }

    @Test
    fun `every rule belongs to a group and has a label`() {
        assertEquals(25, ScoringRule.entries.size)
        assertTrue(ScoringRule.entries.all { it.label.isNotBlank() })
        assertEquals(ScoringGroup.TURNOVERS, ScoringRule.FUMBLE_LOST.group)
    }

    @Test
    fun `presets are found by id`() {
        assertEquals(ScoringPresets.HALF_PPR, ScoringPresets.byId("preset:half"))
        assertNull(ScoringPresets.byId("u1"))
    }

    @Test
    fun `a compare slot needs a player`() {
        assertThrows<IllegalArgumentException> { CompareSlot("", 2025, WeekRange(1, 18)) }
        assertEquals(CompareSlot("p", 2025, WeekRange(1, 8)), CompareSlot("p", 2025, WeekRange(1, 8)))
    }
}
