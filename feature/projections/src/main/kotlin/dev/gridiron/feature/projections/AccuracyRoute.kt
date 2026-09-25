package dev.gridiron.feature.projections

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.gridiron.core.data.AccuracyRepository
import dev.gridiron.core.data.AccuracyRow
import kotlinx.coroutines.CancellationException

/** Local, one-shot loading state -- this screen has no ViewModel, so there is
 * no shared UI-state type to reuse. */
private sealed interface AccuracyUiState {
    object Loading : AccuracyUiState
    data class Loaded(val rows: List<AccuracyRow>) : AccuracyUiState
    data class Failed(val message: String) : AccuracyUiState
}

/** Loads the season's accuracy summary and renders [AccuracyScreen], matching
 * the app's screen scaffold (surface color, safe-drawing insets, back
 * button) the same way [ProjectionsRoute] does. No ViewModel -- this screen
 * has no interaction, just a one-shot read. */
@Composable
public fun AccuracyRoute(
    season: Int,
    repository: AccuracyRepository,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var state by remember { mutableStateOf<AccuracyUiState>(AccuracyUiState.Loading) }
    LaunchedEffect(season) {
        state = try {
            AccuracyUiState.Loaded(repository.summary(season))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AccuracyUiState.Failed(e.message ?: "Failed to load accuracy summary")
        }
    }

    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            TextButton(onClick = onBack, modifier = Modifier.padding(top = 8.dp)) { Text("← Back") }
            when (val s = state) {
                AccuracyUiState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Loading…")
                }
                is AccuracyUiState.Failed -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(s.message)
                }
                is AccuracyUiState.Loaded -> AccuracyScreen(s.rows, modifier = Modifier.padding(top = 48.dp))
            }
        }
    }
}
