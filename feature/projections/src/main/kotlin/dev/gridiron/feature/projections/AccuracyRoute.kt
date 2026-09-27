package dev.gridiron.feature.projections

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.gridiron.core.data.AccuracyRepository
import dev.gridiron.core.data.ScoringRepository
import dev.gridiron.core.model.ScoringProfile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * ☰ → Projection accuracy, starting at [season] and scored with the active
 * profile. A new [dataVersion] (a refresh swapped in new stats) measures again.
 */
@Composable
public fun AccuracyRoute(
    season: Int,
    repository: AccuracyRepository,
    scoring: ScoringRepository,
    onBack: () -> Unit,
    dataVersion: Flow<Long> = flowOf(0L),
) {
    val vm: AccuracyViewModel = viewModel(factory = AccuracyViewModel.factory(repository))
    val state by vm.state.collectAsStateWithLifecycle()
    val profile by scoring.active.collectAsStateWithLifecycle<ScoringProfile?>(initialValue = null)
    val version by dataVersion.collectAsStateWithLifecycle(initialValue = 0L)
    var shown by rememberSaveable { mutableIntStateOf(season) }
    LaunchedEffect(shown, profile, version) { profile?.let { vm.load(shown, it, version) } }
    AccuracyScreen(state, onSeason = { shown = it }, onBack = onBack)
}
