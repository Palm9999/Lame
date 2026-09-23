package dev.gridiron.feature.compare

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.gridiron.core.charts.RadarChart
import dev.gridiron.core.charts.RadarSeries
import dev.gridiron.core.data.ComparePage
import dev.gridiron.core.designsystem.SlotColors
import kotlinx.collections.immutable.persistentListOf

/** Percentile "shape" for exactly two slots; a selector appears above three or four. */
@Composable
internal fun RadarTab(
    page: ComparePage,
    radarPair: Pair<Int, Int>,
    onRadarPairChanged: (Int, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val radar = page.radar
    Column(modifier.padding(16.dp)) {
        if (radar == null) {
            Text("Not enough ranked players for a radar.", style = MaterialTheme.typography.bodyMedium)
            return@Column
        }

        if (page.slots.size > 2) {
            RadarSelector(page, radarPair, onRadarPairChanged)
        }

        val a = radarPair.first
        val b = radarPair.second
        val names = page.slots.map { it.name }
        RadarChart(
            axes = radar.axes,
            series = persistentListOf(
                RadarSeries(names[a], radar.values[a], SlotColors.color(a)),
                RadarSeries(names[b], radar.values[b], SlotColors.color(b)),
            ),
            contentDescription = radarSummary(radar, names, a, b),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            listOf(a, b).forEach { i ->
                Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).background(SlotColors.color(i), CircleShape))
                    Spacer(Modifier.width(4.dp))
                    Text(names[i], style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        Text(
            "Percentile within position. Outer ring = best at the position.",
            Modifier.padding(top = 8.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun RadarSelector(page: ComparePage, radarPair: Pair<Int, Int>, onRadarPairChanged: (Int, Int) -> Unit) {
    fun choose(pickFirst: Boolean, i: Int) {
        val (a0, b0) = radarPair
        val next = if (pickFirst) i to (if (i == b0) a0 else b0) else (if (i == a0) b0 else a0) to i
        onRadarPairChanged(next.first, next.second)
    }
    Column(Modifier.padding(bottom = 8.dp)) {
        listOf("First player" to true, "Second player" to false).forEach { (label, pickFirst) ->
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                page.slots.forEachIndexed { i, slot ->
                    val selected = if (pickFirst) radarPair.first == i else radarPair.second == i
                    FilterChip(
                        selected = selected,
                        onClick = { choose(pickFirst, i) },
                        leadingIcon = { Box(Modifier.size(8.dp).background(SlotColors.color(i), CircleShape)) },
                        label = { Text(slot.name) },
                    )
                }
            }
        }
    }
}
