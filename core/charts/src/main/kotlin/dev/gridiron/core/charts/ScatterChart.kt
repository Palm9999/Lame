package dev.gridiron.core.charts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import kotlinx.collections.immutable.ImmutableList
import java.text.DecimalFormat

@Immutable
public data class ScatterPoint(
    val id: String,
    val x: Float,
    val y: Float,
    val color: Color? = null,
    val label: String? = null,
)

/**
 * Expected against actual. Neutral dots are the population, colored dots the
 * compared players. The diagonal is actual = expected: above it a player is
 * outscoring his opportunity (sell high), below it he's due (buy low).
 * Tap, S Pen hover or mouse hover selects the nearest point.
 */
@Composable
public fun ScatterChart(
    points: ImmutableList<ScatterPoint>,
    xLabel: String,
    yLabel: String,
    aboveLabel: String,
    belowLabel: String,
    contentDescription: String,
    selectedId: String?,
    onSelect: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val bounds = remember(points) { ScatterScale.bounds(points) }
    val ticks = remember(bounds) { ScatterScale.ticks(bounds) }
    val measurer = rememberTextMeasurer()
    val colors = MaterialTheme.colorScheme
    val tickStyle = MaterialTheme.typography.labelSmall.copy(color = colors.onSurfaceVariant)
    val noteStyle = MaterialTheme.typography.labelMedium.copy(color = colors.onSurfaceVariant)
    val labelStyle = MaterialTheme.typography.labelMedium.copy(color = colors.onSurface)
    val density = LocalDensity.current
    val gutter = with(density) { 36.dp.toPx() }
    var plot by remember { mutableStateOf(Size.Zero) }

    fun toScreen(p: ScatterPoint): Offset {
        val span = bounds.endInclusive - bounds.start
        return Offset(
            gutter + (p.x - bounds.start) / span * (plot.width - gutter),
            (plot.height - gutter) * (1 - (p.y - bounds.start) / span),
        )
    }
    val radius = with(density) { 24.dp.toPx() }

    Canvas(
        modifier
            .aspectRatio(1f)
            .padding(8.dp)
            .onSizeChanged { plot = it.toSize() }
            .semantics { this.contentDescription = contentDescription }
            .pointerInput(points, plot) {
                detectTapGestures { at -> onSelect(ScatterScale.nearest(points, ::toScreen, at, radius)?.id) }
            }
            .pointerInput(points, plot) {
                awaitPointerEventScope {
                    while (true) {
                        val e = awaitPointerEvent()
                        val change = e.changes.firstOrNull() ?: continue
                        val hovering = e.type == PointerEventType.Move && !change.pressed &&
                            (change.type == PointerType.Stylus || change.type == PointerType.Mouse)
                        if (hovering) ScatterScale.nearest(points, ::toScreen, change.position, radius)?.let { onSelect(it.id) }
                    }
                }
            },
    ) {
        val lo = bounds.start
        val hi = bounds.endInclusive
        // grid and tick labels
        ticks.forEach { t ->
            val x = toScreen(ScatterPoint("", t, lo)).x
            val y = toScreen(ScatterPoint("", lo, t)).y
            drawLine(colors.outlineVariant, Offset(x, 0f), Offset(x, size.height - gutter), 1f)
            drawLine(colors.outlineVariant, Offset(gutter, y), Offset(size.width, y), 1f)
            val tl = measurer.measure(DecimalFormat("0").format(t), tickStyle)
            drawText(tl, topLeft = Offset(x - tl.size.width / 2f, size.height - gutter + 4f))
            drawText(tl, topLeft = Offset(gutter - tl.size.width - 6f, y - tl.size.height / 2f))
        }
        // actual = expected
        drawLine(
            colors.outline, toScreen(ScatterPoint("", lo, lo)), toScreen(ScatterPoint("", hi, hi)), 2f,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)),
        )
        drawText(measurer.measure(aboveLabel, noteStyle), topLeft = Offset(gutter + 8f, 8f))
        val below = measurer.measure(belowLabel, noteStyle)
        drawText(below, topLeft = Offset(size.width - below.size.width - 8f, size.height - gutter - below.size.height - 8f))
        // axis titles
        val xl = measurer.measure(xLabel, tickStyle)
        drawText(xl, topLeft = Offset((size.width + gutter - xl.size.width) / 2, size.height - xl.size.height))
        val yl = measurer.measure(yLabel, tickStyle)
        // Rotate around the label's own center, placed at the middle of the left
        // gutter: that keeps the whole title inside the gutter instead of
        // spilling past the canvas edge.
        val yPivot = Offset(gutter / 2f, (size.height - gutter) / 2f)
        rotate(-90f, pivot = yPivot) {
            drawText(yl, topLeft = yPivot - Offset(yl.size.width / 2f, yl.size.height / 2f))
        }
        // population first, highlighted players on top
        points.filter { it.color == null }.forEach { drawCircle(colors.onSurfaceVariant.copy(alpha = 0.35f), 3.dp.toPx(), toScreen(it)) }
        points.filter { it.color != null }.forEach { p ->
            val o = toScreen(p)
            drawCircle(p.color!!, 6.dp.toPx(), o)
            // Cleared of the 9dp+2dp selection ring drawn below, so a selected
            // point's label doesn't get sliced by the ring's outline.
            p.label?.let { drawText(measurer.measure(it, labelStyle), topLeft = o + Offset(14.dp.toPx(), -9.dp.toPx())) }
        }
        points.firstOrNull { it.id == selectedId }?.let { drawCircle(colors.primary, 9.dp.toPx(), toScreen(it), style = Stroke(2.dp.toPx())) }
    }
}
