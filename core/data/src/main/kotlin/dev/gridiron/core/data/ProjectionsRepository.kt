package dev.gridiron.core.data

import dev.gridiron.core.database.QueryExecutor
import dev.gridiron.core.database.doubleOrNull
import dev.gridiron.core.database.textOrNull
import dev.gridiron.core.projections.ForecastStatus
import dev.gridiron.core.projections.GameLine
import dev.gridiron.core.projections.ListedProjection
import dev.gridiron.core.projections.PlayerProjection
import dev.gridiron.core.projections.ProjectionComponent
import dev.gridiron.core.projections.ProjectionFactor
import dev.gridiron.core.projections.ProjectionQueries
import dev.gridiron.core.projections.ProjectionsRequest
import dev.gridiron.core.projections.RosProjection
import dev.gridiron.core.projections.RosProjectionsRequest
import dev.gridiron.core.statquery.SqlQuery
import java.time.Instant

public class ProjectionsRepository(private val executor: QueryExecutor) {

    public suspend fun projections(request: ProjectionsRequest): List<PlayerProjection> {
        if (request.playerIds.isEmpty()) return emptyList()

        data class Row(
            val playerId: String,
            val metricId: String,
            val stage: String,
            val mean: Double,
            val variance: Double,
            val family: String?,
        )

        val rows = executor.query(
            ProjectionQueries.weekly(request.playerIds, request.season, request.week),
        ) { Row(it.text(0), it.text(1), it.text(2), it.double(3), it.double(4), it.textOrNull(5)) }

        val factorRows = executor.query(
            ProjectionQueries.factors(request.playerIds, request.season, request.week),
        ) {
            it.text(0) to ProjectionFactor(it.text(1), it.double(2), it.textOrNull(3))
        }

        val byPlayer = rows.groupBy { it.playerId }
        val factorsByPlayer = factorRows.groupBy({ it.first }, { it.second })

        return byPlayer.map { (playerId, playerRows) ->
            PlayerProjection(
                playerId = playerId,
                season = request.season,
                week = request.week,
                baseline = playerRows.filter { it.stage == "baseline" }
                    .map { ProjectionComponent(it.metricId, it.mean, it.variance, it.family) },
                final = playerRows.filter { it.stage == "final" }
                    .map { ProjectionComponent(it.metricId, it.mean, it.variance, it.family) },
                factors = factorsByPlayer[playerId].orEmpty(),
            )
        }
    }

    public suspend fun rosProjections(request: RosProjectionsRequest): List<RosProjection> {
        if (request.playerIds.isEmpty()) return emptyList()

        data class Row(val playerId: String, val metricId: String, val mean: Double, val variance: Double, val family: String?)

        val rows = executor.query(
            ProjectionQueries.ros(request.playerIds, request.season),
        ) { Row(it.text(0), it.text(1), it.double(2), it.double(3), it.textOrNull(4)) }

        return rows.groupBy { it.playerId }.map { (playerId, playerRows) ->
            RosProjection(playerId, playerRows.map { ProjectionComponent(it.metricId, it.mean, it.variance, it.family) })
        }
    }

    /** How the last refresh's forecast went; a null status means the database predates projections. */
    public suspend fun status(): ForecastStatus {
        val meta = executor.query(ProjectionQueries.status()) { it.text(0) to it.text(1) }.toMap()
        val upcoming = meta.mapNotNull { (key, value) ->
            val season = key.takeIf { it.startsWith("forecast_week:") }?.substringAfter(':')?.toIntOrNull()
            val week = value.toIntOrNull()
            if (season != null && week != null) season to week else null
        }.toMap()
        return ForecastStatus(
            status = meta["forecast_status"],
            builtAt = meta["forecast_built_at"]?.let { runCatching { Instant.parse(it) }.getOrNull() },
            upcoming = upcoming,
        )
    }

    public suspend fun weekAll(season: Int, week: Int): List<ListedProjection> = listed(ProjectionQueries.weekAll(season, week))

    public suspend fun rosAll(season: Int): List<ListedProjection> = listed(ProjectionQueries.rosAll(season))

    /** [team]'s regular-season game that week, or null on a bye. */
    public suspend fun game(season: Int, week: Int, team: String): GameLine? =
        executor.query(ProjectionQueries.game(season, week, team)) { row ->
            val home = row.text(0)
            val away = row.text(1)
            GameLine(team, if (team == home) away else home, team == home, row.doubleOrNull(2), row.doubleOrNull(3))
        }.firstOrNull()

    public suspend fun remainingGames(season: Int, fromWeek: Int, team: String): Int =
        executor.query(ProjectionQueries.remainingGames(season, fromWeek, team)) { it.long(0).toInt() }.single()

    private suspend fun listed(query: SqlQuery): List<ListedProjection> {
        data class Row(val playerId: String, val name: String, val position: String?, val team: String?, val component: ProjectionComponent)

        val rows = executor.query(query) {
            Row(it.text(0), it.text(1), it.textOrNull(2), it.textOrNull(3), ProjectionComponent(it.text(4), it.double(5), it.double(6), it.textOrNull(7)))
        }
        return rows.groupBy { it.playerId }.map { (playerId, playerRows) ->
            val first = playerRows.first()
            ListedProjection(playerId, first.name, first.position, first.team, playerRows.map { it.component })
        }
    }
}
