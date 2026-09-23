package dev.gridiron.core.data

import dev.gridiron.core.database.QueryExecutor
import dev.gridiron.core.database.doubleOrNull
import dev.gridiron.core.database.textOrNull
import dev.gridiron.core.statquery.CatalogQueries
import dev.gridiron.core.statquery.Condition
import dev.gridiron.core.statquery.Filter
import dev.gridiron.core.statquery.GridLayout
import dev.gridiron.core.statquery.Sort
import dev.gridiron.core.statquery.StatColumn
import dev.gridiron.core.statquery.StatQueryBuilder
import dev.gridiron.core.statquery.StatQuerySpec
import dev.gridiron.core.statquery.ValueMode
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableMap
import java.util.Locale

public class StatsRepository(
    private val executor: QueryExecutor,
    locale: Locale = Locale.getDefault(),
) {
    private val format = StatFormat(locale)

    public suspend fun catalog(): Catalog {
        val seasons = executor.query(CatalogQueries.seasons) { SeasonInfo(it.long(0).toInt(), it.long(1).toInt()) }
        check(seasons.isNotEmpty()) { "the stats database has no seasons" }
        val metrics = executor.query(CatalogQueries.metrics) {
            MetricInfo(
                id = it.text(0),
                name = it.text(1),
                abbr = it.text(2),
                definition = it.text(3),
                formula = it.textOrNull(4),
                predicts = it.textOrNull(5),
                stability = it.doubleOrNull(6),
            )
        }
        val teams = executor.query(CatalogQueries.teams) { it.text(0) }
        return Catalog(seasons.toImmutableList(), metrics.associateBy { it.id }.toImmutableMap(), teams.toImmutableList())
    }

    public suspend fun grid(request: GridRequest, catalog: Catalog): GridPage {
        val threshold = threshold(request)
        val spec = spec(request, threshold)
        val q = StatQueryBuilder.grid(spec)
        val layout = q.layout

        val rows = executor.query(q.query) { r ->
            val games = r.long(GridLayout.GAMES)
            val position = r.textOrNull(GridLayout.POSITION) ?: "–"
            val team = r.textOrNull(GridLayout.TEAM) ?: "FA"
            GridRowUi(
                playerId = r.text(GridLayout.PLAYER_ID),
                name = r.text(GridLayout.FULL_NAME),
                detail = "$position · $team · $games g",
                cells = spec.columns.map { column ->
                    val pct = r.doubleOrNull(layout.percentileIndex(column))
                    CellUi(
                        text = format.format(column, r.doubleOrNull(layout.valueIndex(column)), request.perGame),
                        heat = pct?.let { ((it - 0.5) * 2).toFloat() },
                    )
                }.toImmutableList(),
            )
        }

        val columns = spec.columns.map { column ->
            val info = catalog.metrics[column.metricId]
            ColumnUi(column, info?.abbr ?: column.metricId, info)
        }
        return GridPage(request, columns.toImmutableList(), rows.toImmutableList(), threshold?.description)
    }

    public suspend fun players(ids: Collection<String>): Map<String, PlayerHeader> = executor.playerHeaders(ids)

    private fun spec(request: GridRequest, threshold: SampleThreshold?): StatQuerySpec {
        val searching = request.name.isNotBlank()
        val snap = request.minSnapShare?.let { Filter(StatColumn.SNAP_SHARE, Condition.AtLeast(it)) }
        return StatQuerySpec(
            season = request.season.season,
            weeks = request.weeks,
            columns = request.pack.columns,
            sort = listOf(Sort(request.sort, request.direction)),
            positions = request.positions.positions,
            teams = request.teams,
            // Unlike the sample qualifier, these are the user's own choices, so a search keeps them.
            filters = listOfNotNull(snap) + request.filters,
            qualifiers = listOfNotNull(threshold?.qualifier),
            // A search should find anyone; players below the bar come back unranked.
            includeUnqualified = searching,
            minGames = if (searching) 1 else threshold?.minGames ?: 1,
            mode = if (request.perGame) ValueMode.PER_GAME else ValueMode.TOTAL,
            percentiles = true,
            name = request.name.takeIf { searching },
            limit = StatQuerySpec.MAX_LIMIT,
            scoring = request.scoring,
        )
    }

    private fun threshold(request: GridRequest): SampleThreshold? =
        SampleThreshold.forRequest(request.sort, request.pack, request.playedWeeks, request.perGame)

    /** How many players [request] matches, ignoring the page limit. Backs the filter sheet's live count. */
    public suspend fun count(request: GridRequest): Int =
        executor.query(StatQueryBuilder.count(spec(request, threshold(request)))) { it.long(0).toInt() }.single()
}
