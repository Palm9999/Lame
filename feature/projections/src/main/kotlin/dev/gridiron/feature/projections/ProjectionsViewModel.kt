package dev.gridiron.feature.projections

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.gridiron.core.data.ProjectionsRepository
import dev.gridiron.core.model.Position
import dev.gridiron.core.model.ScoringProfile
import dev.gridiron.core.model.ScoringRule
import dev.gridiron.core.projections.AttributedFactor
import dev.gridiron.core.projections.DistributionFamily
import dev.gridiron.core.projections.DistributionSpec
import dev.gridiron.core.projections.ProjectionsRequest
import dev.gridiron.core.projections.SimulationResult
import dev.gridiron.core.projections.attributeFactors
import dev.gridiron.core.projections.score
import dev.gridiron.core.projections.simulate
import dev.gridiron.core.projections.tdDependence
import dev.gridiron.core.statquery.Component
import dev.gridiron.core.statquery.RULE_INPUTS
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

public sealed interface ProjectionsUiState {
    public object Loading : ProjectionsUiState
    public object Empty : ProjectionsUiState
    public data class Failed(val message: String) : ProjectionsUiState
    public data class Loaded(
        val playerId: String,
        val baseline: Double,
        val final: Double,
        val factors: List<AttributedFactor>,
        val floorCeiling: SimulationResult,
        val tdDependence: Double,
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
public class ProjectionsViewModel(
    private val repository: ProjectionsRepository,
    // The dispatcher `simulate()`'s ~10k Monte Carlo draws run on, kept off
    // viewModelScope's Main.immediate dispatcher so it can't drop frames.
    // Overridable so tests can pin it to the same (virtual-time) test
    // dispatcher Main is set to, instead of a real thread pool.
    private val simulationDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {
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
            try {
                val results = repository.projections(ProjectionsRequest(setOf(playerId), season, week))
                if (currentRequestKey != requestKey) return@launch // superseded -- drop it

                val projection = results.firstOrNull()
                if (projection == null) {
                    _state.value = ProjectionsUiState.Empty
                    return@launch
                }

                // Today's ETL only ships a final-stage row for a handful of
                // metrics; most components stay baseline-only. Merge each
                // component's baseline value forward when there's no
                // final-stage override, so an unfinished component still
                // counts at its baseline value instead of vanishing from
                // scoring (which would misattribute the whole delta to
                // whatever factor happens to be present).
                val baselineByMetric = projection.baseline.associateBy { it.metricId }
                val finalByMetric = projection.final.associateBy { it.metricId }
                val mergedByMetric = baselineByMetric + finalByMetric // final's entries override baseline's per metric id

                val baselineMap = projection.baseline.associate { Component(it.metricId) to it.mean }
                val finalMap = mergedByMetric.values.associate { Component(it.metricId) to it.mean }
                val mergedComponents = mergedByMetric.values.toList()

                val baselinePoints = score(baselineMap, profile, position)
                val finalPoints = score(finalMap, profile, position)
                val attributed = attributeFactors(baselineMap, finalMap, projection.factors, profile, position)

                // TD dependence: what share of the final projection comes from
                // TD-scoring components, via the same rule-input registry the
                // query builder uses for SQL generation.
                val tdComponents = ScoringRule.entries
                    .filter { it.name.contains("TD") }
                    .flatMap { RULE_INPUTS.getValue(it).actual.map { term -> term.component } }
                    .toSet()
                val tdPoints = score(finalMap.filterKeys { it in tdComponents }, profile, position)
                val tdDependenceValue = tdDependence(tdPoints, finalPoints)

                // Every component simulated as Gamma pending per-metric dist_family
                // wiring (see the note below this block) -- the same fallback
                // Task 6's drawOne() already uses for NEGBINOM/BINOMIAL, so this is
                // a real, defined distribution choice today, not a stub.
                val distributions = mergedComponents.map {
                    DistributionSpec(Component(it.metricId), DistributionFamily.GAMMA, it.mean, it.variance)
                }
                val floorCeiling = withContext(simulationDispatcher) { simulate(distributions, profile, position) }

                if (currentRequestKey != requestKey) return@launch // re-check after the CPU-bound simulate() call
                _state.value = ProjectionsUiState.Loaded(
                    playerId,
                    baselinePoints,
                    finalPoints,
                    attributed,
                    floorCeiling,
                    tdDependenceValue,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = ProjectionsUiState.Failed(e.message ?: "Failed to load projection")
            }
        }
    }

    public companion object {
        public fun factory(repository: ProjectionsRepository): ViewModelProvider.Factory =
            viewModelFactory {
                initializer { ProjectionsViewModel(repository) }
            }
    }
}
