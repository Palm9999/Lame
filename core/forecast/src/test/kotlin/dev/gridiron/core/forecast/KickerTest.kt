package dev.gridiron.core.forecast

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class KickerTest {
    private fun game(week: Int, vararg values: Pair<String, Double>) =
        PlayerGame("K1", 2025, week, "AAA", mapOf("g" to 1.0) + values)

    @Test
    fun `tries rise with implied points along a least-squares line`() {
        val rows = (0 until 80).map { i ->
            val implied = 14.0 + i % 20
            TeamKicks(implied, fieldGoals = 0.5 + 0.06 * implied, extraPoints = 0.1 * implied)
        }
        val fit = AttemptFit.fit(rows)!!
        assertEquals(0.5 + 0.06 * 24, fit.fieldGoals(24.0), 1e-9)
        assertEquals(2.4, fit.extraPoints(24.0), 1e-9)
    }

    @Test
    fun `with few lined games the fit is the flat average of every game, and never negative`() {
        val fit = AttemptFit.fit(listOf(TeamKicks(20.0, 1.0, 2.0), TeamKicks(30.0, 3.0, 4.0), TeamKicks(null, 2.0, 3.0)))!!
        assertEquals(2.0, fit.fieldGoals(50.0), 1e-9)
        assertEquals(3.0, fit.extraPoints(0.0), 1e-9)
        assertNull(AttemptFit.fit(emptyList()))
        val steep = AttemptFit(fgIntercept = -2.0, fgSlope = 0.1, xpIntercept = -1.0, xpSlope = 0.1)
        assertEquals(0.0, steep.fieldGoals(10.0), 1e-9)
    }

    @Test
    fun `the league's distance mix and make rates pool every kick`() {
        val league = kickLeague(
            listOf(
                game(1, "fg_att_0_39" to 2.0, "fg_made_0_39" to 2.0, "fg_att_50" to 2.0, "fg_made_50" to 1.0, "xp_att" to 3.0, "xp_made" to 3.0),
                game(2, "fg_att_40_49" to 4.0, "fg_made_40_49" to 3.0, "xp_att" to 1.0),
            ),
        )!!
        assertEquals(listOf(0.25, 0.5, 0.25), league.mix)
        assertEquals(listOf(1.0, 0.75, 0.5), league.make)
        assertEquals(0.75, league.xpMake, 1e-12)
        assertNull(kickLeague(listOf(game(1, "xp_att" to 2.0))))
    }

    @Test
    fun `a kicker's mix and accuracy move off the league's only with many kicks`() {
        val league = KickLeague(listOf(0.5, 0.3, 0.2), listOf(0.9, 0.8, 0.6), 0.95)
        val rates = kickerRates(listOf(game(1, "fg_att_50" to 10.0, "fg_made_50" to 10.0, "xp_att" to 30.0, "xp_made" to 30.0)), league)
        // 10 kicks from 50+, all made, shrunk with k = 30 attempts toward the league's 60%.
        assertEquals((10 * 1.0 + K.KICK_MAKE_K * 0.6) / (10 + K.KICK_MAKE_K), rates.make[2], 1e-12)
        assertEquals(0.9, rates.make[0], 1e-12) // no kicks from there: the league's
        assertEquals((30 * 1.0 + K.XP_MAKE_K * 0.95) / (30 + K.XP_MAKE_K), rates.xpMake, 1e-12)
        assertEquals((10 * 1.0 + K.KICK_MIX_K * 0.2) / (10 + K.KICK_MIX_K), rates.mix[2], 1e-12)
        assertEquals(1.0, rates.mix.sum(), 1e-12)
    }

    @Test
    fun `a kicker's projected kicks split his team's tries by distance and accuracy`() {
        val stats = kickStats(KickerRates(listOf(0.5, 0.3, 0.2), listOf(0.9, 0.8, 0.5), 0.95), fieldGoals = 2.0, extraPoints = 3.0)
        assertEquals(0.9, stats.getValue("fg_made_0_39"), 1e-12)
        assertEquals(0.48, stats.getValue("fg_made_40_49"), 1e-12)
        assertEquals(0.2, stats.getValue("fg_made_50"), 1e-12)
        assertEquals(2.0 - 0.9 - 0.48 - 0.2, stats.getValue("fg_missed"), 1e-12)
        assertEquals(2.85, stats.getValue("xp_made"), 1e-12)
        assertEquals(0.15, stats.getValue("xp_missed"), 1e-12)
    }

    @Test
    fun `without a line a team's expected points are its recent scoring, shrunk toward the league's`() {
        assertEquals(22.0, teamPoints(emptyList(), 22.0), 1e-12)
        val scored = listOf(30.0, 30.0, 30.0, 30.0)
        val recent = ewma(scored, K.TEAM_HALF_LIFE)!!
        assertEquals((4 * recent + K.TEAM_POINTS_K_GAMES * 22.0) / (4 + K.TEAM_POINTS_K_GAMES), teamPoints(scored, 22.0), 1e-12)
    }
}
