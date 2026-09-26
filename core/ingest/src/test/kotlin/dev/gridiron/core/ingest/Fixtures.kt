package dev.gridiron.core.ingest

import dev.gridiron.core.ingest.pbp.PBP_COLUMNS
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

internal object Fixtures {
    fun csv(header: List<String>, rows: List<Map<String, Any?>>): String = buildString {
        append(header.joinToString(",")).append('\n')
        for (row in rows) append(header.joinToString(",") { cell(row[it]) }).append('\n')
    }

    private fun cell(value: Any?): String {
        val s = value?.toString() ?: return ""
        return if (s.any { it == ',' || it == '"' || it == '\n' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
    }

    fun gzip(text: String): ByteArray {
        val bytes = ByteArrayOutputStream()
        GZIPOutputStream(bytes).use { it.write(text.toByteArray()) }
        return bytes.toByteArray()
    }

    private val PBP_DEFAULTS: Map<String, Any?> = mapOf(
        "season" to 2025, "week" to 1, "season_type" to "REG", "game_id" to "g1",
        "posteam" to "AAA", "defteam" to "BBB", "play_type" to "pass", "pass_attempt" to 0,
        "complete_pass" to 0, "yards_gained" to 0, "pass_touchdown" to 0, "rush_touchdown" to 0,
        "interception" to 0, "sack" to 0, "qb_scramble" to 0, "yardline_100" to 50, "epa" to 0.0,
        "success" to 0, "two_point_attempt" to 0, "first_down_pass" to 0, "first_down_rush" to 0,
        "fumble_lost" to 0, "touchdown" to 0, "home_team" to "AAA", "away_team" to "BBB",
        "total_home_score" to 0, "total_away_score" to 0,
    )

    /** A play-by-play row with the Python tests' neutral defaults. */
    fun pbp(vararg overrides: Pair<String, Any?>): Map<String, Any?> = PBP_DEFAULTS + overrides

    fun pbpCsv(rows: List<Map<String, Any?>>): String = csv(PBP_COLUMNS, rows)
}
