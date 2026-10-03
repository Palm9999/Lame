package dev.gridiron.core.forecast

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BreakoutTest {
    private fun wr(id: String, week: Int, targets: Double, expected: Double = 0.0, team: String = "KC", season: Int = 2025) =
        PlayerGame(id, season, week, team, mapOf("g" to 1.0, "targets" to targets, "x_receptions" to expected))

    private fun rb(id: String, week: Int, carries: Double, targets: Double, team: String = "KC") =
        PlayerGame(id, 2025, week, team, mapOf("g" to 1.0, "carries" to carries, "targets" to targets))

    private fun inputs(
        history: Map<String, List<PlayerGame>>,
        positions: Map<String, String> = history.keys.associateWith { "WR" },
        expectedThrough: Map<Int, Int> = emptyMap(),
        absent: Set<Triple<String, Int, Int>> = emptySet(),
    ) = ForecastInputs(
        players = positions.mapValues { (id, pos) -> PlayerInfo(id, "Name $id", pos, "KC") },
        history = history,
        games = emptyList(),
        teamGames = emptyMap(),
        expectedThrough = expectedThrough,
        absent = absent,
    )

    private fun at(signals: List<Signal>, id: String, week: Int) = signals.firstOrNull { it.playerId == id && it.week == week }

    /** Weeks 1-8 steady at 5 targets, then weeks 9-12 at [recent]. */
    private fun curve(id: String, recent: Double) = (1..8).map { wr(id, it, 5.0) } + (9..12).map { wr(id, it, recent) }

    @Test
    fun `a growing role outscores a steady one, and a shrinking one scores zero from usage`() {
        val signals = Breakout.compute(inputs(mapOf("up" to curve("up", 8.0), "flat" to curve("flat", 5.0), "down" to curve("down", 3.5))))
        val up = at(signals, "up", 13)!!
        val flat = at(signals, "flat", 13)!!
        assertEquals(5.0, up.usageBase, 1e-9)
        assertEquals(8.0, up.usageRecent, 1e-9)
        assertTrue(up.score > flat.score)
        assertEquals(0.0, flat.score, 1e-9)
        // 8 over 5 is a 60% rise: a full usage score, which carries all of the weight when expected points are missing.
        assertEquals(100.0 * (K.BREAKOUT_W_USAGE + K.BREAKOUT_W_EXPECTED), up.score, 1e-9)
        assertNull(at(signals, "down", 13)?.takeIf { it.score > 0.0 })
    }

    @Test
    fun `a player under the usage floor, or with too few games, isn't scored`() {
        val tiny = (1..12).map { wr("tiny", it, if (it > 8) 2.0 else 1.0) }
        val rookie = (9..12).map { wr("rookie", it, 9.0) }
        val signals = Breakout.compute(inputs(mapOf("tiny" to tiny, "rookie" to rookie)))
        assertNull(at(signals, "tiny", 13))
        assertTrue(signals.none { it.playerId == "rookie" })
    }

    @Test
    fun `a player who has been out a month isn't scored`() {
        val games = (1..8).map { wr("gone", it, 5.0) } + (9..12).map { wr("gone", it, 8.0) }
        val signals = Breakout.compute(inputs(mapOf("gone" to games.filter { it.week <= 8 } + games.filter { it.week in 9..12 })))
        assertNotNull(at(signals, "gone", 13))
        // Week 13's games are known only through week 12; a week-17 view is four weeks stale.
        assertNull(at(signals, "gone", 17))
    }

    @Test
    fun `only games before the week are read`() {
        val signals = Breakout.compute(inputs(mapOf("up" to curve("up", 8.0))))
        // Entering week 9 only the flat weeks are behind him; week 9's own 8 targets show up first entering week 10.
        assertEquals(5.0, at(signals, "up", 9)!!.usageRecent, 1e-9)
        assertEquals(5.75, at(signals, "up", 10)!!.usageRecent, 1e-9)
    }

    @Test
    fun `expected points count where ffopportunity covers every game, and fall back to usage where it lags`() {
        val games = (1..8).map { wr("a", it, 5.0, expected = 4.0) } + (9..12).map { wr("a", it, 8.0, expected = 4.0) }
        val covered = Breakout.compute(inputs(mapOf("a" to games), expectedThrough = mapOf(2025 to 12)))
        val withX = at(covered, "a", 13)!!
        assertEquals(4.0, withX.xpBase!!, 1e-9)
        // Usage is up 60% but expected points are flat: only the usage share of the weight counts.
        assertEquals(100.0 * K.BREAKOUT_W_USAGE, withX.score, 1e-9)

        // ffopportunity stops at week 10: the recent games lack it, so the score is usage alone, never a collapse.
        val lagged = at(Breakout.compute(inputs(mapOf("a" to games), expectedThrough = mapOf(2025 to 10))), "a", 13)!!
        assertNull(lagged.xpRecent)
        assertNull(lagged.xpBase)
        assertEquals(100.0 * (K.BREAKOUT_W_USAGE + K.BREAKOUT_W_EXPECTED), lagged.score, 1e-9)
    }

    @Test
    fun `a teammate out adds to the score and is named, and only in his own room`() {
        val history = mapOf(
            "wr1" to curve("wr1", 5.0),
            "wr2" to curve("wr2", 6.0).map { it },
            "rb1" to (1..12).map { rb("rb1", it, 14.0, 3.0) },
        )
        val positions = mapOf("wr1" to "WR", "wr2" to "WR", "rb1" to "RB")
        val out = setOf(Triple("wr2", 2025, 13))
        val signals = Breakout.compute(inputs(history, positions, absent = out))
        val helped = at(signals, "wr1", 13)!!
        assertEquals("Name wr2", helped.outNote)
        assertTrue(helped.vacated > 0.0 && helped.vacated <= 1.0)
        assertEquals(100.0 * K.BREAKOUT_W_VACATED * helped.vacated, helped.score, 1e-9)
        // The running back shares no room with a receiver, and the one who is out isn't scored as helped by himself.
        assertNull(at(signals, "rb1", 13)?.outNote)
        assertFalse(at(signals, "wr2", 13)?.outNote?.contains("wr2") ?: false)
    }

    @Test
    fun `running backs are scored on carries plus targets`() {
        val games = (1..8).map { rb("r", it, 10.0, 2.0) } + (9..12).map { rb("r", it, 14.0, 4.0) }
        val signal = at(Breakout.compute(inputs(mapOf("r" to games), mapOf("r" to "RB"))), "r", 13)!!
        assertEquals(12.0, signal.usageBase, 1e-9)
        assertEquals(18.0, signal.usageRecent, 1e-9)
    }

    @Test
    fun `a forecast writes the signals into the database`(@org.junit.jupiter.api.io.TempDir dir: java.io.File) {
        TestDb(java.io.File(dir, "stats.db")).use { db ->
            db.player("w1", "WR", "KC")
            for (week in 1..12) {
                db.week("w1", 2025, week, "KC", "targets" to if (week > 8) 8.0 else 5.0)
                db.game(2025, week, "KC", "DEN", spread = -3.0, total = 45.0)
            }
            db.meta("expected_through_week:2025", "12")
            Forecast.run(db.conn, java.time.Instant.parse("2025-12-01T00:00:00Z"))
            val rows = db.query("SELECT week, score, usage_recent, usage_base FROM player_week_signal WHERE player_id = 'w1' ORDER BY week")
            assertTrue(rows.any { it[0] == "13" }, "signals: $rows")
            val last = rows.single { it[0] == "13" }
            assertEquals(8.0, last[2]!!.toDouble(), 1e-9)
            assertEquals(5.0, last[3]!!.toDouble(), 1e-9)
        }
    }
}
