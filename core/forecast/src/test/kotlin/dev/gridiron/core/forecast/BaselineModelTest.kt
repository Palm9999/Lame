package dev.gridiron.core.forecast

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.pow

class BaselineModelTest {
    private val teamGames = HashMap<Triple<String, Int, Int>, TeamGame>()

    private fun team(season: Int, week: Int, targets: Double = 30.0, carries: Double = 25.0, attempts: Double = 32.0): TeamGame =
        TeamGame("AAA", season, week).apply {
            this.targets = targets
            this.carries = carries
            passAttempts = attempts
        }.also { teamGames[Triple("AAA", season, week)] = it }

    private fun game(season: Int, week: Int, vararg values: Pair<String, Double>): PlayerGame {
        team(season, week)
        return PlayerGame("P1", season, week, "AAA", mapOf("g" to 1.0) + values)
    }

    // Target share 0.1, catch rate 0.6, 8 yards per target, carry share 0.01, 0.05 expected TDs per target.
    private val rates = Rates(
        mapOf(
            "targets" to 10.0, "team_targets" to 100.0, "receptions" to 6.0, "receiving_yards" to 80.0,
            "carries" to 1.0, "team_carries" to 100.0, "x_rec_td" to 0.5, "x_rec_td_targets" to 10.0,
        ),
    )
    private val volume = TeamVolume(passAttempts = 32.0, targets = 30.0, carries = 25.0)

    private fun model(expectedThrough: Map<Int, Int> = emptyMap()) = BaselineModel(teamGames, expectedThrough)

    private fun targetShare(ctx: PlayerContext): Double = model().share(ctx, rates.targetShare, { it["targets"] }, { it.targets })

    @Test
    fun `two games at a 30 percent share are shrunk toward the position's 10`() {
        val history = listOf(game(2025, 1, "targets" to 9.0), game(2025, 2, "targets" to 9.0))
        val ctx = PlayerContext("WR", 2025, 3, history, regimeBreak = false)

        assertEquals(1.1 / 7, targetShare(ctx), 1e-12) // (2 x 0.3 + 5 x 0.1) / (2 + 5)
        assertEquals(30 * 1.1 / 7, model().project(ctx, rates, volume).getValue("targets"), 1e-12)
    }

    @Test
    fun `last season carries 0_44 of the weight in week 2 unless the regime broke`() {
        val history = listOf(
            game(2024, 16, "targets" to 7.5),
            game(2024, 17, "targets" to 7.5),
            game(2025, 1, "targets" to 9.0),
        )
        val shrunk = (0.3 + 5 * 0.1) / 6

        assertEquals(0.44 * 0.25 + 0.56 * shrunk, targetShare(PlayerContext("WR", 2025, 2, history, regimeBreak = false)), 1e-12)
        assertEquals(shrunk, targetShare(PlayerContext("WR", 2025, 2, history, regimeBreak = true)), 1e-12)
        assertEquals(shrunk, targetShare(PlayerContext("WR", 2025, 6, history, regimeBreak = false)), 1e-12)
    }

    @Test
    fun `a week ffopportunity hasn't processed doesn't count as a week without expected TDs`() {
        val history = listOf(
            game(2025, 1, "targets" to 10.0, "x_receiving_tds" to 1.0),
            game(2025, 2, "targets" to 10.0), // expected TDs for week 2 aren't published yet
        )
        val ctx = PlayerContext("WR", 2025, 3, history, regimeBreak = false)

        val out = model(expectedThrough = mapOf(2025 to 1)).project(ctx, rates, volume)

        // Only week 1 counts: 1.0 expected TD on 10 targets, shrunk with k = 200 toward 0.05.
        assertEquals((10 * 0.1 + 200 * 0.05) / 210, out.getValue("receiving_tds") / out.getValue("targets"), 1e-12)
    }

    @Test
    fun `a player with no history gets the position's rates`() {
        val out = model().project(PlayerContext("WR", 2025, 3, emptyList(), regimeBreak = false), rates, volume)

        assertEquals(3.0, out.getValue("targets"), 1e-12)
        assertEquals(1.8, out.getValue("receptions"), 1e-12)
        assertEquals(24.0, out.getValue("receiving_yards"), 1e-12)
        assertEquals(0.25, out.getValue("carries"), 1e-12)
    }

    @Test
    fun `quarterbacks get passing stats, everyone else receiving stats, and all get rushing`() {
        val qb = model().project(PlayerContext("QB", 2025, 3, emptyList(), regimeBreak = false), rates, volume)
        val wr = model().project(PlayerContext("WR", 2025, 3, emptyList(), regimeBreak = false), rates, volume)

        assertTrue("attempts" in qb && "passing_yards" in qb && "carries" in qb)
        assertFalse("targets" in qb)
        assertTrue("targets" in wr && "carries" in wr)
        assertFalse("attempts" in wr)
        assertTrue(qb.keys.containsAll(listOf("interceptions", "sacks_taken", "passing_first_downs", "passing_2pt", "fumbles_lost")))
    }

    @Test
    fun `team volume is a 4-game EWMA, or the league's average team without games`() {
        val fallback = TeamVolume(30.0, 28.0, 26.0)
        val games = listOf(team(2025, 1, targets = 30.0), team(2025, 2, targets = 40.0))

        assertEquals(30 + (1 - 0.5.pow(0.25)) * 10, teamVolume(games, fallback).targets, 1e-12)
        assertEquals(fallback, teamVolume(emptyList(), fallback))
        assertEquals(32.0 / 57, volume.passRate, 1e-12)
    }
}
