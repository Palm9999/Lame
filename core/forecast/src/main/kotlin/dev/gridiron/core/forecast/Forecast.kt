package dev.gridiron.core.forecast

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import java.io.File
import java.time.Instant

/** `schema_meta`'s `forecast_status` when projections were built. */
public const val FORECAST_OK: String = "ok"

/** What a forecast did, for the refresh report. */
public data class ForecastReport(
    /** [FORECAST_OK], or why there are no projections. */
    public val status: String,
    /** The upcoming week projected, by season. Empty off-season. */
    public val upcoming: Map<Int, Int>,
    /** Weeks projected here, not counting copied seasons or rest-of-season weeks. */
    public val weeks: Int,
    public val rows: Long,
    /** How props went; null when none were given. */
    public val props: PropsOutcome? = null,
)

/** Seasons whose weekly projections are copied from [previous] instead of recomputed. */
public data class SeasonCopy(public val previous: File, public val seasons: Set<Int>)

private val PROJECTION_TABLES = listOf("player_week_projection", "player_week_projection_factor", "player_ros_projection")

public object Forecast {
    /**
     * Projects the built seasons into [conn]'s (empty) projection tables and
     * records the outcome in `schema_meta`. [props], when given, are blended
     * into the upcoming week (spec §5). [onWeek] is called before each week
     * and may throw to cancel.
     */
    public fun run(
        conn: SQLiteConnection,
        builtAt: Instant,
        copy: SeasonCopy? = null,
        props: PropsSnapshot? = null,
        onWeek: (season: Int, week: Int) -> Unit = { _, _ -> },
    ): ForecastReport {
        // ATTACH can't run inside a transaction, so copy first.
        copy?.let { copySeasons(conn, it) }
        val inputs = loadInputs(conn)
        conn.execSQL("BEGIN")
        val report = ProjectionWriter(conn).use { writer ->
            val outcome = Projector(inputs, copy?.seasons.orEmpty(), writer, props, onWeek).run()
            ForecastReport(outcome.status, outcome.upcoming, outcome.weeks, writer.rows, outcome.props)
        }
        writeMeta(conn, report.status, report.upcoming, builtAt)
        conn.execSQL("COMMIT")
        return report
    }

    /** After [run] threw: no projection rows at all, and why. */
    public fun fail(conn: SQLiteConnection, builtAt: Instant, reason: String) {
        // The build runs with the journal off, where ROLLBACK is undefined: close any open transaction, then delete.
        runCatching { conn.execSQL("COMMIT") }
        for (table in PROJECTION_TABLES) conn.execSQL("DELETE FROM $table")
        writeMeta(conn, "failed: $reason", emptyMap(), builtAt)
    }

    private fun copySeasons(conn: SQLiteConnection, copy: SeasonCopy) {
        if (copy.seasons.isEmpty()) return
        conn.prepare("ATTACH DATABASE ? AS prev").use {
            it.bindText(1, copy.previous.path)
            it.step()
        }
        try {
            for (table in listOf("player_week_projection", "player_week_projection_factor")) {
                for (season in copy.seasons) {
                    conn.prepare("INSERT OR REPLACE INTO $table SELECT * FROM prev.$table WHERE season = ?").use {
                        it.bindLong(1, season.toLong())
                        it.step()
                    }
                }
            }
        } finally {
            conn.execSQL("DETACH DATABASE prev")
        }
    }

    private fun writeMeta(conn: SQLiteConnection, status: String, upcoming: Map<Int, Int>, builtAt: Instant) {
        conn.execSQL("DELETE FROM schema_meta WHERE key LIKE 'forecast%'")
        val rows = listOf(
            "forecast_version" to FORECAST_VERSION.toString(),
            "forecast_status" to status,
            "forecast_built_at" to builtAt.toString(),
        ) + upcoming.map { (season, week) -> "forecast_week:$season" to week.toString() }
        conn.prepare("INSERT INTO schema_meta (key, value) VALUES (?, ?)").use { st ->
            for ((key, value) in rows) {
                st.bindText(1, key)
                st.bindText(2, value)
                st.step()
                st.reset()
            }
        }
    }
}
