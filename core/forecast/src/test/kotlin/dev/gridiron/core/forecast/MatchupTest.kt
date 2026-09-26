package dev.gridiron.core.forecast

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MatchupTest {
    /** A double round robin of four teams (24 team-games): every offense gains 7 yards per pass, except against DDD. */
    private fun league(dddAllows: Double): List<MatchupGame> {
        val pairs = listOf("AAA" to "BBB", "CCC" to "DDD", "AAA" to "CCC", "BBB" to "DDD", "AAA" to "DDD", "BBB" to "CCC")
        return (0 until 2).flatMap { round ->
            pairs.withIndex().flatMap { (i, pair) ->
                val week = round * 3 + i / 2 + 1
                val (home, away) = if (round == 0) pair else pair.second to pair.first
                listOf(side(home, away, week, true, dddAllows), side(away, home, week, false, dddAllows))
            }
        }
    }

    private fun side(team: String, opponent: String, week: Int, home: Boolean, dddAllows: Double): MatchupGame {
        val ypa = if (opponent == "DDD") dddAllows else 7.0
        val game = TeamGame(team, 2025, week).apply {
            passAttempts = 30.0
            passYards = 30.0 * ypa
            passTds = 1.5
            carries = 25.0
            rushYards = 100.0
            rushTds = 0.5
        }
        return MatchupGame(game, opponent, home)
    }

    @Test
    fun `a defense that allows more lifts the multiplier, within the cap`() {
        val model = MatchupModel.fit(league(dddAllows = 8.4))

        val vsDdd = model.multiplier(Outcome.PASS_YARDS, "DDD", home = false)
        assertTrue(vsDdd > 1.0 && vsDdd < 1.15, "$vsDdd")
        assertTrue(model.multiplier(Outcome.PASS_YARDS, "AAA", home = false) < 1.0)
        // Every team ran for 4 yards a carry: nothing to adjust.
        assertEquals(1.0, model.multiplier(Outcome.RUSH_YARDS, "DDD", home = false), 1e-9)
        assertEquals("vs DDD: 1st-most pass yards per attempt allowed", model.note("WR", "DDD"))
    }

    @Test
    fun `a stat's multiplier is plays times its efficiency or TD rating`() {
        val model = MatchupModel.fit(league(dddAllows = 8.4))
        val plays = model.multiplier(Outcome.PLAYS, "DDD", home = false)

        assertEquals(plays * model.multiplier(Outcome.PASS_YARDS, "DDD", false), model.multiplier(Side.PASS, StatType.YARDS, "DDD", false), 1e-12)
        assertEquals(plays * model.multiplier(Outcome.RUSH_TD, "DDD", false), model.multiplier(Side.RUSH, StatType.TD, "DDD", false), 1e-12)
        assertEquals(plays, model.multiplier(Side.TOUCH, StatType.COUNT, "DDD", false), 1e-12)
    }

    @Test
    fun `an extreme defense is capped at 15 percent`() {
        val model = MatchupModel.fit(league(dddAllows = 30.0))
        assertEquals(1.15, model.multiplier(Outcome.PASS_YARDS, "DDD", home = false), 1e-12)
    }

    @Test
    fun `too few games means no adjustment`() {
        val model = MatchupModel.fit(league(dddAllows = 8.4).take(12))
        assertEquals(1.0, model.multiplier(Outcome.PASS_YARDS, "DDD", home = false))
        assertEquals("vs DDD: too early to rate defenses", model.note("WR", "DDD"))
    }

    @Test
    fun `an unrated opponent is neutral`() {
        val model = MatchupModel.fit(league(dddAllows = 8.4))
        assertEquals(1.0, model.multiplier(Outcome.PASS_YARDS, "EEE", home = true))
        assertEquals("vs EEE: not rated yet", model.note("WR", "EEE"))
    }
}
