package dev.gridiron.core.projections

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class LineupTest {
    private fun p(id: String, position: String, points: Double) = LineupCandidate(id, position, points)

    private fun BestLineup.at(slot: String) = spots.filter { it.slot == slot }.map { it.player?.playerId }

    @Test
    fun `a flex takes the best leftover`() {
        val lineup = Lineups.best(
            mapOf("RB" to 1, "WR" to 1, "FLEX" to 1),
            listOf(p("rb1", "RB", 15.0), p("rb2", "RB", 12.0), p("wr1", "WR", 11.0), p("te1", "TE", 9.0)),
        )
        assertEquals(listOf("rb1"), lineup.at("RB"))
        assertEquals(listOf("wr1"), lineup.at("WR"))
        assertEquals(listOf("rb2"), lineup.at("FLEX"))
        assertEquals(38.0, lineup.total, 1e-9)
        assertEquals(listOf("te1"), lineup.bench.map { it.playerId })
    }

    @Test
    fun `superflex takes a second QB only when he beats the flex options`() {
        val slots = mapOf("QB" to 1, "RB" to 1, "OP" to 1)
        val qbs = listOf(p("q1", "QB", 20.0), p("q2", "QB", 18.0))
        val rbs = listOf(p("r1", "RB", 14.0), p("r2", "RB", 10.0))
        assertEquals(listOf("q2"), Lineups.best(slots, qbs + rbs).at("OP"))
        assertEquals(listOf("r2"), Lineups.best(slots, listOf(p("q2", "QB", 8.0)) + listOf(p("q1", "QB", 20.0)) + rbs).at("OP"))
    }

    @Test
    fun `a slot nobody can fill stays empty`() {
        val lineup = Lineups.best(mapOf("QB" to 1, "K" to 1), listOf(p("q1", "QB", 20.0)))
        assertEquals(listOf<String?>(null), lineup.at("K"))
        assertNull(lineup.spots.single { it.slot == "K" }.player)
        assertEquals(20.0, lineup.total, 1e-9)
    }

    @Test
    fun `augmenting moves an earlier pick to another slot to make room`() {
        // The WR takes RB/WR first; the RB fits only there, so the WR moves on to WR/TE.
        val lineup = Lineups.best(
            mapOf("RB/WR" to 1, "WR/TE" to 1),
            listOf(p("w1", "WR", 30.0), p("r1", "RB", 25.0)),
        )
        assertEquals(listOf("r1"), lineup.at("RB/WR"))
        assertEquals(listOf("w1"), lineup.at("WR/TE"))
        assertEquals(55.0, lineup.total, 1e-9)
        assertEquals(emptyList<LineupCandidate>(), lineup.bench)
    }

    @Test
    fun `ties break by player id so the lineup is stable`() {
        val a = Lineups.best(mapOf("RB" to 1), listOf(p("b", "RB", 10.0), p("a", "RB", 10.0)))
        val b = Lineups.best(mapOf("RB" to 1), listOf(p("a", "RB", 10.0), p("b", "RB", 10.0)))
        assertEquals(listOf("a"), a.at("RB"))
        assertEquals(a, b)
    }

    @Test
    fun `unknown slot labels are ignored and spots follow the canonical order`() {
        val lineup = Lineups.best(
            mapOf("D/ST" to 1, "WAT" to 3, "QB" to 1, "RB" to 2),
            listOf(p("d", "DST", 7.0), p("q", "QB", 18.0), p("r1", "RB", 9.0), p("r2", "RB", 8.0)),
        )
        assertEquals(listOf("QB", "RB", "RB", "D/ST"), lineup.spots.map { it.slot })
        assertEquals(listOf("r1", "r2"), lineup.at("RB"))
    }

    @Test
    fun `a team defense with a negative week still starts`() {
        val lineup = Lineups.best(mapOf("D/ST" to 1), listOf(p("d1", "DST", -1.0), p("d2", "DST", -3.0)))
        assertEquals(listOf("d1"), lineup.at("D/ST"))
        assertEquals(-1.0, lineup.total, 1e-9)
    }

    @Test
    fun `the default slots are the usual nine starters`() {
        assertEquals(9, Lineups.DEFAULT_SLOTS.values.sum())
    }
}
