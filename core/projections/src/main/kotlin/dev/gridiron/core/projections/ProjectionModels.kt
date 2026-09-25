package dev.gridiron.core.projections

public data class ProjectionComponent(val metricId: String, val mean: Double, val variance: Double)
public data class ProjectionFactor(val factor: String, val logMultiplier: Double, val note: String?)

public data class PlayerProjection(
    val playerId: String,
    val season: Int,
    val week: Int,
    val baseline: List<ProjectionComponent>,
    val final: List<ProjectionComponent>,
    val factors: List<ProjectionFactor>,
)

public data class RosProjection(val playerId: String, val components: List<ProjectionComponent>)

public data class ProjectionsRequest(val playerIds: Set<String>, val season: Int, val week: Int)
public data class RosProjectionsRequest(val playerIds: Set<String>, val season: Int)
