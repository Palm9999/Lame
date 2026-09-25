package dev.gridiron.core.data

import dev.gridiron.core.testing.JdbcQueryExecutor
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.File
import java.sql.DriverManager

class AccuracyRepositoryTest {

    /**
     * A fresh on-disk SQLite database with the `accuracy_summary` table's real
     * schema (see `etl/gridiron_etl/schema.py`), seeded with [insertAccuracyRows],
     * then reopened read-only through [JdbcQueryExecutor] -- the same JDBC
     * fixture [ProjectionsRepositoryTest] uses.
     */
    private fun jdbcFixtureWithSchema(insertAccuracyRows: List<String>): JdbcQueryExecutor {
        val file = File.createTempFile("accuracy-fixture", ".db")
        file.deleteOnExit()
        DriverManager.getConnection("jdbc:sqlite:${file.path}").use { conn ->
            conn.createStatement().use { st ->
                st.executeUpdate(
                    """CREATE TABLE accuracy_summary (
                         position  TEXT NOT NULL, season    INTEGER NOT NULL, metric_id TEXT NOT NULL,
                         baseline  TEXT NOT NULL, sample_n  INTEGER NOT NULL, mae       REAL NOT NULL,
                         rmse      REAL NOT NULL, bias      REAL NOT NULL, r2        REAL,
                         PRIMARY KEY (position, season, metric_id, baseline)) WITHOUT ROWID""",
                )
                insertAccuracyRows.forEach { st.executeUpdate(it) }
            }
        }
        return JdbcQueryExecutor(file.path)
    }

    @Test
    fun `summary reads MAE and baseline label from accuracy_summary`() = runTest {
        jdbcFixtureWithSchema(
            insertAccuracyRows = listOf(
                "INSERT INTO accuracy_summary VALUES ('WR', 2026, 'fantasy_points', 'model', 40, 4.9, 6.1, -0.1, 0.18)",
            ),
        ).use { executor ->
            val repo = AccuracyRepository(executor)
            val rows = repo.summary(2026)
            assertEquals(1, rows.size)
            assertEquals(4.9, rows.first().mae)
            assertEquals("model", rows.first().baseline)
        }
    }
}
