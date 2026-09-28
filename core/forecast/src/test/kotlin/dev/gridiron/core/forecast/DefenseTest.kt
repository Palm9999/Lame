package dev.gridiron.core.forecast

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class DefenseTest {
    @Test
    fun `the points spread is measured once there are enough games`() {
        assertEquals(K.PA_SD_DEFAULT, paSpread(List(K.PA_SD_MIN_GAMES - 1) { 3.0 }), 0.0)
        assertEquals(8.0, paSpread(List(K.PA_SD_MIN_GAMES) { if (it % 2 == 0) 8.0 else -8.0 }), 1e-12)
    }

    @Test
    fun `the league's per-game averages pool every D-ST game`() {
        val games = listOf(
            PlayerGame("DST_A", 2025, 1, "AAA", mapOf("g" to 1.0, "dst_sacks" to 4.0, "points_allowed" to 10.0)),
            PlayerGame("DST_B", 2025, 1, "BBB", mapOf("g" to 1.0, "dst_sacks" to 2.0, "dst_tds" to 1.0, "points_allowed" to 30.0)),
        )
        val league = defenseLeague(games)!!
        assertEquals(3.0, league.perGame.getValue("dst_sacks"), 1e-12)
        assertEquals(0.5, league.perGame.getValue("dst_tds"), 1e-12)
        assertEquals(0.0, league.perGame.getValue("dst_safeties"), 1e-12)
        assertEquals(20.0, league.pointsAllowed, 1e-12)
        assertNull(defenseLeague(emptyList()))
    }

    @Test
    fun `a unit's rate leans on its own games as they add up`() {
        assertEquals(2.5, unitRate(emptyList(), 2.5, 6.0), 1e-12)
        val own = ewma(listOf(4.0, 4.0, 4.0), K.DST_HALF_LIFE)!!
        assertEquals((3 * own + 6 * 2.5) / 9, unitRate(listOf(4.0, 4.0, 4.0), 2.5, 6.0), 1e-12)
    }

    @Test
    fun `an offense that gives up a lot raises the defense's rate, within the cap`() {
        assertEquals(1.0, opponentFactor(emptyList(), 2.5), 1e-12)
        assertEquals(1.0 + K.DST_CAP, opponentFactor(List(40) { 10.0 }, 2.5), 1e-12)
        assertEquals(1.0, opponentFactor(listOf(9.0), 0.0), 1e-12)
    }

    @Test
    fun `the matchup scales stats and points allowed, and a line replaces points allowed`() {
        val league = DefenseLeague(DST_STATS.associateWith { 1.0 }, 22.0)
        val own = DST_STATS.associateWith { 2.0 }

        val stages = defenseStages(own, 20.0, mapOf("dst_sacks" to 1.2), opponentScores = 33.0, league = league, implied = 17.0)

        assertEquals(2.0, stages.baseline.getValue("dst_sacks"), 1e-12)
        assertEquals(2.4, stages.afterMatchup.getValue("dst_sacks"), 1e-12)
        assertEquals(2.0, stages.afterMatchup.getValue("dst_interceptions"), 1e-12)
        assertEquals(2.4, stages.final.getValue("dst_sacks"), 1e-12)
        assertEquals(20.0, stages.baseline.getValue("points_allowed"), 1e-12)
        // 20 allowed against an offense scoring 33 where the league scores 22: 30.
        assertEquals(30.0, stages.afterMatchup.getValue("points_allowed"), 1e-12)
        assertEquals(17.0, stages.final.getValue("points_allowed"), 1e-12)
        assertEquals(DST_STATS.toSet() + "points_allowed", stages.final.keys)

        val noLine = defenseStages(own, 20.0, emptyMap(), 33.0, league, implied = null)
        assertEquals(noLine.afterMatchup, noLine.final)
    }
}
