package dev.gridiron.core.projections

import dev.gridiron.core.model.Position
import dev.gridiron.core.model.ScoringProfile
import dev.gridiron.core.statquery.BONUS_INPUTS
import dev.gridiron.core.statquery.Component
import dev.gridiron.core.statquery.Components
import dev.gridiron.core.statquery.RULE_INPUTS
import kotlin.math.abs

/** The positions the backtest measures, in the page's order. CI's gate holds every one to beating the season-to-date average. */
public val ACCURACY_POSITIONS: List<String> = listOf("QB", "RB", "WR", "TE", "K", "DST")

/** A player-week counts only when the model projected at least this many points (spec §4). */
public const val ACCURACY_MIN_POINTS: Double = 5.0

/**
 * Draws per player-week for the floor and ceiling. A season is about 3,000
 * counted player-weeks, and calibration is a share over all of them, so each
 * needs far fewer draws than a single player's card.
 */
public const val BACKTEST_DRAWS: Int = 250

/** The short-memory baseline's window, in games played. */
private const val LAST_GAMES = 4

/** Every stat a real game's fantasy score reads, points allowed (the tiers') included. */
public val ACTUAL_SCORING_COMPONENTS: List<Component> =
    (RULE_INPUTS.values.flatMap { inputs -> inputs.actual.map { it.component } } + BONUS_INPUTS.values.flatten() + Components.POINTS_ALLOWED)
        .distinct()
        .sortedBy { it.id }

/** A week a player played, with his scoring stats. */
public data class PlayedWeek(val playerId: String, val season: Int, val week: Int, val stats: Map<Component, Double>)

/** The model's final projection for one player-week of the season being measured. */
public data class ProjectedWeek(val playerId: String, val position: String, val week: Int, val components: List<ProjectionComponent>)

/** How far one predictor's points were from what players scored. Error is predicted minus actual. */
public data class ErrorStats(
    val mae: Double,
    /** The mean error: positive when the predictor ran high. */
    val bias: Double,
    /** Null when the actual scores have no spread (a single player-week, say). */
    val r2: Double?,
)

/** One position's season: the model and the two baselines, on the same player-weeks. */
public data class PositionAccuracy(
    val position: String,
    val playerWeeks: Int,
    val model: ErrorStats,
    val seasonAverage: ErrorStats,
    val lastFour: ErrorStats,
    /** The share of actual scores between the model's floor and ceiling: about 0.8 when the spread is right. */
    val calibration: Double,
)

/** Mean absolute error, mean error and R² of (predicted, actual) pairs. */
public fun errorStats(pairs: List<Pair<Double, Double>>): ErrorStats {
    require(pairs.isNotEmpty()) { "no player-weeks to measure" }
    val n = pairs.size
    val mae = pairs.sumOf { (predicted, actual) -> abs(predicted - actual) } / n
    val bias = pairs.sumOf { (predicted, actual) -> predicted - actual } / n
    val meanActual = pairs.sumOf { it.second } / n
    val total = pairs.sumOf { (_, actual) -> (actual - meanActual) * (actual - meanActual) }
    val residual = pairs.sumOf { (predicted, actual) -> (actual - predicted) * (actual - predicted) }
    // Rounding leaves a tiny total when every actual is equal; that is still no spread.
    return ErrorStats(mae, bias, if (total > 1e-9) 1.0 - residual / total else null)
}

private class Sample(
    val actual: Double,
    val model: Double,
    val floor: Double,
    val ceiling: Double,
    val seasonAverage: Double,
    val lastFour: Double,
)

/**
 * The walk-forward backtest of [season] under [profile] (spec §4).
 *
 * A player-week counts when the model projected at least
 * [ACCURACY_MIN_POINTS], the player played that week, and he had already
 * played earlier that season. The last rule keeps the season-to-date average
 * defined, so all three predictors are measured on the same player-weeks.
 * [projected] holds [season]'s past weeks only. [played] holds the weeks
 * actually played, and must include the previous season, because the
 * last-four average reaches back into it. A position with nothing to count
 * is left out. [widening] is for fitting the range factors; everyone else
 * uses [RANGE_WIDENING].
 */
public fun backtest(
    season: Int,
    projected: List<ProjectedWeek>,
    played: List<PlayedWeek>,
    profile: ScoringProfile,
    draws: Int = BACKTEST_DRAWS,
    widening: Map<Position, Double> = RANGE_WIDENING,
): List<PositionAccuracy> {
    val positionOf = projected.associate { it.playerId to it.position }
    // Each projected player's games, oldest first, with the points he scored in each.
    val games: Map<String, List<Pair<PlayedWeek, Double>>> = played
        .filter { it.playerId in positionOf }
        .groupBy { it.playerId }
        .mapValues { (id, weeks) ->
            val position = Position.fromCode(positionOf.getValue(id))
            weeks.sortedWith(compareBy({ it.season }, { it.week })).map { it to score(it.stats, profile, position) }
        }

    val samples = HashMap<String, MutableList<Sample>>()
    for (p in projected) {
        if (p.position !in ACCURACY_POSITIONS) continue
        val mine = games[p.playerId] ?: continue
        val at = mine.indexOfFirst { (game, _) -> game.season == season && game.week == p.week }
        if (at < 0) continue // he didn't play
        val earlier = mine.subList(0, at)
        val seasonToDate = earlier.filter { (game, _) -> game.season == season }.map { it.second }
        if (seasonToDate.isEmpty()) continue // his first game of the season
        val position = Position.fromCode(p.position)
        if (projectedScore(p.components, profile, position) < ACCURACY_MIN_POINTS) continue
        val model = projectPoints(p.components, profile, position, draws, widening)
        samples.getOrPut(p.position) { mutableListOf() } += Sample(
            actual = mine[at].second,
            model = model.points,
            floor = model.floor,
            ceiling = model.ceiling,
            seasonAverage = seasonToDate.average(),
            lastFour = earlier.takeLast(LAST_GAMES).map { it.second }.average(),
        )
    }

    return ACCURACY_POSITIONS.mapNotNull { position ->
        val s = samples[position] ?: return@mapNotNull null
        PositionAccuracy(
            position = position,
            playerWeeks = s.size,
            model = errorStats(s.map { it.model to it.actual }),
            seasonAverage = errorStats(s.map { it.seasonAverage to it.actual }),
            lastFour = errorStats(s.map { it.lastFour to it.actual }),
            calibration = s.count { it.actual >= it.floor && it.actual <= it.ceiling }.toDouble() / s.size,
        )
    }
}
