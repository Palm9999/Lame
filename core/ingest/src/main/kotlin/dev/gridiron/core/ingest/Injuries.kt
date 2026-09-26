package dev.gridiron.core.ingest

import dev.gridiron.core.ingest.csv.readCsv
import java.io.InputStream

internal data class InjuryRow(
    val playerId: String,
    val season: Int,
    val week: Int,
    val team: String?,
    val name: String?,
    val position: String?,
    val status: String?,
    val injury: String?,
    val practice: String?,
)

private val INJURY_COLUMNS = listOf(
    "gsis_id", "season", "week", "team", "full_name", "position",
    "report_status", "report_primary_injury", "practice_status",
)

/** nflverse's weekly injury report, trimmed to what the app shows; the last row per player-week wins. */
internal fun readInjuries(input: InputStream, source: String): List<InjuryRow> {
    val rows = LinkedHashMap<Triple<String, Int, Int>, InjuryRow>()
    readCsv(input, source, INJURY_COLUMNS) { row ->
        val id = row.text("gsis_id") ?: return@readCsv
        val season = row.int("season") ?: return@readCsv
        val week = row.int("week") ?: return@readCsv
        rows[Triple(id, season, week)] = InjuryRow(
            id, season, week, row.text("team"), row.text("full_name"), row.text("position"),
            row.text("report_status"), row.text("report_primary_injury"), row.text("practice_status"),
        )
    }
    return rows.values.toList()
}
