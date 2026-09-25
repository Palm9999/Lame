package dev.gridiron.feature.compare

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.gridiron.core.data.ComparePage
import dev.gridiron.core.data.CompareRowUi
import dev.gridiron.core.designsystem.SlotColors
import dev.gridiron.core.table.StatTable
import dev.gridiron.core.table.TableColumn
import kotlinx.collections.immutable.toImmutableList

private val FrozenWidth = 148.sp
private val ColumnWidth = 84.sp
private val RowHeight = 52.sp
private val HeaderHeight = 48.sp
private const val DIFF_KEY = "diff"

/** Head-to-head: stats as rows, slots as columns, with a Diff column for exactly two slots. */
@Composable
internal fun TableTab(page: ComparePage, onlyDifferences: Boolean, modifier: Modifier = Modifier) {
    val rows = page.groups.flatMap { it.rows }
        .filter { !onlyDifferences || (it.spread?.let { s -> s > 0.10f } ?: false) }
        .toImmutableList()
    val slotCount = page.slots.size
    val showDiff = slotCount == 2
    val columns = (0 until slotCount).map { TableColumn(it, ColumnWidth) }
        .let { if (showDiff) it + TableColumn(DIFF_KEY, ColumnWidth) else it }
        .toImmutableList()

    StatTable(
        columns = columns,
        rows = rows,
        rowKey = CompareRowUi::column,
        frozenWidth = FrozenWidth,
        rowHeight = RowHeight,
        headerHeight = HeaderHeight,
        modifier = modifier.fillMaxSize(),
        frozenHeader = {
            Text(
                "STAT",
                Modifier.align(Alignment.CenterStart).padding(start = 16.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        header = { i ->
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (showDiff && i == slotCount) {
                    Text("Diff", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    val slot = page.slots[i]
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp).background(SlotColors.color(i), CircleShape))
                        Spacer(Modifier.width(4.dp))
                        Text(
                            slot.name,
                            Modifier.padding(end = 4.dp),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
        },
        frozenCell = { _, row ->
            Text(
                row.label,
                Modifier.align(Alignment.CenterStart).padding(start = 16.dp, end = 4.dp),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        cell = { row, i ->
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (showDiff && i == slotCount) {
                    Text(row.diff ?: "—", style = MaterialTheme.typography.bodyMedium)
                } else {
                    val cell = row.cells[i]
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            cell.text,
                            fontWeight = if (row.best == i) FontWeight.Bold else FontWeight.Normal,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (cell.percentile != null) {
                            Text(percentileText(cell.percentile), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        },
        rowDescription = { row -> "${row.label}: " + row.cells.joinToString(", ") { it.text } },
    )
}
