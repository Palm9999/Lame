package dev.gridiron.core.forecast

import androidx.sqlite.SQLiteConnection

/** Where the projector's rows go. */
internal interface ProjectionSink {
    fun projection(playerId: String, season: Int, week: Int, metricId: String, stage: String, mean: Double, variance: Double)

    fun factor(playerId: String, season: Int, week: Int, factor: String, logMultiplier: Double, note: String?)

    fun ros(playerId: String, season: Int, asOfWeek: Int, metricId: String, mean: Double, variance: Double)

    fun rosWeek(playerId: String, season: Int, asOfWeek: Int, week: Int, metricId: String, mean: Double, variance: Double)
}

/** Inserts into the projection tables through four prepared statements. The caller owns the transaction. */
internal class ProjectionWriter(conn: SQLiteConnection) : ProjectionSink, AutoCloseable {
    private val projectionInsert = conn.prepare(
        "INSERT OR REPLACE INTO player_week_projection (player_id, season, week, metric_id, stage, mean, variance) VALUES (?, ?, ?, ?, ?, ?, ?)",
    )
    private val factorInsert = conn.prepare(
        "INSERT OR REPLACE INTO player_week_projection_factor (player_id, season, week, factor, log_multiplier, note) VALUES (?, ?, ?, ?, ?, ?)",
    )
    private val rosInsert = conn.prepare(
        "INSERT OR REPLACE INTO player_ros_projection (player_id, season, as_of_week, metric_id, mean, variance) VALUES (?, ?, ?, ?, ?, ?)",
    )

    private val rosWeekInsert = conn.prepare(
        "INSERT OR REPLACE INTO player_ros_week (player_id, season, as_of_week, week, metric_id, mean, variance) VALUES (?, ?, ?, ?, ?, ?, ?)",
    )

    var rows: Long = 0
        private set

    override fun projection(playerId: String, season: Int, week: Int, metricId: String, stage: String, mean: Double, variance: Double) {
        with(projectionInsert) {
            bindText(1, playerId)
            bindLong(2, season.toLong())
            bindLong(3, week.toLong())
            bindText(4, metricId)
            bindText(5, stage)
            bindDouble(6, mean)
            bindDouble(7, variance)
            step()
            reset()
        }
        rows++
    }

    override fun factor(playerId: String, season: Int, week: Int, factor: String, logMultiplier: Double, note: String?) {
        with(factorInsert) {
            bindText(1, playerId)
            bindLong(2, season.toLong())
            bindLong(3, week.toLong())
            bindText(4, factor)
            bindDouble(5, logMultiplier)
            if (note == null) bindNull(6) else bindText(6, note)
            step()
            reset()
        }
        rows++
    }

    override fun ros(playerId: String, season: Int, asOfWeek: Int, metricId: String, mean: Double, variance: Double) {
        with(rosInsert) {
            bindText(1, playerId)
            bindLong(2, season.toLong())
            bindLong(3, asOfWeek.toLong())
            bindText(4, metricId)
            bindDouble(5, mean)
            bindDouble(6, variance)
            step()
            reset()
        }
        rows++
    }

    override fun rosWeek(playerId: String, season: Int, asOfWeek: Int, week: Int, metricId: String, mean: Double, variance: Double) {
        with(rosWeekInsert) {
            bindText(1, playerId)
            bindLong(2, season.toLong())
            bindLong(3, asOfWeek.toLong())
            bindLong(4, week.toLong())
            bindText(5, metricId)
            bindDouble(6, mean)
            bindDouble(7, variance)
            step()
            reset()
        }
        rows++
    }

    override fun close() {
        projectionInsert.close()
        factorInsert.close()
        rosInsert.close()
        rosWeekInsert.close()
    }
}
