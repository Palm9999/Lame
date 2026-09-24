package dev.gridiron.feature.projections

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.gridiron.core.data.ProjectionsRepository
import dev.gridiron.core.model.Position
import dev.gridiron.core.model.ScoringProfile
import dev.gridiron.core.projections.AttributedFactor
import dev.gridiron.core.projections.DistributionFamily
import dev.gridiron.core.projections.DistributionSpec
import dev.gridiron.core.projections.ProjectionsRequest
import dev.gridiron.core.projections.SimulationResult
import dev.gridiron.core.projections.attributeFactors
import dev.gridiron.core.projections.score
import dev.gridiron.core.projections.simulate
import dev.gridiron.core.statquery.Component
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

public sealed interface ProjectionsUiState {
    public object Loading : ProjectionsUiState
    public data class Loaded(
        val playerId: String,
        val baseline: Double,
        val final: Double,
        val factors: List<AttributedFactor>,
        val floorCeiling: SimulationResult,
    ) : ProjectionsUiState
}

/**
 * Loads a player's projection, attributes the baseline-to-final delta across
 * factors, and runs a floor/ceiling Monte Carlo simulation.
 *
 * [load] tags each call with a request key and re-checks it both before and
 * after the CPU-bound [simulate] call, so a stale (superseded) request can
 * never overwrite fresher state -- the same `page == base.page` tag-and-gate
 * pattern `GridViewModel` already uses for sparklines, applied here to
 * projection loads (two rapid navigations to different players' projection
 * screens, back to back, must not let the first request's late-arriving
 * result overwrite the second player's numbers).
 */
public class ProjectionsViewModel(private val repository: ProjectionsRepository) : ViewModel() {
    private val _state = MutableStateFlow<ProjectionsUiState>(ProjectionsUiState.Loading)
    public val state: StateFlow<ProjectionsUiState> = _state.asStateFlow()

    // The most recent request's identity -- a request whose result arrives after
    // a newer one has already been issued is stale and must not update `_state`,
    // mirroring GridViewModel's `page == base.page` sparkline guard. Includes
    // profile and position, not just playerId/season/week: a scoring-format
    // toggle while a projection screen is already open re-issues load() for the
    // same player, and that must count as a new request too, or the guard
    // becomes a no-op (both in-flight coroutines would share one key and
    // whichever finishes last would win, even if it's the stale one).
    private data class RequestKey(
        val playerId: String,
        val season: Int,
        val week: Int,
        val profile: ScoringProfile,
        val position: Position?,
    )

    private var currentRequestKey: RequestKey? = null

    public fun load(playerId: String, season: Int, week: Int, profile: ScoringProfile, position: Position?) {
        val requestKey = RequestKey(playerId, season, week, profile, position)
        currentRequestKey = requestKey
        _state.value = ProjectionsUiState.Loading

        viewModelScope.launch {
            val results = repository.projections(ProjectionsRequest(setOf(playerId), season, week))
            if (currentRequestKey != requestKey) return@launch // superseded -- drop it

            val projection = results.firstOrNull() ?: return@launch
            val baselineMap = projection.baseline.associate { Component(it.metricId) to it.mean }
            val finalMap = projection.final.associate { Component(it.metricId) to it.mean }

            val baselinePoints = score(baselineMap, profile, position)
            val finalPoints = score(finalMap, profile, position)
            val attributed = attributeFactors(baselineMap, finalMap, projection.factors, profile, position)
            // Every component simulated as Gamma pending per-metric dist_family
            // wiring (see the note below this block) -- the same fallback
            // Task 6's drawOne() already uses for NEGBINOM/BINOMIAL, so this is
            // a real, defined distribution choice today, not a stub.
            val distributions = projection.final.map {
                DistributionSpec(Component(it.metricId), DistributionFamily.GAMMA, it.mean, it.variance)
            }
            val floorCeiling = simulate(distributions, profile, position)

            if (currentRequestKey != requestKey) return@launch // re-check after the CPU-bound simulate() call
            _state.value = ProjectionsUiState.Loaded(playerId, baselinePoints, finalPoints, attributed, floorCeiling)
        }
    }
}
