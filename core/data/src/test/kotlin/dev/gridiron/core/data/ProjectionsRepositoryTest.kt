package dev.gridiron.core.data

import dev.gridiron.core.model.ScoringPresets
import dev.gridiron.core.projections.ForecastStatus
import dev.gridiron.core.projections.PlayerProjection
import dev.gridiron.core.projections.ProjectionsRequest
import dev.gridiron.core.testing.JdbcQueryExecutor
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.File
import java.sql.DriverManager
import java.time.Instant

class ProjectionsRepositoryTest {

    /**
     * A fresh on-disk SQLite database with the projection tables' real schema
     * (see `etl/gridiron_etl/schema.py`), seeded with [insertProjectionRows],
     * then reopened read-only through [JdbcQueryExecutor] -- the same JDBC
     * fixture [StatsRepositoryTest] uses against the real ETL database.
     */
    private fun jdbcFixtureWithSchema(insertProjectionRows: List<String>, withRosWeeks: Boolean = true): JdbcQueryExecutor {
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
                if (withRosWeeks) {
                    st.executeUpdate(
                        """CREATE TABLE player_ros_week (
                             player_id TEXT NOT NULL, season INTEGER NOT NULL, as_of_week INTEGER NOT NULL, week INTEGER NOT NULL,
                             metric_id TEXT NOT NULL, mean REAL NOT NULL, variance REAL NOT NULL,
                             PRIMARY KEY (player_id, season, as_of_week, week, metric_id)) WITHOUT ROWID""",
                    )
                }
                st.executeUpdate("CREATE TABLE metric (id TEXT PRIMARY KEY, dist_family TEXT)")
                st.executeUpdate("CREATE TABLE player (player_id TEXT PRIMARY KEY, full_name TEXT NOT NULL, position TEXT, team TEXT)")
                st.executeUpdate("CREATE TABLE schema_meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
                st.executeUpdate(
                    """CREATE TABLE game (game_id TEXT PRIMARY KEY, season INTEGER NOT NULL, week INTEGER NOT NULL,
                         game_type TEXT NOT NULL, home_team TEXT NOT NULL, away_team TEXT NOT NULL,
                         spread_line REAL, total_line REAL)""",
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

    @Test
    fun `projections carry each stat's distribution family`() = runTest {
        jdbcFixtureWithSchema(
            listOf(
                "INSERT INTO metric VALUES ('targets', 'negbinom')",
                "INSERT INTO player_week_projection VALUES ('P1', 2026, 3, 'targets', 'final', 7.2, 4.0)",
                "INSERT INTO player_week_projection VALUES ('P1', 2026, 3, 'mystery', 'final', 1.0, 1.0)",
            ),
        ).use { executor ->
            val final = ProjectionsRepository(executor).projections(ProjectionsRequest(setOf("P1"), 2026, 3)).single().final
            assertEquals("negbinom", final.single { it.metricId == "targets" }.family)
            assertEquals(null, final.single { it.metricId == "mystery" }.family)
        }
    }

    @Test
    fun `status reads the forecast's outcome, build time and upcoming week`() = runTest {
        jdbcFixtureWithSchema(
            listOf(
                "INSERT INTO schema_meta VALUES ('forecast_status', 'ok')",
                "INSERT INTO schema_meta VALUES ('forecast_built_at', '2026-09-22T11:02:00Z')",
                "INSERT INTO schema_meta VALUES ('forecast_week:2026', '4')",
                "INSERT INTO schema_meta VALUES ('seasons', '2026')",
            ),
        ).use { executor ->
            val status = ProjectionsRepository(executor).status()
            assertEquals("ok", status.status)
            assertEquals(Instant.parse("2026-09-22T11:02:00Z"), status.builtAt)
            assertEquals(mapOf(2026 to 4), status.upcoming)
        }
    }

    @Test
    fun `a database built before projections existed has no status`() = runTest {
        jdbcFixtureWithSchema(emptyList()).use { executor ->
            assertEquals(ForecastStatus(null, null, emptyMap()), ProjectionsRepository(executor).status())
        }
    }

    @Test
    fun `weekAll and rosAll list final projections with who each player is`() = runTest {
        jdbcFixtureWithSchema(
            listOf(
                "INSERT INTO player VALUES ('P1', 'Pat One', 'WR', 'KC')",
                "INSERT INTO player_week_projection VALUES ('P1', 2026, 4, 'targets', 'baseline', 6.0, 3.0)",
                "INSERT INTO player_week_projection VALUES ('P1', 2026, 4, 'targets', 'final', 7.0, 4.0)",
                "INSERT INTO player_ros_projection VALUES ('P1', 2026, 2, 'targets', 90.0, 40.0)",
                "INSERT INTO player_ros_projection VALUES ('P1', 2026, 3, 'targets', 80.0, 35.0)",
            ),
        ).use { executor ->
            val repo = ProjectionsRepository(executor)
            val week = repo.weekAll(2026, 4).single()
            assertEquals("Pat One", week.name)
            assertEquals("WR", week.position)
            assertEquals(listOf(7.0), week.components.map { it.mean })
            assertEquals(listOf(80.0), repo.rosAll(2026).single().components.map { it.mean })
        }
    }

    @Test
    fun `rosWeeks reads the latest build's weeks and scores each one`() = runTest {
        jdbcFixtureWithSchema(
            listOf(
                "INSERT INTO player VALUES ('P1', 'Pat One', 'WR', 'KC')",
                "INSERT INTO player_ros_week VALUES ('P1', 2026, 2, 3, 'receptions', 9.0, 1.0)",
                "INSERT INTO player_ros_week VALUES ('P1', 2026, 3, 4, 'receptions', 5.0, 1.0)",
                "INSERT INTO player_ros_week VALUES ('P1', 2026, 3, 4, 'receiving_yards', 60.0, 1.0)",
                "INSERT INTO player_ros_week VALUES ('P1', 2026, 3, 6, 'receptions', 4.0, 1.0)",
            ),
        ).use { executor ->
            val weeks = ProjectionsRepository(executor).rosWeeks(2026).single()
            assertEquals("WR", weeks.position)
            assertEquals(setOf(4, 6), weeks.weeks.keys)
            // PPR: a catch is a point, ten yards a point.
            assertEquals(mapOf(4 to 11.0, 6 to 4.0), weeks.points(ScoringPresets.PPR).mapValues { Math.round(it.value * 10) / 10.0 })
        }
    }

    @Test
    fun `rosWeeks is empty on a database built before the table existed`() = runTest {
        jdbcFixtureWithSchema(emptyList(), withRosWeeks = false).use { executor ->
            assertEquals(emptyList<Any>(), ProjectionsRepository(executor).rosWeeks(2026))
        }
    }

    @Test
    fun `a game's line and the games left are read from the team's side`() = runTest {
        jdbcFixtureWithSchema(
            listOf(
                "INSERT INTO game VALUES ('g4', 2026, 4, 'REG', 'BUF', 'KC', 2.5, 47.5)",
                "INSERT INTO game VALUES ('g5', 2026, 5, 'REG', 'KC', 'DEN', NULL, NULL)",
                "INSERT INTO game VALUES ('g19', 2026, 19, 'WC', 'KC', 'MIA', NULL, NULL)",
            ),
        ).use { executor ->
            val repo = ProjectionsRepository(executor)
            val line = repo.game(2026, 4, "KC")!!
            assertEquals("BUF", line.opponent)
            assertEquals(false, line.home)
            assertEquals(-2.5, line.favoredBy)
            assertEquals(47.5, line.total)
            assertEquals(null, repo.game(2026, 6, "KC"))
            assertEquals(2, repo.remainingGames(2026, 4, "KC"))
            // KC plays weeks 4 and 5 of the regular season; BUF and DEN miss one each.
            assertEquals(mapOf("BUF" to setOf(5), "KC" to emptySet<Int>(), "DEN" to setOf(4)), repo.byeWeeks(2026))
        }
    }

    @Test
    fun `ESPN's week reads as listed projections, and an older database without it reads nothing`() = runTest {
        jdbcFixtureWithSchema(
            insertProjectionRows = listOf(
                "CREATE TABLE espn_projection (player_id TEXT NOT NULL, season INTEGER NOT NULL, week INTEGER NOT NULL, metric_id TEXT NOT NULL, value REAL NOT NULL, PRIMARY KEY (player_id, season, week, metric_id))",
                "INSERT INTO player VALUES ('P1', 'Pat One', 'WR', 'KC')",
                "INSERT INTO espn_projection VALUES ('P1', 2026, 6, 'receptions', 5.5)",
                "INSERT INTO espn_projection VALUES ('P1', 2026, 6, 'receiving_yards', 70.0)",
                "INSERT INTO espn_projection VALUES ('P1', 2026, 5, 'receptions', 9.0)",
            ),
        ).use { executor ->
            val listed = ProjectionsRepository(executor).espnWeek(2026, 6)
            assertEquals(1, listed.size)
            assertEquals("Pat One", listed.single().name)
            assertEquals(mapOf("receptions" to 5.5, "receiving_yards" to 70.0), listed.single().components.associate { it.metricId to it.mean })
            assertEquals(0.0, listed.single().components.sumOf { it.variance })
        }
        jdbcFixtureWithSchema(emptyList()).use { executor ->
            assertEquals(emptyList<Any>(), ProjectionsRepository(executor).espnWeek(2026, 6))
        }
    }

    @Test
    fun `rosDiscounted names who nflverse lists Out or Doubtful that week, and nothing on failure`() = runTest {
        jdbcFixtureWithSchema(
            insertProjectionRows = listOf(
                "CREATE TABLE injury_report (player_id TEXT NOT NULL, season INTEGER NOT NULL, week INTEGER NOT NULL, status TEXT)",
                "INSERT INTO injury_report VALUES ('P1', 2026, 6, 'Out')",
                "INSERT INTO injury_report VALUES ('P2', 2026, 6, 'Doubtful')",
                "INSERT INTO injury_report VALUES ('P3', 2026, 6, 'Questionable')",
                "INSERT INTO injury_report VALUES ('P4', 2026, 5, 'Out')",
            ),
        ).use { executor ->
            assertEquals(mapOf("P1" to "Out", "P2" to "Doubtful"), ProjectionsRepository(executor).rosDiscounted(2026, 6))
        }
        jdbcFixtureWithSchema(emptyList()).use { executor ->
            assertEquals(emptyMap<String, String>(), ProjectionsRepository(executor).rosDiscounted(2026, 6))
        }
    }
}
