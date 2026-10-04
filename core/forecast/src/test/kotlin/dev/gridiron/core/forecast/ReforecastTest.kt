package dev.gridiron.core.forecast

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.io.File
import java.time.Instant

/** Scratch, never committed. */
@EnabledIfEnvironmentVariable(named = "REFORECAST_OUT", matches = ".+")
class ReforecastTest {
    @Test
    fun run() {
        val out = File(System.getenv("REFORECAST_OUT"))
        File(System.getenv("REFORECAST_SRC")).copyTo(out, overwrite = true)
        BundledSQLiteDriver().open(out.path).use { conn ->
            for (t in listOf("player_week_projection", "player_week_projection_factor", "player_ros_projection", "player_week_signal")) conn.execSQL("DELETE FROM $t")
            val r = Forecast.run(conn, Instant.now())
            println("reforecast ${r.status} ${r.weeks} ${r.rows}")
            check(r.status == FORECAST_OK)
        }
    }
}
