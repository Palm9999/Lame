package dev.gridiron.feature.projections

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlin.math.ceil

/** One player's line: his points in each of the chart's weeks, null on a bye or without a projection. */
internal data class ChartSeries(val playerId: String, val name: String, val points: List<Double?>)

/** At most [MAX_SERIES] players' weekly rest of season over [weeks]; a player with no weekly rows is left out. */
internal fun chartSeries(players: List<ProjectionRow>, weekly: Map<String, Map<Int, Double>>, weeks: List<Int>): List<ChartSeries> =
    players.mapNotNull { p ->
        val w = weekly[p.playerId] ?: return@mapNotNull null
        ChartSeries(p.playerId, p.name, weeks.map { w[it] })
    }.take(MAX_SERIES)

/** Three lines stay readable; the palette (validated for both modes) has three slots. */
internal const val MAX_SERIES = 3

// Categorical slots in fixed order, validated against the app's light (#F8FAF7) and dark (#111412) surfaces:
// light aqua sits under 3:1, so every line carries a direct label and the week table below repeats the numbers.
private val LIGHT = listOf(Color(0xFF2A78D6), Color(0xFFEB6834), Color(0xFF1BAF7A))
private val DARK = listOf(Color(0xFF3987E5), Color(0xFFD95926), Color(0xFF199E70))

@Composable
private fun seriesColor(index: Int): Color =
    (if (MaterialTheme.colorScheme.surface.luminance() < 0.5f) DARK else LIGHT)[index]

/**
 * The picked players' projections week by week through the fantasy playoffs ([playoffs] shaded): 2dp lines, a dot
 * per week, a gap on a bye. Tap or drag to read a week; a legend with totals sits above and the numbers in a table
 * below.
 */
@Composable
internal fun RosCompareChart(series: List<ChartSeries>, weeks: List<Int>, playoffs: Set<Int>) {
    if (series.isEmpty() || weeks.isEmpty()) return
    var selected by remember(series, weeks) { mutableStateOf<Int?>(null) }
    val ink = MaterialTheme.colorScheme.onSurface
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val grid = MaterialTheme.colorScheme.outlineVariant
    val band = MaterialTheme.colorScheme.surfaceContainer
    val surface = MaterialTheme.colorScheme.surface
    val colors = series.indices.map { seriesColor(it) }
    val top = ceil((series.flatMap { it.points.filterNotNull() }.maxOrNull() ?: 1.0) / 5.0).coerceAtLeast(1.0) * 5.0
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).testTag("roschart")) {
        Text("Rest of season, week by week", style = MaterialTheme.typography.labelMedium, color = muted)
        // Legend: always present for two or more lines; text in ink, the dot carries the color.
        for ((i, s) in series.withIndex()) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                Box(Modifier.size(8.dp).background(colors[i], CircleShape))
                Text(
                    "  ${s.name}: ${pts(s.points.sumOf { it ?: 0.0 })} total · playoffs ${pts(s.points.withIndex().sumOf { (j, v) -> if (weeks[j] in playoffs) v ?: 0.0 else 0.0 })}",
                    style = MaterialTheme.typography.labelSmall,
                    color = ink,
                )
            }
        }
        val readout = selected?.let { j ->
            "Week ${weeks[j]}: " + series.joinToString(" · ") { s -> "${s.name.substringAfterLast(' ')} ${s.points[j]?.let(::pts) ?: "bye"}" }
        } ?: "Tap the chart to read a week."
        Text(readout, Modifier.padding(top = 4.dp).testTag("roschart:readout"), style = MaterialTheme.typography.labelSmall, color = muted)
        Canvas(
            Modifier.fillMaxWidth().height(180.dp).padding(top = 4.dp)
                .pointerInput(weeks) {
                    detectTapGestures { o -> selected = index(o.x - INSET.toPx(), size.width - 2 * INSET.toPx(), weeks.size) }
                }
                .pointerInput(weeks) {
                    detectDragGestures { change, _ -> selected = index(change.position.x - INSET.toPx(), size.width - 2 * INSET.toPx(), weeks.size) }
                },
        ) {
            // Inset so the end dots aren't clipped by the chart's edges.
            val inset = INSET.toPx()
            val w = size.width
            val h = size.height
            val span = w - 2 * inset
            val step = if (weeks.size > 1) span / (weeks.size - 1) else span
            fun x(j: Int) = inset + if (weeks.size > 1) j * step else span / 2
            fun y(v: Double) = (inset + (h - 2 * inset) * (1 - v / top)).toFloat()
            // Playoff weeks: a recessive band behind everything.
            weeks.withIndex().filter { it.value in playoffs }.let { idx ->
                if (idx.isNotEmpty()) {
                    val left = (x(idx.first().index) - step / 2).coerceAtLeast(0f)
                    val right = (x(idx.last().index) + step / 2).coerceAtMost(w)
                    drawRect(band, topLeft = Offset(left, 0f), size = Size(right - left, h))
                }
            }
            // Recessive grid: quarter lines.
            for (q in 0..4) {
                val gy = inset + (h - 2 * inset) * q / 4f
                drawLine(grid, Offset(0f, gy), Offset(w, gy), strokeWidth = 1f)
            }
            selected?.let { j -> drawLine(muted, Offset(x(j), 0f), Offset(x(j), h), strokeWidth = 1.dp.toPx()) }
            for ((i, s) in series.withIndex()) {
                val path = Path()
                var open = false
                for ((j, v) in s.points.withIndex()) {
                    if (v == null) {
                        open = false
                        continue
                    }
                    if (open) path.lineTo(x(j), y(v)) else path.moveTo(x(j), y(v))
                    open = true
                }
                drawPath(path, colors[i], style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
                for ((j, v) in s.points.withIndex()) {
                    v ?: continue
                    // A surface ring keeps overlapping dots apart.
                    drawCircle(surface, radius = 5.dp.toPx(), center = Offset(x(j), y(v)))
                    drawCircle(colors[i], radius = 4.dp.toPx(), center = Offset(x(j), y(v)))
                }
            }
        }
        Row(Modifier.fillMaxWidth()) {
            Text("Wk ${weeks.first()}", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = muted)
            Text("0–${pts(top)} pts, lines every ${pts(top / 4)}", style = MaterialTheme.typography.labelSmall, color = muted)
            Text("Wk ${weeks.last()}", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = muted, textAlign = TextAlign.End)
        }
        // The table view: every number the chart draws.
        Row(Modifier.padding(top = 8.dp)) {
            Text("Wk", Modifier.width(36.dp), style = MaterialTheme.typography.labelSmall, color = muted)
            for (s in series) Text(s.name.substringAfterLast(' '), Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = muted, fontWeight = FontWeight.SemiBold)
        }
        for ((j, week) in weeks.withIndex()) {
            Row(Modifier.testTag("roschart:row:$week")) {
                Text("$week${if (week in playoffs) "*" else ""}", Modifier.width(36.dp), style = MaterialTheme.typography.labelSmall, color = muted)
                for (s in series) Text(s.points[j]?.let(::pts) ?: "bye", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = ink)
            }
        }
        Text("* fantasy playoff week", style = MaterialTheme.typography.labelSmall, color = muted)
    }
}

private val INSET = 8.dp

private fun index(x: Float, width: Float, count: Int): Int =
    if (count <= 1) 0 else ((x / width) * (count - 1) + 0.5f).toInt().coerceIn(0, count - 1)

private fun pts(value: Double): String = String.format(Locale.US, "%.1f", value)
