package dev.gridiron.core.forecast

import java.util.Locale

/** The stats ESPN projects, in our metric ids (ingest maps ESPN's codes to these). */
private val ESPN_METRICS = setOf(
    "attempts", "completions", "passing_yards", "passing_tds", "passing_2pt", "interceptions", "sacks_taken", "passing_first_downs",
    "carries", "rushing_yards", "rushing_tds", "rushing_2pt", "rushing_first_downs",
    "targets", "receptions", "receiving_yards", "receiving_tds", "receiving_2pt", "receiving_first_downs", "fumbles_lost",
)

/** Long TDs ESPN doesn't project: they keep their share of their kind's TDs. */
private val LONG_TDS = mapOf(
    "passing_tds_40" to "passing_tds", "passing_tds_50" to "passing_tds",
    "rushing_tds_40" to "rushing_tds", "rushing_tds_50" to "rushing_tds",
    "receiving_tds_40" to "receiving_tds", "receiving_tds_50" to "receiving_tds",
)

/**
 * Layer 7: [final] mixed with ESPN's projection for the same week, [K.ESPN_WEIGHT] of the way toward ESPN's by
 * position. A stat ESPN leaves out of a projection it made is zero. Without one (ESPN has no projection, or
 * projects nothing because it expects him out), the model's stands alone.
 */
internal fun withEspn(final: Map<String, Double>, espn: Map<String, Double>?, position: String): Map<String, Double> {
    val w = K.ESPN_WEIGHT[position] ?: return final
    if (espn.isNullOrEmpty()) return final
    val blended = final.mapValuesTo(LinkedHashMap()) { (metric, mean) ->
        if (metric in ESPN_METRICS) (1 - w) * mean + w * (espn[metric] ?: 0.0) else mean
    }
    for ((long, kind) in LONG_TDS) {
        val before = final[kind] ?: continue
        val mean = final[long] ?: continue
        blended[long] = if (before > 0.0) mean * blended.getValue(kind) / before else 0.0
    }
    return blended
}

/** "ESPN projects 14.2 pts": ESPN's projection in the reference scoring, for the waterfall. */
internal fun espnNote(espn: Map<String, Double>): String = String.format(Locale.US, "ESPN projects %.1f pts", referencePoints(espn))
