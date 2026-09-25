package dev.gridiron.feature.scoring

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.gridiron.core.data.ScoringRepository
import dev.gridiron.core.model.BonusStat
import dev.gridiron.core.model.ScoringGroup
import dev.gridiron.core.model.ScoringProfile
import dev.gridiron.core.model.ScoringRule

@Composable
fun ScoringEditRoute(profileId: String, scoring: ScoringRepository, onDone: () -> Unit) {
    val vm: ScoringEditViewModel = viewModel(key = profileId, factory = ScoringEditViewModel.factory(profileId, scoring))
    val state by vm.state.collectAsStateWithLifecycle()
    ScoringEditScreen(state, vm::onEvent, onDone)
}

@Composable
internal fun ScoringEditScreen(state: EditState, onEvent: (EditEvent) -> Unit, onBack: () -> Unit) {
    // A Surface, not a Box with a background: it also sets the content color
    // that every Text inherits. Without it, text defaults to black, which is
    // unreadable in dark mode.
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            when (state) {
                EditState.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                EditState.NotFound -> Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("This profile no longer exists.", style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(12.dp))
                    TextButton(onClick = onBack) { Text("Back") }
                }
                is EditState.Editing -> EditingContent(state, onEvent, onBack)
            }
        }
    }
}

@Composable
private fun EditingContent(state: EditState.Editing, onEvent: (EditEvent) -> Unit, onBack: () -> Unit) {
    var confirmDiscard by remember { mutableStateOf(false) }

    LaunchedEffect(state.saved) { if (state.saved) onBack() }
    // The back gesture asks too, exactly like "← Back"; with nothing unsaved it
    // stays disabled and back goes straight to the previous screen.
    BackHandler(enabled = state.dirty && !state.saved) { confirmDiscard = true }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { if (state.dirty && !state.saved) confirmDiscard = true else onBack() }) { Text("← Back") }
            Text(
                "Edit scoring",
                Modifier.weight(1f).padding(start = 4.dp),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Button(onClick = { onEvent(EditEvent.Save) }, enabled = state.profile != null && state.dirty && !state.readOnly) {
                Text("Save")
            }
        }

        if (state.readOnly) {
            Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
                Text(
                    "Presets can't be edited. Duplicate this one from the list to make your own.",
                    Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }

        OutlinedTextField(
            value = state.name,
            onValueChange = { onEvent(EditEvent.NameChanged(it)) },
            label = { Text("Name") },
            singleLine = true,
            enabled = !state.readOnly,
            isError = state.errors[FieldKey.Name] != null,
            supportingText = state.errors[FieldKey.Name]?.let { { Text(it) } },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).testTag("field:name"),
        )

        LazyColumn(Modifier.weight(1f).testTag("editorFields")) {
            ScoringGroup.entries.forEach { group ->
                item(key = "header:$group") { GroupHeader(group.label) }
                items(ScoringRule.entries.filter { it.group == group }, key = { it.name }) { rule ->
                    WeightRow(
                        label = rule.label,
                        value = state.weights.getValue(rule),
                        tag = "field:${rule.name}",
                        error = state.errors[FieldKey.Weight(rule)],
                        enabled = !state.readOnly,
                        onChange = { onEvent(EditEvent.WeightChanged(rule, it)) },
                    )
                    if (rule == ScoringRule.RECEPTION) {
                        ScoringProfile.RECEPTION_POSITIONS.forEach { position ->
                            WeightRow(
                                label = "Reception, ${position.code}",
                                value = state.reception[position].orEmpty(),
                                tag = "field:reception:${position.name}",
                                placeholder = "same",
                                error = state.errors[FieldKey.Reception(position)],
                                enabled = !state.readOnly,
                                onChange = { onEvent(EditEvent.ReceptionChanged(position, it)) },
                            )
                        }
                    }
                }
            }

            item(key = "bonusesHeader") {
                GroupHeader("Yardage bonuses")
                Text(
                    "Awarded once per game when the stat lands in the range. 100 to 200 means 100–199.",
                    Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            items(state.bonuses, key = { it.key }) { draft ->
                BonusCard(
                    draft = draft,
                    errors = state.errors,
                    enabled = !state.readOnly,
                    onChange = { onEvent(EditEvent.BonusChanged(draft.key, it)) },
                    onRemove = { onEvent(EditEvent.BonusRemoved(draft.key)) },
                )
            }
            if (!state.readOnly) {
                item(key = "addBonus") {
                    TextButton(onClick = { onEvent(EditEvent.BonusAdded) }, modifier = Modifier.padding(horizontal = 8.dp)) {
                        Text("+ Add yardage bonus")
                    }
                }
            }
            if (state.original.basedOn != null && !state.readOnly) {
                item(key = "resetToPreset") {
                    TextButton(onClick = { onEvent(EditEvent.ResetToPreset) }, modifier = Modifier.padding(horizontal = 8.dp)) {
                        Text("Reset to preset")
                    }
                }
            }
            item(key = "bottomSpace") { Spacer(Modifier.height(24.dp)) }
        }
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("Discard changes?") },
            confirmButton = { TextButton(onClick = { confirmDiscard = false; onBack() }) { Text("Discard") } },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("Keep editing") } },
        )
    }
}

