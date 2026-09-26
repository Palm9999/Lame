package dev.gridiron.core.data

import dev.gridiron.core.testing.JdbcQueryExecutor
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.sql.DriverManager

class PlayerDirectoryTest {
    @TempDir
    lateinit var dir: File

    private fun fixture(withXref: Boolean): JdbcQueryExecutor {
        val file = File(dir, "stats-$withXref.db")
        DriverManager.getConnection("jdbc:sqlite:${file.path}").use { conn ->
            conn.createStatement().use { st ->
                st.executeUpdate("CREATE TABLE player (player_id TEXT, full_name TEXT, search_name TEXT, position TEXT, team TEXT)")
                st.executeUpdate("INSERT INTO player VALUES ('P1', 'Stat Guy', 'stat guy', 'WR', 'KC')")
                if (withXref) {
                    st.executeUpdate("CREATE TABLE player_xref (espn_id TEXT PRIMARY KEY, player_id TEXT NOT NULL, full_name TEXT NOT NULL, position TEXT, team TEXT)")
                    st.executeUpdate("INSERT INTO player_xref VALUES ('101', 'P1', 'Stat Guy', 'WR', 'KC'), ('102', 'P2', 'Rookie Noshow', 'TE', 'BUF')")
                }
            }
        }
        return JdbcQueryExecutor(file.path)
    }

    @Test
    fun `maps espn ids through player_xref`() = runTest {
        val players = PlayerDirectory(fixture(withXref = true))
        assertEquals(mapOf("101" to "P1", "102" to "P2"), players.playerIds(listOf("101", "102", "999")))
        assertEquals(emptyMap<String, String>(), players.playerIds(emptyList()))
    }

    @Test
    fun `a database without player_xref matches nothing instead of failing`() = runTest {
        assertEquals(emptyMap<String, String>(), PlayerDirectory(fixture(withXref = false)).playerIds(listOf("101")))
    }

    @Test
    fun `headers come from player, then from player_xref for players with no stats`() = runTest {
        val players = PlayerDirectory(fixture(withXref = true))
        assertEquals(PlayerHeader("P1", "Stat Guy", "WR", "KC"), players.header("P1"))
        assertEquals(PlayerHeader("P2", "Rookie Noshow", "TE", "BUF"), players.header("P2"))
        assertNull(players.header("P404"))
    }
}
