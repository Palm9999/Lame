package dev.gridiron.feature.projections

import dev.gridiron.core.data.live.LeaguePlayer
import dev.gridiron.core.data.live.MyTeam
import dev.gridiron.core.projections.Lineups
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LineupViewTest {
    private fun row(id: String, position: String, points: Double) = ProjectionRow(id, "Player $id", position, "KC", points, points - 5, points + 5)

    private fun team(slots: Map<String, Int>, vararg players: LeaguePlayer, default: Boolean = false) =
        MyTeam("Mine", 2026, players.toList(), slots, default)

    private fun on(id: String?, name: String = "Player $id") = LeaguePlayer("e-$name", name, "BE", id)

    private val rows = listOf(row("q", "QB", 20.0), row("r1", "RB", 14.0), row("r2", "RB", 12.0), row("w", "WR", 16.0), row("t", "TE", 9.0), row("x", "RB", 30.0))

    @Test
    fun `starters fill the league's slots with the best projected players, the rest on the bench`() {
        val view = lineupView(
            team(mapOf("QB" to 1, "RB" to 1, "WR" to 1, "FLEX" to 1), on("q"), on("r1"), on("r2"), on("w"), on("t")),
            week = 4, weekRows = rows, badges = emptyMap(),
        )
        assertEquals(listOf("QB", "RB", "WR", "FLEX"), view.starters.map { it.slot })
        assertEquals(listOf("q", "r1", "w", "r2"), view.starters.map { it.row?.playerId })
        assertEquals(62.0, view.total, 1e-9)
        assertEquals(listOf("t"), view.bench.map { it.playerId })
        assertFalse(view.defaultSlots)
    }

    @Test
    fun `an Out player scores zero and gives his slot to a healthy one`() {
        val view = lineupView(team(mapOf("RB" to 1), on("r1"), on("r2")), 4, rows, badges = mapOf("r1" to "O"))
        assertEquals("r2", view.starters.single().row?.playerId)
        assertEquals(12.0, view.total, 1e-9)
        assertTrue(view.bench.single().out)
    }

    @Test
    fun `an unmatched or unprojected player is listed apart, and an empty slot stays empty`() {
        val view = lineupView(
            team(mapOf("QB" to 1, "K" to 1), on("q"), on(null, "Nobody Known"), on("bye", "On A Bye")),
            4, rows, emptyMap(),
        )
        assertEquals(listOf(Unlisted("Nobody Known", matched = false), Unlisted("On A Bye", matched = true)), view.unlisted)
        assertNull(view.starters.single { it.slot == "K" }.row)
        assertEquals(20.0, view.total, 1e-9)
    }

    @Test
    fun `the line says who leads and by how much`() {
        assertEquals("You lead by 6.8", matchupLine(98.0, 91.2))
        assertEquals("You trail by 2.1", matchupLine(88.0, 90.1))
        assertEquals("Even", matchupLine(90.0, 90.02))
    }

    @Test
    fun `the default slots are flagged`() {
        val view = lineupView(team(Lineups.DEFAULT_SLOTS, on("q"), default = true), 4, rows, emptyMap())
        assertTrue(view.defaultSlots)
    }
}
