package dev.gridiron.core.forecast

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class LeagueTest {
    private fun game(id: String, week: Int, vararg values: Pair<String, Double>) =
        PlayerGame(id, 2025, week, "AAA", mapOf("g" to 1.0) + values)

    private fun team(week: Int, targets: Double, carries: Double = 0.0) =
        TeamGame("AAA", 2025, week).apply {
            this.targets = targets
            this.carries = carries
        }

    @Test
    fun `rates pool every game at the position`() {
        val totals = LeagueTotals()
        totals.add("WR", game("W1", 1, "targets" to 10.0, "receptions" to 7.0, "receiving_yards" to 90.0), team(1, 40.0), expectedCovered = true)
        totals.add("WR", game("W2", 2, "targets" to 5.0, "receptions" to 2.0, "receiving_yards" to 20.0), team(2, 20.0), expectedCovered = true)

        val rates = totals.rates("WR")!!
        assertEquals(0.25, rates.targetShare, 1e-12) // 15 of 60
        assertEquals(0.6, rates.catchRate, 1e-12) // 9 of 15
        assertEquals(110.0 / 15, rates.yardsPerTarget, 1e-12)
        assertNull(totals.rates("TE"))
    }

    @Test
    fun `the expected TD rate counts covered weeks only, and falls back to actual TDs with none`() {
        val covered = LeagueTotals()
        covered.add("WR", game("W1", 1, "targets" to 10.0, "x_receiving_tds" to 0.5, "receiving_tds" to 2.0), team(1, 40.0), expectedCovered = true)
        covered.add("WR", game("W1", 2, "targets" to 10.0, "receiving_tds" to 1.0), team(2, 40.0), expectedCovered = false)
        assertEquals(0.05, covered.rates("WR")!!.xReceivingTdPerTarget, 1e-12)

        val none = LeagueTotals()
        none.add("WR", game("W1", 1, "targets" to 10.0, "receiving_tds" to 2.0), team(1, 40.0), expectedCovered = false)
        assertEquals(0.2, none.rates("WR")!!.xReceivingTdPerTarget, 1e-12)
    }

    @Test
    fun `rare events are rates per opportunity, and long TDs a share of TDs`() {
        val totals = LeagueTotals()
        totals.add(
            "RB",
            game(
                "R1", 1, "carries" to 20.0, "receptions" to 4.0, "fumbles_lost" to 1.0, "rushing_tds" to 2.0,
                "rushing_tds_40" to 1.0, "rushing_first_downs" to 5.0,
            ),
            team(1, 30.0, carries = 25.0),
            expectedCovered = true,
        )
        val rates = totals.rates("RB")!!
        assertEquals(1.0 / 24, rates.fumblesPerTouch, 1e-12)
        assertEquals(0.5, rates.longTdShare("rushing", 40), 1e-12)
        assertEquals(0.0, rates.longTdShare("rushing", 50), 1e-12)
        assertEquals(0.25, rates.rushFirstDownsPerCarry, 1e-12)
        assertEquals(0.8, rates.carryShare, 1e-12)
    }

    @Test
    fun `rates are a snapshot that later games don't move`() {
        val totals = LeagueTotals()
        totals.add("WR", game("W1", 1, "targets" to 10.0), team(1, 40.0), expectedCovered = true)
        val before = totals.rates("WR")!!
        totals.add("WR", game("W1", 2, "targets" to 30.0), team(2, 40.0), expectedCovered = true)
        assertEquals(0.25, before.targetShare, 1e-12)
    }
}
