package dev.gridiron.core.data.live

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PlayoffOddsCheckTest {
    /** Four teams, 14 weeks of round robin: team 1 scores most, 4 least, so 1 and 2 make a two-team cut. */
    private fun season(seeds: Map<Int, Int?>, playoffTeams: Int? = 2): HistorySeason {
        val strength = mapOf(1 to 130.0, 2 to 115.0, 3 to 100.0, 4 to 85.0)
        val pairs = listOf(listOf(1 to 2, 3 to 4), listOf(1 to 3, 2 to 4), listOf(1 to 4, 2 to 3))
        val games = (1..14).flatMap { w ->
            pairs[w % 3].map { (h, a) ->
                val hp = strength.getValue(h) + (w % 4) * 3
                val ap = strength.getValue(a) + (w % 5) * 3
                HistoryGame(w, h, a, hp, ap, if (hp > ap) "HOME" else "AWAY", playoff = false)
            }
        }
        val teams = (1..4).map { HistoryTeam(it, "T$it", null, 0, 0, 0, 0.0, 0.0, null, seeds[it]) }
        return HistorySeason(2024, teams, emptyMap(), games, playoffTeams)
    }

    @Test
    fun `odds from scores so far beat chance on a season the stronger teams won`() {
        val checks = playoffOddsCheck(listOf(season(mapOf(1 to 1, 2 to 2, 3 to 3, 4 to 4))), weeks = listOf(6), sims = 500)
        val c = checks.single()
        assertEquals(4, c.cases)
        assertTrue(c.brier < c.chanceBrier, c.toString())
    }

    @Test
    fun `a season without seeds or a playoff count is left out`() {
        assertTrue(playoffOddsCheck(listOf(season(mapOf(1 to null, 2 to null, 3 to null, 4 to null)))).isEmpty())
        assertTrue(playoffOddsCheck(listOf(season(mapOf(1 to 1, 2 to 2, 3 to 3, 4 to 4), playoffTeams = null))).isEmpty())
    }
}
