package dev.gridiron.feature.players

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.gridiron.core.datastore.MAX_PRESETS
import dev.gridiron.core.datastore.MAX_PRESET_NAME
import dev.gridiron.core.datastore.PresetWeeks

/**
 * The presets list. Saving, renaming and replacing swap the list for a short form
 * in the same sheet, so there is one window (and one keyboard) to manage.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PresetsSheet(state: GridUiState.Ready, sheet: PresetSheet, onEvent: (GridEvent) -> Unit) {
    ModalBottomSheet(onDismissRequest = { onEvent(GridEvent.PresetsClosed) }) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            when (sheet) {
                PresetSheet.Listing -> PresetList(state, onEvent)
                is PresetSheet.Saving -> SaveForm(sheet.weeks, state.request.season.defaultWeeks.last, onEvent)
                is PresetSheet.Renaming -> RenameForm(sheet, onEvent)
                is PresetSheet.ConfirmReplace -> {
                    Text("Replace \"${sheet.name}\"?", Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
                    Text(
                        "A preset with this name exists. Replace its view with the one you have open?",
                        Modifier.padding(vertical = 12.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    FormButtons("Replace", true, "presets:replace", { onEvent(GridEvent.PresetReplaceConfirmed) }, onEvent)
                }
            }
        }
    }
}

@Composable
private fun PresetList(state: GridUiState.Ready, onEvent: (GridEvent) -> Unit) {
    Text("Presets", Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
    if (state.presets.isEmpty()) {
        Text(
            "Save the view you have open to come back to it.",
            Modifier.padding(vertical = 12.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        LazyColumn(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(state.presets, key = { it.preset.id }) { row ->
                PresetRowItem(
                    row,
                    onApply = { onEvent(GridEvent.PresetApplied(row.preset.id)) },
                    onRename = { onEvent(GridEvent.PresetRenameRequested(row.preset.id)) },
                    onDelete = { onEvent(GridEvent.PresetDeleted(row.preset.id)) },
                )
            }
        }
    }
    state.deletedPreset?.let { deleted ->
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Deleted \"${deleted.name}\"", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = { onEvent(GridEvent.PresetDeleteUndone) }, modifier = Modifier.testTag("presets:undo")) { Text("Undo") }
        }
    }
    if (state.presetsFull) {
        Text(
            "You have $MAX_PRESETS presets. Delete one to save another.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    TextButton(
        onClick = { onEvent(GridEvent.PresetSaveRequested) },
        enabled = !state.presetsFull,
        modifier = Modifier.testTag("presets:save"),
    ) { Text("Save current view…") }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PresetRowItem(row: PresetRow, onApply: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Box {
        Column(
            Modifier
                .fillMaxWidth()
                .testTag("presets:row:${row.preset.id}")
                // An unavailable preset can't apply, so a tap offers the menu instead.
                .combinedClickable(
                    onClickLabel = if (row.unavailable == null) "Apply" else "Rename or delete",
                    onLongClickLabel = "Rename or delete",
                    onLongClick = { menu = true },
                    onClick = { if (row.unavailable == null) onApply() else menu = true },
                )
                .padding(vertical = 8.dp),
        ) {
            val muted = if (row.unavailable == null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
            Text(row.preset.name, style = MaterialTheme.typography.bodyLarge, color = muted)
            Text(row.summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            row.unavailable?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text("Rename") },
                onClick = { menu = false; onRename() },
                modifier = Modifier.testTag("presets:menu:rename"),
            )
            DropdownMenuItem(
                text = { Text("Delete") },
                onClick = { menu = false; onDelete() },
                modifier = Modifier.testTag("presets:menu:delete"),
            )
        }
    }
}

@Composable
private fun SaveForm(initial: PresetWeeks, maxWeeks: Int, onEvent: (GridEvent) -> Unit) {
    var name by remember { mutableStateOf("") }
    var whole by remember { mutableStateOf(initial is PresetWeeks.WholeSeason) }
    var n by remember { mutableStateOf(((initial as? PresetWeeks.LastN)?.n ?: 4).coerceIn(1, maxWeeks.coerceAtLeast(1))) }
    Text("Save current view", Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
    OutlinedTextField(
        value = name,
        onValueChange = { if (it.length <= MAX_PRESET_NAME) name = it },
        label = { Text("Name") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).testTag("presets:name"),
    )
    Text("Weeks", style = MaterialTheme.typography.labelLarge)
    Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = whole, onClick = { whole = true }, label = { Text("Whole season") }, modifier = Modifier.testTag("presets:weeks:whole"))
        FilterChip(selected = !whole, onClick = { whole = false }, label = { Text("Last $n weeks") }, modifier = Modifier.testTag("presets:weeks:last"))
    }
    if (!whole) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { n = (n - 1).coerceAtLeast(1) }, modifier = Modifier.testTag("presets:n:minus")) { Text("−") }
            Text("$n", style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = { n = (n + 1).coerceAtMost(maxWeeks.coerceAtLeast(1)) }, modifier = Modifier.testTag("presets:n:plus")) { Text("+") }
        }
    }
    FormButtons(
        "Save",
        name.isNotBlank(),
        "presets:confirm",
        { onEvent(GridEvent.PresetSaved(name, if (whole) PresetWeeks.WholeSeason else PresetWeeks.LastN(n))) },
        onEvent,
    )
}

@Composable
private fun RenameForm(sheet: PresetSheet.Renaming, onEvent: (GridEvent) -> Unit) {
    var name by remember { mutableStateOf(sheet.name) }
    Text("Rename preset", Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
    OutlinedTextField(
        value = name,
        onValueChange = { if (it.length <= MAX_PRESET_NAME) name = it },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).testTag("presets:name"),
    )
    FormButtons("Rename", name.isNotBlank(), "presets:rename:confirm", { onEvent(GridEvent.PresetRenamed(sheet.id, name)) }, onEvent)
}

/** Cancel (back to the list) and the confirming action, at the foot of a form. */
@Composable
private fun FormButtons(confirm: String, enabled: Boolean, tag: String, onConfirm: () -> Unit, onEvent: (GridEvent) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        TextButton(onClick = { onEvent(GridEvent.PresetDialogDismissed) }, modifier = Modifier.testTag("presets:cancel")) { Text("Cancel") }
        TextButton(onClick = onConfirm, enabled = enabled, modifier = Modifier.testTag(tag)) { Text(confirm) }
    }
}
