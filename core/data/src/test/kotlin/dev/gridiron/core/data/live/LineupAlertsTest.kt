package dev.gridiron.core.data.live

import dev.gridiron.core.data.GameState
import dev.gridiron.core.data.ScoreGame
import dev.gridiron.core.data.ScoresWeek
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.Instant

class LineupAlertsTest {
    private val early = Instant.parse("2026-10-11T17:00:00Z")
    private val late = Instant.parse("2026-10-11T20:25:00Z")
    private fun game(home: String, away: String, at: Instant) = ScoreGame(home, away, null, null, GameState.SCHEDULED, at, null, null, null)
    private val week = ScoresWeek(2026, 6, listOf(game("KC", "BUF", early), game("DAL", "PHI", late)), byes = listOf("SF"), liveError = null)

    private val players = listOf(
        WeekPlayer("w1", "Kay Sea", "WR", "KC", 15.0),
        WeekPlayer("w2", "Niner", "WR", "SF", 14.0),
        WeekPlayer("w3", "Cowboy", "WR", "DAL", 9.0),
        WeekPlayer("w4", "Eagle", "WR", "PHI", 11.0),
        WeekPlayer("r1", "Back Bill", "RB", "BUF", 12.0),
        WeekPlayer("t1", "Tight Kay", "TE", "KC", 7.0),
    ).associateBy { it.playerId }

    private val team = MyTeam(
        "Mine", 2026,
        listOf(
            LeaguePlayer("1", "Kay Sea", "WR", "w1"),
            LeaguePlayer("2", "Niner", "FLEX", "w2"),
            LeaguePlayer("3", "Cowboy", "BE", "w3"),
            LeaguePlayer("4", "Eagle", "BE", "w4"),
            LeaguePlayer("5", "Back Bill", "BE", "r1"),
            LeaguePlayer("6", "Tight Kay", "BE", "t1"),
        ),
        mapOf("WR" to 1, "FLEX" to 1), slotsAreDefault = false,
    )

    @Test
    fun `an Out starter in this window gets the best bench player whose game hasn't started`() {
        val alerts = LineupAlerts.check(team, players, mapOf("w1" to "O"), week, early)
        val out = alerts.single { it.starter.playerId == "w1" }
        assertEquals("Lineup: Kay Sea is Out", out.title)
        // Eagle (11.0, late) beats Cowboy (9.0); Back Bill can't play WR; Tight Kay plays now but isn't a WR.
        assertEquals("w4", out.replacement?.playerId)
        assertEquals("Start Eagle (11.0 pts) at WR instead.", out.text)
    }

    @Test
    fun `a bye starter is flagged only in the week's first window, and two alerts never share a replacement`() {
        val first = LineupAlerts.check(team, players, mapOf("w1" to "O"), week, early)
        assertEquals(listOf("w1", "w2"), first.map { it.starter.playerId })
        assertEquals("is on bye", first[1].reason)
        // The FLEX takes the next best: Back Bill (12.0, early game, not yet started at the window).
        assertEquals("r1", first[1].replacement?.playerId)
        assertEquals(emptyList<LineupAlert>(), LineupAlerts.check(team, players, emptyMap(), week, late))
    }

    @Test
    fun `a Questionable starter or one in another window is left alone, and a bench with no fit says so`() {
        assertEquals(emptyList<LineupAlert>(), LineupAlerts.check(team.copy(players = team.players.take(1)), players, mapOf("w1" to "Q"), week, early))
        assertEquals(emptyList<LineupAlert>(), LineupAlerts.check(team.copy(players = team.players.take(1)), players, mapOf("w1" to "O"), week, late))
        val alone = LineupAlerts.check(team.copy(players = team.players.take(1)), players, mapOf("w1" to "D"), week, early).single()
        assertNull(alone.replacement)
        assertEquals("No one on your bench can take his WR slot.", alone.text)
    }
}
