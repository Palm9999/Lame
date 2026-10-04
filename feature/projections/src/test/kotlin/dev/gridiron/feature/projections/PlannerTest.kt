package dev.gridiron.feature.projections

import dev.gridiron.core.data.live.LeaguePlayer
import dev.gridiron.core.data.live.MyTeam
import org.junit.Assert.assertEquals
import org.junit.Test

class PlannerTest {
    private fun row(id: String, pos: String, team: String) = ProjectionRow(id, "P $id", pos, team, 0.0, 0.0, 0.0)

    private val rows = listOf(
        row("t1", "TE", "KC"), row("t2", "TE", "BUF"), row("t3", "TE", "DAL"),
        row("d1", "DST", "KC"), row("d2", "DST", "NYJ"), row("d3", "DST", "SF"),
    )
    private val weekly = mapOf(
        "t1" to mapOf(8 to 9.0, 10 to 9.0), // KC on bye in week 9
        "t2" to mapOf(8 to 6.0, 9 to 6.5, 10 to 6.0),
        "t3" to mapOf(8 to 4.0, 9 to 5.0, 10 to 4.0),
        "d1" to mapOf(8 to 6.0, 9 to 0.0, 10 to 7.0),
        "d2" to mapOf(8 to 9.0, 9 to 8.0),
        "d3" to mapOf(8 to 5.0, 9 to 10.0),
    )
    private val team = MyTeam("Mine", 2026, listOf(LeaguePlayer("1", "P t1", "TE", "t1"), LeaguePlayer("2", "P d1", "D/ST", "d1")), mapOf("TE" to 1), slotsAreDefault = false)

    @Test
    fun `a bye leaves a hole, named with why and filled by the best free agent that week`() {
        val holes = holes(team, rows, weekly, mapOf("KC" to setOf(9)), rostered = setOf("t1", "d1", "t2"), weeks = listOf(8, 9, 10))
        val h = holes.single()
        assertEquals(9, h.week)
        assertEquals("TE", h.slot)
        assertEquals("P t1 on bye", h.why)
        // t2 is on a league team; t3 is free.
        assertEquals("t3", h.fill?.first?.playerId)
        assertEquals(5.0, h.fill!!.second, 1e-9)
    }

    @Test
    fun `no projection without a bye says so`() {
        val hurt = weekly + ("t1" to mapOf(8 to 9.0))
        assertEquals("P t1 not projected", holes(team, rows, hurt, emptyMap(), setOf("t1"), listOf(10)).single().why)
    }

    @Test
    fun `streamers are the best free D-ST each week beside your own`() {
        val s = streams(team, rows, weekly, rostered = setOf("t1", "d1"), weeks = listOf(8, 9))
        assertEquals(listOf("d2", "d3"), s.map { it.pick.playerId })
        assertEquals(6.0, s[0].yours!!, 1e-9)
        // Week 9: d1 projects nothing.
        assertEquals(null, s[1].yours)
    }
}
