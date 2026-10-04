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

    private fun targetShare(ctx: PlayerContext): Double =
        model().share(ctx, rates.targetShare * 0.5, { it["targets"] }, { it.targets })

    @Test
    fun `a newcomer's two games at 30 percent are shrunk toward half the position's 10`() {
        val history = listOf(game(2025, 1, "targets" to 9.0), game(2025, 2, "targets" to 9.0))
        val ctx = PlayerContext("WR", 2025, 3, history, regimeBreak = false)

        assertEquals(0.85 / 7, targetShare(ctx), 1e-12) // (2 x 0.3 + 5 x 0.05) / (2 + 5)
        assertEquals(30 * 0.85 / 7, model().project(ctx, rates, volume).getValue("targets"), 1e-12)
    }

    @Test
    fun `this season is shrunk toward the player's own last season, unless the regime broke`() {
        val history = listOf(
            game(2024, 16, "targets" to 7.5),
            game(2024, 17, "targets" to 7.5),
            game(2025, 1, "targets" to 9.0),
        )

        assertEquals((0.3 + 5 * 0.25) / 6, targetShare(PlayerContext("WR", 2025, 2, history, regimeBreak = false)), 1e-12)
        assertEquals((0.3 + 5 * 0.05) / 6, targetShare(PlayerContext("WR", 2025, 2, history, regimeBreak = true)), 1e-12)
        // No fade by week number: last season keeps its pseudo-games until this season's games outweigh them.
        assertEquals((0.3 + 5 * 0.25) / 6, targetShare(PlayerContext("WR", 2025, 12, history, regimeBreak = false)), 1e-12)
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
    fun `a player with no history gets a newcomer's share and the position's rates`() {
        val out = model().project(PlayerContext("WR", 2025, 3, emptyList(), regimeBreak = false), rates, volume)

        assertEquals(1.5, out.getValue("targets"), 1e-12) // 30 x (0.1 x 0.5)
        assertEquals(0.9, out.getValue("receptions"), 1e-12)
        assertEquals(12.0, out.getValue("receiving_yards"), 1e-12)
        assertEquals(0.125, out.getValue("carries"), 1e-12) // 25 x (0.01 x 0.5)
    }

    @Test
    fun `only the starting QB passes, shrunk toward a starter's share rather than his backup history`() {
        // One relief appearance last season: 3.2 of the team's 32 attempts, a 0.1 share.
        val ctx = PlayerContext("QB", 2025, 1, listOf(game(2024, 5, "attempts" to 3.2)), regimeBreak = false)
        val m = model()

        assertEquals(0.97, m.shares(ctx, rates, starter = true).pass, 1e-12)
        assertEquals(0.0, m.shares(ctx, rates, starter = false).pass)
        assertEquals(0.0, m.project(ctx, rates, volume, m.shares(ctx, rates, starter = false)).getValue("attempts"))
    }

    @Test
    fun `a team's target and carry shares are scaled to sum to one`() {
        val n = normalizeShares(
            mapOf("a" to Shares(0.0, 0.6, 0.1), "b" to Shares(0.0, 0.6, 0.3), "q" to Shares(0.97, 0.0, 0.0)),
        )
        assertEquals(0.5, n.getValue("a").target, 1e-12)
        assertEquals(0.25, n.getValue("a").carry, 1e-12)
        assertEquals(0.75, n.getValue("b").carry, 1e-12)
        assertEquals(0.97, n.getValue("q").pass, 1e-12)
        assertEquals(Shares(0.0, 0.0, 0.0), normalizeShares(mapOf("x" to Shares(0.0, 0.0, 0.0))).getValue("x"))
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

    @Test
    fun `a pass catcher's projection moves part of the way to his games this season`() {
        val history = listOf(
            game(2024, 17, "targets" to 20.0),
            game(2025, 1, "targets" to 4.0, "receiving_tds" to 1.0),
            game(2025, 2, "targets" to 8.0),
        )
        val out = withSeasonForm(mapOf("targets" to 10.0, "receiving_tds" to 0.2), PlayerContext("WR", 2025, 3, history, regimeBreak = false))

        val w = K.SEASON_FORM_WEIGHT
        assertEquals((1 - w) * 10.0 + w * 6.0, out.getValue("targets"), 1e-12) // last season's game doesn't count
        assertEquals((1 - w) * 0.2 + w * 0.5, out.getValue("receiving_tds"), 1e-12)
    }

    @Test
    fun `a quarterback, or anyone without a game this season, keeps the model's projection`() {
        val projected = mapOf("attempts" to 30.0)
        val qb = listOf(game(2025, 1, "attempts" to 40.0))
        assertEquals(projected, withSeasonForm(projected, PlayerContext("QB", 2025, 2, qb, regimeBreak = false)))
        val lastSeasonOnly = listOf(game(2024, 17, "targets" to 9.0))
        assertEquals(mapOf("targets" to 3.0), withSeasonForm(mapOf("targets" to 3.0), PlayerContext("TE", 2025, 1, lastSeasonOnly, regimeBreak = false)))
    }
}
