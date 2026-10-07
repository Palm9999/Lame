package dev.gridiron.core.ingest

import dev.gridiron.core.ingest.csv.MissingColumnsException
import dev.gridiron.core.ingest.pbp.Play
import dev.gridiron.core.ingest.pbp.PlayerWeek
import dev.gridiron.core.ingest.pbp.play
import dev.gridiron.core.ingest.pbp.row
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream

class FtnTest {
    private val header = listOf(
        "nflverse_game_id", "nflverse_play_id", "is_play_action", "is_qb_out_of_pocket", "is_interception_worthy",
        "is_throw_away", "is_catchable_ball", "is_contested_ball", "is_created_reception", "is_drop", "n_blitzers",
        "is_screen_pass", "is_rpo", "is_motion", "is_no_huddle", "n_defense_box",
    )

    private fun stream(header: List<String>, vararg rows: Map<String, Any?>) =
        ByteArrayInputStream(Fixtures.csv(header, rows.toList()).toByteArray())

    private fun ftnRow(vararg overrides: Pair<String, Any?>): Map<String, Any?> = mapOf(
        "nflverse_game_id" to "g1", "nflverse_play_id" to 7, "is_play_action" to "FALSE", "is_qb_out_of_pocket" to "FALSE",
        "is_interception_worthy" to "FALSE", "is_throw_away" to "FALSE", "is_catchable_ball" to "FALSE",
        "is_contested_ball" to "FALSE", "is_created_reception" to "FALSE", "is_drop" to "FALSE", "n_blitzers" to 0,
        "is_screen_pass" to "FALSE", "is_rpo" to "FALSE", "is_motion" to "FALSE", "is_no_huddle" to "FALSE", "n_defense_box" to 0,
    ) + overrides

    private fun flags(
        catchable: Boolean = false, contested: Boolean = false, drop: Boolean = false, created: Boolean = false,
        playAction: Boolean = false, outOfPocket: Boolean = false, throwAway: Boolean = false,
        intWorthy: Boolean = false, blitzers: Int = 0, screen: Boolean = false, rpo: Boolean = false,
        motion: Boolean = false, noHuddle: Boolean = false, box: Int = 0,
    ) = FtnFlags(catchable, contested, drop, created, playAction, outOfPocket, throwAway, intWorthy, blitzers, screen, rpo, motion, noHuddle, box)

    /** Runs the aggregator over plays, each with the FTN flags it was charted with (null: FTN has no row for it). */
    private fun run(vararg charted: Pair<Play, FtnFlags?>): List<PlayerWeek> {
        val index = charted.mapNotNull { (p, f) -> if (f != null && p.gameId != null && p.playId != null) FtnKey(p.gameId, p.playId) to f else null }.toMap()
        return FtnAggregator(index).apply { charted.forEach { add(it.first) } }.rows()
    }

    private fun pass(playId: Int, receiver: String? = "WR1", passer: String? = "QB1", attempt: Double = 1.0, sack: Double = 0.0, week: Int = 1, twoPoint: Double = 0.0) =
        play(playId = playId, passAttempt = attempt, sack = sack, receiver = receiver, passer = passer, week = week, twoPointAttempt = twoPoint)

    @Test
    fun `reads TRUE and FALSE flags and requires every column`() {
        val index = readFtn(stream(header, ftnRow("is_drop" to "TRUE", "is_catchable_ball" to "TRUE", "n_blitzers" to 2)), "ftn_charting_2025.csv")
        val f = index.getValue(FtnKey("g1", 7))
        assertTrue(f.drop && f.catchable)
        assertEquals(false, f.contested)
        assertEquals(2, f.blitzers)
        val renamed = header.map { if (it == "is_drop") "is_dropped" else it }
        assertThrows(MissingColumnsException::class.java) {
            readFtn(stream(renamed, ftnRow("is_dropped" to "TRUE")), "ftn_charting_2025.csv")
        }
    }

    @Test
    fun `a blank flag reads as false and a blank blitzer count as zero`() {
        val f = readFtn(stream(header, ftnRow("is_drop" to "", "n_blitzers" to "")), "ftn_charting_2025.csv").getValue(FtnKey("g1", 7))
        assertEquals(false, f.drop)
        assertEquals(0, f.blitzers)
    }

