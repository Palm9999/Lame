package dev.gridiron.core.projections

import java.time.Instant

/** One projected stat. [family] is the metric registry's `dist_family`; null simulates as gamma. */
public data class ProjectionComponent(val metricId: String, val mean: Double, val variance: Double, val family: String? = null)

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

/** One player's projected stats and who he is: a row of the Projections list. */
public data class ListedProjection(
    val playerId: String,
    val name: String,
    val position: String?,
    val team: String?,
    val components: List<ProjectionComponent>,
)

/** The forecast as the last refresh left it. */
public data class ForecastStatus(
    /** "ok", why there are no projections, or null for a database built before projections existed. */
    val status: String?,
    val builtAt: Instant?,
    /** The upcoming week projected, by season. */
    val upcoming: Map<Int, Int>,
)

/** A game's line from [team]'s side. */
public data class GameLine(val team: String, val opponent: String, val home: Boolean, val spread: Double?, val total: Double?) {
    /** Points [team] is favored by; negative when it's the underdog. nflverse's spread is the home team's. */
    val favoredBy: Double? get() = spread?.let { if (home) it else -it }
}
