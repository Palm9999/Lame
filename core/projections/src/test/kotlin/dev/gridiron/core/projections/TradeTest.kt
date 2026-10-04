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

    private val b = Trades.BENCH_WEIGHT

    @Test
    fun `a trade is valued by each best lineup before and after, plus a tenth of the best bench player`() {
        val out = Trades.evaluate(slots, mine, theirs, give = setOf("r2"), get = setOf("w3"))
        assertEquals(410.0 + b * 140.0, out.mineBefore, 1e-9)
        assertEquals(490.0 + b * 60.0, out.mineAfter, 1e-9)
        assertEquals(390.0 + b * 140.0, out.theirsBefore, 1e-9)
        assertEquals(480.0 + b * 50.0, out.theirsAfter, 1e-9)
        assertEquals(emptyList<LineupCandidate>(), out.myDrops + out.theirDrops)
    }

    @Test
    fun `giving a starter for a bench player loses what the lineup loses, not his whole total`() {
        val out = Trades.evaluate(slots, mine, theirs, give = setOf("r1"), get = setOf("r3"))
        // r2 (140) steps in for r1 (150); r3 (50) becomes the bench. Theirs: r1 (150) replaces r3 (50).
        assertEquals(-10.0 + b * (50.0 - 140.0), out.myGain, 1e-9)
        assertEquals(100.0 + b * (140.0 - 140.0), out.theirGain, 1e-9)
    }

    @Test
    fun `byes count, each week being its own best lineup`() {
        // Two QBs: q1 scores 20 a week but sits week 2 (bye); q2 scores 12 in both weeks.
        val qbSlots = mapOf("QB" to 1)
        val both = listOf(
            LineupCandidate("q1", "QB", 20.0, mapOf(1 to 20.0)),
            LineupCandidate("q2", "QB", 24.0, mapOf(1 to 12.0, 2 to 12.0)),
        )
        // Week 1: q1 starts (20), q2 benched (12); week 2: q2 starts alone.
        assertEquals(20.0 + b * 12.0 + 12.0, Trades.value(qbSlots, both), 1e-9)
        // On season totals it would have been q2's 24 plus q1 on the bench.
        assertEquals(24.0 + b * 20.0, Trades.value(qbSlots, both.map { it.copy(weekly = emptyMap()) }), 1e-9)
    }

    @Test
    fun `the side receiving more players drops its lowest to keep its roster size`() {
        val out = Trades.evaluate(slots, mine, theirs, give = setOf("r2", "w1"), get = setOf("w3"))
        assertEquals(emptyList<LineupCandidate>(), out.myDrops)
        // They get r2 and w1 for w3: four players become five, so their lowest (r3, 50) goes.
        assertEquals(listOf("r3"), out.theirDrops.map { it.playerId })
        assertEquals(190.0 + 140.0 + 150.0 + b * 60.0, out.theirsAfter, 1e-9)
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

class TradeTwoForTwoTest {
    @Test
    fun `two-for-two trades are offered when no smaller one helps both`() {
        // Each side needs both of the other's surplus starters at a two-slot position.
        val slots = mapOf("RB" to 2, "WR" to 2)
        fun p(id: String, pos: String, pts: Double) = LineupCandidate(id, pos, pts)
        val mine = listOf(p("r1", "RB", 100.0), p("r2", "RB", 100.0), p("r3", "RB", 95.0), p("r4", "RB", 95.0), p("w1", "WR", 10.0), p("w2", "WR", 10.0))
        val theirs = listOf(p("w3", "WR", 100.0), p("w4", "WR", 100.0), p("w5", "WR", 95.0), p("w6", "WR", 95.0), p("r5", "RB", 10.0), p("r6", "RB", 10.0))
        val ideas = Trades.ideas(slots, mine, listOf("Rivals" to theirs))
        assertTrue(ideas.any { it.give.size == 2 && it.get.size == 2 }, ideas.toString())
    }
}

class TradeTimingTest {
    @Test
    fun `a twelve-team league's ideas, valued week by week, take about a second at most`() {
        val slots = mapOf("QB" to 1, "RB" to 2, "WR" to 2, "TE" to 1, "FLEX" to 1, "D/ST" to 1, "K" to 1)
        val positions = listOf("QB", "QB", "RB", "RB", "RB", "RB", "RB", "WR", "WR", "WR", "WR", "WR", "TE", "TE", "K", "DST")
        val rng = java.util.Random(7)
        // Thirteen weeks left, each player on a bye in one of them.
        fun roster(t: Int) = positions.mapIndexed { i, pos ->
            val bye = 5 + rng.nextInt(13)
            val weekly = (5..17).filter { it != bye }.associateWith { 3.0 + rng.nextDouble() * 12.0 }
            LineupCandidate("t$t-$i", pos, weekly.values.sum(), weekly)
        }
        val mine = roster(0)
        val partners = (1..11).map { "Team $it" to roster(it) }
        Trades.ideas(slots, mine, partners) // warm up
        val started = System.nanoTime()
        Trades.ideas(slots, mine, partners)
        val ms = (System.nanoTime() - started) / 1e6
        println("trade ideas: $ms ms")
        assertTrue(ms < 1500, "took $ms ms")
    }
}
