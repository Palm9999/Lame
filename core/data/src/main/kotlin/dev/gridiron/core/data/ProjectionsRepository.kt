package dev.gridiron.core.data

import dev.gridiron.core.database.QueryExecutor
import dev.gridiron.core.database.textOrNull
import dev.gridiron.core.projections.PlayerProjection
import dev.gridiron.core.projections.ProjectionComponent
import dev.gridiron.core.projections.ProjectionFactor
import dev.gridiron.core.projections.ProjectionQueries
import dev.gridiron.core.projections.ProjectionsRequest
import dev.gridiron.core.projections.RosProjection
import dev.gridiron.core.projections.RosProjectionsRequest

public class ProjectionsRepository(private val executor: QueryExecutor) {

    public suspend fun projections(request: ProjectionsRequest): List<PlayerProjection> {
        if (request.playerIds.isEmpty()) return emptyList()

        data class Row(
            val playerId: String,
            val metricId: String,
            val stage: String,
            val mean: Double,
            val variance: Double,
        )

        val rows = executor.query(
            ProjectionQueries.weekly(request.playerIds, request.season, request.week),
        ) { Row(it.text(0), it.text(1), it.text(2), it.double(3), it.double(4)) }

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
                    .map { ProjectionComponent(it.metricId, it.mean, it.variance) },
                final = playerRows.filter { it.stage == "final" }
                    .map { ProjectionComponent(it.metricId, it.mean, it.variance) },
                factors = factorsByPlayer[playerId].orEmpty(),
            )
        }
    }

    public suspend fun rosProjections(request: RosProjectionsRequest): List<RosProjection> {
        if (request.playerIds.isEmpty()) return emptyList()

        data class Row(val playerId: String, val metricId: String, val mean: Double, val variance: Double)

        val rows = executor.query(
            ProjectionQueries.ros(request.playerIds, request.season),
        ) { Row(it.text(0), it.text(1), it.double(2), it.double(3)) }

        return rows.groupBy { it.playerId }.map { (playerId, playerRows) ->
            RosProjection(playerId, playerRows.map { ProjectionComponent(it.metricId, it.mean, it.variance) })
        }
    }
}
