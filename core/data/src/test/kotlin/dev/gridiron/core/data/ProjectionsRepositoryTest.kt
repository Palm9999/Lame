package dev.gridiron.core.data

import dev.gridiron.core.projections.PlayerProjection
import dev.gridiron.core.projections.ProjectionsRequest
import dev.gridiron.core.testing.JdbcQueryExecutor
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.File
import java.sql.DriverManager

class ProjectionsRepositoryTest {

    /**
     * A fresh on-disk SQLite database with the projection tables' real schema
     * (see `etl/gridiron_etl/schema.py`), seeded with [insertProjectionRows],
     * then reopened read-only through [JdbcQueryExecutor] -- the same JDBC
     * fixture [StatsRepositoryTest] uses against the real ETL database.
     */
    private fun jdbcFixtureWithSchema(insertProjectionRows: List<String>): JdbcQueryExecutor {
        val file = File.createTempFile("projections-fixture", ".db")
        file.deleteOnExit()
        DriverManager.getConnection("jdbc:sqlite:${file.path}").use { conn ->
            conn.createStatement().use { st ->
                st.executeUpdate(
                    """CREATE TABLE player_week_projection (
                         player_id TEXT NOT NULL, season INTEGER NOT NULL, week INTEGER NOT NULL,
                         metric_id TEXT NOT NULL, stage TEXT NOT NULL, mean REAL NOT NULL, variance REAL NOT NULL,
                         PRIMARY KEY (player_id, season, week, metric_id, stage)) WITHOUT ROWID""",
                )
                st.executeUpdate(
                    """CREATE TABLE player_week_projection_factor (
                         player_id TEXT NOT NULL, season INTEGER NOT NULL, week INTEGER NOT NULL,
                         factor TEXT NOT NULL, log_multiplier REAL NOT NULL, note TEXT,
                         PRIMARY KEY (player_id, season, week, factor)) WITHOUT ROWID""",
                )
                st.executeUpdate(
                    """CREATE TABLE player_ros_projection (
                         player_id TEXT NOT NULL, season INTEGER NOT NULL, as_of_week INTEGER NOT NULL,
                         metric_id TEXT NOT NULL, mean REAL NOT NULL, variance REAL NOT NULL,
                         PRIMARY KEY (player_id, season, as_of_week, metric_id)) WITHOUT ROWID""",
                )
                insertProjectionRows.forEach { st.executeUpdate(it) }
            }
        }
        return JdbcQueryExecutor(file.path)
    }

    @Test
    fun `projections returns baseline and final rows for a requested player`() = runTest {
        jdbcFixtureWithSchema(
            insertProjectionRows = listOf(
                "INSERT INTO player_week_projection VALUES ('P1', 2026, 3, 'targets', 'baseline', 6.0, 0.0)",
                "INSERT INTO player_week_projection VALUES ('P1', 2026, 3, 'targets', 'final', 7.2, 4.0)",
            ),
        ).use { executor ->
            val repo = ProjectionsRepository(executor)
            val result = repo.projections(ProjectionsRequest(setOf("P1"), season = 2026, week = 3))
            assertEquals(1, result.size)
            assertEquals(6.0, result.first().baseline.first { it.metricId == "targets" }.mean)
            assertEquals(7.2, result.first().final.first { it.metricId == "targets" }.mean)
        }
    }

    @Test
    fun `an empty player id set returns an empty list, not an error`() = runTest {
        jdbcFixtureWithSchema(insertProjectionRows = emptyList()).use { executor ->
            val repo = ProjectionsRepository(executor)
            val result = repo.projections(ProjectionsRequest(emptySet(), season = 2026, week = 3))
            assertEquals(emptyList<PlayerProjection>(), result)
        }
    }
}
