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
 * The weekly average is stored too, under the metric's own id. A missing
 * average or a weight of 0 stores nothing: absent means no NGS data.
 */

private val KEY_COLUMNS = listOf("season", "week", "team_abbr", "player_gsis_id")

/** One published average: stored as `column x weight` in [sum], and as itself under the visible id [visible]. */
private class NgsAverage(val column: String, val sum: String, val visible: String)

/**
 * A total stored as is under [component] (RYOE), and per weight under [perWeight]. Null before NGS's RYOE model
 * (2018), when the column is empty.
 */
private class NgsTotal(val column: String, val component: String, val perWeight: String)

/** A weight column and the averages it scales. */
private class NgsFile(
    val weightColumn: String,
    val weightComponent: String,
    val averages: List<NgsAverage>,
    val totals: List<NgsTotal> = emptyList(),
)

private val PASSING = NgsFile(
    "attempts", "ngs_attempts",
    listOf(
        NgsAverage("avg_time_to_throw", "ngs_ttt_w", "ngs_time_to_throw"),
        NgsAverage("aggressiveness", "ngs_aggr_w", "ngs_aggressiveness"),
        NgsAverage("avg_intended_air_yards", "ngs_iay_w", "ngs_intended_air_yards"),
    ),
)
private val RUSHING = NgsFile(
    "rush_attempts", "ngs_carries",
    listOf(
        NgsAverage("efficiency", "ngs_eff_w", "ngs_rush_efficiency"),
        NgsAverage("percent_attempts_gte_eight_defenders", "ngs_box_w", "ngs_stacked_box_pct"),
    ),
    totals = listOf(NgsTotal("rush_yards_over_expected", "ngs_ryoe", "ngs_ryoe_per_att")),
)

/** Receiving has two weights: separation and cushion are per target, YAC over expected per reception. */
private val RECEIVING_TARGETS = NgsFile(
    "targets", "ngs_targets",
    listOf(
        NgsAverage("avg_cushion", "ngs_cush_w", "ngs_cushion"),
        NgsAverage("avg_separation", "ngs_sep_w", "ngs_separation"),
    ),
)
private val RECEIVING_RECEPTIONS = NgsFile(
    "receptions", "ngs_receptions",
    listOf(NgsAverage("avg_yac_above_expectation", "ngs_yacoe_w", "ngs_yac_over_expected")),
)

internal fun readNgsPassing(input: InputStream, source: String): List<PlayerWeek> = readNgs(input, source, listOf(PASSING))

internal fun readNgsRushing(input: InputStream, source: String): List<PlayerWeek> = readNgs(input, source, listOf(RUSHING))

internal fun readNgsReceiving(input: InputStream, source: String): List<PlayerWeek> =
    readNgs(input, source, listOf(RECEIVING_TARGETS, RECEIVING_RECEPTIONS))

private fun readNgs(input: InputStream, source: String, files: List<NgsFile>): List<PlayerWeek> = buildList {
    val columns = KEY_COLUMNS + files.flatMap { f -> listOf(f.weightColumn) + f.averages.map { it.column } + f.totals.map { it.column } }
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
    for (a in file.averages) double(a.column)?.let {
        into[a.sum] = it * weight
        into[a.visible] = it
    }
    for (t in file.totals) double(t.column)?.let {
        into[t.component] = it
        into[t.perWeight] = it / weight
    }
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
