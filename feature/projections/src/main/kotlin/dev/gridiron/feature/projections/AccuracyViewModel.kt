package dev.gridiron.feature.projections

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.gridiron.core.data.AccuracyRepository
import dev.gridiron.core.model.ScoringProfile
import dev.gridiron.core.projections.PositionAccuracy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

public sealed interface AccuracyState {
    public data object Loading : AccuracyState

    public data class Unavailable(val message: String) : AccuracyState

    public data class Loaded(
        val season: Int,
        /** Seasons with a finished week projected, oldest first. */
        val seasons: List<Int>,
        /** The scoring profile's name: every number is in its points. */
        val profile: String,
        /** By position. Empty until some player has two games in [season]. */
        val positions: List<PositionAccuracy>,
    ) : AccuracyState
}

/** Runs a season's backtest under the active profile, off the main thread. */
public class AccuracyViewModel(
    private val repository: AccuracyRepository,
    private val compute: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {
    private val _state = MutableStateFlow<AccuracyState>(AccuracyState.Loading)
    public val state: StateFlow<AccuracyState> = _state.asStateFlow()

    // A load superseded by a newer one (another season, a profile switch) must not overwrite it.
    private var latest = 0

    public fun load(season: Int, profile: ScoringProfile) {
        val request = ++latest
        _state.value = AccuracyState.Loading
        viewModelScope.launch {
            val next = try {
                build(season, profile)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AccuracyState.Unavailable("Couldn't measure accuracy: ${e.message}.")
            }
            if (request == latest) _state.value = next
        }
    }

    private suspend fun build(season: Int, profile: ScoringProfile): AccuracyState {
        val status = repository.status().status
            ?: return AccuracyState.Unavailable("No projections yet. Refresh stats to build them.")
        if (status != "ok") return AccuracyState.Unavailable("Projections unavailable: $status.")
        val seasons = repository.seasons()
        if (seasons.isEmpty()) return AccuracyState.Unavailable("No finished weeks have been projected yet.")
        val shown = if (season in seasons) season else seasons.last()
        val positions = withContext(compute) { repository.backtest(shown, profile) }
        return AccuracyState.Loaded(shown, seasons, profile.name, positions)
    }

    public companion object {
        public fun factory(repository: AccuracyRepository): ViewModelProvider.Factory =
            viewModelFactory { initializer { AccuracyViewModel(repository) } }
    }
}
