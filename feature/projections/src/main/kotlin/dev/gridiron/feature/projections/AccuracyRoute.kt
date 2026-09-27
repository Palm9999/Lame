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

/** ☰ → Projection accuracy, starting at [season] and scored with the active profile. */
@Composable
public fun AccuracyRoute(season: Int, repository: AccuracyRepository, scoring: ScoringRepository, onBack: () -> Unit) {
    val vm: AccuracyViewModel = viewModel(factory = AccuracyViewModel.factory(repository))
    val state by vm.state.collectAsStateWithLifecycle()
    val profile by scoring.active.collectAsStateWithLifecycle<ScoringProfile?>(initialValue = null)
    var shown by rememberSaveable { mutableIntStateOf(season) }
    LaunchedEffect(shown, profile) { profile?.let { vm.load(shown, it) } }
    AccuracyScreen(state, onSeason = { shown = it }, onBack = onBack)
}
