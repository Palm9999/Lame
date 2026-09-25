package dev.gridiron.feature.players

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.gridiron.core.data.Catalog
import dev.gridiron.core.data.StatFormat
import dev.gridiron.core.data.StatPack
import dev.gridiron.core.data.filterColumnOrder
import dev.gridiron.core.statquery.Filter
import dev.gridiron.core.statquery.StatColumn

/** Team toggles that apply as you tap. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun TeamSheet(teams: List<String>, selected: Set<String>, onChange: (Set<String>) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            Text("Teams", Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
            Text(
                "By current team: a traded player counts for his new team in every week.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = selected.isEmpty(), onClick = { onChange(emptySet()) }, label = { Text("All teams") })
                teams.forEach { team ->
                    FilterChip(
                        selected = team in selected,
                        onClick = { onChange(if (team in selected) selected - team else selected + team) },
                        label = { Text(team) },
                        modifier = Modifier.testTag("team:$team"),
                    )
                }
            }
        }
    }
}

/**
 * Edits a draft of the advanced filters. Every change to the draft's complete
 * rows is reported for the live count; nothing reaches the Grid until Apply.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FilterSheet(
    catalog: Catalog,
    pack: StatPack,
    sort: StatColumn,
    perGame: Boolean,
    applied: List<Filter>,
    count: DraftCount?,
    onDraftChanged: (List<Filter>) -> Unit,
    onApply: (List<Filter>) -> Unit,
    onDismiss: () -> Unit,
) {
    var draft by remember { mutableStateOf(FilterDraft.of(applied)) }
    LaunchedEffect(draft.complete) { onDraftChanged(draft.complete) }
    val columns = remember(pack) { filterColumnOrder(pack) }
    fun name(c: StatColumn) = catalog.metrics[c.metricId]?.name ?: c.metricId

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp).verticalScroll(rememberScrollState())) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Filters", Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = { draft = draft.clear() }, enabled = draft.rows.isNotEmpty()) { Text("Clear all") }
            }
            if (perGame) {
                Text("Values are per game.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            draft.rows.forEachIndexed { index, row ->
                key(row.id) {
                    FilterRow(index, row, columns, ::name, onChange = { draft = draft.update(it) }, onRemove = { draft = draft.remove(row.id) })
                }
            }
            TextButton(
                onClick = { draft = draft.add(sort) },
                enabled = draft.canAdd,
                modifier = Modifier.testTag("filter:add"),
            ) { Text("+ Add filter") }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    when (count) {
                        null, DraftCount.Counting -> "Counting…"
                        is DraftCount.Matches -> if (count.count == 1) "1 player matches" else "${count.count} players match"
                        DraftCount.Unavailable -> "Count unavailable"
                    },
                    Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
                    style = MaterialTheme.typography.labelLarge,
                )
                TextButton(onClick = onDismiss) { Text("Cancel") }
                Button(onClick = { onApply(draft.complete) }, modifier = Modifier.testTag("filter:apply")) { Text("Apply") }
            }
        }
    }
}

@Composable
private fun FilterRow(
    index: Int,
    row: FilterRowDraft,
    columns: List<StatColumn>,
    name: (StatColumn) -> String,
    onChange: (FilterRowDraft) -> Unit,
    onRemove: () -> Unit,
) {
    var picking by remember { mutableStateOf(false) }
    val percent = StatFormat.isPercent(row.column)
    val error = row.showsError
    Column(Modifier.padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                TextButton(onClick = { picking = true }) { Text(name(row.column) + " ▾", maxLines = 1) }
                DropdownMenu(expanded = picking, onDismissRequest = { picking = false }) {
                    columns.forEach { c ->
                        DropdownMenuItem(text = { Text(name(c)) }, onClick = { picking = false; onChange(row.copy(column = c)) })
                    }
                }
            }
            TextButton(
                onClick = onRemove,
                modifier = Modifier.size(48.dp).semantics { contentDescription = "Remove filter on ${name(row.column)}" },
            ) { Text("✕") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            FilterOp.entries.forEach { op ->
                FilterChip(selected = row.op == op, onClick = { onChange(row.copy(op = op)) }, label = { Text(op.symbol) })
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val suffix: (@Composable () -> Unit)? = if (percent) ({ Text("%") }) else null
            OutlinedTextField(
                value = row.first,
                onValueChange = { onChange(row.copy(first = it)) },
                label = { Text(if (row.op == FilterOp.BETWEEN) "Min" else "Value") },
                suffix = suffix,
                isError = error,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(1f).testTag("filter:value:$index"),
            )
            if (row.op == FilterOp.BETWEEN) {
                OutlinedTextField(
                    value = row.second,
                    onValueChange = { onChange(row.copy(second = it)) },
                    label = { Text("Max") },
                    suffix = suffix,
                    isError = error,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f).testTag("filter:second:$index"),
                )
            }
        }
    }
}