@Composable
private fun GroupHeader(label: String) {
    Text(
        label,
        Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold,
    )
}

@Composable
private fun WeightRow(
    label: String,
    value: String,
    tag: String,
    error: String?,
    enabled: Boolean,
    onChange: (String) -> Unit,
    placeholder: String? = null,
) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f).padding(end = 8.dp), style = MaterialTheme.typography.bodyMedium)
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            enabled = enabled,
            isError = error != null,
            supportingText = error?.let { { Text(it) } },
            placeholder = placeholder?.let { { Text(it) } },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.width(96.dp).testTag(tag),
        )
    }
}

@Composable
private fun BonusCard(
    draft: BonusDraft,
    errors: Map<FieldKey, String>,
    enabled: Boolean,
    onChange: (BonusDraft) -> Unit,
    onRemove: () -> Unit,
) {
    var statMenu by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    TextButton(onClick = { statMenu = true }, enabled = enabled) { Text("${draft.stat.label} ▾") }
                    DropdownMenu(expanded = statMenu, onDismissRequest = { statMenu = false }) {
                        BonusStat.entries.forEach { stat ->
                            DropdownMenuItem(text = { Text(stat.label) }, onClick = { statMenu = false; onChange(draft.copy(stat = stat)) })
                        }
                    }
                }
                if (enabled) TextButton(onClick = onRemove, modifier = Modifier.testTag("bonus:remove:${draft.key}")) { Text("Remove") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = draft.min,
                    onValueChange = { onChange(draft.copy(min = it)) },
                    label = { Text("Min") },
                    singleLine = true,
                    enabled = enabled,
                    isError = errors[FieldKey.BonusMin(draft.key)] != null,
                    supportingText = errors[FieldKey.BonusMin(draft.key)]?.let { { Text(it) } },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f).testTag("bonus:min:${draft.key}"),
                )
                OutlinedTextField(
                    value = draft.max,
                    onValueChange = { onChange(draft.copy(max = it)) },
                    label = { Text("Max") },
                    placeholder = { Text("no limit") },
                    singleLine = true,
                    enabled = enabled,
                    isError = errors[FieldKey.BonusMax(draft.key)] != null,
                    supportingText = errors[FieldKey.BonusMax(draft.key)]?.let { { Text(it) } },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f).testTag("bonus:max:${draft.key}"),
                )
                OutlinedTextField(
                    value = draft.points,
                    onValueChange = { onChange(draft.copy(points = it)) },
                    label = { Text("Pts") },
                    singleLine = true,
                    enabled = enabled,
                    isError = errors[FieldKey.BonusPoints(draft.key)] != null,
                    supportingText = errors[FieldKey.BonusPoints(draft.key)]?.let { { Text(it) } },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f).testTag("bonus:points:${draft.key}"),
                )
            }
        }
    }
}
