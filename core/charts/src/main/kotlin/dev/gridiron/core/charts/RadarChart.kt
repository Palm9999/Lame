package dev.gridiron.core.charts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import kotlinx.collections.immutable.ImmutableList
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

@Immutable
public data class RadarSeries(val name: String, val values: ImmutableList<Float?>, val color: Color)

/**
 * Percentile "shape" for up to two players. Secondary to the bars: fixed axis
 * order, rings at 25/50/75/100, no more than eight axes.
 */
@Composable
public fun RadarChart(
    axes: ImmutableList<String>,
    series: ImmutableList<RadarSeries>,
    contentDescription: String,
    modifier: Modifier = Modifier,
) {
    require(axes.size in 3..8) { "a radar needs 3 to 8 axes, got ${axes.size}" }
    val measurer = rememberTextMeasurer()
    val grid = MaterialTheme.colorScheme.outlineVariant
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
    Canvas(modifier.aspectRatio(1f).padding(32.dp).semantics { this.contentDescription = contentDescription }) {
        val c = center
        val radius = size.minDimension / 2
        fun point(i: Int, v: Float): Offset {
            val a = (-PI / 2 + 2 * PI * i / axes.size).toFloat()
            return c + Offset(cos(a), sin(a)) * (radius * v.coerceIn(0f, 1f))
        }
        for (ring in listOf(0.25f, 0.5f, 0.75f, 1f)) {
            val path = Path().apply {
                axes.indices.forEach { i -> point(i, ring).let { if (i == 0) moveTo(it.x, it.y) else lineTo(it.x, it.y) } }
                close()
            }
            drawPath(path, grid, style = Stroke(1.dp.toPx()))
        }
        axes.forEachIndexed { i, label ->
            drawLine(grid, c, point(i, 1f), 1.dp.toPx())
            val layout = measurer.measure(label, labelStyle)
            val tip = point(i, 1.12f)
            drawText(layout, topLeft = tip - Offset(layout.size.width / 2f, layout.size.height / 2f))
        }
        series.forEach { s ->
            val path = Path().apply {
                s.values.forEachIndexed { i, v -> point(i, v ?: 0f).let { if (i == 0) moveTo(it.x, it.y) else lineTo(it.x, it.y) } }
                close()
            }
            drawPath(path, s.color.copy(alpha = 0.18f))
            drawPath(path, s.color, style = Stroke(2.dp.toPx()))
        }
    }
}
