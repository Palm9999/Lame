package dev.gridiron.core.data.live

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class LineupReviewTest {
    private fun p(id: String, slot: String, points: Double?) = MatchupPlayer(id, "P$id", slot, points)
    private val slots = mapOf("QB" to 1, "RB" to 1, "WR" to 1, "FLEX" to 1)
    private val positions = mapOf("q" to "QB", "r1" to "RB", "r2" to "RB", "w1" to "WR", "w2" to "WR", "x" to "WR")

    @Test
    fun `the review counts what the bench would have added and names the swaps`() {
        val side = MatchupSide(
            1, 0.0,
            listOf(p("q", "QB", 20.0), p("r1", "RB", 4.0), p("w1", "WR", 12.0), p("w2", "FLEX", 3.0), p("r2", "BE", 18.0), p("x", "IR", 30.0)),
        )
        val review = LineupReview.of(5, side, slots) { positions[it.espnId] }
        assertEquals(39.0, review.scored, 1e-9)
        // Best: QB 20, RB r2 18, WR 12, FLEX r1 4 (IR can't start).
        assertEquals(54.0, review.best, 1e-9)
        assertEquals(15.0, review.left, 1e-9)
        assertEquals(listOf("r2"), review.shouldHaveStarted.map { it.espnId })
        assertEquals(listOf("w2"), review.shouldHaveSat.map { it.espnId })
    }

    @Test
    fun `a lineup already the best leaves nothing, and a starter of unknown position still counts`() {
        val side = MatchupSide(1, 0.0, listOf(p("q", "QB", 20.0), p("r1", "RB", 9.0), p("w1", "WR", 12.0), p("zz", "FLEX", 7.0), p("w2", "BE", 3.0)))
        val review = LineupReview.of(5, side, slots) { positions[it.espnId] }
        assertEquals(48.0, review.scored, 1e-9)
        assertEquals(48.0, review.best, 1e-9)
        assertEquals(0.0, review.left, 1e-9)
        assertEquals(emptyList<MatchupPlayer>(), review.shouldHaveSat)
    }
}
