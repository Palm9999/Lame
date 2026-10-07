package dev.gridiron.feature.projections

import dev.gridiron.core.data.live.LeaguePlayer
import dev.gridiron.core.data.live.MyTeam
import org.junit.Assert.assertEquals
import org.junit.Test

class LineupHandcuffTest {
    private fun row(id: String, position: String, team: String, points: Double, usage: Usage) =
        ProjectionRow(id, "$position $id", position, team, points, 0.0, 0.0, usage = usage)

    private fun rb(id: String, team: String, points: Double, carries: Double, targets: Double, rushing: Double = 0.0, receiving: Double = 0.0) =
        row(id, "RB", team, points, Usage(carries, targets, rushing, receiving))

    private val rows = listOf(
        rb("a1", "KC", 16.0, 16.0, 4.0), rb("a2", "KC", 5.0, 6.0, 2.0, rushing = 3.5, receiving = 1.5), rb("a3", "KC", 2.0, 2.0, 0.0),
        row("q", "QB", "KC", 20.0, Usage(4.0, 0.0, 2.0, 0.0)), row("w", "WR", "KC", 14.0, Usage(0.0, 30.0, 0.0, 14.0)),
        rb("b1", "BUF", 7.0, 8.0, 2.0), rb("b2", "BUF", 6.0, 7.0, 2.0),
    )

    private val team = MyTeam("Mine", 2026, listOf(LeaguePlayer("1", "RB a1", "RB", "a1"), LeaguePlayer("2", "RB b1", "RB", "b1")), emptyMap(), slotsAreDefault = true)

    @Test
    fun `the backup is the RB teammate with the most touches, and the starter's carries and targets spread over the whole team`() {
        val cuffs = handcuffs(team, rows, mapOf("a2" to "Rivals"))
        // b1 projects under 8 points: not a starter worth insuring.
        assertEquals(listOf("a1"), cuffs.map { it.starter.playerId })
        val h = cuffs.single()
        assertEquals("a2", h.backup.playerId)
        // KC's carries: 28 (the QB's 4 included), 12 without a1; targets: 36 (the WR's 30 included), 32 without.
        // a2's 3.5 rushing points grow by 28/12, his 1.5 receiving points by 36/32.
        assertEquals(5.0 + 3.5 * (28.0 / 12 - 1) + 1.5 * (36.0 / 32 - 1), h.ifOut, 1e-9)
        assertEquals("Rivals", h.owner)
    }

    @Test
    fun `a WR's backup is the WR teammate with the most touches, after the RBs, and a TE has none`() {
        val more = rows + listOf(
            row("w2", "WR", "KC", 6.0, Usage(0.0, 6.0, 0.0, 6.0)), row("w3", "WR", "KC", 3.0, Usage(0.0, 3.0, 0.0, 3.0)),
            row("t1", "TE", "KC", 10.0, Usage(0.0, 7.0, 0.0, 10.0)), row("t2", "TE", "KC", 3.0, Usage(0.0, 3.0, 0.0, 3.0)),
        )
        val withWr = team.copy(players = team.players + LeaguePlayer("4", "WR w", "WR", "w") + LeaguePlayer("5", "TE t1", "TE", "t1"))
        val cuffs = handcuffs(withWr, more, emptyMap())
        assertEquals(listOf("a1", "w"), cuffs.map { it.starter.playerId })
        val h = cuffs.last()
        assertEquals("w2", h.backup.playerId)
        // KC's targets: 4 + 2 + 30 + 6 + 3 + 7 + 3 = 55, 25 without w; w2's 6 receiving points grow by 55/25. No carries move.
        assertEquals(6.0 + 6.0 * (55.0 / 25 - 1), h.ifOut, 1e-9)
    }

    @Test
    fun `a team with no other RB has no handcuff`() {
        assertEquals(emptyList<HandcuffLine>(), handcuffs(team, rows.filter { it.playerId !in setOf("a2", "a3") }, emptyMap()))
    }

    @Test
    fun `a backup already on the roster is yours, and one on no team is a free agent`() {
        assertEquals("you", handcuffs(team.copy(players = team.players + LeaguePlayer("3", "RB a2", "BE", "a2")), rows, emptyMap()).single().owner)
        assertEquals(null, handcuffs(team, rows, emptyMap()).single().owner)
    }
}
