package dev.gridiron.core.ingest

import dev.gridiron.core.ingest.pbp.PlayerWeek

/** One row of `player_week_stat`. */
internal data class Fact(
    val playerId: String,
    val season: Int,
    val week: Int,
    val team: String?,
    val metricId: String,
    val value: Double,
)

/**
 * Unpivots to the long fact shape the database stores, like `transform.to_long`:
 * nulls are dropped, and zeros are dropped for [sparse] metrics (absent means zero).
 */
internal fun toFacts(
    rows: List<PlayerWeek>,
    metricIds: List<String> = METRICS.map { it.id },
    sparse: Set<String> = SPARSE_METRIC_IDS,
): List<Fact> = buildList {
    for (row in rows) {
        for (id in metricIds) {
            val value = row.values[id] ?: continue
            if (value == 0.0 && id in sparse) continue
            add(Fact(row.playerId, row.season, row.week, row.team, id, value))
        }
    }
}
