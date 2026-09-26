package dev.gridiron.core.ingest.pbp

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TeamDefenseTest {
    @Test
    fun `counts what each defense allowed and took away`() {
        val agg = TeamDefenseAggregator()
        listOf(
            play(defteam = "KC", playType = "pass", yardsGained = 20.0, homeTeam = "KC", awayTeam = "BUF",
                totalHomeScore = 0.0, totalAwayScore = 7.0),
            play(defteam = "KC", playType = "pass", yardsGained = 0.0, interception = 1.0, touchdown = 1.0,
                tdTeam = "KC", homeTeam = "KC", awayTeam = "BUF", totalHomeScore = 7.0, totalAwayScore = 7.0),
            play(defteam = "BUF", playType = "run", yardsGained = -3.0, sack = 1.0, fumbleLost = 1.0,
                homeTeam = "KC", awayTeam = "BUF", totalHomeScore = 10.0, totalAwayScore = 7.0),
            play(defteam = "BUF", playType = "punt", yardsGained = 40.0, seasonType = "PRE",
                homeTeam = "KC", awayTeam = "BUF", totalHomeScore = 99.0, totalAwayScore = 99.0),
        ).forEach(agg::add)
        val out = agg.rows().associateBy { it.team }

        assertEquals(TeamDefenseRow("BUF", 2025, 1, 10.0, -3.0, 1.0, 0.0, 1.0, 0.0), out["BUF"])
        assertEquals(TeamDefenseRow("KC", 2025, 1, 7.0, 20.0, 0.0, 1.0, 0.0, 1.0), out["KC"])
        assertEquals(listOf("BUF", "KC"), agg.rows().map { it.team })
    }
}
