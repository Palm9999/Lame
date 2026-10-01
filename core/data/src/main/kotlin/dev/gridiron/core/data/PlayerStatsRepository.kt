package dev.gridiron.core.data

import dev.gridiron.core.database.QueryExecutor
import dev.gridiron.core.database.ResultRow
import dev.gridiron.core.database.doubleOrNull
import dev.gridiron.core.database.textOrNull
import dev.gridiron.core.model.Position
import dev.gridiron.core.model.ScoringProfile
import dev.gridiron.core.model.WeekRange
import dev.gridiron.core.statquery.Aggregate
import dev.gridiron.core.statquery.CatalogQueries
import dev.gridiron.core.statquery.GridLayout
import dev.gridiron.core.statquery.PlayerStatsQueries
import dev.gridiron.core.statquery.StatColumn
import dev.gridiron.core.statquery.StatQueryBuilder
import dev.gridiron.core.statquery.StatQuerySpec
import dev.gridiron.core.statquery.ValueMode
import kotlinx.collections.immutable.toImmutableList
import java.util.Locale

/**
 * The Player page's "Season stats": a season line (totals, per-game values and
 * position percentiles) and a game log, both from the Grid's own query builder,
 * so every number matches what the Grid shows for the same player.
 */
public class PlayerStatsRepository(
    private val executor: QueryExecutor,
    locale: Locale = Locale.getDefault(),
) {
    private val format = StatFormat(locale)

    private class Names(val name: String, val abbr: String)

    /** One Grid row for the player: his games, each column's value, and each column's percentile if ranked. */
    private class Values(val games: Int, val values: Map<StatColumn, Double?>, val percentiles: Map<StatColumn, Float?>)

    /**
     * [playerId]'s stats for [season], or for his latest season when [season] is
     * null or isn't one he has games in; [PlayerStats.EMPTY] if he has none.
     * Scored with [scoring].
     */
    public suspend fun stats(playerId: String, position: Position?, scoring: ScoringProfile, season: Int? = null): PlayerStats {
        val seasons = executor.query(PlayerStatsQueries.seasons(playerId)) { it.long(0).toInt() }
        if (seasons.isEmpty()) return PlayerStats.EMPTY
        val chosen = season?.takeIf { it in seasons } ?: seasons.last()
        val info = executor.query(CatalogQueries.seasons) { SeasonInfo(it.long(0).toInt(), it.long(1).toInt()) }
            .firstOrNull { it.season == chosen } ?: return PlayerStats.EMPTY
        val names = executor.query(CatalogQueries.metrics) { it.text(0) to Names(it.text(1), it.text(2)) }.toMap()

        val weeks = info.defaultWeeks
        val playedWeeks = weeks.last - weeks.first + 1
        val lineColumns = CompareMetricSets.groupsFor(position).values.flatten()
        val qualifier = CompareMetricSets.qualifier(position)
        val columns = (lineColumns + qualifier).distinct()

        val total = seasonValues(playerId, position, chosen, weeks, columns, qualifier, playedWeeks, perGame = false, scoring)
        val perGame = seasonValues(playerId, position, chosen, weeks, columns, qualifier, playedWeeks, perGame = true, scoring)
        // A charted stat the player has no data for is left out: a dash or a zero there would say nothing true.
        val line = lineColumns.filter { it !in CompareMetricSets.CHARTED || total?.values?.get(it) != null }.map { column ->
            SeasonLineRow(
                column = column,
                label = names[column.metricId]?.name ?: column.metricId,
                total = format.format(column, zeroFilled(column, total?.values?.get(column)), perGame = false),
                perGame = if (hasPerGame(column)) {
                    format.format(column, zeroFilled(column, perGame?.values?.get(column)), perGame = true)
                } else {
                    ""
                },
                percentile = perGame?.percentiles?.get(column),
            )
        }

        val logColumns = PlayerStatSets.logColumns(position)
        val played = executor.query(PlayerStatsQueries.weekTeams(playerId, chosen)) { it.long(0).toInt() to it.textOrNull(1) }
            .filter { (week, _) -> week in weeks.first..weeks.last }
        val schedule = executor.query(PlayerStatsQueries.games(chosen)) {
            ScheduleGame(it.long(0).toInt(), it.text(1), it.text(2), it.intOrNull(3), it.intOrNull(4))
        }
        val log = played.map { (week, team) ->
            val values = weekValues(playerId, position, chosen, week, logColumns, scoring)
            val m = matchup(team, week, schedule)
            GameLogRow(
                week = week,
                opponent = m.opponent,
                result = m.result,
                cells = logColumns.map { c -> format.format(c, zeroFilled(c, values[c]), perGame = false) }.toImmutableList(),
            )
        }

        return PlayerStats(
            season = chosen,
            seasons = seasons.toImmutableList(),
            games = (perGame ?: total)?.games ?: 0,
            bar = SampleThreshold.forSample(qualifier, playedWeeks, perGame = true)?.description,
            ranked = perGame?.percentiles?.get(qualifier) != null,
            line = line.toImmutableList(),
            logHeaders = logColumns.map { names[it.metricId]?.abbr ?: it.metricId }.toImmutableList(),
            log = log.toImmutableList(),
        )
    }

    /**
     * One Grid row for [playerId] over [weeks]. The per-game row is ranked
     * against the position's qualified players (the way Compare ranks), and
     * falls back to an unranked row when the games floor would drop him.
     */
    private suspend fun seasonValues(
        playerId: String,
        position: Position?,
        season: Int,
        weeks: WeekRange,
        columns: List<StatColumn>,
        qualifier: StatColumn,
        playedWeeks: Int,
        perGame: Boolean,
        scoring: ScoringProfile,
    ): Values? {
        val threshold = SampleThreshold.forSample(qualifier, playedWeeks, perGame)
        fun spec(ranked: Boolean) = StatQuerySpec(
            season = season,
            weeks = weeks,
            columns = columns,
            positions = setOfNotNull(position),
            qualifiers = if (ranked) listOfNotNull(threshold?.qualifier) else emptyList(),
            includeUnqualified = true,
            minGames = if (ranked) threshold?.minGames ?: 1 else 1,
            mode = if (perGame) ValueMode.PER_GAME else ValueMode.TOTAL,
            percentiles = ranked && perGame,
            playerIds = setOf(playerId),
            limit = 1,
            scoring = scoring,
        )
        return run(spec(ranked = true), columns) ?: run(spec(ranked = false), columns)
    }

    /** [playerId]'s one-week totals for [columns]; empty if he has no row that week. */
    private suspend fun weekValues(
        playerId: String,
        position: Position?,
        season: Int,
        week: Int,
        columns: List<StatColumn>,
        scoring: ScoringProfile,
    ): Map<StatColumn, Double?> {
        val spec = StatQuerySpec(
            season = season,
            weeks = WeekRange.single(week),
            columns = columns,
            positions = setOfNotNull(position),
            includeUnqualified = true,
            playerIds = setOf(playerId),
            limit = 1,
            scoring = scoring,
        )
        return run(spec, columns)?.values.orEmpty()
    }

    private suspend fun run(spec: StatQuerySpec, columns: List<StatColumn>): Values? {
        val q = StatQueryBuilder.grid(spec)
        val layout = q.layout
        return executor.query(q.query) { r ->
            Values(
                games = r.long(GridLayout.GAMES).toInt(),
                values = columns.associateWith { r.doubleOrNull(layout.valueIndex(it)) },
                percentiles = if (spec.percentiles) {
                    columns.associateWith { r.doubleOrNull(layout.percentileIndex(it))?.toFloat() }
                } else {
                    emptyMap()
                },
            )
        }.singleOrNull()
    }

    /** A total with no stored fact is a zero the database stores sparsely, not a missing value. */
    private fun zeroFilled(column: StatColumn, value: Double?): Double? =
        value ?: if (column.aggregate is Aggregate.Total) 0.0 else null

    /** Counting stats and fantasy points have a per-game value; a rate's is just its total. */
    private fun hasPerGame(column: StatColumn): Boolean = column.aggregate is Aggregate.Total || column.aggregate is Aggregate.Scored

    private fun ResultRow.intOrNull(index: Int): Int? = if (isNull(index)) null else long(index).toInt()
}

/** One regular-season game from the `game` table. */
internal data class ScheduleGame(val week: Int, val home: String, val away: String, val homeScore: Int?, val awayScore: Int?)

/** A week's opponent and result from a player's side. */
internal data class Matchup(val opponent: String, val result: String?)

/** [team]'s game in [week], from its own side: "vs BAL" and "W 27–20", or a dash when there is no such game. */
internal fun matchup(team: String?, week: Int, schedule: List<ScheduleGame>): Matchup {
    val game = team?.let { t -> schedule.firstOrNull { it.week == week && (it.home == t || it.away == t) } }
        ?: return Matchup(StatFormat.MISSING, null)
    val home = game.home == team
    val opponent = if (home) "vs ${game.away}" else "@ ${game.home}"
    val own = if (home) game.homeScore else game.awayScore
    val theirs = if (home) game.awayScore else game.homeScore
    if (own == null || theirs == null) return Matchup(opponent, null)
    val outcome = when {
        own > theirs -> "W"
        own < theirs -> "L"
        else -> "T"
    }
    return Matchup(opponent, "$outcome $own–$theirs")
}
