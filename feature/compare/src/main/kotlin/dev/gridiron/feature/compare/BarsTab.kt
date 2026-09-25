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
import dev.gridiron.core.charts.ordinal
import dev.gridiron.core.data.CompareGroupUi
import dev.gridiron.core.data.CompareRowUi
import dev.gridiron.core.designsystem.SlotColors
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList

/** "78th"/"92nd"/"83rd", or "—" for a slot with no ranked value at all. */
internal fun percentileText(percentile: Float?): String = percentile?.let { ordinal((it * 100).toInt()) } ?: "—"

/**
 * Every group as a composite row plus one bar row per stat. Long-press or
 * hover a stat's label (S Pen or mouse) for its definition. Only [charted]
 * slots get a bar: a slot with no data (no games, no season) is excluded from
 * charts, and the rest keep their slot colors so they still match the header.
 */
@Composable
internal fun BarsTab(groups: ImmutableList<CompareGroupUi>, charted: List<Int>, modifier: Modifier = Modifier) {
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
                    bars = charted.map { i -> Bar(group.composite[i], percentileText(group.composite[i]), SlotColors.color(i)) }
                        .toImmutableList(),
                )
            }
            items(group.rows, key = { "row:${group.group}:${it.column}" }) { row -> StatBarRow(row, charted) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StatBarRow(row: CompareRowUi, charted: List<Int>) {
    val bars = charted.map { i -> Bar(row.cells[i].percentile, row.cells[i].text, SlotColors.color(i)) }.toImmutableList()
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
