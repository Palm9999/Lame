package dev.gridiron.core.data

import dev.gridiron.core.model.WeekRange
import dev.gridiron.core.statquery.Direction
import dev.gridiron.core.statquery.StatColumn
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap

public data class Catalog(
    val seasons: ImmutableList<SeasonInfo>,
    val metrics: ImmutableMap<String, MetricInfo>,
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

/** What the Grid screen is asking for. */
public data class GridRequest(
    val season: SeasonInfo,
    val weeks: WeekRange,
    val pack: StatPack,
    val positions: PositionFilter = PositionFilter.ALL,
    val sort: StatColumn = pack.defaultSort,
    val direction: Direction = defaultDirection(sort),
    val perGame: Boolean = false,
    val name: String = "",
) {
    /** Weeks in the range that have actually been played. */
    public val playedWeeks: Int
        get() = (minOf(weeks.last, season.lastWeek) - weeks.first + 1).coerceAtLeast(1)

    public companion object {
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
    val cells: ImmutableList<CellUi>,
)

/**
 * @property heat Positional percentile mapped to -1..1: +1 best at the
 *   position, -1 worst, null when there's no value to rank.
 */
public data class CellUi(val text: String, val heat: Float?)
