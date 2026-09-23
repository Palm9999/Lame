package dev.gridiron.core.charts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.gridiron.core.designsystem.NumberStyle
import kotlinx.collections.immutable.ImmutableList

@Immutable
public data class Bar(val fraction: Float?, val text: String, val color: Color)

/**
 * One stat across the compared players: a 0–100 percentile bar per player with
 * its value beside it. A dashed outline with no fill means "not ranked".
 */
@Composable
public fun PercentileBarRow(
    label: String,
    bars: ImmutableList<Bar>,
    modifier: Modifier = Modifier,
    labelSlot: (@Composable () -> Unit)? = null,
) {
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    val midline = MaterialTheme.colorScheme.outline
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = "$label: " + bars.joinToString(", ") { b ->
                    b.text + (b.fraction?.let { ", ${ordinal((it * 100).toInt())} percentile" } ?: ", not ranked")
                }
            },
    ) {
        if (labelSlot != null) labelSlot() else Text(label, style = MaterialTheme.typography.labelLarge)
        bars.forEach { bar ->
            Row(Modifier.padding(top = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Canvas(Modifier.weight(1f).height(10.dp)) {
                    val r = CornerRadius(size.height / 2)
                    drawRoundRect(track, cornerRadius = r)
                    val f = bar.fraction
                    if (f == null) {
                        drawRoundRect(
                            bar.color.copy(alpha = 0.6f), cornerRadius = r,
                            style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))),
                        )
                    } else {
                        drawRoundRect(bar.color, size = size.copy(width = size.width * f.coerceIn(0f, 1f)), cornerRadius = r)
                    }
                    // The position median, so "above average" reads at a glance.
                    drawLine(midline, Offset(size.width / 2, 0f), Offset(size.width / 2, size.height), 1.dp.toPx())
                }
                Spacer(Modifier.width(8.dp))
                Text(bar.text, Modifier.width(72.dp), style = NumberStyle, textAlign = TextAlign.End, maxLines = 1)
            }
        }
    }
}
