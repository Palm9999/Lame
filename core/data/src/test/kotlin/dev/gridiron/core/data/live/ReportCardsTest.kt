package dev.gridiron.core.data.live

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ReportCardsTest {
    private fun p(id: String, slot: String, points: Double, position: String = "RB") =
        MatchupPlayer(id, "P$id", slot, points, position = position)

    private fun side(team: Int, vararg players: MatchupPlayer) =
        MatchupSide(team, players.filter { it.slot != "BE" && it.slot != "IR" }.sumOf { it.espnPoints ?: 0.0 }, players.toList())

    private val slots = mapOf("RB" to 1)
    private val names = mapOf(1 to "Ones", 2 to "Twos", 3 to "Threes", 4 to "Fours")

    // Week 1: Ones start 10 (bench 30: best 30), Twos start 20; Threes 25, Fours 5.
    // Week 2: Ones 40, Twos 15 (bench 16: best 16); Threes 12, Fours 30.
    private val weeks = mapOf(
        1 to listOf(
            LeagueMatchup(1, side(1, p("a", "RB", 10.0), p("b", "BE", 30.0)), side(2, p("c", "RB", 20.0))),
            LeagueMatchup(1, side(3, p("d", "RB", 25.0)), side(4, p("e", "RB", 5.0))),
        ),
        2 to listOf(
            LeagueMatchup(2, side(1, p("b", "RB", 40.0)), side(2, p("c", "RB", 15.0), p("f", "BE", 16.0))),
            LeagueMatchup(2, side(3, p("d", "RB", 12.0)), side(4, p("e", "RB", 30.0))),
        ),
    )

    // Ones drafted a and c (c then traded to Twos); Twos drafted f; Threes d; Fours e. b was a pickup.
    private val picks = listOf(
        DraftPick("a", null, 1, 1, keeper = false),
        DraftPick("c", null, 2, 1, keeper = false),
        DraftPick("f", null, 1, 2, keeper = false),
        DraftPick("d", null, 1, 3, keeper = false),
        DraftPick("e", null, 1, 4, keeper = false),
    )

    private fun cards(picks: List<DraftPick>? = this.picks) = ReportCards.of(weeks, picks, slots, { it.position }, names)

    @Test
    fun `lineups grade each team's share of its best lineup`() {
        val byTeam = cards().associateBy { it.teamId }
        assertEquals(50.0 / 70.0, byTeam.getValue(1).lineupShare!!, 1e-9)
        assertEquals(35.0 / 36.0, byTeam.getValue(2).lineupShare!!, 1e-9)
        assertEquals(1.0, byTeam.getValue(3).lineupShare!!, 1e-9)
        assertEquals(listOf(1, 1, 3, 4), listOf(3, 4, 2, 1).map { byTeam.getValue(it).places.getValue(Grade.LINEUPS) })
    }

    @Test
    fun `draft credits the drafting team wherever its picks start, moves the rest`() {
        val byTeam = cards().associateBy { it.teamId }
        // Ones: a 10 for them + c 20 and 15 for Twos = 45 drafted; b 40 started for Ones is a move.
        assertEquals(45.0, byTeam.getValue(1).draftPoints!!, 1e-9)
        assertEquals(40.0, byTeam.getValue(1).movesPoints!!, 1e-9)
        // Twos: f never started; c (Ones' pick) is their move.
        assertEquals(0.0, byTeam.getValue(2).draftPoints!!, 1e-9)
        assertEquals(35.0, byTeam.getValue(2).movesPoints!!, 1e-9)
        assertEquals(1, byTeam.getValue(1).places.getValue(Grade.DRAFT))
        assertEquals(1, byTeam.getValue(1).places.getValue(Grade.MOVES))
    }

    @Test
    fun `strength and luck come from all-play`() {
        val byTeam = cards().associateBy { it.teamId }
        // Ones: week 1 10 beats Fours only (1/3), week 2 40 beats all (1): 4/3 of 2 games.
        assertEquals((1.0 / 3 + 1.0) / 2, byTeam.getValue(1).allPlay, 1e-9)
        // Ones lost week 1, won week 2: 1 win against 4/3 all-play.
        assertEquals(1.0 - 4.0 / 3, byTeam.getValue(1).luck, 1e-9)
    }

    @Test
    fun `overall ranks the average place, best first`() {
        val list = cards()
        assertEquals((1..4).toList(), list.map { it.overall }.sorted())
        assertEquals(list.sortedBy { it.overall }, list)
        val ones = list.single { it.teamId == 1 }
        assertEquals(Grade.entries.map { ones.places.getValue(it) }.average(), ones.averagePlace, 1e-9)
    }

    @Test
    fun `without a draft, draft and moves have no grade`() {
        val ones = cards(picks = null).single { it.teamId == 1 }
        assertNull(ones.draftPoints)
        assertNull(ones.movesPoints)
        assertEquals(setOf(Grade.LINEUPS, Grade.STRENGTH, Grade.LUCK), ones.places.keys)
    }

    @Test
    fun `no weeks, no cards`() {
        assertEquals(emptyList<ReportCard>(), ReportCards.of(emptyMap(), picks, slots, { it.position }, names))
    }
}
