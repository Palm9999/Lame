package dev.gridiron.core.forecast

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class KindsTest {
    @Test
    fun `every stat the baseline model produces has a kind`() {
        val model = BaselineModel(emptyMap(), emptyMap())
        val rates = Rates(emptyMap())
        val volume = TeamVolume(32.0, 30.0, 25.0)
        for (position in POSITIONS) {
            val out = model.project(PlayerContext(position, 2025, 3, emptyList(), regimeBreak = false), rates, volume)
            assertTrue(KINDS.keys.containsAll(out.keys), "$position: ${out.keys - KINDS.keys}")
        }
    }

    @Test
    fun `adjust multiplies each stat by its kind's multiplier`() {
        val out = adjust(mapOf("targets" to 10.0, "receiving_yards" to 80.0, "rushing_tds" to 0.5)) { side, type ->
            when {
                type == StatType.YARDS -> 1.25
                side == Side.RUSH -> 2.0
                else -> 1.0
            }
        }
        assertEquals(mapOf("targets" to 10.0, "receiving_yards" to 100.0, "rushing_tds" to 1.0), out)
    }

    @Test
    fun `reference points are full PPR`() {
        val points = referencePoints(
            mapOf("receptions" to 5.0, "receiving_yards" to 60.0, "receiving_tds" to 0.5, "fumbles_lost" to 0.1, "passing_yards" to 250.0),
        )
        assertEquals(5 + 6 + 3 - 0.2 + 10, points, 1e-12)
    }

    @Test
    fun `ordinals`() {
        assertEquals(listOf("1st", "2nd", "3rd", "4th", "11th", "12th", "13th", "21st", "32nd"), listOf(1, 2, 3, 4, 11, 12, 13, 21, 32).map(::ordinal))
    }
}
