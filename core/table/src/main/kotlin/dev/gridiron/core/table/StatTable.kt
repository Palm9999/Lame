package dev.gridiron.core.table

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import kotlinx.collections.immutable.ImmutableList

/**
 * A column's width, in sp rather than dp so it grows with the user's font size.
 * Fixed dp widths silently clip "128.4" to "128." at large font scales, which
 * is worse than a visibly broken layout.
 */
@Immutable
public data class TableColumn(val key: Any, val width: TextUnit)

/**
 * A table with a frozen first column and horizontally scrolling stat columns.
 *
 * Every row and the header share one hoisted [ScrollState], with the frozen
 * cell outside the scrolling part. One state means one fling and no drift:
 * per-row lazy rows would each own a scroll position and fight to stay in sync.
 *
 * Accessibility follows the spec: each row is a single node described by
 * [rowDescription], rather than a cell-by-cell tree a screen reader would need
 * forty swipes to cross.
 */
@Composable
public fun <R> StatTable(
    columns: ImmutableList<TableColumn>,
    rows: ImmutableList<R>,
    rowKey: (R) -> Any,
    frozenWidth: TextUnit,
    rowHeight: TextUnit,
    headerHeight: TextUnit,
    frozenHeader: @Composable BoxScope.() -> Unit,
    header: @Composable BoxScope.(columnIndex: Int) -> Unit,
    frozenCell: @Composable BoxScope.(index: Int, row: R) -> Unit,
    cell: @Composable BoxScope.(row: R, columnIndex: Int) -> Unit,
    rowDescription: (R) -> String,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    horizontalState: ScrollState = rememberScrollState(),
) {
    val density = LocalDensity.current
    val frozen = with(density) { frozenWidth.toDp() }
    val widths = remember(columns, density) { columns.map { with(density) { it.width.toDp() } } }
    val rowH = with(density) { rowHeight.toDp() }
    val headerH = with(density) { headerHeight.toDp() }

    // Read only inside the draw phase, so scrolling redraws the edge shadow
    // without recomposing a single row.
    val scrolled by remember(horizontalState) { derivedStateOf { horizontalState.value > 0 } }
    val shadow = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
    val frozenEdge = Modifier.drawWithContent {
        drawContent()
        if (scrolled) {
            drawRect(
                brush = Brush.horizontalGradient(listOf(shadow, Color.Transparent), startX = size.width, endX = size.width + 6.dp.toPx()),
                topLeft = Offset(size.width, 0f),
                size = Size(6.dp.toPx(), size.height),
            )
        }
    }

    val surface = MaterialTheme.colorScheme.surface
    val zebra = MaterialTheme.colorScheme.surfaceContainerLow

    Column(modifier) {
        Row(Modifier.height(headerH).background(MaterialTheme.colorScheme.surfaceContainer)) {
            Box(Modifier.width(frozen).fillMaxHeight().then(frozenEdge), content = frozenHeader)
            Row(Modifier.horizontalScroll(horizontalState)) {
                widths.forEachIndexed { i, w -> Box(Modifier.width(w).fillMaxHeight()) { header(i) } }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        LazyColumn(state = listState) {
            itemsIndexed(rows, key = { _, row -> rowKey(row) }) { index, row ->
                val background = if (index % 2 == 1) zebra else surface
                Row(
                    Modifier
                        .height(rowH)
                        .background(background)
                        .clearAndSetSemantics { contentDescription = rowDescription(row) },
                ) {
                    Box(Modifier.width(frozen).fillMaxHeight().background(background).then(frozenEdge)) {
                        frozenCell(index, row)
                    }
                    Row(Modifier.horizontalScroll(horizontalState)) {
                        widths.forEachIndexed { i, w -> Box(Modifier.width(w).fillMaxHeight()) { cell(row, i) } }
                    }
                }
            }
        }
    }
}