    @Test
    fun `a row without a game or play id is skipped`() {
        val index = readFtn(stream(header, ftnRow("nflverse_play_id" to ""), ftnRow("nflverse_game_id" to "")), "ftn_charting_2025.csv")
        assertTrue(index.isEmpty())
    }

    @Test
    fun `the target receiver is credited catchable, contested, drop and created`() {
        val rows = run(pass(1) to flags(catchable = true, contested = true, drop = true, created = true), pass(2) to flags())
        val wr = rows.row("WR1")
        assertEquals(2.0, wr["ftn_targets"])
        assertEquals(1.0, wr["ftn_catchable"])
        assertEquals(1.0, wr["ftn_contested"])
        assertEquals(1.0, wr["ftn_drops"])
        assertEquals(1.0, wr["ftn_created_rec"])
        assertEquals(0.5, wr["ftn_catchable_rate"])
        assertEquals(0.5, wr["ftn_contested_rate"])
        assertEquals(0.5, wr["ftn_drop_rate"])
    }

    @Test
    fun `the passer is credited with the dropback weight, attempt plus sack plus scramble`() {
        val scramble = play(playId = 3, playType = "run", qbScramble = 1.0, rusher = "QB1", passer = "QB1")
        val rows = run(
            pass(1) to flags(playAction = true, blitzers = 1),
            pass(2, receiver = null, attempt = 0.0, sack = 1.0) to flags(outOfPocket = true, blitzers = 3),
            scramble to flags(playAction = true, outOfPocket = true),
        )
        val qb = rows.row("QB1")
        assertEquals(3.0, qb["ftn_dropbacks"])
        assertEquals(2.0, qb["ftn_pa_db"])
        assertEquals(2.0, qb["ftn_blitz_db"])
        assertEquals(2.0, qb["ftn_oop_db"])
        assertEquals(2.0 / 3.0, qb["ftn_play_action_rate"]!!, 1e-12)
        assertEquals(2.0 / 3.0, qb["ftn_blitz_rate"]!!, 1e-12)
        assertEquals(2.0 / 3.0, qb["ftn_out_of_pocket_rate"]!!, 1e-12)
    }

    @Test
    fun `screens and motion are credited to the target and the passer, RPO and no-huddle to the passer`() {
        val rows = run(
            pass(1) to flags(screen = true, motion = true, rpo = true),
            pass(2) to flags(noHuddle = true, motion = true),
            pass(3) to flags(),
            pass(4) to flags(),
        )
        val wr = rows.row("WR1")
        assertEquals(1.0, wr["ftn_screen_targets"])
        assertEquals(0.25, wr["ftn_screen_target_rate"])
        assertEquals(0.5, wr["ftn_motion_target_rate"])
        val qb = rows.row("QB1")
        assertEquals(0.25, qb["ftn_screen_rate"])
        assertEquals(0.25, qb["ftn_rpo_rate"])
        assertEquals(0.25, qb["ftn_no_huddle_rate"])
        assertEquals(0.5, qb["ftn_motion_rate"])
    }

    @Test
    fun `the box count averages over the rusher's carries FTN counted it on`() {
        fun carry(playId: Int) = play(playId = playId, playType = "run", rusher = "RB1")
        val rb = run(carry(1) to flags(box = 8), carry(2) to flags(box = 6), carry(3) to flags(box = 0)).row("RB1")
        assertEquals(2.0, rb["ftn_box_carries"])
        assertEquals(14.0, rb["ftn_box_sum"])
        assertEquals(7.0, rb["ftn_avg_box"])
    }

    @Test
    fun `throwaways count on the passer's dropbacks`() {
        val qb = run(pass(1) to flags(throwAway = true), pass(2) to flags(), pass(3) to flags(), pass(4) to flags()).row("QB1")
        assertEquals(1.0, qb["ftn_throwaway"])
        assertEquals(0.25, qb["ftn_throwaway_rate"])
    }

