package dev.gridiron.core.data

import dev.gridiron.core.model.Position
import dev.gridiron.core.model.ScoringPresets
import dev.gridiron.core.testing.JdbcQueryExecutor
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import java.io.File
import java.sql.DriverManager

class AccuracyRepositoryTest {

    /**
     * A fresh on-disk SQLite database with the tables the backtest reads, in
     * their v7 shape, seeded with [inserts], then reopened read-only through
     * [JdbcQueryExecutor].
     */
    private fun fixture(inserts: List<String>): JdbcQueryExecutor {
        val file = File.createTempFile("accuracy-fixture", ".db")
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
                    """CREATE TABLE player_week_stat (
                         player_id TEXT NOT NULL, season INTEGER NOT NULL, week INTEGER NOT NULL, team TEXT NOT NULL,
                         metric_id TEXT NOT NULL, value REAL NOT NULL,
                         PRIMARY KEY (player_id, season, week, metric_id)) WITHOUT ROWID""",
                )
                st.executeUpdate("CREATE TABLE metric (id TEXT PRIMARY KEY, dist_family TEXT)")
                st.executeUpdate("CREATE TABLE player (player_id TEXT PRIMARY KEY, full_name TEXT NOT NULL, position TEXT, team TEXT)")
                st.executeUpdate("CREATE TABLE schema_meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
                inserts.forEach { st.executeUpdate(it) }
            }
        }
        return JdbcQueryExecutor(file.path)
    }

    private fun meta(key: String, value: String) = "INSERT INTO schema_meta VALUES ('$key', '$value')"

    private fun player(id: String, position: String) = "INSERT INTO player VALUES ('$id', 'Player $id', '$position', 'KC')"

    /** A zero-variance final projection of [receptions] catches for 10 yards each. */
    private fun projected(id: String, season: Int, week: Int, receptions: Double) = listOf(
        "INSERT INTO player_week_projection VALUES ('$id', $season, $week, 'receptions', 'final', $receptions, 0.0)",
        "INSERT INTO player_week_projection VALUES ('$id', $season, $week, 'receiving_yards', 'final', ${receptions * 10}, 0.0)",
    )

    /** A week played ([Components.GAMES] = 1) with [receptions] catches for 10 yards each, plus [tds] receiving TDs. */
    private fun played(id: String, season: Int, week: Int, receptions: Double, tds: Double = 0.0) = listOf(
        "INSERT INTO player_week_stat VALUES ('$id', $season, $week, 'KC', 'g', 1.0)",
        "INSERT INTO player_week_stat VALUES ('$id', $season, $week, 'KC', 'receptions', $receptions)",
        "INSERT INTO player_week_stat VALUES ('$id', $season, $week, 'KC', 'receiving_yards', ${receptions * 10})",
        "INSERT INTO player_week_stat VALUES ('$id', $season, $week, 'KC', 'receiving_tds', $tds)",
    )

    @Test
    fun `seasons are the ones with a finished week projected`() = runTest {
        fixture(
            listOf(meta("forecast_status", "ok"), meta("forecast_week:2026", "1"), player("w", "WR")) +
                projected("w", 2025, 2, 6.0) +
                // 2026 has only its upcoming week so far.
                projected("w", 2026, 1, 6.0) +
                "INSERT INTO player_week_projection VALUES ('w', 2026, 1, 'receptions', 'baseline', 6.0, 0.0)",
        ).use { executor ->
            assertEquals(listOf(2025), AccuracyRepository(executor).seasons())
        }
    }

    @Test
    fun `the backtest reads past final projections and the weeks actually played`() = runTest {
        fixture(
            listOf(meta("forecast_status", "ok"), meta("forecast_week:2025", "5"), player("w", "WR"), player("k", "K")) +
                played("w", 2024, 17, 2.0) + // 4 points: last four reaches back to it
                played("w", 2025, 1, 5.0) + // 10
                played("w", 2025, 2, 8.0, tds = 1.0) + // 22: 8 catches, 80 yards, a TD
                played("w", 2025, 4, 4.0) + // 8
                played("w", 2025, 5, 10.0) + // Thursday of the upcoming week: not finished, so it doesn't count
                projected("w", 2025, 1, 6.0) + // first game of the season
                projected("w", 2025, 2, 6.0) + // 12 against 22
                projected("w", 2025, 3, 6.0) + // didn't play
                projected("w", 2025, 4, 4.0) + // 8 against 8
                projected("w", 2025, 5, 7.0) +
                // A kicker is measured too, on his own row.
                played("k", 2025, 1, 9.0) + played("k", 2025, 2, 9.0) + projected("k", 2025, 2, 9.0),
        ).use { executor ->
            val results = AccuracyRepository(executor).backtest(2025, ScoringPresets.PPR)
            assertEquals(listOf("WR", "K"), results.map { it.position })
            val wr = results.first()

            assertEquals("WR", wr.position)
            assertEquals(2, wr.playerWeeks)
            assertEquals((10.0 + 0.0) / 2, wr.model.mae, 1e-9)
            // Last four: week 2 has (4 + 10) / 2 = 7 against 22; week 4 has (4 + 10 + 22) / 3 = 12 against 8.
            assertEquals((15.0 + 4.0) / 2, wr.lastFour.mae, 1e-9)
            assertEquals(0.5, wr.calibration, 1e-9)
        }
    }

    @Test
    fun `a second backtest of the same season and profile is the kept one, a new profile computes again`() = runTest {
        fixture(
            listOf(meta("forecast_status", "ok"), meta("forecast_week:2025", "5"), player("w", "WR")) +
                played("w", 2025, 1, 5.0) + projected("w", 2025, 1, 6.0),
        ).use { executor ->
            val repo = AccuracyRepository(executor)
            val first = repo.backtest(2025, ScoringPresets.PPR)
            assertSame(first, repo.backtest(2025, ScoringPresets.PPR))
            assertNotSame(first, repo.backtest(2025, ScoringPresets.HALF_PPR))
        }
    }

    @Test
    fun `a database built before projections has no seasons and nothing to measure`() = runTest {
        fixture(emptyList()).use { executor ->
            val repo = AccuracyRepository(executor)
            assertEquals(null, repo.status().status)
            assertEquals(emptyList<Int>(), repo.seasons())
            assertEquals(emptyList<Any>(), repo.backtest(2025, ScoringPresets.PPR))
        }
    }

    @Test
    fun `the backtest widens ranges by the factors it's given`() = runTest {
        fixture(
            listOf(
                meta("forecast_status", "ok"), meta("forecast_week:2025", "5"), player("w", "WR"),
                // 12 points projected, with some spread: 22 scored lands outside the raw simulation's range.
                "INSERT INTO player_week_projection VALUES ('w', 2025, 2, 'receptions', 'final', 6.0, 9.0)",
                "INSERT INTO player_week_projection VALUES ('w', 2025, 2, 'receiving_yards', 'final', 60.0, 900.0)",
            ) + played("w", 2025, 1, 5.0) + played("w", 2025, 2, 11.0),
        ).use { executor ->
            val repo = AccuracyRepository(executor)
            suspend fun held(k: Double) = repo.backtest(2025, ScoringPresets.PPR, widening = mapOf(Position.WR to k)).single().calibration

            assertEquals(0.0, held(0.0), 0.0)
            assertEquals(1.0, held(5.0), 0.0)
        }
    }
}
