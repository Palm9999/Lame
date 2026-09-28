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

        assertEquals(TeamDefenseRow("BUF", 2025, 1, 10.0, -3.0, 1.0, 0.0, 1.0, 0.0, 0.0, 0.0), out["BUF"])
        assertEquals(TeamDefenseRow("KC", 2025, 1, 7.0, 20.0, 0.0, 1.0, 0.0, 1.0, 0.0, 0.0), out["KC"])
        assertEquals(listOf("BUF", "KC"), agg.rows().map { it.team })
    }

    @Test
    fun `a safety goes to the team that scored it, and a kickoff return TD to the receiving team`() {
        val agg = TeamDefenseAggregator()
        listOf(
            // With no scores on the play, the defense on it scored the safety.
            play(posteam = "BUF", defteam = "KC", playType = "run", safety = 1.0, homeTeam = "KC", awayTeam = "BUF", totalHomeScore = 2.0),
            // A punt returner tackled in his own end zone: the punting team (posteam) scores it.
            play(
                posteam = "KC", defteam = "BUF", playType = "punt", safety = 1.0, homeTeam = "KC", awayTeam = "BUF",
                totalHomeScore = 4.0, posteamScore = 2.0, posteamScorePost = 4.0,
            ),
            // An offense tackled in its own end zone, with scores: the defense scores it.
            play(
                posteam = "BUF", defteam = "KC", playType = "pass", safety = 1.0, homeTeam = "KC", awayTeam = "BUF",
                totalHomeScore = 6.0, posteamScore = 0.0, posteamScorePost = 0.0,
            ),
            // nflverse lists the receiving team as posteam on a kickoff.
            play(
                posteam = "BUF", defteam = "KC", playType = "kickoff", touchdown = 1.0, tdTeam = "BUF",
                homeTeam = "KC", awayTeam = "BUF", totalHomeScore = 2.0, totalAwayScore = 6.0,
            ),
            // A punt return TD scores for defteam, so it's already a defensive TD.
            play(
                posteam = "KC", defteam = "BUF", playType = "punt", touchdown = 1.0, tdTeam = "BUF",
                homeTeam = "KC", awayTeam = "BUF", totalHomeScore = 2.0, totalAwayScore = 12.0,
            ),
        ).forEach(agg::add)
        val out = agg.rows().associateBy { it.team }

        assertEquals(3.0, out.getValue("KC").safeties)
        assertEquals(0.0, out.getValue("BUF").safeties)
        assertEquals(0.0, out.getValue("KC").kickReturnTds)
        assertEquals(1.0, out.getValue("BUF").kickReturnTds)
        assertEquals(1.0, out.getValue("BUF").defensiveTds)
    }
}
