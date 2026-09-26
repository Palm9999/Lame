package dev.gridiron.core.forecast

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.Instant
import java.util.Locale

/**
 * Re-runs the forecast on a copy of CI's three-season database and prints
 * how long it took: the JVM stand-in for the spec's under-30-seconds-on-the-
 * phone target, which the refresh toast measures on the device.
 */
@EnabledIfEnvironmentVariable(named = "GRIDIRON_STATS_DB", matches = ".+")
class ForecastTimingTest {
    @TempDir
    lateinit var dir: File

    @Test
    fun `a full forecast of the CI database finishes well under a minute`() {
        val source = File(System.getenv("GRIDIRON_STATS_DB"))
        assumeTrue(source.isFile, "no database at $source")
        val copy = source.copyTo(File(dir, "stats.db"))
        BundledSQLiteDriver().open(copy.path).use { conn ->
            for (table in listOf("player_week_projection", "player_week_projection_factor", "player_ros_projection")) {
                conn.execSQL("DELETE FROM $table")
            }
            val started = System.nanoTime()
            val report = Forecast.run(conn, Instant.now())
            val seconds = (System.nanoTime() - started) / 1e9
            println(String.format(Locale.US, "forecast: %d weeks, %d rows in %.1f s", report.weeks, report.rows, seconds))

            assertEquals(FORECAST_OK, report.status)
            assertTrue(seconds < 60, "took $seconds s")
        }
    }
}
