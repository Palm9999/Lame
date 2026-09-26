package dev.gridiron.core.data

import dev.gridiron.core.testing.JdbcQueryExecutor
import dev.gridiron.core.testing.StatsDb
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.sql.DriverManager

class TeamsRepositoryTest {

    private fun fixture(vararg inserts: String): JdbcQueryExecutor {
        val file = File.createTempFile("teams-fixture", ".db")
        file.deleteOnExit()
        DriverManager.getConnection("jdbc:sqlite:${file.path}").use { conn ->
            conn.createStatement().use { st ->
                st.executeUpdate(
                    """CREATE TABLE team_week_defense (team TEXT, season INTEGER, week INTEGER,
                         points_allowed REAL, yards_allowed REAL, sacks REAL, interceptions REAL,
                         fumbles_recovered REAL, defensive_tds REAL)""",
                )
                st.executeUpdate(
                    """CREATE TABLE injury_report (player_id TEXT, season INTEGER, week INTEGER, team TEXT,
                         name TEXT, position TEXT, status TEXT, injury TEXT, practice TEXT)""",
                )
                inserts.forEach { st.executeUpdate(it) }
            }
        }
        return JdbcQueryExecutor(file.path)
    }

    @Test
    fun `defense sums the season and sorts by points allowed per game`() = runTest {
        val repo = TeamsRepository(
            fixture(
                "INSERT INTO team_week_defense VALUES ('KC', 2026, 1, 30, 400, 1, 0, 0, 0)",
                "INSERT INTO team_week_defense VALUES ('KC', 2026, 2, 10, 250, 3, 1, 1, 1)",
                "INSERT INTO team_week_defense VALUES ('BUF', 2026, 1, 14, 300, 2, 0, 0, 0)",
            ),
        )
        val rows = repo.defense(2026)
        assertEquals(listOf("BUF", "KC"), rows.map { it.team })
        assertEquals(DefenseRow("KC", 2, 40.0, 650.0, 4.0, 1.0, 1.0, 1.0), rows[1])
    }

    @Test
    fun `injuries keep each player's latest report`() = runTest {
        val repo = TeamsRepository(
            fixture(
                "INSERT INTO injury_report VALUES ('p1', 2026, 1, 'KC', 'A', 'WR', 'Questionable', 'Ankle', 'Limited')",
                "INSERT INTO injury_report VALUES ('p1', 2026, 2, 'KC', 'A', 'WR', 'Out', 'Ankle', 'DNP')",
                "INSERT INTO injury_report VALUES ('p2', 2026, 2, 'KC', 'B', 'RB', NULL, NULL, 'Full')",
            ),
        )
        val rows = repo.injuries(2026)
        assertEquals(1, rows.size)
        assertEquals("Out", rows.single().status)
        assertEquals("p1", rows.single().playerId)
    }

    @Test
    fun `real database has defense for every built season`() = runTest {
        val path = StatsDb.path
        assumeTrue(path != null)
        val rows = TeamsRepository(JdbcQueryExecutor(path!!)).defense(2025)
        assertEquals(32, rows.size)
        assertTrue(rows.all { it.games >= 17 })
    }
}
