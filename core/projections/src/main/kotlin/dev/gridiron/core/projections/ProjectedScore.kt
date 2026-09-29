package dev.gridiron.core.projections

import dev.gridiron.core.model.Position
import dev.gridiron.core.model.ScoringProfile
import dev.gridiron.core.statquery.Component
import dev.gridiron.core.statquery.Components
import kotlin.math.sqrt

/**
 * A projection's points under [profile]: the projected means scored, except a
 * D/ST's points and yards allowed, whose tiers are scored in expectation.
 * Both are about normal (their variance is the spread squared), and a
 * projection of `g` games (rest of season) scores each game's tier from the
 * per-game mean and spread. The tier of the mean would put every game in one
 * tier, and the tier of a season's summed totals means nothing.
 */
public fun projectedScore(components: List<ProjectionComponent>, profile: ScoringProfile, position: Position?): Double {
    val allowed = components.firstOrNull { it.metricId == Components.POINTS_ALLOWED.id }
    val yards = components.firstOrNull { it.metricId == Components.YARDS_ALLOWED.id }
    val tiered = setOf(Components.POINTS_ALLOWED.id, Components.YARDS_ALLOWED.id)
    val means = components.filter { it.metricId !in tiered }.associate { Component(it.metricId) to it.mean }
    val scored = score(means, profile, position)
    if (allowed == null && yards == null) return scored
    val games = (means[Components.GAMES] ?: 1.0).coerceAtLeast(1.0)
    fun perGame(c: ProjectionComponent, expected: (Double, Double) -> Double) =
        games * expected(c.mean / games, sqrt(c.variance.coerceAtLeast(0.0) / games))
    return scored +
        (allowed?.let { perGame(it, profile::expectedPointsAllowedPoints) } ?: 0.0) +
        (yards?.let { perGame(it, profile::expectedYardsAllowedPoints) } ?: 0.0)
}
