package dev.gridiron.core.data

import dev.gridiron.core.database.QueryExecutor
import dev.gridiron.core.database.doubleOrNull
import dev.gridiron.core.database.textOrNull
import dev.gridiron.core.model.Position
import dev.gridiron.core.model.WeekRange
import dev.gridiron.core.statquery.Aggregate
import dev.gridiron.core.statquery.CatalogQueries
import dev.gridiron.core.statquery.Condition
import dev.gridiron.core.statquery.Filter
import dev.gridiron.core.statquery.GridLayout
import dev.gridiron.core.statquery.RollupWindow
import dev.gridiron.core.statquery.Sort
import dev.gridiron.core.statquery.StatColumn
import dev.gridiron.core.statquery.StatQueryBuilder
import dev.gridiron.core.statquery.StatQuerySpec
import dev.gridiron.core.statquery.ValueMode
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import java.util.Locale

/** Kickers and team defenses: listed only under their own chips (or their own packs), never among the offense. */
private val UNITS: Set<Position> = setOf(Position.K, Position.DST)

public class StatsRepository(
    private val executor: QueryExecutor,
    locale: Locale = Locale.getDefault(),
    /** Emits whenever the database behind [executor] is replaced; screens reload on each value. */
    public val dataVersion: Flow<Long> = flowOf(0L),
) {
    private val format = StatFormat(locale)

    public suspend fun catalog(): Catalog {
        val windows = rollups().groupBy({ it.first }, { it.second })
        val seasons = executor.query(CatalogQueries.seasons) {
            val season = it.long(0).toInt()
            SeasonInfo(season, it.long(1).toInt(), windows[season].orEmpty())
        }
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
        val spec = spec(request, threshold).copy(rollups = request.season.rollups)
        val q = StatQueryBuilder.grid(spec)
        val layout = q.layout

        // An empty roster lists no one; the query would read an empty id set as everyone.
        val rows = if (request.onlyPlayers?.isEmpty() == true) emptyList() else executor.query(q.query) { r ->
            val games = r.long(GridLayout.GAMES).toInt()
            val position = r.textOrNull(GridLayout.POSITION)
            val team = r.textOrNull(GridLayout.TEAM)
            GridRowUi(
                playerId = r.text(GridLayout.PLAYER_ID),
                name = r.text(GridLayout.FULL_NAME),
                detail = "${position?.let(Position::label) ?: "–"} · ${team ?: "FA"} · $games g",
                position = position,
                team = team,
                games = games,
                cells = spec.columns.map { column ->
                    val pct = r.doubleOrNull(layout.percentileIndex(column))
                    val text = format.format(column, r.doubleOrNull(layout.valueIndex(column)), request.perGame)
                    CellUi(
                        text = text,
                        heat = pct?.let { ((it - 0.5) * 2).toFloat() },
                        display = if (StatFormat.isPercent(column)) text.removeSuffix("%") else text,
                    )
                }.toImmutableList(),
            )
        }

        val columns = spec.columns.map { column ->
            val info = catalog.metrics[column.metricId]
            val abbr = info?.abbr ?: column.metricId
            // A percent cell shows digits only, so its header must carry the %.
            ColumnUi(column, if (StatFormat.isPercent(column) && !abbr.endsWith("%")) "$abbr %" else abbr, info)
        }
        return GridPage(request, columns.toImmutableList(), rows.toImmutableList(), threshold?.description)
    }

    public suspend fun players(ids: Collection<String>): Map<String, PlayerHeader> = executor.playerHeaders(ids)

    private fun spec(request: GridRequest, threshold: SampleThreshold?): StatQuerySpec {
        val searching = request.name.isNotBlank()
        // A roster is the user's own pick, so like a search it lists everyone on it, ranked or not.
        val listing = searching || request.onlyPlayers != null
        // A K or D/ST pack is its chip's, even if a request pairs it with another chip.
        val chip = request.pack.unit ?: request.positions
        val units = chip == PositionFilter.K || chip == PositionFilter.DST
        val snap = request.minSnapShare?.takeUnless { units }?.let { Filter(StatColumn.SNAP_SHARE, Condition.AtLeast(it)) }
        return StatQuerySpec(
            season = request.season.season,
            weeks = request.weeks,
            columns = request.pack.columns,
            sort = listOf(Sort(request.sort, request.direction)),
            positions = chip.positions,
            excludedPositions = if (units) emptySet() else UNITS,
            teams = request.teams,
            playerIds = request.onlyPlayers.orEmpty(),
            excludedPlayerIds = request.excludePlayers,
            // Unlike the sample qualifier, these are the user's own choices, so a search keeps them.
            filters = listOfNotNull(snap) + request.filters,
            qualifiers = listOfNotNull(threshold?.qualifier),
            // A search should find anyone; players below the bar come back unranked.
            includeUnqualified = listing,
            minGames = if (listing) 1 else threshold?.minGames ?: 1,
            mode = if (request.perGame) ValueMode.PER_GAME else ValueMode.TOTAL,
            percentiles = true,
            name = request.name.takeIf { searching },
            limit = StatQuerySpec.MAX_LIMIT,
            scoring = request.scoring,
        )
    }

    /** Season and window for every stored rollup. A database built before schema 9 has none. */
    private suspend fun rollups(): List<Pair<Int, RollupWindow>> = try {
        executor.query(CatalogQueries.windows) { it.long(0).toInt() to RollupWindow(it.text(1), WeekRange(it.long(2).toInt(), it.long(3).toInt())) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        emptyList()
    }

    private fun threshold(request: GridRequest): SampleThreshold? =
        SampleThreshold.forRequest(request.sort, request.pack, request.playedWeeks, request.perGame)

    /** How many players [request] matches, ignoring the page limit. Backs the filter sheet's live count. */
    public suspend fun count(request: GridRequest): Int =
        if (request.onlyPlayers?.isEmpty() == true) {
            0
        } else {
            val spec = spec(request, threshold(request)).copy(rollups = request.season.rollups)
            executor.query(StatQueryBuilder.count(spec)) { it.long(0).toInt() }.single()
        }
}
