package dev.gridiron.core.projections

import dev.gridiron.core.model.Position
import dev.gridiron.core.model.ScoringProfile
import dev.gridiron.core.statquery.Component
import dev.gridiron.core.statquery.Components
import kotlin.math.sqrt

/**
 * A projection's points under [profile]: the projected means scored, except a
 * D/ST's points allowed, whose tiers are scored in expectation. Points
 * allowed are about normal (their variance is the spread squared), and a
 * projection of `g` games (rest of season) scores each game's tier from the
 * per-game mean and spread. The tier of the mean would put every game in one
 * tier, and the tier of a season's summed points allowed means nothing.
 */
public fun projectedScore(components: List<ProjectionComponent>, profile: ScoringProfile, position: Position?): Double {
    val allowed = components.firstOrNull { it.metricId == Components.POINTS_ALLOWED.id }
    val means = components.filter { it.metricId != Components.POINTS_ALLOWED.id }.associate { Component(it.metricId) to it.mean }
    val scored = score(means, profile, position)
    if (allowed == null) return scored
    val games = (means[Components.GAMES] ?: 1.0).coerceAtLeast(1.0)
    return scored + games * profile.expectedPointsAllowedPoints(allowed.mean / games, sqrt(allowed.variance.coerceAtLeast(0.0) / games))
}
