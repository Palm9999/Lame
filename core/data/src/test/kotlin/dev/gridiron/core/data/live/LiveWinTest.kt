package dev.gridiron.core.data.live

import dev.gridiron.core.data.GameState
import dev.gridiron.core.data.ScoreGame
import dev.gridiron.core.data.ScoresWeek
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LiveWinTest {
    @Test
    fun `the clock reads quarters, breaks and finals`() {
        assertEquals(0.0, GameClock.shareLeft(GameState.FINAL, "Final/OT"))
        assertEquals(1.0, GameClock.shareLeft(GameState.SCHEDULED, "10/1 - 8:15 PM EDT"))
        assertEquals((15 + 4 + 14 / 60.0) / 60.0, GameClock.shareLeft(GameState.LIVE, "4:14 - 3rd"), 1e-9)
        assertEquals(15.0 / 60.0 * 4, GameClock.shareLeft(GameState.LIVE, "15:00 - 1st"), 1e-9)
        assertEquals(0.5, GameClock.shareLeft(GameState.LIVE, "Halftime"))
        assertEquals(0.25, GameClock.shareLeft(GameState.LIVE, "End of 3rd"))
        assertEquals(0.0, GameClock.shareLeft(GameState.LIVE, "3:12 - OT"))
        assertEquals(0.5, GameClock.shareLeft(GameState.LIVE, "Delayed"))
    }

    private fun game(home: String, away: String, state: GameState, detail: String?) = ScoreGame(home, away, null, null, state, null, detail, null, null)
    private fun p(id: String, slot: String, pts: Double?) = MatchupPlayer("e$id", "P$id", slot, pts, playerId = id)

    @Test
    fun `a starter's final is his points so far plus his projection over what is left of his game`() {
        val week = ScoresWeek(2026, 5, listOf(game("KC", "BUF", GameState.FINAL, "Final"), game("DAL", "PHI", GameState.LIVE, "Halftime")), emptyList(), null)
        val players = mapOf(
            "a" to LivePlayer("KC", 15.0, 6.0),
            "b" to LivePlayer("DAL", 12.0, 6.0),
            "c" to LivePlayer("PHI", 10.0, 6.0),
            "d" to LivePlayer("BUF", 20.0, 6.0),
        )
        val mine = MatchupSide(1, 0.0, listOf(p("a", "WR", 22.0), p("b", "RB", 5.0), p("x", "BE", 30.0)))
        val theirs = MatchupSide(2, 0.0, listOf(p("c", "WR", 4.0), p("d", "QB", 9.0)))
        val live = LiveWin.estimate(mine, theirs, week, players)
        assertEquals(27.0, live.myScore, 1e-9)
        // a is done (22); b has half his game left: 5 + 6.
        assertEquals(33.0, live.myExpected, 1e-9)
        assertEquals(13.0 + 5.0, live.theirExpected, 1e-9)
        assertTrue(live.chance > 0.9)
    }
}