    @Test
    fun `interception-worthy counts only on attempts`() {
        val sack = pass(2, receiver = null, attempt = 0.0, sack = 1.0)
        val qb = run(pass(1) to flags(intWorthy = true), pass(3) to flags(), sack to flags(intWorthy = true)).row("QB1")
        assertEquals(2.0, qb["ftn_attempts"])
        assertEquals(1.0, qb["ftn_int_worthy"])
        assertEquals(0.5, qb["ftn_int_worthy_rate"])
        assertEquals(3.0, qb["ftn_dropbacks"])
    }

    @Test
    fun `a sack counts as a dropback but not an attempt, and has no interception-worthy rate when it is all he threw`() {
        val qb = run(pass(1, receiver = null, attempt = 0.0, sack = 1.0) to flags(blitzers = 2)).row("QB1")
        assertEquals(1.0, qb["ftn_dropbacks"])
        assertEquals(0.0, qb["ftn_attempts"])
        assertEquals(1.0, qb["ftn_blitz_rate"])
        assertNull(qb["ftn_int_worthy_rate"])
    }

    @Test
    fun `kneels, spikes and two-point tries count nowhere`() {
        val kneel = play(playId = 1, playType = "qb_kneel", rusher = "QB1", passer = "QB1")
        val spike = play(playId = 2, playType = "qb_spike", passAttempt = 1.0, passer = "QB1")
        val rows = run(kneel to flags(playAction = true), spike to flags(playAction = true), pass(3, twoPoint = 1.0) to flags(drop = true))
        assertTrue(rows.isEmpty(), rows.map { it.playerId }.toString())
    }

    @Test
    fun `a play with no FTN row counts nowhere, denominators included`() {
        val rows = run(pass(1) to flags(drop = true), pass(2) to null, pass(3) to null)
        val wr = rows.row("WR1")
        assertEquals(1.0, wr["ftn_targets"])
        assertEquals(1.0, rows.row("QB1")["ftn_dropbacks"])
        assertEquals(1.0, wr["ftn_drop_rate"])
    }

    @Test
    fun `a play with no receiver or passer credits nobody and does not crash`() {
        val rows = run(pass(1, receiver = null, passer = null) to flags(drop = true, playAction = true))
        assertTrue(rows.isEmpty())
    }

    @Test
    fun `a play id of null matches nothing`() {
        val index = mapOf(FtnKey("g1", 1) to flags(drop = true))
        val rows = FtnAggregator(index).apply { add(play(playId = null, passAttempt = 1.0, receiver = "WR1", passer = "QB1")) }.rows()
        assertTrue(rows.isEmpty())
    }

    @Test
    fun `a play from another game does not match`() {
        val index = mapOf(FtnKey("g2", 1) to flags(drop = true))
        val rows = FtnAggregator(index).apply { add(play(gameId = "g1", playId = 1, passAttempt = 1.0, receiver = "WR1", passer = "QB1")) }.rows()
        assertTrue(rows.isEmpty())
    }

    @Test
    fun `a zero-drop week stores zeros and the weekly rates`() {
        val wr = run(pass(1) to flags(catchable = true), pass(2) to flags(catchable = true)).row("WR1")
        assertEquals(0.0, wr["ftn_drops"])
        assertEquals(0.0, wr["ftn_created_rec"])
        assertEquals(0.0, wr["ftn_contested"])
        assertEquals(0.0, wr["ftn_drop_rate"])
        assertEquals(0.0, wr["ftn_contested_rate"])
        assertEquals(1.0, wr["ftn_catchable_rate"])
    }

    @Test
    fun `two weeks give separate rows`() {
        val rows = run(pass(1, week = 1) to flags(drop = true), pass(2, week = 2) to flags())
        val wr = rows.filter { it.playerId == "WR1" }.associateBy { it.week }
        assertEquals(1.0, wr.getValue(1).values["ftn_drops"])
        assertEquals(0.0, wr.getValue(2).values["ftn_drops"])
        assertEquals("AAA", wr.getValue(1).team)
    }

    @Test
    fun `a player who both threw and was targeted has one row with both sets`() {
        val trick = play(playId = 5, passAttempt = 1.0, receiver = "QB1", passer = "WR1")
        val rows = run(pass(1) to flags(), trick to flags(catchable = true))
        val wr = rows.row("WR1")
        assertEquals(1.0, wr["ftn_targets"])
        assertEquals(1.0, wr["ftn_dropbacks"])
    }
}
