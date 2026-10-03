package dev.gridiron.core.projections

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class PickupsTest {
    private fun p(id: String, position: String, points: Double) = LineupCandidate(id, position, points)

    private val slots = mapOf("QB" to 1, "RB" to 1, "FLEX" to 1)
    private val roster = listOf(p("q", "QB", 20.0), p("r1", "RB", 14.0), p("r2", "RB", 9.0), p("w1", "WR", 8.0))

    @Test
    fun `a pickup's gain is the rise in the lineup total and names who he replaces`() {
        // Now: q, r1, FLEX r2 (9) = 43. With w2 (13) at FLEX: 47, and r2 leaves the lineup.
        val pickup = Lineups.pickups(slots, roster, listOf(p("w2", "WR", 13.0))).single()
        assertEquals("w2", pickup.add.playerId)
        assertEquals(4.0, pickup.gain, 1e-9)
        assertEquals("FLEX", pickup.slot)
        assertEquals("r2", pickup.replaces?.playerId)
    }

    @Test
    fun `one who would not start is skipped`() {
        assertEquals(emptyList<Pickup>(), Lineups.pickups(slots, roster, listOf(p("w2", "WR", 8.5), p("k", "K", 30.0))))
    }

    @Test
    fun `a pickup into an empty slot replaces no one`() {
        val pickup = Lineups.pickups(mapOf("QB" to 1, "K" to 1), listOf(p("q", "QB", 20.0)), listOf(p("k", "K", 7.0))).single()
        assertEquals(7.0, pickup.gain, 1e-9)
        assertNull(pickup.replaces)
        assertNull(pickup.drop)
    }

    @Test
    fun `the drop is the lowest bench player, the displaced starter included`() {
        val bench = roster + p("t", "TE", 2.0)
        // w2 displaces r2 (9); the bench is then w1 (8), t (2) and r2 (9): drop t.
        assertEquals("t", Lineups.pickups(slots, bench, listOf(p("w2", "WR", 13.0))).single().drop?.playerId)
        // Without t, the displaced r2 (9) and w1 (8) make up the bench: drop w1.
        assertEquals("w1", Lineups.pickups(slots, roster, listOf(p("w2", "WR", 13.0))).single().drop?.playerId)
    }

    @Test
    fun `ranked by gain and limited`() {
        val free = listOf(p("a", "WR", 10.0), p("b", "WR", 15.0), p("c", "WR", 12.0), p("d", "RB", 16.0))
        val picked = Lineups.pickups(slots, roster, free, limit = 2)
        assertEquals(listOf("d", "b"), picked.map { it.add.playerId })
        assertEquals(listOf(7.0, 6.0), picked.map { it.gain })
    }
}
