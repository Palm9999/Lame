package dev.gridiron.feature.projections

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.gridiron.core.data.ProjectionsRepository
import dev.gridiron.core.data.live.WaiverTrend
import dev.gridiron.core.data.live.WaiverTrendsResult
import dev.gridiron.core.model.Position
import dev.gridiron.core.model.ScoringProfile
import dev.gridiron.core.projections.ListedProjection
import dev.gridiron.core.projections.projectedScore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** The app's points for the upcoming [week] and rest of season, by player id, under one profile; empty without projections. */
public data class TrendPoints(val week: Int?, val weekPoints: Map<String, Double>, val rosPoints: Map<String, Double>) {
    public companion object {
        public val NONE: TrendPoints = TrendPoints(null, emptyMap(), emptyMap())
    }
}

/** One row of the list: the trend, its [change] in the list's terms, and the app's points when it knows the player. */
public data class TrendRow(val trend: WaiverTrend, val change: Double, val weekPoints: Double?, val rosPoints: Double?)

public sealed interface WaiverTrendsState {
    public data object Loading : WaiverTrendsState

    public data class Loaded(val result: WaiverTrendsResult, val points: TrendPoints) : WaiverTrendsState
}

/** How many rows each list shows. */
internal const val TREND_ROWS = 25

/**
 * The [TREND_ROWS] biggest risers ([added]) or fallers, at [position] (null for all): by the week's change once the
 * phone has a snapshot from a week ago, else by ESPN's own change.
 */
public fun trendRows(result: WaiverTrendsResult, points: TrendPoints, added: Boolean, position: String?): List<TrendRow> =
    result.trends
        .filter { position == null || it.position == position }
        .map { t ->
            val change = (if (result.weekly) t.weekChange else t.espnChange) ?: 0.0
            TrendRow(t, change, t.playerId?.let(points.weekPoints::get), t.playerId?.let(points.rosPoints::get))
        }
        .filter { if (added) it.change > 0 else it.change < 0 }
        .sortedWith(compareBy<TrendRow> { if (added) -it.change else it.change }.thenByDescending { it.trend.percentOwned }.thenBy { it.trend.espnId })
        .take(TREND_ROWS)

/** Scores the forecast's upcoming week and rest of season with [profile]; [TrendPoints.NONE] when there is none. */
public suspend fun trendPoints(repository: ProjectionsRepository, season: Int, profile: ScoringProfile): TrendPoints {
    val status = repository.status()
    val week = status.upcoming[season]
    if (status.status != "ok" || week == null) return TrendPoints.NONE
    fun scored(listed: List<ListedProjection>) =
        listed.associate { it.playerId to projectedScore(it.components, profile, it.position?.let(Position::fromCode)) }
    return TrendPoints(week, scored(repository.weekAll(season, week)), scored(repository.rosAll(season)))
}

/** Loads ESPN's roster trends and the app's points for them. */
public class WaiverTrendsViewModel(
    private val trends: suspend (Int) -> WaiverTrendsResult,
    private val points: suspend (Int, ScoringProfile) -> TrendPoints,
) : ViewModel() {
    private val _state = MutableStateFlow<WaiverTrendsState>(WaiverTrendsState.Loading)
    public val state: StateFlow<WaiverTrendsState> = _state.asStateFlow()

    // A load superseded by a newer one (a profile switch mid-load) must not overwrite it.
    private var latest = 0

    public fun load(season: Int, profile: ScoringProfile) {
        val request = ++latest
        viewModelScope.launch {
            val result = trends(season)
            val scored = try {
                points(season, profile)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                TrendPoints.NONE
            }
            if (request == latest) _state.value = WaiverTrendsState.Loaded(result, scored)
        }
    }

    public companion object {
        public fun factory(
            trends: suspend (Int) -> WaiverTrendsResult,
            points: suspend (Int, ScoringProfile) -> TrendPoints,
        ): ViewModelProvider.Factory = viewModelFactory { initializer { WaiverTrendsViewModel(trends, points) } }
    }
}
