package dev.gridiron.feature.compare

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RichTooltip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.gridiron.core.charts.Bar
import dev.gridiron.core.charts.PercentileBarRow
import dev.gridiron.core.data.CompareGroupUi
import dev.gridiron.core.data.CompareRowUi
import dev.gridiron.core.designsystem.SlotColors
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList

/** "78th", or "—" for a slot with no ranked value at all. */
internal fun percentileText(percentile: Float?): String = percentile?.let { "${(it * 100).toInt()}th" } ?: "—"

/**
 * Every group as a composite row plus one bar row per stat. Long-press or
 * hover a stat's label (S Pen or mouse) for its definition.
 */
@Composable
internal fun BarsTab(groups: ImmutableList<CompareGroupUi>, modifier: Modifier = Modifier) {
    LazyColumn(modifier) {
        groups.forEach { group ->
            stickyHeader(key = "header:${group.group}") {
                Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                    Text(
                        group.group.label,
                        Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            item(key = "composite:${group.group}") {
                PercentileBarRow(
                    label = "Overall ${group.group.label}",
                    bars = group.composite.mapIndexed { i, c -> Bar(c, percentileText(c), SlotColors.color(i)) }.toImmutableList(),
                )
            }
            items(group.rows, key = { "row:${group.group}:${it.column}" }) { row -> StatBarRow(row) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StatBarRow(row: CompareRowUi) {
    val bars = row.cells.mapIndexed { i, cell -> Bar(cell.percentile, cell.text, SlotColors.color(i)) }.toImmutableList()
    val info = row.info
    PercentileBarRow(
        label = row.label,
        bars = bars,
        labelSlot = if (info == null) {
            null
        } else {
            {
                TooltipBox(
                    positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
                    tooltip = { RichTooltip(title = { Text(info.name) }) { Text(info.definition) } },
                    state = rememberTooltipState(),
                ) {
                    Text(row.label, style = MaterialTheme.typography.labelLarge)
                }
            }
        },
    )
}
