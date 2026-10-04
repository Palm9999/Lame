package dev.gridiron.feature.projections

import dev.gridiron.core.data.live.LeaguePlayer
import dev.gridiron.core.data.live.MyTeam
import org.junit.Assert.assertEquals
import org.junit.Test

class LineupHandcuffTest {
    private fun rb(id: String, team: String, points: Double, touches: Double) = ProjectionRow(id, "RB $id", "RB", team, points, 0.0, 0.0, touches = touches)

    private val rows = listOf(
        rb("a1", "KC", 16.0, 20.0), rb("a2", "KC", 5.0, 8.0), rb("a3", "KC", 2.0, 2.0),
        rb("b1", "BUF", 7.0, 10.0), rb("b2", "BUF", 6.0, 9.0),
    )

    private val team = MyTeam("Mine", 2026, listOf(LeaguePlayer("1", "RB a1", "RB", "a1"), LeaguePlayer("2", "RB b1", "RB", "b1")), emptyMap(), slotsAreDefault = true)

    @Test
    fun `the backup is the teammate with the most touches, scaled up by the starter's share`() {
        val cuffs = handcuffs(team, rows, mapOf("a2" to "Rivals"))
        // b1 projects under 8 points: not a starter worth insuring.
        assertEquals(listOf("a1"), cuffs.map { it.starter.playerId })
        val h = cuffs.single()
        assertEquals("a2", h.backup.playerId)
        // KC's RBs touch it 30 times; without a1, 10: a2's 5.0 grows threefold.
        assertEquals(15.0, h.ifOut, 1e-9)
        assertEquals("Rivals", h.owner)
    }

    @Test
    fun `a backup already on the roster is yours, and one on no team is a free agent`() {
        assertEquals("you", handcuffs(team.copy(players = team.players + LeaguePlayer("3", "RB a2", "BE", "a2")), rows, emptyMap()).single().owner)
        assertEquals(null, handcuffs(team, rows, emptyMap()).single().owner)
    }
}
