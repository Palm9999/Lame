package dev.gridiron.core.data

import dev.gridiron.core.database.QueryExecutor
import dev.gridiron.core.database.textOrNull
import dev.gridiron.core.model.Position
import dev.gridiron.core.model.ScoringProfile
import dev.gridiron.core.projections.AccuracyQueries
import dev.gridiron.core.projections.BACKTEST_DRAWS
import dev.gridiron.core.projections.ForecastStatus
import dev.gridiron.core.projections.PlayedWeek
import dev.gridiron.core.projections.PositionAccuracy
import dev.gridiron.core.projections.ProjectedWeek
import dev.gridiron.core.projections.ProjectionComponent
import dev.gridiron.core.projections.RANGE_WIDENING
import dev.gridiron.core.projections.backtest
import dev.gridiron.core.statquery.Component
import dev.gridiron.core.statquery.Components

/** The accuracy page's numbers: the stored past-week projections against the games actually played. */
public class AccuracyRepository(private val executor: QueryExecutor) {
    private val projections = ProjectionsRepository(executor)

    private data class Key(val status: ForecastStatus, val season: Int, val profile: ScoringProfile, val draws: Int, val widening: Map<Position, Double>)

    /** The last backtest per season and profile; a refresh changes the forecast status, so stale ones are never read. */
    private val cache = java.util.concurrent.ConcurrentHashMap<Key, List<PositionAccuracy>>()

    /** How the last refresh's forecast went. */
    public suspend fun status(): ForecastStatus = projections.status()

    /**
     * Seasons with at least one finished week projected, oldest first. The
     * upcoming week isn't finished, even after one of its games is played.
     */
    public suspend fun seasons(): List<Int> {
        val upcoming = status().upcoming
        return executor.query(AccuracyQueries.firstProjectedWeeks()) { it.long(0).toInt() to it.long(1).toInt() }
            .filter { (season, firstWeek) -> firstWeek < (upcoming[season] ?: Int.MAX_VALUE) }
            .map { it.first }
    }

    /**
     * [season]'s backtest under [profile], by position. It simulates every
     * counted player-week, which is seconds of work on a phone, so call it
     * off the main thread. Kept until the next refresh, so a second visit is instant.
     */
    public suspend fun backtest(
        season: Int,
        profile: ScoringProfile,
        draws: Int = BACKTEST_DRAWS,
        widening: Map<Position, Double> = RANGE_WIDENING,
    ): List<PositionAccuracy> {
        val status = status()
        val key = Key(status, season, profile, draws, widening)
        cache[key]?.let { return it }
        return compute(season, profile, draws, widening, status).also { result ->
            cache.keys.removeIf { it.status != status }
            cache[key] = result
        }
    }

    private suspend fun compute(
        season: Int,
        profile: ScoringProfile,
        draws: Int,
        widening: Map<Position, Double>,
        status: ForecastStatus,
    ): List<PositionAccuracy> {
        val beforeWeek = status.upcoming[season] ?: Int.MAX_VALUE

        data class Row(val playerId: String, val position: String, val week: Int, val component: ProjectionComponent)

        val rows = executor.query(AccuracyQueries.projected(season, beforeWeek)) {
            Row(it.text(0), it.text(1), it.long(2).toInt(), ProjectionComponent(it.text(3), it.double(4), it.double(5), it.textOrNull(6)))
        }
        val projected = rows.groupBy { it.playerId to it.week }.map { (key, weekRows) ->
            ProjectedWeek(key.first, weekRows.first().position, key.second, weekRows.map { it.component })
        }

        data class Fact(val playerId: String, val season: Int, val week: Int, val metricId: String, val value: Double)

        val facts = executor.query(AccuracyQueries.played(season - 1, season)) {
            Fact(it.text(0), it.long(1).toInt(), it.long(2).toInt(), it.text(3), it.double(4))
        }
        // A week counts as played when it has a recorded play.
        val played = facts.groupBy { Triple(it.playerId, it.season, it.week) }
            .filter { (_, weekFacts) -> weekFacts.any { it.metricId == Components.GAMES.id && it.value > 0.0 } }
            .map { (key, weekFacts) ->
                PlayedWeek(key.first, key.second, key.third, weekFacts.associate { Component(it.metricId) to it.value })
            }
        return backtest(season, projected, played, profile, draws, widening)
    }
}
