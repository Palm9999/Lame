package dev.gridiron.core.projections

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TradeTest {
    private fun p(id: String, position: String, points: Double) = LineupCandidate(id, position, points)

    private val slots = mapOf("QB" to 1, "RB" to 1, "WR" to 1)

    // Mine: two good RBs, a weak WR. Theirs: two good WRs, a weak RB.
    private val mine = listOf(p("q1", "QB", 200.0), p("r1", "RB", 150.0), p("r2", "RB", 140.0), p("w1", "WR", 60.0))
    private val theirs = listOf(p("q2", "QB", 190.0), p("w2", "WR", 150.0), p("w3", "WR", 140.0), p("r3", "RB", 50.0))

    @Test
    fun `a trade is valued by each best lineup before and after`() {
        val out = Trades.evaluate(slots, mine, theirs, give = setOf("r2"), get = setOf("w3"))
        assertEquals(410.0, out.mineBefore, 1e-9)
        assertEquals(490.0, out.mineAfter, 1e-9)
        assertEquals(390.0, out.theirsBefore, 1e-9)
        assertEquals(480.0, out.theirsAfter, 1e-9)
        assertEquals(80.0, out.myGain, 1e-9)
        assertEquals(90.0, out.theirGain, 1e-9)
    }

    @Test
    fun `giving a starter for a bench player loses what the lineup loses, not his whole total`() {
        val out = Trades.evaluate(slots, mine, theirs, give = setOf("r1"), get = setOf("r3"))
        // r2 (140) steps in for r1 (150): mine loses 10. Theirs: r1 (150) replaces r3 (50).
        assertEquals(-10.0, out.myGain, 1e-9)
        assertEquals(100.0, out.theirGain, 1e-9)
    }

    @Test
    fun `ideas only list trades that help both sides, the user's gain first`() {
        val ideas = Trades.ideas(slots, mine, listOf("Rivals" to theirs))
        assertTrue(ideas.isNotEmpty())
        for (idea in ideas) {
            assertTrue(idea.outcome.myGain >= Trades.MIN_GAIN && idea.outcome.theirGain >= Trades.MIN_GAIN)
            assertEquals("Rivals", idea.partner)
        }
        assertEquals(listOf("r2"), ideas.first().give.map { it.playerId })
        assertEquals(listOf("w2"), ideas.first().get.map { it.playerId })
        assertEquals(ideas.sortedByDescending { it.outcome.myGain }, ideas)
    }

    @Test
    fun `no idea when neither side has a surplus the other needs`() {
        val balanced = listOf(p("q2", "QB", 190.0), p("r3", "RB", 150.0), p("w2", "WR", 150.0))
        val same = listOf(p("q1", "QB", 200.0), p("r1", "RB", 150.0), p("w1", "WR", 150.0))
        assertEquals(emptyList<TradeIdea>(), Trades.ideas(slots, same, listOf("Rivals" to balanced)))
    }

    @Test
    fun `ideas are capped per partner and in all`() {
        val partners = (1..6).map { "Team $it" to theirs.map { c -> c.copy(playerId = "${c.playerId}-$it") } }
        val ideas = Trades.ideas(slots, mine, partners, limit = 5, perPartner = 1)
        assertEquals(5, ideas.size)
        assertEquals(5, ideas.map { it.partner }.distinct().size)
    }
}

class TradeTimingTest {
    @Test
    fun `a twelve-team league's ideas take well under a second`() {
        val slots = mapOf("QB" to 1, "RB" to 2, "WR" to 2, "TE" to 1, "FLEX" to 1, "D/ST" to 1, "K" to 1)
        val positions = listOf("QB", "QB", "RB", "RB", "RB", "RB", "RB", "WR", "WR", "WR", "WR", "WR", "TE", "TE", "K", "DST")
        val rng = java.util.Random(7)
        fun roster(t: Int) = positions.mapIndexed { i, pos -> LineupCandidate("t$t-$i", pos, 40.0 + rng.nextDouble() * 160.0) }
        val mine = roster(0)
        val partners = (1..11).map { "Team $it" to roster(it) }
        Trades.ideas(slots, mine, partners) // warm up
        val started = System.nanoTime()
        Trades.ideas(slots, mine, partners)
        val ms = (System.nanoTime() - started) / 1e6
        println("trade ideas: $ms ms")
        assertTrue(ms < 1000, "took $ms ms")
    }
}
