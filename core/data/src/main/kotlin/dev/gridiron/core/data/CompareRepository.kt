package dev.gridiron.core.data

import dev.gridiron.core.database.QueryExecutor
import dev.gridiron.core.database.ResultRow
import dev.gridiron.core.database.doubleOrNull
import dev.gridiron.core.model.CompareSlot
import dev.gridiron.core.model.Position
import dev.gridiron.core.model.WeekRange
import dev.gridiron.core.statquery.GridLayout
import dev.gridiron.core.statquery.StatColumn
import dev.gridiron.core.statquery.StatQueryBuilder
import dev.gridiron.core.statquery.StatQuerySpec
import dev.gridiron.core.statquery.ValueMode
import kotlinx.collections.immutable.toImmutableList
import java.util.Locale
import kotlin.math.abs

/** Everything the Compare screen shows, built from one Grid query per group of like slots. */
public class CompareRepository(
    private val executor: QueryExecutor,
    locale: Locale = Locale.getDefault(),
) {
    private val format = StatFormat(locale)

    private data class Found(
        val status: SlotStatus,
        val games: Long = 0,
        val values: Map<StatColumn, Double?> = emptyMap(),
        val percentiles: Map<StatColumn, Float?> = emptyMap(),
    )

    public suspend fun compare(request: CompareRequest, catalog: Catalog): ComparePage {
        val headers = executor.playerHeaders(request.slots.map { it.playerId })
        val positions = request.slots.map { s -> headers[s.playerId]?.position?.let(Position::fromCode) }
        val found = arrayOfNulls<Found>(request.slots.size)

        // Unknown players and seasons first; the rest grouped so like slots share a query.
        request.slots.forEachIndexed { i, s ->
            when {
                headers[s.playerId] == null -> found[i] = Found(SlotStatus.MISSING)
                catalog.seasons.none { it.season == s.season } -> found[i] = Found(SlotStatus.NO_SEASON)
            }
        }
        request.slots.indices.filter { found[it] == null }
            .groupBy { Triple(request.slots[it].season, request.slots[it].weeks, positions[it]) }
            .forEach { (key, idx) ->
                val (season, weeks, position) = key
                val rows = rankedRows(season, weeks, position, idx.map { request.slots[it].playerId }.toSet(), request, catalog)
                for (i in idx) {
                    found[i] = rows[request.slots[i].playerId]
                        ?: unrankedRow(request.slots[i], position, request)
                        ?: Found(SlotStatus.NO_GAMES)
                }
            }

        val slots = request.slots.mapIndexed { i, s -> header(s, headers[s.playerId], found[i]!!, positions[i], catalog) }
        val usable = request.slots.indices.filter { found[it]!!.status in setOf(SlotStatus.OK, SlotStatus.SMALL_SAMPLE) }
        val groups = CompareMetricSets.union(usable.map { positions[it] }).map { (group, columns) ->
            val rows = columns.map { column -> row(column, request, positions, found.map { it!! }, catalog) }
            CompareGroupUi(
                group,
                request.slots.indices.map { i ->
                    rows.mapNotNull { it.cells[i].percentile }.takeIf { it.isNotEmpty() }?.average()?.toFloat()
                }.toImmutableList(),
                rows.toImmutableList(),
            )
        }
        val radar = usable.firstOrNull()?.let { first ->
            val axes = CompareMetricSets.radarAxes(positions[first])
            RadarUi(
                axes.map { catalog.metrics[it.metricId]?.abbr ?: it.metricId }.toImmutableList(),
                request.slots.indices.map { i -> axes.map { found[i]!!.percentiles[it] }.toImmutableList() }.toImmutableList(),
            )
        }
        val scatter = usable.firstOrNull()?.let { first ->
            scatter(request.slots[first], positions[first], request, found.map { it!! }, headers, catalog)
        }
        return ComparePage(request, slots.toImmutableList(), groups.toImmutableList(), radar, scatter)
    }

    /** The columns Compare shows for [position], plus its qualifier, in a stable order. */
    private fun columnsFor(position: Position?): List<StatColumn> =
        (CompareMetricSets.groupsFor(position).values.flatten() + CompareMetricSets.qualifier(position)).distinct()

    /**
     * One Grid query, shared by every slot with this season, week range and
     * position, filtered down to [ids] after percentiles are computed.
     */
    private suspend fun rankedRows(
        season: Int,
        weeks: WeekRange,
        position: Position?,
        ids: Set<String>,
        request: CompareRequest,
        catalog: Catalog,
    ): Map<String, Found> {
        val qualifier = CompareMetricSets.qualifier(position)
        val columns = columnsFor(position)
        val seasonInfo = catalog.season(season)
        val playedWeeks = (minOf(weeks.last, seasonInfo.lastWeek) - weeks.first + 1).coerceAtLeast(1)
        val threshold = SampleThreshold.forSample(qualifier, playedWeeks, request.perGame)
        val spec = StatQuerySpec(
            season = season,
            weeks = weeks,
            columns = columns,
            positions = setOfNotNull(position),
            qualifiers = listOfNotNull(threshold?.qualifier),
            includeUnqualified = true,
            minGames = threshold?.minGames ?: 1,
            mode = if (request.perGame) ValueMode.PER_GAME else ValueMode.TOTAL,
            percentiles = true,
            playerIds = ids,
            limit = ids.size,
            scoring = request.scoring,
        )
        val q = StatQueryBuilder.grid(spec)
        val layout = q.layout
        return executor.query(q.query) { r ->
            r.text(GridLayout.PLAYER_ID) to Found(
                status = if (r.doubleOrNull(layout.percentileIndex(qualifier)) != null) SlotStatus.OK else SlotStatus.SMALL_SAMPLE,
                games = r.long(GridLayout.GAMES),
                values = columns.associateWith { column -> r.doubleOrNull(layout.valueIndex(column)) },
                percentiles = columns.associateWith { column -> r.doubleOrNull(layout.percentileIndex(column))?.toFloat() },
            )
        }.toMap()
    }

    /**
     * A slot dropped from its position's population by the per-game games
     * floor: queried alone with that floor relaxed, so it still shows values.
     * Null (per-game off, or the player has no games at all in this range).
     */
    private suspend fun unrankedRow(slot: CompareSlot, position: Position?, request: CompareRequest): Found? {
        if (!request.perGame) return null
        val columns = columnsFor(position)
        val spec = StatQuerySpec(
            season = slot.season,
            weeks = slot.weeks,
            columns = columns,
            positions = setOfNotNull(position),
            includeUnqualified = true,
            minGames = 1,
            mode = ValueMode.PER_GAME,
            percentiles = false,
            playerIds = setOf(slot.playerId),
            limit = 1,
            scoring = request.scoring,
        )
        val q = StatQueryBuilder.grid(spec)
        val layout = q.layout
        return executor.query(q.query) { r ->
            Found(
                status = SlotStatus.SMALL_SAMPLE,
                games = r.long(GridLayout.GAMES),
                values = columns.associateWith { column -> r.doubleOrNull(layout.valueIndex(column)) },
            )
        }.singleOrNull()
    }

    private fun header(slot: CompareSlot, player: PlayerHeader?, found: Found, position: Position?, catalog: Catalog): SlotHeader {
        val name = player?.name ?: slot.playerId
        val detail = when (found.status) {
            SlotStatus.NO_SEASON -> "No ${slot.season} data"
            SlotStatus.MISSING -> "Not in the database"
            else -> buildString {
                append(position?.code ?: "–")
                append(" · ")
                append(player?.team ?: "FA")
                append(" · ")
                append(describeSlot(slot, catalog))
                if (found.games > 0) append(" · ${found.games} g")
                if (found.status == SlotStatus.SMALL_SAMPLE) append(" · small sample")
                if (found.status == SlotStatus.NO_GAMES) append(" · no games")
            }
        }
        return SlotHeader(slot, name, detail, found.status, position)
    }

    private fun row(
        column: StatColumn,
        request: CompareRequest,
        positions: List<Position?>,
        found: List<Found>,
        catalog: Catalog,
    ): CompareRowUi {
        val cells = found.mapIndexed { i, f ->
            val applicable = f.status in USABLE && column in CompareMetricSets.groupsFor(positions[i]).values.flatten()
            if (!applicable) {
                CompareCellUi(NOT_APPLICABLE, null, null)
            } else {
                val value = f.values[column]
                CompareCellUi(format.format(column, value, request.perGame), value, f.percentiles[column])
            }
        }
        val percentiles = cells.mapNotNull { it.percentile }
        val spread = if (percentiles.size >= 2) percentiles.max() - percentiles.min() else null
        val values = cells.withIndex().mapNotNull { (i, c) -> c.value?.let { i to it } }
        val best = if (values.size >= 2 && values.map { it.second }.distinct().size > 1) {
            (if (column.higherIsBetter) values.maxByOrNull { it.second } else values.minByOrNull { it.second })?.first
        } else {
            null
        }
        val diff = if (cells.size == 2) {
            val v0 = cells[0].value
            val v1 = cells[1].value
            if (v0 != null && v1 != null) {
                val d = v0 - v1
                val magnitude = format.format(column, abs(d), request.perGame)
                val zero = format.format(column, 0.0, request.perGame)
                if (magnitude == zero) "0" else "${if (d >= 0) "+" else "−"}$magnitude"
            } else {
                null
            }
        } else {
            null
        }
        val info = catalog.metrics[column.metricId]
        return CompareRowUi(column, info?.name ?: column.metricId, info, cells.toImmutableList(), spread, best, diff)
    }

    /**
     * The position's per-game fantasy-points-vs-expected population for the
     * first usable slot's season and range, with each usable slot's own point
     * picked out of its own season and range.
     */
    private suspend fun scatter(
        slot: CompareSlot,
        position: Position?,
        request: CompareRequest,
        found: List<Found>,
        headers: Map<String, PlayerHeader>,
        catalog: Catalog,
    ): ScatterUi? {
        if (position == null) return null
        val qualifier = CompareMetricSets.qualifier(position)
        val seasonInfo = catalog.season(slot.season)
        val playedWeeks = (minOf(slot.weeks.last, seasonInfo.lastWeek) - slot.weeks.first + 1).coerceAtLeast(1)
        val threshold = SampleThreshold.forSample(qualifier, playedWeeks, perGame = true)
        val populationSpec = StatQuerySpec(
            season = slot.season,
            weeks = slot.weeks,
            columns = SCATTER_COLUMNS,
            positions = setOf(position),
            qualifiers = listOfNotNull(threshold?.qualifier),
            minGames = threshold?.minGames ?: 1,
            mode = ValueMode.PER_GAME,
            limit = StatQuerySpec.MAX_LIMIT,
            scoring = request.scoring,
        )
        val popQuery = StatQueryBuilder.grid(populationSpec)
        val popLayout = popQuery.layout
        val population = executor.query(popQuery.query) { r -> scatterPoint(r, popLayout, r.text(GridLayout.FULL_NAME)) }

        val points = request.slots.mapIndexed { i, s ->
            if (found[i].status !in USABLE) return@mapIndexed null
            val spec = StatQuerySpec(
                season = s.season,
                weeks = s.weeks,
                columns = SCATTER_COLUMNS,
                includeUnqualified = true,
                minGames = 1,
                mode = ValueMode.PER_GAME,
                playerIds = setOf(s.playerId),
                limit = 1,
                scoring = request.scoring,
            )
            val q = StatQueryBuilder.grid(spec)
            val layout = q.layout
            executor.query(q.query) { r -> scatterPoint(r, layout, headers[s.playerId]?.name ?: s.playerId) }.singleOrNull()
        }

        return ScatterUi(position, slot.season, slot.weeks, population.toImmutableList(), points.toImmutableList())
    }

    private fun scatterPoint(r: ResultRow, layout: GridLayout, name: String) =
        ScatterPointUi(
            playerId = r.text(GridLayout.PLAYER_ID),
            name = name,
            xfpPerGame = r.doubleOrNull(layout.valueIndex(StatColumn.EXPECTED_FANTASY_POINTS)) ?: 0.0,
            fpPerGame = r.doubleOrNull(layout.valueIndex(StatColumn.FANTASY_POINTS)) ?: 0.0,
        )

    private companion object {
        val USABLE = setOf(SlotStatus.OK, SlotStatus.SMALL_SAMPLE)
        val SCATTER_COLUMNS = listOf(StatColumn.FANTASY_POINTS, StatColumn.EXPECTED_FANTASY_POINTS)

        /** Distinct from [StatFormat.MISSING]: a stat that doesn't apply to this slot's position at all. */
        const val NOT_APPLICABLE = "—"
    }
}
