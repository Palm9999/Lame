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
    fun `the lineup's spread combines its starters' and an Out player adds none`() {
        // Each row's range is 10 points wide, so each starter's SD is 10 / 2.5632.
        val view = lineupView(team(mapOf("QB" to 1, "RB" to 1), on("q"), on("r1")), 4, rows, emptyMap())
        val sd = 10 / (2 * 1.2816)
        assertEquals(kotlin.math.sqrt(2.0) * sd, view.spread, 1e-9)
        assertEquals(34.0 - 1.2816 * view.spread, view.low, 1e-9)
        assertEquals(34.0 + 1.2816 * view.spread, view.high, 1e-9)
        val hurt = lineupView(team(mapOf("RB" to 1), on("r1")), 4, rows, badges = mapOf("r1" to "O"))
        assertEquals(0.0, hurt.spread, 1e-12)
        assertEquals(0.0, hurt.low, 1e-12)
    }

    @Test
    fun `the chance to win follows the margin and both spreads`() {
        val mine = lineupView(team(mapOf("QB" to 1, "RB" to 1), on("q"), on("r1")), 4, rows, emptyMap())
        assertEquals(0.5, winChance(mine, mine), 1e-9)
        val theirs = lineupView(team(mapOf("QB" to 1, "RB" to 1), on("q"), on("r2")), 4, rows, emptyMap())
        val chance = winChance(mine, theirs)
        assertEquals(dev.gridiron.core.model.normalCdf(2.0 / kotlin.math.sqrt(2 * mine.spread * mine.spread)), chance, 1e-9)
        assertEquals(1 - chance, winChance(theirs, mine), 1e-9)
        assertEquals("1% to win", winLine(0.0001))
        assertEquals("99% to win", winLine(0.9999))
        assertEquals("57% to win", winLine(0.566))
    }

    @Test
    fun `the lineup check names who to start and sit against the lineup set in ESPN`() {
        fun at(id: String?, slot: String, name: String = "Player $id") = LeaguePlayer("e-$name", name, slot, id)
        // ESPN starts r2 at RB and the TE at FLEX; the best lineup starts r1 at RB and r2 at FLEX.
        val t = team(mapOf("QB" to 1, "RB" to 1, "WR" to 1, "FLEX" to 1), at("q", "QB"), at("r1", "BE"), at("r2", "RB"), at("w", "WR"), at("t", "FLEX"))
        val best = lineupView(t, 4, rows, emptyMap())
        val check = lineupCheck(t, best, rows, emptyMap())!!
        assertEquals(57.0, check.current, 1e-9)
        assertEquals(62.0, check.best, 1e-9)
        assertEquals(5.0, check.gain, 1e-9)
        assertEquals(listOf("r1"), check.start.map { it.playerId })
        assertEquals(listOf("t"), check.sit.map { it.playerId })
    }

    @Test
    fun `the lineup check flags an Out or unprojected starter, and is quiet when nothing is set`() {
        fun at(id: String?, slot: String, name: String = "Player $id") = LeaguePlayer("e-$name", name, slot, id)
        val t = team(mapOf("RB" to 1), at("r1", "RB"), at("r2", "BE"))
        val check = lineupCheck(t, lineupView(t, 4, rows, mapOf("r1" to "O")), rows, mapOf("r1" to "O"))!!
        assertEquals(0.0, check.current, 1e-9)
        assertEquals(listOf("r2"), check.start.map { it.playerId })
        assertEquals(listOf(CheckPlayer("r1", "Player r1", "RB", 0.0)), check.sit)
        val bye = team(mapOf("RB" to 1), at("gone", "RB", "On Bye"), at("r2", "BE"))
        assertEquals(listOf(CheckPlayer("gone", "On Bye", "RB", null)), lineupCheck(bye, lineupView(bye, 4, rows, emptyMap()), rows, emptyMap())!!.sit)
        val benchOnly = team(mapOf("RB" to 1), on("r1"), on("r2"))
        assertNull(lineupCheck(benchOnly, lineupView(benchOnly, 4, rows, emptyMap()), rows, emptyMap()))
    }

    @Test
    fun `the line says who leads and by how much`() {
        assertEquals("You lead by 6.8", matchupLine(98.0, 91.2))
        assertEquals("You trail by 2.1", matchupLine(88.0, 90.1))
        assertEquals("Even", matchupLine(90.0, 90.02))
    }

    @Test
    fun `free agents exclude everyone on a league team`() {
        val mine = team(mapOf("QB" to 1, "RB" to 1), on("q"), on("r2"))
        val picks = waiverPickups(mine, rows, emptyMap(), rostered = setOf("q", "r2", "x"))
        // x (30) is on another team; w can't fill a slot; r1 beats r2 at RB by 2.
        assertEquals(listOf("r1"), picks.map { it.add.playerId })
        assertEquals(2.0, picks.single().gain, 1e-9)
        assertEquals("r2", picks.single().replaces?.playerId)
        assertEquals("r2", picks.single().drop?.playerId)
        assertEquals("RB", picks.single().slot)
    }

    @Test
    fun `a pickup whose starter is hurt carries the note`() {
        val mine = team(mapOf("QB" to 1, "RB" to 1), on("q"), on("r2"))
        val picks = waiverPickups(mine, rows, emptyMap(), rostered = setOf("q", "r2", "x"), starterOut = mapOf("r1" to "RB1 Star Back is Doubtful"))
        assertEquals("RB1 Star Back is Doubtful", picks.single().starterOut)
    }

    @Test
    fun `an Out free agent is never suggested`() {
        val mine = team(mapOf("QB" to 1, "RB" to 1), on("q"), on("r2"))
        assertEquals(emptyList<PickupLine>(), waiverPickups(mine, rows, mapOf("r1" to "O"), rostered = setOf("q", "r2", "x")))
    }

    @Test
    fun `the default slots are flagged`() {
        val view = lineupView(team(Lineups.DEFAULT_SLOTS, on("q"), default = true), 4, rows, emptyMap())
        assertTrue(view.defaultSlots)
    }
}
