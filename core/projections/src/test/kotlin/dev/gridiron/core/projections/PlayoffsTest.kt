package dev.gridiron.core.projections

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PlayoffsTest {
    private fun t(id: Int, wins: Double, losses: Double, pf: Double = 0.0) = SimTeam(id, "T$id", wins, losses, pf)

    @Test
    fun `with no games left the standings decide, ties going to points for`() {
        val teams = listOf(t(1, 5.0, 3.0, 900.0), t(2, 6.0, 2.0, 800.0), t(3, 5.0, 3.0, 950.0), t(4, 2.0, 6.0))
        val odds = Playoffs.simulate(teams, emptyList(), emptyMap(), playoffTeams = 2).associateBy { it.teamId }
        assertEquals(1.0, odds.getValue(2).playoffChance)
        assertEquals(1.0, odds.getValue(2).topSeedChance)
        assertEquals(1.0, odds.getValue(3).playoffChance)
        assertEquals(0.0, odds.getValue(1).playoffChance)
        assertEquals(5.0, odds.getValue(1).expectedWins)
    }

    @Test
    fun `a much stronger team wins its game nearly always, an even one about half`() {
        val teams = listOf(t(1, 0.0, 0.0), t(2, 0.0, 0.0), t(3, 0.0, 0.0), t(4, 0.0, 0.0))
        val games = listOf(SimGame(5, 1, 2), SimGame(5, 3, 4))
        val weekly = mapOf(
            1 to mapOf(5 to TeamWeek(150.0, 10.0)),
            2 to mapOf(5 to TeamWeek(90.0, 10.0)),
            3 to mapOf(5 to TeamWeek(110.0, 20.0)),
            4 to mapOf(5 to TeamWeek(110.0, 20.0)),
        )
        val odds = Playoffs.simulate(teams, games, weekly, playoffTeams = 2).associateBy { it.teamId }
        assertTrue(odds.getValue(1).expectedWins > 0.99)
        assertEquals(0.5, odds.getValue(3).expectedWins, 0.03)
        assertEquals(1.0, odds.getValue(3).expectedWins + odds.getValue(4).expectedWins, 1e-9)
        // Same seed, same answer.
        assertEquals(odds, Playoffs.simulate(teams, games, weekly, playoffTeams = 2).associateBy { it.teamId })
    }

    @Test
    fun `a team week is its best lineup that week, byes scoring nothing, with the starters' spreads combined`() {
        val roster = listOf(
            LineupCandidate("q1", "QB", 30.0, mapOf(5 to 20.0)),
            LineupCandidate("q2", "QB", 25.0, mapOf(5 to 10.0, 6 to 15.0)),
        )
        val weeks = Playoffs.teamWeeks(mapOf("QB" to 1), roster, listOf(5, 6, 7))
        assertEquals(TeamWeek(20.0, 0.47 * 20.0), weeks[5])
        assertEquals(TeamWeek(15.0, 0.47 * 15.0), weeks[6])
        assertEquals(0.0, weeks.getValue(7).mean)
    }
}
