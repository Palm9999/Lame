package dev.gridiron.feature.projections

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClaimPlanTest {
    private fun row(id: String) = ProjectionRow(id, "P$id", "RB", "KC", 10.0, 5.0, 15.0)
    private fun add(id: String, gain: Double, drop: String?) = PickupLine(row(id), gain, "RB", null, drop?.let(::row))

    @Test
    fun `claims go by gain, and a claim sharing a drop waits on the one above`() {
        val plan = claimPlan(listOf(add("b", 20.0, "x"), add("a", 40.0, "x"), add("c", 10.0, "y")), left = 100, others = emptyList())
        assertEquals(listOf("a", "b", "c"), plan.claims.map { it.line.add.playerId })
        assertNull(plan.claims[0].onlyIfFails)
        assertEquals(1, plan.claims[1].onlyIfFails)
        assertNull(plan.claims[2].onlyIfFails)
    }

    @Test
    fun `bids never spend more than is left between claims that could all win`() {
        val plan = claimPlan(listOf(add("a", 60.0, "x"), add("b", 60.0, "y"), add("c", 60.0, "z")), left = 100, others = emptyList())
        // Half of 100, half of the 50 left, half of the 25 left (rounded).
        assertEquals(listOf(50, 25, 13), plan.claims.map { it.bid })
        assertTrue(plan.claims.sumOf { it.bid } <= 100)
        // A conditional claim bids from what's left above it but not the claim it waits on.
        val shared = claimPlan(listOf(add("a", 60.0, "x"), add("b", 60.0, "x")), left = 100, others = emptyList())
        assertEquals(listOf(50, 50), shared.claims.map { it.bid })
    }

    @Test
    fun `at most five claims and none once the money is gone`() {
        val many = (1..8).map { add("p$it", 60.0, "d$it") }
        assertEquals(5, claimPlan(many, left = 100, others = emptyList()).claims.size)
        assertEquals(0, claimPlan(many, left = 0, others = emptyList()).claims.size)
    }

    @Test
    fun `the league's spending places what you have left`() {
        val plan = claimPlan(emptyList(), left = 45, others = listOf("Rivals" to 82, "Bees" to 40, "Cats" to 10, "Unknown" to null))
        assertEquals("You have $45, 2nd most of 4. Most: Rivals $82; median $40.", plan.standing)
        assertNull(claimPlan(emptyList(), left = 45, others = listOf("Unknown" to null)).standing)
    }

    @Test
    fun `a claim reads its bid, drop and condition`() {
        val plan = claimPlan(listOf(add("a", 60.0, "x"), add("b", 60.0, "x"), add("c", 60.0, null)), left = 100, others = emptyList())
        assertEquals(listOf("#1 Add Pa ($50), drop Px", "#2 Add Pb ($50), drop Px · only if #1 fails", "#3 Add Pc ($25)"), plan.claims.map(::claimText))
    }
}
