package dev.gridiron.core.projections

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

class OpportunitiesTest {
    private val now = Instant.parse("2026-10-02T12:00:00Z")

    private fun u(id: String, position: String, usage: Double, team: String = "KC", ppg: Double? = 10.0) =
        UsageRow(id, "Player $id", position, team, usage, 4, ppg)

    private val backs = listOf(u("r1", "RB", 90.0), u("r2", "RB", 60.0), u("r3", "RB", 30.0), u("r4", "RB", 5.0))

    private fun find(rows: List<UsageRow>, vararg flags: Pair<String, InjuryFlag>) = Opportunities.find(rows, flags.toMap(), now)

    @Test
    fun `a doubtful RB1 moves RB2 to RB1 and RB3 to RB2`() {
        val found = find(backs, "r1" to InjuryFlag("D", now))
        assertEquals(listOf("r2", "r3"), found.map { it.player.playerId })
        assertEquals(listOf(2 to 1, 3 to 2), found.map { it.fromRank to it.toRank })
        assertEquals(listOf("r1"), found.first().injured.map { it.playerId })
        assertEquals("D", found.first().injured.single().abbr)
        assertEquals(1, found.first().injured.single().rank)
    }

    @Test
    fun `a questionable player counts only when ESPN dated it within a week`() {
        val fresh = InjuryFlag("Q", now.minus(Duration.ofDays(3)))
        val stale = InjuryFlag("Q", now.minus(Duration.ofDays(20)))
        assertEquals(listOf("r2", "r3"), find(backs, "r1" to fresh).map { it.player.playerId })
        assertTrue(find(backs, "r1" to stale).isEmpty())
        assertTrue(find(backs, "r1" to InjuryFlag("Q", null)).isEmpty())
        assertTrue(find(backs, "r1" to InjuryFlag("A", now)).isEmpty())
    }

    @Test
    fun `a QB1 out lifts the backup, and only the backup`() {
        val qbs = listOf(u("q1", "QB", 150.0), u("q2", "QB", 4.0), u("q3", "QB", 1.0))
        val found = find(qbs, "q1" to InjuryFlag("O", now))
        assertEquals(listOf("q2"), found.map { it.player.playerId })
        assertEquals(2 to 1, found.single().fromRank to found.single().toRank)
    }

    @Test
    fun `an injured player outside the top two moves no one`() {
        assertTrue(find(backs, "r3" to InjuryFlag("IR", now), "r4" to InjuryFlag("O", now)).isEmpty())
    }

    @Test
    fun `an injured RB2 brings RB3 up one place`() {
        val found = find(backs, "r2" to InjuryFlag("D", now))
        assertEquals(listOf("r3"), found.map { it.player.playerId })
        assertEquals(listOf("r2"), found.single().injured.map { it.playerId })
    }

    @Test
    fun `two injured starters bring the third man up two places`() {
        val found = find(backs, "r1" to InjuryFlag("O", now), "r2" to InjuryFlag("D", now))
        assertEquals(listOf("r3", "r4"), found.map { it.player.playerId })
        assertEquals(listOf(3 to 1, 4 to 2), found.map { it.fromRank to it.toRank })
        assertEquals(listOf("r1", "r2"), found.first().injured.map { it.playerId })
    }

    @Test
    fun `teams and positions are ranked apart`() {
        val rows = backs + u("w1", "WR", 40.0) + u("x1", "RB", 70.0, team = "BUF") + u("x2", "RB", 20.0, team = "BUF")
        val found = find(rows, "x1" to InjuryFlag("O", now))
        assertEquals(listOf("x2"), found.map { it.player.playerId })
    }

    @Test
    fun `ties in usage break by points per game then id`() {
        val tie = listOf(u("b", "RB", 50.0, ppg = 8.0), u("a", "RB", 50.0, ppg = 8.0), u("c", "RB", 50.0, ppg = 12.0), u("d", "RB", 1.0))
        // Order: c (12 ppg), a, b (id). Hurting c lifts a to RB1 and b to RB2.
        val found = find(tie, "c" to InjuryFlag("O", now))
        assertEquals(listOf("a", "b"), found.map { it.player.playerId })
    }
}
