package dev.gridiron.feature.projections

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.gridiron.core.data.ProjectionsRepository
import dev.gridiron.core.model.ScoringPresets

/**
 * Hosts [WaterfallCard] for one player/season/week. Always scores with
 * [ScoringPresets.PPR] and no position override -- profile/position aren't
 * wired through navigation yet, a deliberate scope limit (see task-11 brief).
 */
@Composable
public fun ProjectionsRoute(
    playerId: String,
    season: Int,
    week: Int,
    repository: ProjectionsRepository,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val vm: ProjectionsViewModel = viewModel(factory = ProjectionsViewModel.factory(repository))
    val state by vm.state.collectAsStateWithLifecycle()

    LaunchedEffect(playerId, season, week) {
        vm.load(playerId, season, week, ScoringPresets.PPR, position = null)
    }

    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            TextButton(onClick = onBack, modifier = Modifier.padding(top = 8.dp)) { Text("← Back") }
            when (val s = state) {
                ProjectionsUiState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                ProjectionsUiState.Empty -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No projection available")
                }
                is ProjectionsUiState.Failed -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(s.message)
                }
                is ProjectionsUiState.Loaded -> WaterfallCard(
                    baseline = s.baseline,
                    factors = s.factors,
                    final = s.final,
                    floorCeiling = s.floorCeiling,
                    tdDependenceValue = s.tdDependence,
                    modifier = Modifier.padding(top = 48.dp),
                )
            }
        }
    }
}
