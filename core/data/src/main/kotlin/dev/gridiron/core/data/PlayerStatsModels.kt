package dev.gridiron.core.data

import dev.gridiron.core.statquery.StatColumn
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

/** One stat on the Player page's season line. */
public data class SeasonLineRow(
    val column: StatColumn,
    val label: String,
    val total: String,
    /** Blank for a rate, whose per-game value is its total. */
    val perGame: String,
    /** His place among the position's qualified players (1st = best), or null when he is below the ranking bar. */
    val place: Place?,
)

/** One played week: [opponent] reads "vs DAL", "@ DAL" or a dash; [result] reads "W 27–20", or is null before a score exists. */
public data class GameLogRow(
    val week: Int,
    val opponent: String,
    val result: String?,
    val cells: ImmutableList<String>,
    /** The week's shares, one per [PlayerStats.usageHeaders], as fractions; null where the week has none. */
    val usage: ImmutableList<Double?> = persistentListOf(),
    /** The week's Next Gen Stats and FTN charting, one per [PlayerStats.chartedHeaders]; a dash where it has none. */
    val charted: ImmutableList<String> = persistentListOf(),
)

/** Everything the Player page's "Season stats" section shows for one player and season. */
public data class PlayerStats(
    val season: Int,
    /** The seasons he has games in, oldest first: the chips. Empty when he has none. */
    val seasons: ImmutableList<Int>,
    val games: Int,
    /** The qualifying bar in use ("min 3 targets per game, 4+ games"), or null where every player is ranked. */
    val bar: String?,
    /** Whether he clears the bar, so his percentiles exist. */
    val ranked: Boolean,
    val line: ImmutableList<SeasonLineRow>,
    /** The game log's column headers, one per cell. */
    val logHeaders: ImmutableList<String>,
    val log: ImmutableList<GameLogRow>,
    /** The usage charts' names, one per [GameLogRow.usage] value ([PlayerStatSets.usageColumns]). */
    val usageHeaders: ImmutableList<String> = persistentListOf(),
    /** The "Charted by week" headers, one per [GameLogRow.charted] value; empty when no week has any charted stat. */
    val chartedHeaders: ImmutableList<String> = persistentListOf(),
) {
    public companion object {
        /** A player with no games in any built season. */
        public val EMPTY: PlayerStats = PlayerStats(
            season = 0,
            seasons = persistentListOf(),
            games = 0,
            bar = null,
            ranked = false,
            line = persistentListOf(),
            logHeaders = persistentListOf(),
            log = persistentListOf(),
        )
    }
}
