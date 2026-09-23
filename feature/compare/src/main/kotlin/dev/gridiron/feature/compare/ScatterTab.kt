package dev.gridiron.feature.compare

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.gridiron.core.charts.ScatterChart
import dev.gridiron.core.charts.ScatterPoint
import dev.gridiron.core.data.Catalog
import dev.gridiron.core.data.ComparePage
import dev.gridiron.core.data.CompareTrayRepository
import dev.gridiron.core.data.weeksLabel
import dev.gridiron.core.designsystem.SlotColors
import kotlinx.collections.immutable.toImmutableList
import java.util.Locale

/** Actual against expected fantasy points per game for the first slot's position, season and range. */
@Composable
internal fun ScatterTab(
    page: ComparePage,
    catalog: Catalog,
    selectedPoint: String?,
    onSelect: (String?) -> Unit,
    onAddToCompare: (String, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scatter = page.scatter
    Column(modifier.padding(16.dp)) {
        if (scatter == null) {
            Text("Not enough data for a scatter.", style = MaterialTheme.typography.bodyMedium)
            return@Column
        }
        val slotIds = page.slots.map { it.slot.playerId }.toSet()
        val namesById = (scatter.population + scatter.slots.filterNotNull()).associate { it.playerId to it.name }
        val points = buildList {
            addAll(
                scatter.population.filter { it.playerId !in slotIds }
                    .map { ScatterPoint(it.playerId, it.xfpPerGame.toFloat(), it.fpPerGame.toFloat()) },
            )
            scatter.slots.forEachIndexed { i, p ->
                if (p != null) add(ScatterPoint(p.playerId, p.xfpPerGame.toFloat(), p.fpPerGame.toFloat(), SlotColors.color(i), p.name))
            }
        }.toImmutableList()

        ScatterChart(
            points = points,
            xLabel = "Expected points per game",
            yLabel = "Points per game",
            aboveLabel = "Sell high ↑",
            belowLabel = "Buy low ↓",
            contentDescription = scatterSummary(scatter.slots.filterNotNull(), scatter.population.size),
            selectedId = selectedPoint,
            onSelect = onSelect,
            modifier = Modifier.fillMaxWidth(),
        )
        val season = catalog.seasons.firstOrNull { it.season == scatter.season }
        val weeks = season?.let { weeksLabel(it, scatter.weeks) } ?: "${scatter.weeks.first}–${scatter.weeks.last}"
        Text(
            "${scatter.position.code}s, ${scatter.season} $weeks, per game",
            Modifier.padding(top = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        val selected = points.firstOrNull { it.id == selectedPoint }
        if (selected != null) {
            val name = namesById[selected.id] ?: selected.id
            val full = page.slots.size >= CompareTrayRepository.CAPACITY
            Card(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Text(name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        "${"%.1f".format(Locale.US, selected.y)} per game · ${"%.1f".format(Locale.US, selected.x)} expected",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Button(
                        onClick = { onAddToCompare(selected.id, name) },
                        enabled = !full,
                        modifier = Modifier.padding(top = 8.dp),
                    ) {
                        Text(if (full) "Compare holds ${CompareTrayRepository.CAPACITY} players" else "Add to compare")
                    }
                }
            }
        }
    }
}
