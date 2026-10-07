package dev.gridiron.core.statquery

import dev.gridiron.core.model.Position
import dev.gridiron.core.model.ScoringProfile
import dev.gridiron.core.model.WeekRange

/**
 * Everything the Grid can ask for. Every screen state derives from one of these,
 * and the builder turns it into SQL deterministically: equal specs always yield
 * identical SQL and binds, so results can be cached by spec.
 *
 * @property columns Displayed columns, in display order.
 * @property sort Sort keys in priority order. Empty means the first column, descending.
 * @property positions Empty means every position.
 * @property excludedPositions Never these positions. Players with no position are kept.
 * @property teams Team abbreviations. Empty means every team.
 * @property playerIds Only these players. Applied after percentiles, like [filters], so it never moves a percentile.
 * @property excludedPlayerIds Never these players. Applied after percentiles, like [playerIds].
 * @property filters Narrow the view. Applied to values as displayed, so per-game
 *   in [ValueMode.PER_GAME], and after percentiles, so they never move one.
 * @property qualifiers Define who is ranked: percentiles are computed among
 *   players meeting every qualifier ("54+ targets"), so a starter isn't
 *   flattered by comparison with a backup who saw one target.
 * @property includeUnqualified Also return players who miss a qualifier, with
 *   no percentile. For name search, which should find anyone.
 * @property alwaysShow These players are returned even when they miss a qualifier or [minGames], unranked, like a search
 *   hit. They never join the ranked population, so they move no one's percentile.
 * @property minGames Population floor, applied before percentiles are computed.
 * @property percentiles Adds a 0..1 positional percentile beside each column, 1 = best.
 * @property ranks Adds each column's place among the ranked players (1 = best, ties share one) and how many were
 *   ranked, after its percentile. Needs [percentiles].
 * @property name Free-text player name filter, matched on name and word prefixes.
 * @property scoring The profile fantasy columns are scored with. Required when
 *   any column, sort, filter or qualifier is a fantasy column.
 * @property rollups The pre-aggregated windows the database holds for [season]. When [weeks] equals one, the component sums
 *   are read from the rollup instead of the weekly facts; fantasy points weight those sums and read weekly facts
 *   only for yardage bonuses and points- and yards-allowed tiers.
 */
public data class StatQuerySpec(
    val season: Int,
    val weeks: WeekRange,
    val columns: List<StatColumn>,
    val sort: List<Sort> = emptyList(),
    val positions: Set<Position> = emptySet(),
    val excludedPositions: Set<Position> = emptySet(),
    val teams: Set<String> = emptySet(),
    val playerIds: Set<String> = emptySet(),
    val excludedPlayerIds: Set<String> = emptySet(),
    val filters: List<Filter> = emptyList(),
    val qualifiers: List<Filter> = emptyList(),
    val includeUnqualified: Boolean = false,
    val alwaysShow: Set<String> = emptySet(),
    val minGames: Int = 1,
    val mode: ValueMode = ValueMode.TOTAL,
    val percentiles: Boolean = false,
    val ranks: Boolean = false,
    val name: String? = null,
    val limit: Int = DEFAULT_LIMIT,
    val offset: Int = 0,
    val scoring: ScoringProfile? = null,
    val rollups: List<RollupWindow> = emptyList(),
    /** [StatColumn.DYNASTY_VALUE]'s value by player id; a player without one reads blank. */
    val external: Map<String, Double> = emptyMap(),
) {
    init {
        require(season in MIN_SEASON..MAX_SEASON) { "season $season outside $MIN_SEASON..$MAX_SEASON" }
        require(columns.isNotEmpty()) { "at least one column is required" }
        require(columns.toSet().size == columns.size) { "duplicate columns: $columns" }
        require(sort.map { it.column }.toSet().size == sort.size) { "duplicate sort keys: $sort" }
        require(limit in 1..MAX_LIMIT) { "limit $limit outside 1..$MAX_LIMIT" }
        require(offset >= 0) { "offset must not be negative" }
        require(minGames >= 0) { "minGames must not be negative" }
        require(!ranks || percentiles) { "ranks need percentiles" }
        val usesFantasy = (columns + sort.map { it.column } + filters.map { it.column } + qualifiers.map { it.column })
            .any { it.isFantasy }
        require(!usesFantasy || scoring != null) { "fantasy columns need a scoring profile" }
        require(external.size <= MAX_EXTERNAL) { "at most $MAX_EXTERNAL external values, got ${external.size}" }
        require(external.values.all { it.isFinite() }) { "external values must be finite" }
    }

    public companion object {
        public const val DEFAULT_LIMIT: Int = 100
        public const val MAX_LIMIT: Int = 1000

        /** Two binds each, well under SQLite's 32,766. */
        public const val MAX_EXTERNAL: Int = 5000

        /** First season of nflverse play-by-play. */
        public const val MIN_SEASON: Int = 1999
        public const val MAX_SEASON: Int = 2100
    }
}

/** A window of `player_window_stat` (`S`, `L3`, ...) and the weeks its sums cover. */
public data class RollupWindow(val window: String, val weeks: WeekRange)

public data class Sort(val column: StatColumn, val direction: Direction = Direction.DESCENDING)

public enum class Direction { ASCENDING, DESCENDING }

public enum class ValueMode {
    /** Sums over the range; rates recomputed from summed components. */
    TOTAL,

    /** Counting columns divided by games played. Rates are unaffected. */
    PER_GAME,
}

public data class Filter(val column: StatColumn, val condition: Condition)

public sealed interface Condition {
    public data class AtLeast(val value: Double) : Condition {
        init { requireFinite(value) }
    }

    public data class AtMost(val value: Double) : Condition {
        init { requireFinite(value) }
    }

    public data class GreaterThan(val value: Double) : Condition {
        init { requireFinite(value) }
    }

    public data class LessThan(val value: Double) : Condition {
        init { requireFinite(value) }
    }

    public data class Between(val min: Double, val max: Double) : Condition {
        init {
            requireFinite(min)
            requireFinite(max)
            require(min <= max) { "min $min exceeds max $max" }
        }
    }
}

private fun requireFinite(value: Double) {
    require(value.isFinite()) { "filter value must be finite, was $value" }
}
