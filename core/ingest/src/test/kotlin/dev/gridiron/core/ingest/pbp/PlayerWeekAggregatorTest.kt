package dev.gridiron.core.ingest.pbp

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class PlayerWeekAggregatorTest {

    @ParameterizedTest
    @CsvSource("25,0,0,0", "20,1,0,0", "10,1,1,0", "5,1,1,1", "1,1,1,1")
    fun `carry zone thresholds are inclusive`(yl: Double, rz: Double, gz: Double, gl: Double) {
        val r = base(listOf(carry("RB1", yl = yl))).row("RB1")
        assertEquals(listOf(rz, gz, gl), listOf(r["rz_carries"], r["gz_carries"], r["gl_carries"]))
    }

    @Test
    fun `end zone target when air yards reach the goal line`() {
        val rows = base(listOf(target("WR1", 15.0, yl = 15.0), target("WR1", 14.0, yl = 15.0), target("WR1", 30.0, yl = 15.0)))
        assertEquals(2.0, rows.row("WR1")["ez_targets"])
    }

    @Test
    fun `designed QB runs exclude scrambles`() {
        val r = base(listOf(carry("QB1", yl = 3.0), carry("QB1", yl = 3.0, qbScramble = 1.0))).row("QB1")
        assertEquals(2.0, r["gl_carries"])
        assertEquals(1.0, r["qb_rush_inside_5"])
    }

    @Test
    fun `two-point attempts, non-scrimmage plays and preseason do not count`() {
        val rows = base(listOf(
            target("WR1", 5.0),
            target("WR1", 2.0, twoPointAttempt = 1.0),
            play(playType = "no_play", receiver = "WR1"),
            target("WR1", 10.0, seasonType = "PRE"),
        ))
        assertEquals(1.0, rows.row("WR1")["targets"])
    }

    @Test
    fun `a kneel counts as a carry and its yards count`() {
        val r = base(listOf(carry("QB1", 5.0), kneel("QB1", -2.0))).row("QB1")
        assertEquals(2.0, r["carries"])
        assertEquals(3.0, r["rushing_yards"])
        assertEquals(1.0, r["carries_eff"])
    }

    @Test
    fun `a kneel at the 3 is a carry but not goal-line usage`() {
        val r = base(listOf(carry("QB1", 2.0, yl = 3.0), kneel("QB1", -1.0, yl = 3.0))).row("QB1")
        assertEquals(2.0, r["carries"])
        assertEquals(1.0, r["rushing_yards"])
        assertEquals(1.0, r["carries_eff"])
        assertEquals(listOf(1.0, 1.0, 1.0), listOf(r["rz_carries"], r["gz_carries"], r["gl_carries"]))
        assertEquals(1.0, r["qb_rush_inside_5"])
    }

    @Test
    fun `a spike counts as a pass attempt but not a dropback`() {
        val r = base(listOf(target("WR1", 10.0, complete = true, yds = 10.0, epa = 0.8), spike("QB1"))).row("QB1")
        assertEquals(2.0, r["attempts"])
        assertEquals(1.0, r["dropbacks"])
        assertEquals(0.8, r["pass_epa"]!!, 1e-12)
    }

    @Test
    fun `a player with rushing and receiving is one row`() {
        val r = base(listOf(carry("RB1", 5.0), target("RB1", 3.0, complete = true, yds = 8.0))).row("RB1")
        assertEquals(listOf(1.0, 1.0, 1.0), listOf(r["carries"], r["targets"], r["receptions"]))
    }

    @Test
    fun `team denominators are stored and exclude kneels`() {
        val r = base(listOf(target("WR1", 10.0), target("WR1", 20.0), target("WR2", 30.0), carry("RB1"), kneel("QB1"))).row("WR1")
        assertEquals(3.0, r["team_targets"])
        assertEquals(60.0, r["team_air_yards"])
        assertEquals(1.0, r["team_carries"])
    }

    @Test
    fun `CPOE components allow attempt weighting`() {
        val qb = base(listOf(target("WR1", 5.0, cpoe = 10.0), target("WR1", 5.0, cpoe = -4.0), target("WR1", 5.0, cpoe = null))).row("QB1")
        assertEquals(6.0, qb["cpoe_sum"])
        assertEquals(2.0, qb["cpoe_n"])
        assertEquals(3.0, qb["cpoe"])
    }

    @Test
    fun `EPA and CPOE columns are null for players without that kind of play`() {
        val rb = base(listOf(carry("RB1", 3.0, epa = 0.4))).row("RB1")
        assertEquals(0.4, rb["rush_epa"]!!, 1e-12)
        assertNull(rb["rec_epa"])
        assertNull(rb["pass_epa"])
        assertNull(rb["cpoe"])
        assertNull(rb["cpoe_sum"])
        assertEquals(0.0, rb["cpoe_n"])
    }

    @Test
    fun `first downs credit passer, receiver and rusher`() {
        val rows = base(listOf(
            target("WR1", 8.0, complete = true, yds = 12.0, firstDownPass = 1.0),
            target("WR1", 3.0, complete = true, yds = 4.0),
            carry("RB1", 11.0, firstDownRush = 1.0),
        ))
        assertEquals(1.0, rows.row("WR1")["receiving_first_downs"])
        assertEquals(1.0, rows.row("QB1")["passing_first_downs"])
        assertEquals(1.0, rows.row("RB1")["rushing_first_downs"])
    }

    @Test
    fun `long touchdowns count at 40 and 50 and nest`() {
        val rows = base(listOf(
            target("WR1", 30.0, complete = true, yds = 55.0, td = 1.0),
            target("WR1", 20.0, complete = true, yds = 42.0, td = 1.0),
            target("WR1", 5.0, complete = true, yds = 39.0, td = 1.0),
            target("WR1", 45.0, complete = true, yds = 60.0),
            carry("RB1", 61.0, td = 1.0),
        ))
        val wr = rows.row("WR1")
        val qb = rows.row("QB1")
        val rb = rows.row("RB1")
        assertEquals(listOf(2.0, 1.0), listOf(wr["receiving_tds_40"], wr["receiving_tds_50"]))
        assertEquals(listOf(2.0, 1.0), listOf(qb["passing_tds_40"], qb["passing_tds_50"]))
        assertEquals(listOf(1.0, 1.0), listOf(rb["rushing_tds_40"], rb["rushing_tds_50"]))
    }

    @Test
    fun `fumbles lost are credited to the ball carrier`() {
        val rows = base(listOf(
            carry("RB1", 3.0, fumbleLost = 1.0, fumbler = "RB1"),
            target("WR1", 5.0, complete = true, yds = 9.0, fumbleLost = 1.0, fumbler = "WR1"),
            play(playType = "pass", sack = 1.0, passer = "QB1", fumbleLost = 1.0, fumbler = "QB1"),
        ))
        assertEquals(1.0, rows.row("RB1")["fumbles_lost"])
        assertEquals(1.0, rows.row("WR1")["fumbles_lost"])
        assertEquals(1.0, rows.row("QB1")["fumbles_lost"])
    }

    @Test
    fun `recovered fumbles and defender fumbles do not count`() {
        val rows = base(listOf(
            carry("RB1", 3.0, fumbleLost = 0.0, fumbler = "RB1"),
            target("WR1", 5.0, complete = true, yds = 9.0, fumbleLost = 1.0, fumbler = "CB9"),
        ))
        assertEquals(0.0, rows.row("RB1")["fumbles_lost"])
        assertEquals(0.0, rows.row("WR1")["fumbles_lost"])
        assertTrue(rows.none { it.playerId == "CB9" })
    }

    @Test
    fun `successful two-point conversions are credited and add no targets`() {
        val rows = base(listOf(
            target("WR1", 10.0),
            target("WR1", 2.0, complete = true, yds = 2.0, twoPointAttempt = 1.0, twoPointResult = "success"),
            target("WR1", 2.0, twoPointAttempt = 1.0, twoPointResult = "failure"),
            carry("RB1", 2.0, twoPointAttempt = 1.0, twoPointResult = "success"),
        ))
        val wr = rows.row("WR1")
        assertEquals(1.0, wr["receiving_2pt"])
        assertEquals(1.0, wr["targets"])
        assertEquals(1.0, rows.row("QB1")["passing_2pt"])
        assertEquals(1.0, rows.row("RB1")["rushing_2pt"])
    }
}
