package dev.gridiron.core.projections

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ReplacementLevelTest {
    private fun ps(pos: String, vararg points: Double) = points.mapIndexed { i, p -> LineupCandidate("$pos$i", pos, p) }

    @Test
    fun `replacement is the best player left once every team's slots are filled, flex going to the best left`() {
        val players = ps("QB", 300.0, 280.0, 200.0) + ps("RB", 250.0, 240.0, 230.0, 220.0, 150.0) + ps("WR", 260.0, 210.0, 205.0, 100.0)
        // Two teams of QB, RB, WR, FLEX: QBs 1-2 start; RBs 1-2 and WRs 1-2; the two flexes take RB 230 and RB 220.
        val levels = ReplacementLevel.of(players, teams = 2, slots = mapOf("QB" to 1, "RB" to 1, "WR" to 1, "FLEX" to 1))
        assertEquals(mapOf("QB" to 200.0, "RB" to 150.0, "WR" to 205.0), levels)
        val values = ReplacementLevel.values(players, levels)
        assertEquals(100.0, values.getValue("QB0"), 1e-9)
        assertEquals(-105.0, values.getValue("WR3"), 1e-9)
    }

    @Test
    fun `a position with too few players has a replacement level of zero`() {
        assertEquals(mapOf("K" to 0.0), ReplacementLevel.of(ps("K", 150.0), teams = 12, slots = mapOf("K" to 1)))
    }
}
