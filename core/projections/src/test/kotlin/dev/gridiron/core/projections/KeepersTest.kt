package dev.gridiron.core.projections

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class KeepersTest {
    @Test
    fun `drafted player costs his round`() {
        assertEquals(5, Keepers.cost(KeeperPick(5, keeper = false), penalty = 1, undraftedRound = 16, override = null))
    }

    @Test
    fun `a keeper pick costs a round less, never below 1`() {
        assertEquals(4, Keepers.cost(KeeperPick(5, keeper = true), penalty = 1, undraftedRound = 16, override = null))
        assertEquals(1, Keepers.cost(KeeperPick(1, keeper = true), penalty = 1, undraftedRound = 16, override = null))
        assertEquals(1, Keepers.cost(KeeperPick(2, keeper = true), penalty = 3, undraftedRound = 16, override = null))
    }

    @Test
    fun `undrafted costs the undrafted round`() {
        assertEquals(14, Keepers.cost(null, penalty = 1, undraftedRound = 14, override = null))
    }

    @Test
    fun `an override wins`() {
        assertEquals(9, Keepers.cost(KeeperPick(2, keeper = false), penalty = 1, undraftedRound = 16, override = 9))
        assertEquals(1, Keepers.cost(null, penalty = 1, undraftedRound = 16, override = 0))
    }

    @Test
    fun `worth round is redraft rank over teams rounded up`() {
        assertEquals(1, Keepers.worth(1, teams = 12))
        assertEquals(1, Keepers.worth(12, teams = 12))
        assertEquals(2, Keepers.worth(13, teams = 12))
        assertEquals(3, Keepers.worth(25, teams = 10))
    }

    private fun c(id: String, round: Int?, rank: Int?, dynasty: Int?, keeper: Boolean = false) =
        KeeperCandidate(id, round?.let { KeeperPick(it, keeper) }, rank, dynasty)

    @Test
    fun `top N by surplus keep, ties by dynasty value`() {
        val rows = Keepers.rank(
            listOf(
                c("a", round = 1, rank = 2, dynasty = 9000), // cost 1, worth 1: 0
                c("b", round = 10, rank = 30, dynasty = 3000), // cost 10, worth 3: +7
                c("c", round = null, rank = 40, dynasty = 4000), // cost 16, worth 4: +12
                c("d", round = 8, rank = 13, dynasty = 5000), // cost 8, worth 2: +6
                c("e", round = 9, rank = 25, dynasty = 6000), // cost 9, worth 3: +6, more dynasty than d
            ),
            penalty = 1, undraftedRound = 16, overrides = emptyMap(), teams = 12, keepers = 3,
        )
        assertEquals(listOf("c", "b", "e", "d", "a"), rows.map { it.playerId })
        assertEquals(listOf(true, true, true, false, false), rows.map { it.keep })
        assertEquals(12, rows.first().surplus)
        assertEquals(16, rows.first().cost)
        assertEquals(4, rows.first().worth)
    }

    @Test
    fun `players without worth list last and never keep`() {
        val rows = Keepers.rank(
            listOf(c("k", round = 15, rank = null, dynasty = null), c("a", round = 1, rank = 200, dynasty = 100)),
            penalty = 1, undraftedRound = 16, overrides = emptyMap(), teams = 12, keepers = 2,
        )
        assertEquals(listOf("a", "k"), rows.map { it.playerId })
        assertEquals(listOf(true, false), rows.map { it.keep })
        assertNull(rows.last().worth)
        assertNull(rows.last().surplus)
        assertEquals(15, rows.last().cost)
    }

    @Test
    fun `an override for someone gone is ignored`() {
        val rows = Keepers.rank(
            listOf(c("a", round = 3, rank = 5, dynasty = 100)),
            penalty = 1, undraftedRound = 16, overrides = mapOf("gone" to 1, "a" to 2), teams = 12, keepers = 1,
        )
        assertEquals(listOf("a"), rows.map { it.playerId })
        assertEquals(2, rows.single().cost)
    }
}
