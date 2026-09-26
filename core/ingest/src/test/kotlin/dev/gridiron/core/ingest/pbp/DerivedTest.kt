package dev.gridiron.core.ingest.pbp

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DerivedTest {
    private fun run(vararg plays: Play) = weeklyPlayerStats(plays.toList())

    @Test
    fun `target share is player over team`() {
        val rows = run(target("WR1", 10.0), target("WR1", 10.0), target("WR1", 10.0), target("WR2", 10.0))
        assertEquals(0.75, rows.row("WR1")["target_share"]!!, 1e-12)
        assertEquals(0.25, rows.row("WR2")["target_share"]!!, 1e-12)
    }

    @Test
    fun `air yards share can go negative and is preserved`() {
        val rows = run(target("WR1", 20.0), target("RB1", -5.0))
        assertEquals(-5.0 / 15, rows.row("RB1")["air_yards_share"]!!, 1e-12)
        assertEquals(20.0 / 15, rows.row("WR1")["air_yards_share"]!!, 1e-12)
    }

    @Test
    fun `WOPR clamps shares so it never goes negative`() {
        val rows = run(target("WR1", 20.0), target("RB1", -5.0))
        assertEquals(1.5 * 0.5 + 0.7 * 0.0, rows.row("RB1")["wopr"]!!, 1e-12)
        assertEquals(1.5 * 0.5 + 0.7 * 1.0, rows.row("WR1")["wopr"]!!, 1e-12)
    }

    @Test
    fun `carry share`() {
        assertEquals(0.75, run(carry("RB1"), carry("RB1"), carry("RB1"), carry("RB2")).row("RB1")["carry_share"]!!, 1e-12)
    }

    @Test
    fun `aDOT, RACR and catch rate`() {
        val r = run(target("WR1", 10.0, complete = true, yds = 15.0), target("WR1", 30.0)).row("WR1")
        assertEquals(20.0, r["adot"]!!, 1e-12)
        assertEquals(15.0 / 40, r["racr"]!!, 1e-12)
        assertEquals(0.5, r["catch_rate"]!!, 1e-12)
    }

    @Test
    fun `a zero denominator yields null, not an error or a zero`() {
        val r = run(carry("RB1", 5.0)).row("RB1")
        assertNull(r["adot"])
        assertNull(r["catch_rate"])
        assertNull(r["epa_per_dropback"])
    }

    @Test
    fun `rush success rate`() {
        val r = run(carry("RB1", success = 1.0), carry("RB1"), carry("RB1", success = 1.0), carry("RB1", success = 1.0)).row("RB1")
        assertEquals(0.75, r["rush_success_rate"]!!, 1e-12)
    }

    @Test
    fun `weighted opportunities`() {
        assertEquals(2 + 2.6, run(carry("RB1"), carry("RB1"), target("RB1", 2.0)).row("RB1")["weighted_opportunities"]!!, 1e-12)
    }

    @Test
    fun `a kneel does not change success rate, EPA per carry or carry share`() {
        val rows = run(
            carry("RB1", 10.0, success = 1.0, epa = 1.0),
            carry("RB1", 5.0, success = 0.0, epa = -0.2),
            kneel("QB1", -2.0),
            carry("RB2", 3.0, success = 1.0, epa = 0.5),
        )
        val rb1 = rows.row("RB1")
        assertEquals(0.5, rb1["rush_success_rate"]!!, 1e-12)
        assertEquals((1.0 - 0.2) / 2, rb1["rush_epa_per_carry"]!!, 1e-12)
        assertEquals(2.0 / 3, rb1["carry_share"]!!, 1e-12)
    }

    @Test
    fun `a kneel is not a weighted opportunity`() {
        val r = run(carry("QB1", 2.0, yl = 3.0), kneel("QB1", -1.0, yl = 3.0), target("QB1", 2.0, qb = "QB2")).row("QB1")
        assertEquals(1 + 2.6, r["weighted_opportunities"]!!, 1e-12)
    }

    @Test
    fun `a spike does not dilute EPA per dropback`() {
        val r = run(target("WR1", 10.0, complete = true, yds = 10.0, epa = 0.8), spike("QB1")).row("QB1")
        assertEquals(0.8, r["epa_per_dropback"]!!, 1e-12)
    }

    @Test
    fun `every player-week gets one game and a total EPA`() {
        val rows = run(target("WR1", 5.0, epa = 0.3), carry("RB1", epa = -0.1))
        assertTrue(rows.all { it.values["g"] == 1.0 })
        assertEquals(0.3, rows.row("WR1")["total_epa"]!!, 1e-12)
        assertEquals(0.0, rows.row("QB1")["total_epa"]!!, 1e-12)
    }
}
