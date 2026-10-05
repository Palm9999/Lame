package dev.gridiron.core.data.live

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class LeagueRecapTest {
    private fun side(id: Int, total: Double, vararg starters: Pair<String, Double>) =
        MatchupSide(id, total, starters.map { (n, p) -> MatchupPlayer(n, n, "WR", p) } + MatchupPlayer("b$id", "Bench $id", "BE", 99.0))

    private val names = mapOf(1 to "Ace", 2 to "Bee", 3 to "Cee", 4 to "Dee")

    @Test
    fun `the last week's best starters, top score, blowout and closest game`() {
        val weeks = mapOf(
            1 to listOf(LeagueMatchup(1, side(1, 100.0), side(2, 90.0)), LeagueMatchup(1, side(3, 80.0), side(4, 70.0))),
            2 to listOf(
                LeagueMatchup(2, side(1, 120.0, "Star" to 40.0, "Mid" to 20.0), side(3, 60.0, "Low" to 10.0)),
                LeagueMatchup(2, side(2, 95.0, "Good" to 30.0), side(4, 97.0, "Ok" to 25.0)),
            ),
        )
        val recap = LeagueRecaps.of(weeks, names)
        val w = recap.lastWeek!!
        assertEquals(2, w.week)
        // Bench players never count.
        assertEquals(listOf("Star", "Good", "Ok"), w.topScorers.map { it.first.name })
        assertEquals("Ace" to 120.0, w.highScore)
        assertEquals(RecapGame("Ace", 120.0, "Cee", 60.0), w.blowout)
        assertEquals(RecapGame("Dee", 97.0, "Bee", 95.0), w.closest)
    }

    @Test
    fun `luck is wins against the all-play record`() {
        // Week 1: Bee scores second-most but meets the top score; Cee scores third and beats the lowest.
        val weeks = mapOf(1 to listOf(LeagueMatchup(1, side(1, 100.0), side(2, 90.0)), LeagueMatchup(1, side(3, 80.0), side(4, 70.0))))
        val luck = LeagueRecaps.of(weeks, names).luck.associateBy { it.name }
        assertEquals(1.0, luck.getValue("Ace").allPlayWins, 1e-9)
        assertEquals(2.0 / 3, luck.getValue("Bee").allPlayWins, 1e-9)
        assertEquals(-2.0 / 3, luck.getValue("Bee").luck, 1e-9)
        assertEquals(1.0 - 1.0 / 3, luck.getValue("Cee").luck, 1e-9)
    }
}
