package dev.gridiron.core.ingest

import dev.gridiron.core.ingest.csv.readCsv
import dev.gridiron.core.ingest.pbp.PlayerWeek
import java.io.InputStream

/**
 * ffopportunity column to our expected component. Actual counterparts come
 * from play-by-play; only the model's expectations are taken from this file.
 * rec_interception_exp is deliberately not charged to receivers.
 */
internal val EXPECTED_COLUMNS: Map<String, String> = linkedMapOf(
    "pass_completions_exp" to "x_completions",
    "receptions_exp" to "x_receptions",
    "pass_yards_gained_exp" to "x_passing_yards",
    "rush_yards_gained_exp" to "x_rushing_yards",
    "rec_yards_gained_exp" to "x_receiving_yards",
    "pass_touchdown_exp" to "x_passing_tds",
    "rush_touchdown_exp" to "x_rushing_tds",
    "rec_touchdown_exp" to "x_receiving_tds",
    "pass_two_point_conv_exp" to "x_passing_2pt",
    "rush_two_point_conv_exp" to "x_rushing_2pt",
    "rec_two_point_conv_exp" to "x_receiving_2pt",
    "pass_first_down_exp" to "x_passing_first_downs",
    "rush_first_down_exp" to "x_rushing_first_downs",
    "rec_first_down_exp" to "x_receiving_first_downs",
    "pass_interception_exp" to "x_interceptions",
)

/** ffopportunity's own actuals and totals, read only to cross-check ours. */
internal val EXPECTED_ACTUAL_COLUMNS: List<String> = listOf(
    "pass_completions", "receptions", "pass_yards_gained", "rec_yards_gained",
    "rush_yards_gained", "pass_touchdown", "rec_touchdown", "rush_touchdown",
    "pass_two_point_conv", "rec_two_point_conv", "rush_two_point_conv",
    "pass_first_down", "rec_first_down", "rush_first_down", "pass_interception",
    "rec_fumble_lost", "rush_fumble_lost", "total_fantasy_points", "total_fantasy_points_exp",
)

internal class ExpectedRow(
    val playerId: String,
    val season: Int,
    val week: Int,
    val team: String?,
    val values: Map<String, Double?>,
) {
    /** An ffopportunity column, null as 0 like polars' `fill_null(0)`. */
    fun value(column: String): Double = values[column] ?: 0.0

    /** Our expected components, keyed like a weekly row, for the fact table. */
    fun toPlayerWeek(): PlayerWeek = PlayerWeek(
        season, week, team, playerId,
        EXPECTED_COLUMNS.entries.associateTo(LinkedHashMap<String, Double?>()) { (src, dst) -> dst to value(src) },
    )
}

/** ffopportunity's weekly file. Season is text and week a float there; rows without a player are dropped. */
internal fun readExpected(input: InputStream, source: String): List<ExpectedRow> = buildList {
    val numeric = EXPECTED_COLUMNS.keys.toList() + EXPECTED_ACTUAL_COLUMNS
    readCsv(input, source, listOf("season", "week", "player_id", "posteam") + numeric) { row ->
        val playerId = row.text("player_id") ?: return@readCsv
        val season = row.int("season") ?: return@readCsv
        val week = row.int("week") ?: return@readCsv
        add(ExpectedRow(playerId, season, week, row.text("posteam"), numeric.associateWith { row.double(it) }))
    }
}
