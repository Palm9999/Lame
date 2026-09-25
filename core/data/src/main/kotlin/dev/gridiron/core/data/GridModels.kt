package dev.gridiron.core.data

import dev.gridiron.core.model.CompareSlot
import dev.gridiron.core.model.ScoringPresets
import dev.gridiron.core.model.ScoringProfile
import dev.gridiron.core.model.WeekRange
import dev.gridiron.core.statquery.Direction
import dev.gridiron.core.statquery.Filter
import dev.gridiron.core.statquery.StatColumn
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.persistentListOf

public data class Catalog(
    val seasons: ImmutableList<SeasonInfo>,
    val metrics: ImmutableMap<String, MetricInfo>,
    /** Current team abbreviations, alphabetical. */
    val teams: ImmutableList<String> = persistentListOf(),
) {
    public val latest: SeasonInfo get() = seasons.last()

    public fun season(year: Int): SeasonInfo = seasons.first { it.season == year }
}

/** A season in the database and the last week that has data. */
public data class SeasonInfo(val season: Int, val lastWeek: Int) {
    /** Regular season, cut off at the last week actually played. */
    public val defaultWeeks: WeekRange
        get() = WeekRange(1, minOf(WeekRange.lastRegularSeasonWeek(season), lastWeek))
}

/** Display metadata for a stat, from the ETL's metric table. */
public data class MetricInfo(
    val id: String,
    val name: String,
    val abbr: String,
    val definition: String,
    val formula: String?,
    val predicts: String?,
    val stability: Double?,
)

/**
 * What the Grid screen is asking for.
 *
 * @property teams Current-team abbreviations; empty means every team.
 * @property minSnapShare Snap-share floor as a fraction, one of [SNAP_SHARE_CHOICES]; null means any.
 * @property filters Advanced filters, ANDed, in display units already converted
 *   to stored units (see [FilterUnits]).
 */
public data class GridRequest(
    val season: SeasonInfo,
    val weeks: WeekRange,
    val pack: StatPack,
    val positions: PositionFilter = PositionFilter.ALL,
    val sort: StatColumn = pack.defaultSort,
    val direction: Direction = defaultDirection(sort),
    val perGame: Boolean = false,
    val name: String = "",
    val scoring: ScoringProfile = ScoringPresets.PPR,
    val teams: Set<String> = emptySet(),
    val minSnapShare: Double? = null,
    val filters: List<Filter> = emptyList(),
) {
    init {
        require(filters.size <= MAX_FILTERS) { "at most $MAX_FILTERS filters, got ${filters.size}" }
        require(minSnapShare == null || minSnapShare in SNAP_SHARE_CHOICES) { "snap share $minSnapShare not offered" }
    }

    /** Weeks in the range that have actually been played. */
    public val playedWeeks: Int
        get() = (minOf(weeks.last, season.lastWeek) - weeks.first + 1).coerceAtLeast(1)

    public companion object {
        public const val MAX_FILTERS: Int = 8
        public val SNAP_SHARE_CHOICES: List<Double> = listOf(0.25, 0.5, 0.75)

        /** Best first: most yards, fewest interceptions. */
        public fun defaultDirection(column: StatColumn): Direction =
            if (column.higherIsBetter) Direction.DESCENDING else Direction.ASCENDING
    }
}

public data class GridPage(
    val request: GridRequest,
    val columns: ImmutableList<ColumnUi>,
    val rows: ImmutableList<GridRowUi>,
    /** Who qualifies to be ranked, described for display; null if everyone. */
    val threshold: String?,
)

public data class ColumnUi(val column: StatColumn, val header: String, val info: MetricInfo?)

public data class GridRowUi(
    val playerId: String,
    val name: String,
    /** "WR · KC · 17 g" */
    val detail: String,
    val position: String?,
    val team: String?,
    val games: Int,
    val cells: ImmutableList<CellUi>,
)

/**
 * @property heat Positional percentile mapped to -1..1: +1 best at the
 *   position, -1 worst, null when there's no value to rank.
 */
public data class CellUi(val text: String, val heat: Float?)

public data class PlayerHeader(val playerId: String, val name: String, val position: String?, val team: String?)

/** A tray chip: the player's name and a short "2025 · Wk 1–8". */
public data class TraySlotUi(val slot: CompareSlot, val name: String, val detail: String)
