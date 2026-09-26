package dev.gridiron.core.forecast

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import kotlin.math.pow

class GameScriptTest {
    // AAA at home, favored by 3, total 44: AAA is implied for 23.5, BBB for 20.5.
    private val game = Game(2025, 3, true, "AAA", "BBB", false, 3.0, 44.0, null, null, null, null)

    @Test
    fun `the favorite scores more and passes a little less`() {
        val script = gameScript(game, "AAA", leagueImplied = 22.0, passRate = 0.6)!!
        val ratio = 23.5 / 22

        assertEquals(0.97, script.passAdjust, 1e-12) // (0.6 - 3 x 0.006) / 0.6
        assertEquals(1.045, script.rushAdjust, 1e-12) // 0.418 / 0.4
        assertEquals(ratio * 0.97, script.multiplier(Side.PASS, StatType.TD), 1e-12)
        assertEquals(ratio.pow(0.5) * 1.045, script.multiplier(Side.RUSH, StatType.YARDS), 1e-12)
        assertEquals(ratio.pow(0.25), script.multiplier(Side.TOUCH, StatType.COUNT), 1e-12)
        assertEquals("Implied 23.5 pts (+1.5)", script.note)
    }

    @Test
    fun `the underdog passes more`() {
        val script = gameScript(game, "BBB", leagueImplied = 22.0, passRate = 0.6)!!
        assertEquals(1.03, script.passAdjust, 1e-12)
        assertEquals("Implied 20.5 pts (-1.5)", script.note)
    }

    @Test
    fun `no line posted means no game script`() {
        assertNull(gameScript(game.copy(spread = null), "AAA", 22.0, 0.6))
        assertNull(gameScript(game.copy(total = null), "AAA", 22.0, 0.6))
    }

    @Test
    fun `an extreme total is capped at one and a half times the league's`() {
        val script = gameScript(game.copy(total = 120.0, spread = 0.0), "AAA", leagueImplied = 22.0, passRate = 0.6)!!
        assertEquals(1.5, script.multiplier(Side.TOUCH, StatType.TD), 1e-12)
    }
}
