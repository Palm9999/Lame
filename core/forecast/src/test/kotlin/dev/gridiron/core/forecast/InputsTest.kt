package dev.gridiron.core.forecast

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class InputsTest {
    @TempDir
    lateinit var dir: File

    @Test
    fun `players, their weeks, team totals, games and expected-points coverage load`() {
        TestDb(File(dir, "stats.db")).use { db ->
            db.player("QB1", "QB", "AAA")
            db.player("WR1", "WR", "AAA")
            db.player("K1", "K", "AAA")
            db.week("QB1", 2025, 1, "AAA", "attempts" to 30.0, "passing_yards" to 250.0, "passing_tds" to 2.0)
            db.week("WR1", 2025, 1, "AAA", "targets" to 8.0, "receptions" to 5.0, "x_receiving_tds" to 0.4)
            db.week("WR1", 2025, 2, "AAA", "targets" to 6.0)
            // Kickers aren't projected: this target must not reach AAA's team total.
            db.week("K1", 2025, 1, "AAA", "targets" to 1.0)
            db.game(2025, 1, "AAA", "BBB", spread = 3.0, total = 44.0, homeQb = "QB1", homeCoach = "Coach A")
            db.game(2025, 5, "BBB", "AAA", played = false)
            db.meta("expected_through_week:2025", "1")

            val inputs = loadInputs(db.conn)

            assertEquals(setOf("QB1", "WR1"), inputs.players.keys)
            val wr = inputs.history.getValue("WR1")
            assertEquals(listOf(1, 2), wr.map { it.week })
            assertEquals(8.0, wr[0]["targets"])
            assertEquals(0.0, wr[1]["receptions"])
            val team = inputs.teamGames.getValue(Triple("AAA", 2025, 1))
            assertEquals(30.0, team.passAttempts)
            assertEquals(8.0, team.targets)
            assertEquals(250.0, team.passYards)
            assertEquals(2.0, team.passTds)
            assertEquals(2, inputs.games.size)
            val first = inputs.games[0]
            assertTrue(first.played)
            assertFalse(inputs.games[1].played)
            assertEquals("BBB", first.opponentOf("AAA"))
            assertEquals("QB1", first.qbOf("AAA"))
            assertEquals("Coach A", first.coachOf("AAA"))
            assertEquals(mapOf(2025 to 1), inputs.expectedThrough)
        }
    }

    @Test
    fun `implied points split the total by the home-favored spread`() {
        val game = Game(2026, 1, true, "SEA", "NE", false, 3.0, 44.5, null, null, null, null)
        assertEquals(23.75, game.impliedPoints("SEA"))
        assertEquals(20.75, game.impliedPoints("NE"))
        assertEquals(-3.0, game.favoredBy("NE"))
        assertNull(game.copy(total = null).impliedPoints("SEA"))
        assertNull(game.copy(spread = null).impliedPoints("SEA"))
    }
}
