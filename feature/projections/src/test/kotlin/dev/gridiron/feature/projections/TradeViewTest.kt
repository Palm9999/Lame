package dev.gridiron.feature.projections

import dev.gridiron.core.data.live.LeaguePlayer
import dev.gridiron.core.data.live.MyTeam
import dev.gridiron.core.projections.LineupCandidate
import dev.gridiron.core.projections.TradeOutcome
import org.junit.Assert.assertEquals
import org.junit.Test

class TradeViewTest {
    @Test
    fun `a roster's players carry their rest of season, an unprojected one zero and an unmatched one nothing`() {
        val team = MyTeam(
            "Mine", 2026,
            listOf(LeaguePlayer("1", "Hurt Guy", "IR", "h"), LeaguePlayer("2", "Star", "RB", "s"), LeaguePlayer("3", "Unknown", "BE", null)),
            mapOf("RB" to 1), slotsAreDefault = false,
        )
        val rows = mapOf("s" to ProjectionRow("s", "Star Back", "RB", "KC", 120.0, 0.0, 0.0))
        val players = tradePlayers(team, rows)
        assertEquals(listOf(TradePlayer("s", "Star Back", "RB", "KC", 120.0), TradePlayer("h", "Hurt Guy", null, null, 0.0)), players)
        assertEquals(listOf(LineupCandidate("s", "RB", 120.0)), candidates(players))
    }

    @Test
    fun `the verdict reads the trade from your side`() {
        fun v(mine: Double, theirs: Double) = verdict(TradeOutcome(100.0, 100.0 + mine, 100.0, 100.0 + theirs))
        assertEquals("Good for both teams", v(10.0, 5.0))
        assertEquals("Helps you, costs them: they may say no", v(10.0, -5.0))
        assertEquals("Helps them more than you", v(0.0, 8.0))
        assertEquals("Hurts both lineups", v(-5.0, -5.0))
        assertEquals("Costs your lineup", v(-5.0, 10.0))
        assertEquals("About even", v(1.0, -1.0))
        assertEquals("+25.6", gainText(25.6))
        assertEquals("−7.8", gainText(-7.8))
    }
}
