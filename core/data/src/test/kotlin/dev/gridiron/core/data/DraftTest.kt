package dev.gridiron.core.data

import dev.gridiron.core.model.ScoringPresets
import dev.gridiron.core.testing.JdbcQueryExecutor
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.sql.DriverManager

class DraftTest {
    @TempDir
    lateinit var dir: File

    private val adp = checkNotNull(javaClass.getResource("/adp.json")).readText()

    @Test
    fun `ADP parses FFC's August 2025 board, kickers and defenses renamed, best first`() {
        val board = AdpParser.parse(adp)
        assertEquals(16, board.size)
        assertEquals("Ja'Marr Chase", board.first().name)
        assertTrue(setOf("QB", "RB", "WR", "TE", "DST").containsAll(board.map { it.position }.toSet()))
        assertEquals(board.sortedBy { it.adp }, board)
        assertEquals(6, board.first().bye)
        assertThrows<AdpFormatException> { AdpParser.parse("{}") }
        assertEquals("ppr", AdpParser.format(ScoringPresets.PPR))
        assertEquals("half-ppr", AdpParser.format(ScoringPresets.HALF_PPR))
        assertEquals("standard", AdpParser.format(ScoringPresets.STANDARD))
    }

    @Test
    fun `the board matches players by name and position and a defense by team`() = runTest {
        val file = File(dir, "s.db")
        DriverManager.getConnection("jdbc:sqlite:${file.path}").use { c ->
            c.createStatement().use { st ->
                st.executeUpdate("CREATE TABLE player (player_id TEXT PRIMARY KEY, full_name TEXT, search_name TEXT, position TEXT, team TEXT)")
                st.executeUpdate("INSERT INTO player VALUES ('p1', 'Ja''Marr Chase', 'jamarr chase', 'WR', 'CIN'), ('p2', 'Amon-Ra St. Brown', 'amonra st brown', 'WR', 'DET'), ('p3', 'Derrick Henry Jr.', 'derrick henry jr', 'RB', 'BAL')")
            }
        }
        val repo = DraftRepository(JdbcQueryExecutor(file.path)) { url ->
            assertTrue("adp/ppr?teams=10&year=2026" in url, url)
            adp
        }
        val result = repo.board(2026, 10, ScoringPresets.PPR)
        assertNull(result.message)
        val byName = result.players.associateBy { it.name }
        assertEquals("p1", byName.getValue("Ja'Marr Chase").playerId)
        assertEquals("p2", byName.getValue("Amon-Ra St. Brown").playerId)
        assertEquals("DST_DAL", byName.getValue("Dallas Defense").playerId)
        // A suffix one source has and the other doesn't still matches.
        assertEquals("p3", byName.getValue("Derrick Henry").playerId)
        // No weekly stats table here: matched, but no points per game.
        assertNull(byName.getValue("Ja'Marr Chase").lastPerGame)
        assertEquals(5, result.matched)
    }

    private fun p(name: String, pos: String, adp: Double) = BoardPlayer(name, name, pos, null, adp, null)

    @Test
    fun `advice moves needed positions up and holds kickers and defenses to the last two rounds`() {
        val board = listOf(p("WR1", "WR", 10.0), p("RB1", "RB", 14.0), p("QB1", "QB", 12.0), p("K1", "K", 1.0), p("TE1", "TE", 30.0))
        val slots = mapOf("QB" to 1, "RB" to 2, "WR" to 2, "TE" to 1, "K" to 1, "D/ST" to 1)
        // Two WRs already: RB (14 - 8) comes before WR (10) and QB (12 - 8 = 4) leads.
        val mine = listOf(p("a", "WR", 1.0), p("b", "WR", 2.0))
        assertEquals(listOf("QB1", "RB1", "WR1", "TE1"), DraftAdvice.suggestions(board, mine, slots, round = 3, rounds = 15).map { it.name })
        assertEquals("K1", DraftAdvice.suggestions(board, mine, slots, round = 14, rounds = 15).first().name)
    }
}
