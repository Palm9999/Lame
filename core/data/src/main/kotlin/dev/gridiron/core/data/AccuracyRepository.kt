package dev.gridiron.core.data

import dev.gridiron.core.database.QueryExecutor
import dev.gridiron.core.database.doubleOrNull
import dev.gridiron.core.statquery.Bind
import dev.gridiron.core.statquery.SqlQuery

public data class AccuracyRow(
    val position: String,
    val metricId: String,
    val baseline: String,
    val sampleN: Int,
    val mae: Double,
    val rmse: Double,
    val bias: Double,
    val r2: Double?,
)

public class AccuracyRepository(private val executor: QueryExecutor) {
    public suspend fun summary(season: Int): List<AccuracyRow> = executor.query(
        SqlQuery(
            """
            SELECT position, metric_id, baseline, sample_n, mae, rmse, bias, r2
            FROM accuracy_summary
            WHERE season = ?
            ORDER BY position, metric_id, baseline
            """.trimIndent(),
            listOf(Bind.Integer(season.toLong())),
        ),
    ) {
        AccuracyRow(
            position = it.text(0),
            metricId = it.text(1),
            baseline = it.text(2),
            sampleN = it.long(3).toInt(),
            mae = it.double(4),
            rmse = it.double(5),
            bias = it.double(6),
            r2 = it.doubleOrNull(7),
        )
    }
}
