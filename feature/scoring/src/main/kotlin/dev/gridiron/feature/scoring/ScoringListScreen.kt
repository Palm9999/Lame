package dev.gridiron.feature.scoring

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.gridiron.core.data.ScoringRepository
import dev.gridiron.core.model.ScoringPresets
import dev.gridiron.core.model.ScoringProfile

@Composable
fun ScoringListRoute(scoring: ScoringRepository, onEdit: (String) -> Unit, onBack: () -> Unit) {
    val vm: ScoringListViewModel = viewModel(factory = ScoringListViewModel.factory(scoring))
    val state by vm.state.collectAsStateWithLifecycle()
    ScoringListScreen(state, vm::onEvent, onEdit, onBack)
}

@Composable
internal fun ScoringListScreen(
    state: ListState,
    onEvent: (ListEvent) -> Unit,
    onEdit: (String) -> Unit,
    onBack: () -> Unit,
) {
    // A Surface, not a Box with a background: it also sets the content color
    // that every Text inherits. Without it, text defaults to black, which is
    // unreadable in dark mode.
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onBack) { Text("← Back") }
                    Text("Scoring profiles", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
                HorizontalDivider()
                LazyColumn(Modifier.weight(1f)) {
                    items(state.profiles, key = { it.id }) { profile ->
                        ProfileRow(profile, state.activeId, onEvent, onEdit)
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    LaunchedEffect(state.editRequest) {
        state.editRequest?.let {
            onEdit(it)
            onEvent(ListEvent.EditOpened)
        }
    }
}

@Composable
private fun ProfileRow(profile: ScoringProfile, activeId: String, onEvent: (ListEvent) -> Unit, onEdit: (String) -> Unit) {
    var confirmDelete by remember { mutableStateOf(false) }
    val basePreset = profile.basedOn?.let(ScoringPresets::byId)
    val subtitle = when {
        profile.isPreset -> "Preset"
        basePreset != null -> "Custom · based on ${basePreset.name}"
        else -> "Custom"
    }

    Column(Modifier.fillMaxWidth().testTag("profile:${profile.id}").padding(horizontal = 8.dp, vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = profile.id == activeId, onClick = { onEvent(ListEvent.SetActive(profile.id)) })
            Column(Modifier.weight(1f)) {
                Text(profile.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                Text(subtitle, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 40.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (!profile.isPreset) {
                TextButton(onClick = { onEdit(profile.id) }, modifier = Modifier.testTag("edit:${profile.id}")) { Text("Edit") }
            }
            TextButton(
                onClick = { onEvent(ListEvent.Duplicate(profile.id)) },
                modifier = Modifier.testTag("duplicate:${profile.id}"),
            ) { Text("Duplicate") }
            if (!profile.isPreset) {
                TextButton(onClick = { confirmDelete = true }, modifier = Modifier.testTag("delete:${profile.id}")) { Text("Delete") }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete ${profile.name}?") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; onEvent(ListEvent.Delete(profile.id)) }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}
