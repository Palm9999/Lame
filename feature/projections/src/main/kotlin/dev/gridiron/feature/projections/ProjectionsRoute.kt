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
import dev.gridiron.core.data.PlayerDirectory
import dev.gridiron.core.data.ProjectionsRepository
import dev.gridiron.core.data.ScoringRepository
import dev.gridiron.core.model.Position
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/** Hosts [WaterfallCard] for one player's week, scored with the active profile and the player's own position. */
@Composable
public fun ProjectionsRoute(
    playerId: String,
    season: Int,
    week: Int,
    repository: ProjectionsRepository,
    scoring: ScoringRepository,
    players: PlayerDirectory?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    /** Bumped when a refresh swaps in new stats: the waterfall loads again. */
    dataVersion: Flow<Long> = flowOf(0L),
) {
    val vm: ProjectionsViewModel = viewModel(factory = ProjectionsViewModel.factory(repository))
    val state by vm.state.collectAsStateWithLifecycle()
    val profile by scoring.active.collectAsStateWithLifecycle(initialValue = null)

    val version by dataVersion.collectAsStateWithLifecycle(initialValue = 0L)

    LaunchedEffect(playerId, season, week, profile, version) {
        val active = profile ?: return@LaunchedEffect
        val position = players?.header(playerId)?.position?.let(Position::fromCode)
        vm.load(playerId, season, week, active, position)
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
