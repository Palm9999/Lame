package dev.gridiron.core.data

import dev.gridiron.core.database.QueryExecutor
import dev.gridiron.core.database.doubleOrNull
import dev.gridiron.core.database.textOrNull
import dev.gridiron.core.model.WeekRange
import dev.gridiron.core.statquery.Aggregate
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import java.util.Locale

public class StatsRepository(
    private val executor: QueryExecutor,
    locale: Locale = Locale.getDefault(),
    /** Emits whenever the database behind [executor] is replaced; screens reload on each value. */
    public val dataVersion: Flow<Long> = flowOf(0L),
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
            val games = r.long(GridLayout.GAMES).toInt()
            val position = r.textOrNull(GridLayout.POSITION)
            val team = r.textOrNull(GridLayout.TEAM)
            GridRowUi(
                playerId = r.text(GridLayout.PLAYER_ID),
                name = r.text(GridLayout.FULL_NAME),
                detail = "${position ?: "–"} · ${team ?: "FA"} · $games g",
                position = position,
                team = team,
                games = games,
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

    /**
     * The sorted column's last six played weeks for every row on [page]. Each
     * week reuses the Grid query itself, restricted to the page's players, so
     * a week's rate or fantasy points are exactly what the Grid shows for that
     * single week. The page already decided who is listed, so no filters apply.
     */
    public suspend fun sparklines(page: GridPage): Map<String, Sparkline> {
        val r = page.request
        val window = sparklineWeeks(r.season, r.weeks) ?: return emptyMap()
        if (page.rows.isEmpty()) return emptyMap()
        val ids = page.rows.mapTo(LinkedHashSet()) { it.playerId }
        val column = r.sort
        val byWeek = window.map { week ->
            val q = StatQueryBuilder.grid(
                StatQuerySpec(
                    season = r.season.season,
                    weeks = WeekRange.single(week),
                    columns = listOf(column),
                    playerIds = ids,
                    includeUnqualified = true,
                    minGames = 1,
                    limit = StatQuerySpec.MAX_LIMIT,
                    scoring = r.scoring,
                ),
            )
            executor.query(q.query) { row ->
                // Every returned row played that week. A total with no fact is a
                // zero the database stores sparsely, not a missing week.
                val v = row.doubleOrNull(q.layout.valueIndex(column))
                    ?: if (column.aggregate is Aggregate.Total) 0.0 else null
                row.text(GridLayout.PLAYER_ID) to v
            }.toMap()
        }
        return ids.associateWith { id ->
            val values = byWeek.map { it[id] }
            Sparkline(window, values, values.map { format.format(column, it, perGame = false) })
        }
    }

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
