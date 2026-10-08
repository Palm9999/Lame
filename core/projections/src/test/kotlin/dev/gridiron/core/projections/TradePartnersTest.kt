package dev.gridiron.core.projections

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TradePartnersTest {
    private val slots = mapOf("QB" to 1, "RB" to 1, "WR" to 1)
    private fun c(id: String, pos: String, pts: Double) = LineupCandidate(id, pos, pts)

    // Me: weak RB (50), deep at WR (bench 140 beats most WR starters).
    private val me = listOf(c("q1", "QB", 200.0), c("r1", "RB", 50.0), c("w1", "WR", 150.0), c("w2", "WR", 140.0))
    // Rivals: deep at RB (bench 120), weak WR (60): the best fit.
    private val rivals = listOf(c("q2", "QB", 190.0), c("r2", "RB", 130.0), c("r3", "RB", 120.0), c("w3", "WR", 60.0))
    // Others: deep at RB too, but strong at WR: half a fit.
    private val others = listOf(c("q3", "QB", 180.0), c("r4", "RB", 125.0), c("r5", "RB", 110.0), c("w4", "WR", 160.0))
    // Thin: no bench, but short at WR, so you could sell (140 over their 90).
    private val thin = listOf(c("q4", "QB", 170.0), c("r6", "RB", 90.0), c("w5", "WR", 90.0))
    // Stacked: no bench and every starter above your bench.
    private val stacked = listOf(c("q5", "QB", 250.0), c("r7", "RB", 200.0), c("w6", "WR", 200.0))

    private val ranked = TradePartners.rank(slots, me, listOf("Others" to others, "Rivals" to rivals, "Thin" to thin, "Stacked" to stacked))

    @Test
    fun `the best fit is deep where you're thin and thin where you're deep`() {
        // Rivals 70 + 80; Others 110 over 50; Thin a one-way sale, 140 over 90.
        assertEquals(listOf("Rivals", "Others", "Thin"), ranked.map { it.partner })
        assertEquals(listOf(150.0, 60.0, 50.0), ranked.map { it.fit })
    }

    @Test
    fun `a fit names what they have and what you can offer`() {
        val r = ranked.first()
        assertEquals(listOf("r3"), r.theyHave.map { it.playerId })
        assertEquals(listOf("WR"), r.theyNeed)
        assertEquals(listOf("w2"), r.youOffer.map { it.playerId })
        // Their RB3 120 over my RB 50, plus my WR2 140 over their WR 60.
        assertEquals(70.0 + 80.0, r.fit, 1e-9)
    }

    @Test
    fun `a team with nothing to trade either way isn't listed`() {
        assertEquals(false, ranked.any { it.partner == "Stacked" })
    }

    @Test
    fun `at most the limit`() {
        assertEquals(1, TradePartners.rank(slots, me, listOf("Others" to others, "Rivals" to rivals), limit = 1).size)
    }

    @Test
    fun `week by week, a bye leaves a hole a partner's bench fills, and kickers count`() {
        val kSlots = mapOf("RB" to 1, "K" to 1)
        fun w(id: String, pos: String, vararg weeks: Pair<Int, Double>) = LineupCandidate(id, pos, weeks.sumOf { it.second }, weeks.toMap())
        // My one RB is on bye in week 2; their bench RB plays both weeks. Their kicker bench outscores mine too.
        val mine = listOf(w("r1", "RB", 1 to 20.0), w("k1", "K", 1 to 5.0, 2 to 5.0))
        val theirs = listOf(w("r2", "RB", 1 to 25.0, 2 to 25.0), w("r3", "RB", 1 to 10.0, 2 to 10.0), w("k2", "K", 1 to 9.0, 2 to 9.0), w("k3", "K", 1 to 8.0, 2 to 8.0))
        val fit = TradePartners.rank(kSlots, mine, listOf("Them" to theirs)).single()
        // Week 1: bench RB 10 under my 20, kicker 8 over 5 = 3. Week 2: RB 10 over my 0 = 10, kicker 3. Total 16.
        assertEquals(16.0, fit.fit, 1e-9)
        assertEquals(listOf("k3"), fit.theyHave.map { it.playerId })
    }
}
