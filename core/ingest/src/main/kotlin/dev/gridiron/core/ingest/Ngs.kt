package dev.gridiron.core.ingest

import dev.gridiron.core.ingest.csv.CsvRow
import dev.gridiron.core.ingest.csv.readCsv
import dev.gridiron.core.ingest.pbp.PlayerWeek
import java.io.InputStream

/**
 * Next Gen Stats: nflverse's weekly tracking averages, one row per player-week.
 * Twin of `etl/gridiron_etl/ngs.py`.
 *
 * NGS publishes averages, so each is stored as average x weight beside its
 * weight (NGS's own attempts, carries, targets or receptions). A range then
 * recomputes as sum(avg x weight) / sum(weight), never a mean of weekly means.
 * A missing average or a weight of 0 stores nothing: absent means no NGS data.
 */

private val KEY_COLUMNS = listOf("season", "week", "team_abbr", "player_gsis_id")

/** A weight column, and the columns whose averages it scales into components. */
private class NgsFile(val weightColumn: String, val weightComponent: String, val weighted: Map<String, String>, val direct: Map<String, String> = emptyMap())

private val PASSING = NgsFile(
    "attempts", "ngs_attempts",
    linkedMapOf("avg_time_to_throw" to "ngs_ttt_w", "aggressiveness" to "ngs_aggr_w", "avg_intended_air_yards" to "ngs_iay_w"),
)
private val RUSHING = NgsFile(
    "rush_attempts", "ngs_carries",
    linkedMapOf("efficiency" to "ngs_eff_w", "percent_attempts_gte_eight_defenders" to "ngs_box_w"),
    direct = linkedMapOf("rush_yards_over_expected" to "ngs_ryoe"),
)

/** Receiving has two weights: separation and cushion are per target, YAC over expected per reception. */
private val RECEIVING_TARGETS = NgsFile(
    "targets", "ngs_targets", linkedMapOf("avg_cushion" to "ngs_cush_w", "avg_separation" to "ngs_sep_w"),
)
private val RECEIVING_RECEPTIONS = NgsFile(
    "receptions", "ngs_receptions", linkedMapOf("avg_yac_above_expectation" to "ngs_yacoe_w"),
)

internal fun readNgsPassing(input: InputStream, source: String): List<PlayerWeek> = readNgs(input, source, listOf(PASSING))

internal fun readNgsRushing(input: InputStream, source: String): List<PlayerWeek> = readNgs(input, source, listOf(RUSHING))

internal fun readNgsReceiving(input: InputStream, source: String): List<PlayerWeek> =
    readNgs(input, source, listOf(RECEIVING_TARGETS, RECEIVING_RECEPTIONS))

private fun readNgs(input: InputStream, source: String, files: List<NgsFile>): List<PlayerWeek> = buildList {
    val columns = KEY_COLUMNS + files.flatMap { listOf(it.weightColumn) + it.weighted.keys + it.direct.keys }
    readCsv(input, source, columns) { row ->
        val playerId = row.text("player_gsis_id") ?: return@readCsv
        val season = row.int("season") ?: return@readCsv
        // Week 0 is the season's aggregate row: keeping it would double-count and break week filters.
        val week = row.int("week")?.takeIf { it >= 1 } ?: return@readCsv
        val values = LinkedHashMap<String, Double?>()
        for (file in files) row.addComponents(file, values)
        if (values.isNotEmpty()) add(PlayerWeek(season, week, row.text("team_abbr"), playerId, values))
    }
}

private fun CsvRow.addComponents(file: NgsFile, into: MutableMap<String, Double?>) {
    val weight = double(file.weightColumn)?.takeIf { it > 0 } ?: return
    into[file.weightComponent] = weight
    for ((column, component) in file.weighted) double(column)?.let { into[component] = it * weight }
    for ((column, component) in file.direct) double(column)?.let { into[component] = it }
}

/**
 * NGS numbers the Super Bowl week 23, play-by-play 22. When a season's NGS has
 * no week 22 its week 23 becomes 22; a season with both keeps them as they are.
 */
internal fun remapPostseasonWeeks(rows: List<PlayerWeek>): List<PlayerWeek> {
    val hasWeek22 = rows.filter { it.week == 22 }.mapTo(HashSet()) { it.season }
    return rows.map {
        if (it.week == 23 && it.season !in hasWeek22) PlayerWeek(it.season, 22, it.team, it.playerId, it.values) else it
    }
}

/** One row per (season, week, player) from the three files, with week numbers matching play-by-play. */
internal fun mergeNgs(vararg groups: List<PlayerWeek>): List<PlayerWeek> {
    data class Key(val season: Int, val week: Int, val playerId: String)
    val merged = LinkedHashMap<Key, PlayerWeek>()
    for (row in remapPostseasonWeeks(groups.flatMap { it })) {
        val target = merged.getOrPut(Key(row.season, row.week, row.playerId)) {
            PlayerWeek(row.season, row.week, row.team, row.playerId, LinkedHashMap())
        }
        target.values.putAll(row.values)
    }
    return merged.values.toList()
}
