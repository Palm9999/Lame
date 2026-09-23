package dev.gridiron.core.charts

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp
import kotlinx.collections.immutable.ImmutableList

/**
 * A tiny trend line. Nulls are gaps: the line breaks rather than dropping to
 * zero, and a point with no neighbor still shows as a dot. The last point is
 * emphasized. Draws nothing for fewer than two points. Decorative: the
 * containing row describes the values for screen readers.
 */
@Composable
public fun Sparkline(values: ImmutableList<Float?>, color: Color, modifier: Modifier = Modifier) {
    val present = values.filterNotNull()
    if (present.size < 2) return
    Canvas(modifier) {
        val pad = 2.dp.toPx()
        val min = present.min()
        val span = (present.max() - min).takeIf { it > 0f }
        val stepX = (size.width - 2 * pad) / (values.size - 1).coerceAtLeast(1)
        val h = size.height - 2 * pad
        fun at(i: Int, v: Float) = Offset(
            pad + i * stepX,
            // A flat line sits in the middle rather than on the floor.
            pad + if (span == null) h / 2 else h - (v - min) / span * h,
        )

        val stroke = 1.5.dp.toPx()
        values.forEachIndexed { i, v ->
            if (v == null) return@forEachIndexed
            val p = at(i, v)
            val next = values.getOrNull(i + 1)
            if (next != null) drawLine(color, p, at(i + 1, next), strokeWidth = stroke, cap = StrokeCap.Round)
            val isolated = values.getOrNull(i - 1) == null && next == null
            if (isolated) drawCircle(color, radius = stroke, center = p)
        }
        val last = values.indexOfLast { it != null }
        drawCircle(color, radius = 2.dp.toPx(), center = at(last, values[last]!!))
    }
}
